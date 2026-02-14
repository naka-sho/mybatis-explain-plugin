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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.SimpleExecutor;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExplainInterceptorTest {

  private static SqlSessionFactory sqlSessionFactory;
  private static SqlSessionFactory noDebugSessionFactory;
  private static DataSource dataSource;

  @BeforeAll
  static void setUp() throws Exception {
    dataSource = new UnpooledDataSource(
        "org.h2.Driver",
        "jdbc:h2:mem:explain_test;DB_CLOSE_DELAY=-1",
        "sa",
        ""
    );

    try (Connection conn = dataSource.getConnection();
         Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE IF NOT EXISTS users (id INT PRIMARY KEY, name VARCHAR(100), email VARCHAR(200))");
      stmt.execute("MERGE INTO users (id, name, email) KEY(id) VALUES (1, 'Alice', 'alice@example.com')");
      stmt.execute("MERGE INTO users (id, name, email) KEY(id) VALUES (2, 'Bob', 'bob@example.com')");
    }

    sqlSessionFactory = buildSessionFactory(StdOutImpl.class);
    noDebugSessionFactory = buildSessionFactory(NoLoggingImpl.class);
  }

  private static SqlSessionFactory buildSessionFactory(Class<? extends Log> logImpl) {
    TransactionFactory txFactory = new JdbcTransactionFactory();
    Environment env = new Environment("test", txFactory, dataSource);

    Configuration configuration = new Configuration(env);
    configuration.setLogImpl(logImpl);
    configuration.addInterceptor(new ExplainInterceptor());

    ResultMap resultMap = new ResultMap.Builder(configuration, "userResultMap", HashMap.class,
        Collections.<ResultMapping>emptyList(), true).build();
    configuration.addResultMap(resultMap);

    configuration.addMappedStatement(new MappedStatement.Builder(configuration,
        "io.github.nakasho.mybatis.explain.selectUser",
        new RawSqlSource(configuration, "SELECT id, name, email FROM users WHERE id = #{id}", Integer.class),
        SqlCommandType.SELECT)
        .resultMaps(Collections.singletonList(resultMap)).build());

    configuration.addMappedStatement(new MappedStatement.Builder(configuration,
        "io.github.nakasho.mybatis.explain.updateUser",
        new RawSqlSource(configuration, "UPDATE users SET name = #{name} WHERE id = #{id}", Map.class),
        SqlCommandType.UPDATE).build());

    configuration.addMappedStatement(new MappedStatement.Builder(configuration,
        "io.github.nakasho.mybatis.explain.insertUser",
        new RawSqlSource(configuration,
            "INSERT INTO users (id, name, email) VALUES (#{id}, #{name}, #{email})", Map.class),
        SqlCommandType.INSERT).build());

    configuration.addMappedStatement(new MappedStatement.Builder(configuration,
        "io.github.nakasho.mybatis.explain.deleteUser",
        new RawSqlSource(configuration, "DELETE FROM users WHERE id = #{id}", Map.class),
        SqlCommandType.DELETE).build());

    ResultMap callableResultMap = new ResultMap.Builder(configuration, "callableResultMap", HashMap.class,
        Collections.<ResultMapping>emptyList(), true).build();
    configuration.addResultMap(callableResultMap);
    configuration.addMappedStatement(new MappedStatement.Builder(configuration,
        "io.github.nakasho.mybatis.explain.callableStmt",
        new RawSqlSource(configuration, "SELECT 1", null),
        SqlCommandType.SELECT)
        .statementType(StatementType.CALLABLE)
        .resultMaps(Collections.singletonList(callableResultMap)).build());

    return new SqlSessionFactoryBuilder().build(configuration);
  }

  @Test
  @DisplayName("Integration: query with EXPLAIN (H2)")
  void shouldQuerySuccessfullyWithExplainInterceptor() {
    try (SqlSession session = sqlSessionFactory.openSession()) {
      List<Object> results = session.selectList("io.github.nakasho.mybatis.explain.selectUser", 1);
      assertNotNull(results);
      assertEquals(1, results.size());
      @SuppressWarnings("unchecked")
      Map<String, Object> row = (Map<String, Object>) results.get(0);
      assertEquals(1, row.get("ID"));
      assertEquals("Alice", row.get("NAME"));
    }
  }

  @Test
  @DisplayName("Integration: update with EXPLAIN (H2)")
  void shouldUpdateSuccessfullyWithExplainInterceptor() {
    try (SqlSession session = sqlSessionFactory.openSession()) {
      Map<String, Object> params = new HashMap<>();
      params.put("id", 2);
      params.put("name", "Robert");
      assertEquals(1, session.update("io.github.nakasho.mybatis.explain.updateUser", params));
      session.rollback();
    }
  }

  @Test
  @DisplayName("Interceptor: skip EXPLAIN when debug disabled")
  void interceptShouldSkipExplainWhenDebugDisabled() {
    try (SqlSession session = noDebugSessionFactory.openSession()) {
      List<Object> results = session.selectList("io.github.nakasho.mybatis.explain.selectUser", 1);
      assertNotNull(results);
      assertEquals(1, results.size());
    }
  }

  @Test
  @DisplayName("Interceptor: skip CALLABLE statements")
  void interceptShouldSkipCallableStatement() throws Throwable {
    Log log = mock(Log.class);
    when(log.isDebugEnabled()).thenReturn(true);

    MappedStatement ms = mock(MappedStatement.class);
    when(ms.getStatementLog()).thenReturn(log);
    when(ms.getStatementType()).thenReturn(StatementType.CALLABLE);

    Object expected = new Object();
    Invocation invocation = mock(Invocation.class);
    when(invocation.proceed()).thenReturn(expected);
    when(invocation.getArgs()).thenReturn(new Object[]{ms, null});

    Object result = new ExplainInterceptor().intercept(invocation);

    assertSame(expected, result);
    verify(log, never()).debug(anyString());
  }

  @Test
  @DisplayName("Interceptor: skip when debug disabled (mock)")
  void interceptShouldSkipWhenDebugDisabledViaMock() throws Throwable {
    Log log = mock(Log.class);
    when(log.isDebugEnabled()).thenReturn(false);

    MappedStatement ms = mock(MappedStatement.class);
    when(ms.getStatementLog()).thenReturn(log);

    Object expected = new Object();
    Invocation invocation = mock(Invocation.class);
    when(invocation.proceed()).thenReturn(expected);
    when(invocation.getArgs()).thenReturn(new Object[]{ms, null});

    Object result = new ExplainInterceptor().intercept(invocation);

    assertSame(expected, result);
    verify(log, never()).debug(anyString());
  }

  @Test
  @DisplayName("executeExplain: select")
  void executeExplainShouldProduceOutputForSelect() throws Exception {
    Configuration config = sqlSessionFactory.getConfiguration();
    MappedStatement ms = config.getMappedStatement("io.github.nakasho.mybatis.explain.selectUser");
    BoundSql boundSql = ms.getBoundSql(1);
    Executor executor = newExecutor(config);
    try {
      new ExplainInterceptor().executeExplain(ms, 1, boundSql, executor);
    } finally {
      executor.close(false);
    }
  }

  @Test
  @DisplayName("executeExplain: update")
  void executeExplainShouldProduceOutputForUpdate() throws Exception {
    Configuration config = sqlSessionFactory.getConfiguration();
    MappedStatement ms = config.getMappedStatement("io.github.nakasho.mybatis.explain.updateUser");
    Map<String, Object> params = Map.of("id", 1, "name", "Test");
    BoundSql boundSql = ms.getBoundSql(params);
    Executor executor = newExecutor(config);
    try {
      new ExplainInterceptor().executeExplain(ms, params, boundSql, executor);
    } finally {
      executor.rollback(true);
      executor.close(false);
    }
  }

  @Test
  @DisplayName("executeExplain: insert")
  void executeExplainShouldProduceOutputForInsert() throws Exception {
    Log log = mock(Log.class);

    ResultSetMetaData metaData = mock(ResultSetMetaData.class);
    when(metaData.getColumnCount()).thenReturn(1);

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString(1)).thenReturn("InsertPlan");
    when(rs.getMetaData()).thenReturn(metaData);

    PreparedStatement pstmt = mock(PreparedStatement.class);
    when(pstmt.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(pstmt);

    Executor executor = newMockExecutor(conn);

    Configuration config = sqlSessionFactory.getConfiguration();
    MappedStatement realMs = config.getMappedStatement("io.github.nakasho.mybatis.explain.insertUser");
    Map<String, Object> params = Map.of("id", 3, "name", "Carol", "email", "carol@example.com");
    BoundSql boundSql = realMs.getBoundSql(params);

    MappedStatement ms = cloneMsWithLog(realMs, log);

    new ExplainInterceptor().executeExplain(ms, params, boundSql, executor);

    verify(conn).prepareStatement("EXPLAIN " + boundSql.getSql());
    verify(log).debug("<== ExplainPlan: InsertPlan");
  }

  @Test
  @DisplayName("executeExplain: delete")
  void executeExplainShouldProduceOutputForDelete() throws Exception {
    Log log = mock(Log.class);

    ResultSetMetaData metaData = mock(ResultSetMetaData.class);
    when(metaData.getColumnCount()).thenReturn(1);

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString(1)).thenReturn("DeletePlan");
    when(rs.getMetaData()).thenReturn(metaData);

    PreparedStatement pstmt = mock(PreparedStatement.class);
    when(pstmt.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(pstmt);

    Executor executor = newMockExecutor(conn);

    Configuration config = sqlSessionFactory.getConfiguration();
    MappedStatement realMs = config.getMappedStatement("io.github.nakasho.mybatis.explain.deleteUser");
    Map<String, Object> params = Map.of("id", 3);
    BoundSql boundSql = realMs.getBoundSql(params);

    MappedStatement ms = cloneMsWithLog(realMs, log);

    new ExplainInterceptor().executeExplain(ms, params, boundSql, executor);

    verify(conn).prepareStatement("EXPLAIN " + boundSql.getSql());
    verify(log).debug("<== ExplainPlan: DeletePlan");
  }

  @Test
  @DisplayName("executeExplain: handles invalid SQL")
  void executeExplainShouldHandleInvalidSqlGracefully() throws Exception {
    Configuration config = sqlSessionFactory.getConfiguration();
    MappedStatement badMs = new MappedStatement.Builder(config,
        "io.github.nakasho.mybatis.explain.badStmt",
        new RawSqlSource(config, "INVALID SQL SYNTAX", null),
        SqlCommandType.SELECT)
        .resultMaps(Collections.singletonList(config.getResultMap("userResultMap"))).build();
    BoundSql boundSql = badMs.getBoundSql(null);
    Executor executor = newExecutor(config);
    try {
      new ExplainInterceptor().executeExplain(badMs, null, boundSql, executor);
    } finally {
      executor.close(false);
    }
  }

  @Test
  @DisplayName("executeExplain: logs exception")
  void executeExplainShouldLogErrorOnException() throws Exception {
    Log log = mock(Log.class);

    Transaction transaction = mock(Transaction.class);
    when(transaction.getConnection()).thenThrow(new SQLException("Connection failed"));

    Executor executor = mock(Executor.class);
    when(executor.getTransaction()).thenReturn(transaction);

    Configuration config = sqlSessionFactory.getConfiguration();
    MappedStatement realMs = config.getMappedStatement("io.github.nakasho.mybatis.explain.selectUser");
    BoundSql boundSql = realMs.getBoundSql(1);

    // Use real MappedStatement and swap statementLog via reflection
    MappedStatement ms = cloneMsWithLog(realMs, log);

    new ExplainInterceptor().executeExplain(ms, 1, boundSql, executor);

    verify(log).debug("<== ExplainPlan: Failed to execute EXPLAIN: Connection failed");
  }

  @Test
  @DisplayName("executeExplain: multi-column output")
  void executeExplainShouldFormatMultipleColumns() throws Exception {
    Log log = mock(Log.class);

    ResultSetMetaData metaData = mock(ResultSetMetaData.class);
    when(metaData.getColumnCount()).thenReturn(3);
    when(metaData.getColumnLabel(1)).thenReturn("id");
    when(metaData.getColumnLabel(2)).thenReturn("select_type");
    when(metaData.getColumnLabel(3)).thenReturn("table");

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString(1)).thenReturn("1");
    when(rs.getString(2)).thenReturn("SIMPLE");
    when(rs.getString(3)).thenReturn("users");
    when(rs.getMetaData()).thenReturn(metaData);

    PreparedStatement pstmt = mock(PreparedStatement.class);
    when(pstmt.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(pstmt);

    Transaction tx = mock(Transaction.class);
    when(tx.getConnection()).thenReturn(conn);

    Executor executor = mock(Executor.class);
    when(executor.getTransaction()).thenReturn(tx);

    Configuration config = sqlSessionFactory.getConfiguration();
    MappedStatement realMs = config.getMappedStatement("io.github.nakasho.mybatis.explain.selectUser");
    BoundSql boundSql = realMs.getBoundSql(1);

    MappedStatement ms = cloneMsWithLog(realMs, log);

    new ExplainInterceptor().executeExplain(ms, 1, boundSql, executor);

    verify(log).debug("<== ExplainPlan: id=1, select_type=SIMPLE, table=users");
  }

  @Test
  @DisplayName("executeExplain: multi-column output (multiple rows)")
  void executeExplainShouldFormatMultipleColumnsMultipleRows() throws Exception {
    Log log = mock(Log.class);

    ResultSetMetaData metaData = mock(ResultSetMetaData.class);
    when(metaData.getColumnCount()).thenReturn(2);
    when(metaData.getColumnLabel(1)).thenReturn("type");
    when(metaData.getColumnLabel(2)).thenReturn("detail");

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, true, false);
    when(rs.getString(1)).thenReturn("Seq Scan", "Sort");
    when(rs.getString(2)).thenReturn("on users", "by id");
    when(rs.getMetaData()).thenReturn(metaData);

    PreparedStatement pstmt = mock(PreparedStatement.class);
    when(pstmt.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(pstmt);

    Transaction tx = mock(Transaction.class);
    when(tx.getConnection()).thenReturn(conn);

    Executor executor = mock(Executor.class);
    when(executor.getTransaction()).thenReturn(tx);

    Configuration config = sqlSessionFactory.getConfiguration();
    MappedStatement realMs = config.getMappedStatement("io.github.nakasho.mybatis.explain.selectUser");
    BoundSql boundSql = realMs.getBoundSql(1);

    MappedStatement ms = cloneMsWithLog(realMs, log);

    new ExplainInterceptor().executeExplain(ms, 1, boundSql, executor);

    verify(log).debug("<== ExplainPlan: type=Seq Scan, detail=on users");
    verify(log).debug("<== ExplainPlan: type=Sort, detail=by id");
  }

  @Test
  @DisplayName("executeExplain: Oracle databaseId uses EXPLAIN PLAN FOR prefix")
  void executeExplainShouldUseOraclePrefix() throws Exception {
    Log log = mock(Log.class);

    ResultSetMetaData metaData = mock(ResultSetMetaData.class);
    when(metaData.getColumnCount()).thenReturn(1);

    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString(1)).thenReturn("OraclePlan");
    when(rs.getMetaData()).thenReturn(metaData);

    PreparedStatement pstmt = mock(PreparedStatement.class);
    when(pstmt.executeQuery()).thenReturn(rs);

    Connection conn = mock(Connection.class);
    when(conn.prepareStatement(anyString())).thenReturn(pstmt);

    Executor executor = newMockExecutor(conn);

    Configuration config = sqlSessionFactory.getConfiguration();
    MappedStatement realMs = config.getMappedStatement("io.github.nakasho.mybatis.explain.selectUser");
    BoundSql boundSql = realMs.getBoundSql(1);

    MappedStatement ms = cloneMsWithLogAndDatabaseId(realMs, log, "oracle");

    new ExplainInterceptor().executeExplain(ms, 1, boundSql, executor);

    verify(conn).prepareStatement("EXPLAIN PLAN FOR " + boundSql.getSql());
    verify(log).debug("<== ExplainPlan: OraclePlan");
  }

  @Test
  @DisplayName("executeExplain: SQL Server databaseId skips EXPLAIN")
  void executeExplainShouldSkipForSqlServer() throws Exception {
    Log log = mock(Log.class);

    Executor executor = mock(Executor.class);

    Configuration config = sqlSessionFactory.getConfiguration();
    MappedStatement realMs = config.getMappedStatement("io.github.nakasho.mybatis.explain.selectUser");
    BoundSql boundSql = realMs.getBoundSql(1);

    MappedStatement ms = cloneMsWithLogAndDatabaseId(realMs, log, "sqlserver");

    new ExplainInterceptor().executeExplain(ms, 1, boundSql, executor);

    verify(executor, never()).getTransaction();
    verify(log, never()).debug(anyString());
  }

  @Test
  @DisplayName("setProperties: accepts Properties")
  void setPropertiesShouldAcceptProperties() {
    new ExplainInterceptor().setProperties(new Properties());
  }

  private static Executor newExecutor(Configuration config) throws SQLException {
    return new SimpleExecutor(config, config.getEnvironment().getTransactionFactory()
        .newTransaction(config.getEnvironment().getDataSource(), null, false));
  }

  private static Executor newMockExecutor(Connection conn) throws SQLException {
    Transaction tx = mock(Transaction.class);
    when(tx.getConnection()).thenReturn(conn);
    Executor executor = mock(Executor.class);
    when(executor.getTransaction()).thenReturn(tx);
    return executor;
  }

  private static MappedStatement cloneMsWithLog(MappedStatement original, Log log) throws Exception {
    return cloneMsWithLogAndDatabaseId(original, log, null);
  }

  private static MappedStatement cloneMsWithLogAndDatabaseId(MappedStatement original, Log log, String databaseId)
      throws Exception {
    Configuration config = original.getConfiguration();
    MappedStatement ms = new MappedStatement.Builder(config,
        original.getId() + ".logOverride." + System.nanoTime(),
        original.getSqlSource(), original.getSqlCommandType())
        .resultMaps(original.getResultMaps())
        .databaseId(databaseId)
        .build();
    Field field = MappedStatement.class.getDeclaredField("statementLog");
    field.setAccessible(true);
    field.set(ms, log);
    return ms;
  }
}
