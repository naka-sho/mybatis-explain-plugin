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

/**
 * Database types with their corresponding EXPLAIN prefix.
 * A {@code null} prefix indicates that EXPLAIN is not supported.
 */
public enum DatabaseType {

  DEFAULT("EXPLAIN "),
  ORACLE("EXPLAIN PLAN FOR "),
  SQL_SERVER(null);

  private final String explainPrefix;

  DatabaseType(String explainPrefix) {
    this.explainPrefix = explainPrefix;
  }

  /**
   * Returns the EXPLAIN prefix for this database type, or {@code null} if EXPLAIN is not supported.
   *
   * @return the EXPLAIN prefix, or {@code null}
   */
  public String getExplainPrefix() {
    return explainPrefix;
  }

  /**
   * Resolves a {@link DatabaseType} from a MyBatis databaseId.
   * Returns {@link #DEFAULT} when databaseId is {@code null} or unrecognized.
   *
   * @param databaseId the databaseId from {@link org.apache.ibatis.mapping.MappedStatement#getDatabaseId()}
   * @return the matching {@link DatabaseType}
   */
  public static DatabaseType fromDatabaseId(String databaseId) {
    if (databaseId == null) {
      return DEFAULT;
    }
    String lower = databaseId.toLowerCase();
    if ("oracle".equals(lower)) {
      return ORACLE;
    }
    if ("sqlserver".equals(lower) || "sql server".equals(lower)) {
      return SQL_SERVER;
    }
    return DEFAULT;
  }
}
