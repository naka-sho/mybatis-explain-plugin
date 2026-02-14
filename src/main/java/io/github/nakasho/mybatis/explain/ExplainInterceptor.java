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
import java.util.Properties;

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
    String sql = boundSql.getSql();
    String explainSql = "EXPLAIN " + sql;
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

  @Override
  public void setProperties(Properties properties) {
    // no properties needed
  }
}
