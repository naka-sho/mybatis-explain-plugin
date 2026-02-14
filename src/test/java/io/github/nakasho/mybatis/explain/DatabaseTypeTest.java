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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DatabaseTypeTest {

  @Test
  @DisplayName("DEFAULT has 'EXPLAIN ' prefix")
  void defaultPrefixShouldBeExplain() {
    assertEquals("EXPLAIN ", DatabaseType.DEFAULT.getExplainPrefix());
  }

  @Test
  @DisplayName("ORACLE has 'EXPLAIN PLAN FOR ' prefix")
  void oraclePrefixShouldBeExplainPlanFor() {
    assertEquals("EXPLAIN PLAN FOR ", DatabaseType.ORACLE.getExplainPrefix());
  }

  @Test
  @DisplayName("SQL_SERVER has null prefix (not supported)")
  void sqlServerPrefixShouldBeNull() {
    assertNull(DatabaseType.SQL_SERVER.getExplainPrefix());
  }

  @Test
  @DisplayName("fromDatabaseId: null returns DEFAULT")
  void fromDatabaseIdNullShouldReturnDefault() {
    assertSame(DatabaseType.DEFAULT, DatabaseType.fromDatabaseId(null));
  }

  @Test
  @DisplayName("fromDatabaseId: 'oracle' returns ORACLE")
  void fromDatabaseIdOracleLowerShouldReturnOracle() {
    assertSame(DatabaseType.ORACLE, DatabaseType.fromDatabaseId("oracle"));
  }

  @Test
  @DisplayName("fromDatabaseId: 'Oracle' returns ORACLE")
  void fromDatabaseIdOracleMixedCaseShouldReturnOracle() {
    assertSame(DatabaseType.ORACLE, DatabaseType.fromDatabaseId("Oracle"));
  }

  @Test
  @DisplayName("fromDatabaseId: 'sqlserver' returns SQL_SERVER")
  void fromDatabaseIdSqlserverShouldReturnSqlServer() {
    assertSame(DatabaseType.SQL_SERVER, DatabaseType.fromDatabaseId("sqlserver"));
  }

  @Test
  @DisplayName("fromDatabaseId: 'sql server' returns SQL_SERVER")
  void fromDatabaseIdSqlServerWithSpaceShouldReturnSqlServer() {
    assertSame(DatabaseType.SQL_SERVER, DatabaseType.fromDatabaseId("sql server"));
  }

  @Test
  @DisplayName("fromDatabaseId: 'SQL Server' returns SQL_SERVER")
  void fromDatabaseIdSqlServerMixedCaseShouldReturnSqlServer() {
    assertSame(DatabaseType.SQL_SERVER, DatabaseType.fromDatabaseId("SQL Server"));
  }

  @Test
  @DisplayName("fromDatabaseId: unknown returns DEFAULT")
  void fromDatabaseIdUnknownShouldReturnDefault() {
    assertSame(DatabaseType.DEFAULT, DatabaseType.fromDatabaseId("mysql"));
  }

  @Test
  @DisplayName("values() returns all enum constants")
  void valuesShouldReturnAllConstants() {
    DatabaseType[] values = DatabaseType.values();
    assertEquals(3, values.length);
    assertSame(DatabaseType.DEFAULT, values[0]);
    assertSame(DatabaseType.ORACLE, values[1]);
    assertSame(DatabaseType.SQL_SERVER, values[2]);
  }

  @Test
  @DisplayName("valueOf() resolves by name")
  void valueOfShouldResolveByName() {
    assertSame(DatabaseType.DEFAULT, DatabaseType.valueOf("DEFAULT"));
    assertSame(DatabaseType.ORACLE, DatabaseType.valueOf("ORACLE"));
    assertSame(DatabaseType.SQL_SERVER, DatabaseType.valueOf("SQL_SERVER"));
  }
}
