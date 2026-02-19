# MyBatis Explain Plugin

![CI](https://github.com/naka-sho/mybatis-explain-plugin/actions/workflows/ci.yml/badge.svg)

MyBatis の Interceptor 機構を利用して、SQL 実行後に `EXPLAIN` を自動実行し、実行計画をログ出力するプラグインです。

## 特徴

- `Executor.query` および `Executor.update` をインターセプトし、実行後に `EXPLAIN <SQL>` を実行
- `MappedStatement.getDatabaseId()` に基づいてデータベースごとの EXPLAIN 構文を自動選択
- MyBatis の `statementLog` を利用してマッパー単位でログ出力（ログレベル: DEBUG）
- `CALLABLE` ステートメントは自動スキップ
- EXPLAIN 非対応 DB（SQL Server 等）は自動的にスキップ
- EXPLAIN 実行に失敗しても元のクエリには影響しない
- Spring 非依存 — 素の MyBatis でも Spring Boot でも利用可能

## 動作要件

- Java 11+
- MyBatis 3.5.x+
- `EXPLAIN` 構文をサポートするデータベース（PostgreSQL, MySQL, Oracle, H2 など）

## インストール

### Maven

```xml
<dependency>
  <groupId>io.github.naka-sho</groupId>
  <artifactId>mybatis-explain-plugin</artifactId>
  <version>1.0.2</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.github.naka-sho:mybatis-explain-plugin:1.0.2'
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

> **⚠ 本番環境での利用について**
>
> 本プラグインは開発・デバッグ用途を想定しています。
> 1 クエリあたり追加の EXPLAIN が発行されるため、本番環境ではBean登録を無効にしてください。
> `@Profile` やプロパティによる制御を推奨します。

#### Profile で制御する例

```java
@Configuration
@Profile("dev")
public class MyBatisDevConfig {

    @Bean
    public ExplainInterceptor explainInterceptor() {
        return new ExplainInterceptor();
    }
}
```

#### プロパティで制御する例

```java
@Configuration
@ConditionalOnProperty(name = "mybatis.explain.enabled", havingValue = "true")
public class MyBatisExplainConfig {

    @Bean
    public ExplainInterceptor explainInterceptor() {
        return new ExplainInterceptor();
    }
}
```

```yaml
# application-dev.yml
mybatis:
  explain:
    enabled: true
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

- `Executor.query`/`Executor.update` の実行後に EXPLAIN を発行します
- `MappedStatement.getDatabaseId()` を参照し、データベースに応じた EXPLAIN プレフィックスを選択します
  - デフォルト: `EXPLAIN <SQL>`
  - Oracle: `EXPLAIN PLAN FOR <SQL>`
  - SQL Server: EXPLAIN 非対応のためスキップ
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

`MappedStatement.getDatabaseId()` に基づいてデータベースごとの EXPLAIN 構文を自動選択します。

| データベース | 対応 | EXPLAIN プレフィックス | 出力形式 | 備考 |
|---|---|---|---|---|
| PostgreSQL | o | `EXPLAIN ` | テキスト（単一カラム） | ツリー形式の実行計画 |
| MySQL | o | `EXPLAIN ` | テーブル（複数カラム） | `id`, `select_type`, `table`, `type` 等 |
| MariaDB | o | `EXPLAIN ` | テーブル（複数カラム） | MySQL と同様の形式 |
| H2 | o | `EXPLAIN ` | テキスト（単一カラム） | 簡易的な実行計画 |
| SQLite | o | `EXPLAIN ` | バイトコード形式 | `EXPLAIN QUERY PLAN` ではなく `EXPLAIN` を実行 |
| CockroachDB | o | `EXPLAIN ` | テキスト（単一カラム） | PostgreSQL 互換 |
| TiDB | o | `EXPLAIN ` | テーブル（複数カラム） | MySQL 互換 |
| Oracle | o | `EXPLAIN PLAN FOR ` | — | databaseId=`oracle` で自動切り替え |
| SQL Server | — | スキップ | — | databaseId=`sqlserver` で EXPLAIN をスキップ |
| DB2 | x | — | — | `EXPLAIN PLAN FOR` が必要。今後対応予定 |

SQL Server など EXPLAIN 非対応の DB では EXPLAIN がスキップされ、元のクエリのみが実行されます。

## 注意事項

- 本プラグインは開発・デバッグ用途を想定しています。本番環境では DEBUG ログを無効にするか、依存を除外してください
- EXPLAIN の構文はデータベースごとに異なります。`databaseId` が未設定の場合は `EXPLAIN <SQL>` をデフォルトで発行します
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
