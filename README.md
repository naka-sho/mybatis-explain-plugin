# MyBatis Explain Plugin

![CI](https://github.com/naka-sho/mybatis-explain-plugin/actions/workflows/ci.yml/badge.svg)

MyBatis の Interceptor 機構を利用して、SQL 実行後に `EXPLAIN` を自動実行し、実行計画をログ出力するプラグインです。

## 特徴

- `Executor.query` および `Executor.update` をインターセプトし、実行後に `EXPLAIN <SQL>` を実行
- MyBatis の `statementLog` を利用してマッパー単位でログ出力（ログレベル: DEBUG）
- `CALLABLE` ステートメントは自動スキップ
- EXPLAIN 実行に失敗しても元のクエリには影響しない
- Spring 非依存 — 素の MyBatis でも Spring Boot でも利用可能

## 動作要件

- Java 11+
- MyBatis 3.5.x+
- `EXPLAIN` 構文をサポートするデータベース（PostgreSQL, MySQL, H2 など）

## インストール

### Maven

```xml
<dependency>
  <groupId>io.github.naka-sho</groupId>
  <artifactId>mybatis-explain-plugin</artifactId>
  <version>1.0.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.github.naka-sho:mybatis-explain-plugin:1.0.0'
```

## 設定

### MyBatis XML 設定

```xml
<plugins>
  <plugin interceptor="io.github.nakasho.mybatis.explain.ExplainInterceptor" />
</plugins>
```

### Spring Boot

`@Bean` として登録すると mybatis-spring-boot-starter が自動的にインターセプターとして認識します。

```java
@Configuration
public class MyBatisConfig {

    @Bean
    public ExplainInterceptor explainInterceptor() {
        return new ExplainInterceptor();
    }
}
```

### ログレベル

ExplainPlan はマッパーの `statementLog` に対して `DEBUG` レベルで出力されます。
対象マッパーのログレベルを DEBUG に設定してください。

```yaml
# application.yml (Spring Boot)
logging:
  level:
    com.example.mapper: DEBUG
```

## 仕組み

- `Executor.query`/`Executor.update` の実行後に `EXPLAIN <SQL>` を発行します
- `statementLog` が DEBUG の場合のみ EXPLAIN を実行します
- バインドパラメータは元 SQL と同じ値を利用します
- EXPLAIN 実行時の例外は DEBUG に出力し、元のクエリには影響しません

## ログ出力例

### PostgreSQL

```
==>  Preparing: SELECT * FROM users WHERE id = ?
==> Parameters: 1(Integer)
<==      Total: 1
<== ExplainPlan: Index Scan using users_pkey on users  (cost=0.15..8.17 rows=1 width=72)
<== ExplainPlan:   Index Cond: (id = 1)
```

### MySQL（複数カラム形式）

```
==>  Preparing: SELECT * FROM users WHERE id = ?
==> Parameters: 1(Integer)
<==      Total: 1
<== ExplainPlan: id=1, select_type=SIMPLE, table=users, type=const, possible_keys=PRIMARY, key=PRIMARY, key_len=4, ref=const, rows=1, Extra=NULL
```

### EXPLAIN 失敗時

```
<== ExplainPlan: Failed to execute EXPLAIN: <error message>
```

## 対応データベース

本プラグインは `EXPLAIN <SQL>` をそのまま発行します。この構文をサポートするデータベースで動作します。

| データベース | 対応 | 出力形式 | 備考 |
|---|---|---|---|
| PostgreSQL | o | テキスト（単一カラム） | `EXPLAIN <SQL>` — ツリー形式の実行計画 |
| MySQL | o | テーブル（複数カラム） | `EXPLAIN <SQL>` — `id`, `select_type`, `table`, `type` 等 |
| MariaDB | o | テーブル（複数カラム） | MySQL と同様の形式 |
| H2 | o | テキスト（単一カラム） | `EXPLAIN <SQL>` — 簡易的な実行計画 |
| SQLite | o | バイトコード形式 | `EXPLAIN QUERY PLAN <SQL>` ではなく `EXPLAIN <SQL>` を実行 |
| CockroachDB | o | テキスト（単一カラム） | PostgreSQL 互換 |
| TiDB | o | テーブル（複数カラム） | MySQL 互換 |
| Oracle | x | — | `EXPLAIN PLAN FOR` + `DBMS_XPLAN` が必要。`EXPLAIN <SQL>` は非対応 |
| SQL Server | x | — | `SET SHOWPLAN_XML ON` 等が必要。`EXPLAIN` 構文なし |
| DB2 | x | — | `EXPLAIN PLAN FOR` が必要。`EXPLAIN <SQL>` は非対応 |

非対応 DB で使用した場合、EXPLAIN の実行に失敗しますが元のクエリには影響しません（エラーログが出力されるのみ）。

## 注意事項

- 本プラグインは開発・デバッグ用途を想定しています。本番環境では DEBUG ログを無効にするか、依存を除外してください
- EXPLAIN の構文はデータベースごとに異なります。`EXPLAIN <SQL>` をそのまま発行するため、対象 DB がこの構文をサポートしている必要があります
- クエリ実行後に EXPLAIN を発行するため、1 クエリあたり追加の DB アクセスが 1 回発生します
- MyBatis のログ実装（`logImpl`）が無効だと `statementLog` が出力されないため、ログ設定を確認してください

## ビルド

```bash
mvn clean install
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Code of Conduct

See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## Security

See [SECURITY.md](SECURITY.md).

## ライセンス

Apache License 2.0. See [LICENSE](LICENSE).
