/*
 *    Copyright 2009-2026 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package io.github.nakasho.mybatis.explain;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * MyBatis interceptor that executes {@code EXPLAIN <SQL>} after query/update.
 * It runs only when the statement log is DEBUG and skips CALLABLE statements.
 * EXPLAIN failures are logged at DEBUG and do not affect the original execution.
 */
@Intercepts({
    @Signature(type = Executor.class, method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(type = Executor.class, method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class}),
    @Signature(type = Executor.class, method = "update",
        args = {MappedStatement.class, Object.class})
})
public class ExplainInterceptor implements Interceptor {

  private static final Pattern FROM_OR_UPDATE_PATTERN =
      Pattern.compile("(?i)\\b(?:FROM|UPDATE)\\s+(\\w+)(?:\\s+(\\w+))?");

  private static final Set<String> SQL_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
      "WHERE", "SET", "GROUP", "ORDER", "HAVING", "LIMIT", "JOIN", "ON",
      "AND", "OR", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL",
      "UNION", "EXCEPT", "INTERSECT", "AS", "SELECT", "FROM", "INTO"
  )));

  /**
   * Creates a new interceptor instance.
   */
  public ExplainInterceptor() {
    // default constructor
  }

  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    Object proceed = invocation.proceed();

    MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
    Log statementLog = ms.getStatementLog();

    if (statementLog.isDebugEnabled() && ms.getStatementType() != StatementType.CALLABLE) {
      Object parameter = invocation.getArgs()[1];
      BoundSql boundSql = ms.getBoundSql(parameter);
      executeExplain(ms, parameter, boundSql, (Executor) invocation.getTarget());
    }

    return proceed;
  }

  /**
   * Executes EXPLAIN using the same parameters and transaction as the original statement.
   * Package-private to allow focused tests without reflection.
   */
  void executeExplain(MappedStatement ms, Object parameter, BoundSql boundSql, Executor executor) {
    Log statementLog = ms.getStatementLog();
    DatabaseType databaseType = DatabaseType.fromDatabaseId(ms.getDatabaseId());
    String explainPrefix = databaseType.getExplainPrefix();
    if (explainPrefix == null) {
      return;
    }
    String sql = boundSql.getSql();
    if (databaseType.isNoFullTableScanRequired()) {
      sql = injectNoFullTableScanHint(sql);
    }
    String explainSql = explainPrefix + sql;
    Configuration configuration = ms.getConfiguration();

    try {
      Connection connection = executor.getTransaction().getConnection();
      try (PreparedStatement stmt = connection.prepareStatement(explainSql)) {
        ParameterHandler parameterHandler = configuration.newParameterHandler(ms, parameter, boundSql);
        parameterHandler.setParameters(stmt);
        try (ResultSet rs = stmt.executeQuery()) {
          ResultSetMetaData metaData = rs.getMetaData();
          int columnCount = metaData.getColumnCount();
          while (rs.next()) {
            if (columnCount == 1) {
              statementLog.debug("<== ExplainPlan: " + rs.getString(1));
            } else {
              StringBuilder row = new StringBuilder();
              for (int i = 1; i <= columnCount; i++) {
                if (i > 1) {
                  row.append(", ");
                }
                row.append(metaData.getColumnLabel(i)).append("=").append(rs.getString(i));
              }
              statementLog.debug("<== ExplainPlan: " + row);
            }
          }
        }
      }
    } catch (Exception e) {
      statementLog.debug("<== ExplainPlan: Failed to execute EXPLAIN: " + e.getMessage());
    }
  }

  /**
   * Injects a {@code NO_FULL_TABLE_SCAN} optimizer hint into the SQL to prevent the MySQL optimizer
   * from choosing a full table scan even on small datasets, making index existence verifiable.
   * Supports SELECT, UPDATE, and DELETE statements. Returns the original SQL unchanged if no
   * FROM/UPDATE clause is found.
   *
   * <p>Package-private for testing.
   */
  static String injectNoFullTableScanHint(String sql) {
    String trimmed = sql.trim();
    String tableRef = extractFirstTableRef(trimmed);
    if (tableRef == null) {
      return trimmed;
    }
    String hint = "/*+ NO_FULL_TABLE_SCAN(" + tableRef + ") */";
    String upper = trimmed.toUpperCase();
    if (upper.startsWith("SELECT")) {
      return trimmed.replaceFirst("(?i)\\bSELECT\\b", Matcher.quoteReplacement("SELECT " + hint));
    }
    if (upper.startsWith("UPDATE")) {
      return trimmed.replaceFirst("(?i)\\bUPDATE\\b", Matcher.quoteReplacement("UPDATE " + hint));
    }
    if (upper.startsWith("DELETE")) {
      return trimmed.replaceFirst("(?i)\\bDELETE\\b", Matcher.quoteReplacement("DELETE " + hint));
    }
    return trimmed;
  }

  private static String extractFirstTableRef(String sql) {
    Matcher m = FROM_OR_UPDATE_PATTERN.matcher(sql);
    if (!m.find()) {
      return null;
    }
    String tableName = m.group(1);
    String secondWord = m.group(2);
    if (secondWord != null && !SQL_KEYWORDS.contains(secondWord.toUpperCase())) {
      return secondWord;
    }
    return tableName;
  }

  @Override
  public void setProperties(Properties properties) {
    // no properties needed
  }
}
