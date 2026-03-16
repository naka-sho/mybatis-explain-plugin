#!/usr/bin/env python3
"""mybatis_log_to_tsv.py の動作確認テスト。

使い方:
    python tools/test_mybatis_log_to_tsv.py
"""

import io
import sys
import unittest

sys.path.insert(0, __file__.rsplit("/", 1)[0])  # tools/ ディレクトリをパスに追加
from mybatis_log_to_tsv import LogParser, QueryEntry, write_tsv


class TestLogParser(unittest.TestCase):

    # ------------------------------------------------------------------
    # PostgreSQL 形式（単一カラム）
    # ------------------------------------------------------------------

    def test_postgresql_single_column(self):
        """PostgreSQL の単一カラム ExplainPlan を正しく収集する。"""
        lines = [
            "2024-01-15 10:30:00.123 DEBUG com.example.UserMapper.selectById - ==>  Preparing: SELECT * FROM users WHERE id = ?",
            "2024-01-15 10:30:00.124 DEBUG com.example.UserMapper.selectById - ==> Parameters: 1(Integer)",
            "2024-01-15 10:30:00.125 DEBUG com.example.UserMapper.selectById - <==      Total: 1",
            "2024-01-15 10:30:00.126 DEBUG com.example.UserMapper.selectById - <== ExplainPlan: Index Scan using users_pkey on users  (cost=0.15..8.17 rows=1 width=72)",
            "2024-01-15 10:30:00.127 DEBUG com.example.UserMapper.selectById - <== ExplainPlan:   Index Cond: (id = 1)",
        ]
        entries = self._parse(lines)

        self.assertEqual(len(entries), 1)
        e = entries[0]
        self.assertEqual(e.query_id,   "com.example.UserMapper.selectById")
        self.assertEqual(e.sql,        "SELECT * FROM users WHERE id = ?")
        self.assertEqual(e.parameters, "1(Integer)")
        self.assertEqual(e.explain,
            "Index Scan using users_pkey on users  (cost=0.15..8.17 rows=1 width=72)\n"
            "  Index Cond: (id = 1)"
        )

    # ------------------------------------------------------------------
    # MySQL 形式（複数カラム）
    # ------------------------------------------------------------------

    def test_mysql_multi_column(self):
        """MySQL の複数カラム ExplainPlan を 1 行として収集する。"""
        lines = [
            "[main] DEBUG com.example.UserMapper.selectById - ==>  Preparing: SELECT * FROM users WHERE id = ?",
            "[main] DEBUG com.example.UserMapper.selectById - ==> Parameters: 1(Integer)",
            "[main] DEBUG com.example.UserMapper.selectById - <==      Total: 1",
            "[main] DEBUG com.example.UserMapper.selectById - <== ExplainPlan: id=1, select_type=SIMPLE, table=users, type=const, key=PRIMARY, rows=1, Extra=NULL",
        ]
        entries = self._parse(lines)

        self.assertEqual(len(entries), 1)
        self.assertIn("select_type=SIMPLE", entries[0].explain)

    # ------------------------------------------------------------------
    # UPDATE 文
    # ------------------------------------------------------------------

    def test_update_statement(self):
        """UPDATE 文のログを正しくパースする（Total の代わりに Updates）。"""
        lines = [
            "2024-01-15 DEBUG com.example.UserMapper.updateName - ==>  Preparing: UPDATE users SET name = ? WHERE id = ?",
            "2024-01-15 DEBUG com.example.UserMapper.updateName - ==> Parameters: Alice(String), 1(Integer)",
            "2024-01-15 DEBUG com.example.UserMapper.updateName - <==    Updates: 1",
            "2024-01-15 DEBUG com.example.UserMapper.updateName - <== ExplainPlan: Update on users  (cost=0.15..8.17 rows=1 width=78)",
        ]
        entries = self._parse(lines)

        self.assertEqual(len(entries), 1)
        e = entries[0]
        self.assertEqual(e.sql,        "UPDATE users SET name = ? WHERE id = ?")
        self.assertEqual(e.parameters, "Alice(String), 1(Integer)")
        self.assertIn("Update on users", e.explain)

    # ------------------------------------------------------------------
    # 同一マッパーへの連続呼び出し
    # ------------------------------------------------------------------

    def test_same_query_id_same_sql_deduplication(self):
        """同じ queryId かつ同じ SQL の場合は重複をスキップして 1 件にまとめる。"""
        lines = [
            "DEBUG com.example.UserMapper.findAll - ==>  Preparing: SELECT * FROM users",
            "DEBUG com.example.UserMapper.findAll - ==> Parameters: ",
            "DEBUG com.example.UserMapper.findAll - <== ExplainPlan: Seq Scan on users",
            # 2 回目（同じ SQL）→ スキップされる
            "DEBUG com.example.UserMapper.findAll - ==>  Preparing: SELECT * FROM users",
            "DEBUG com.example.UserMapper.findAll - ==> Parameters: ",
            "DEBUG com.example.UserMapper.findAll - <== ExplainPlan: Seq Scan on users",
        ]
        entries = self._parse(lines)
        self.assertEqual(len(entries), 1)

    def test_same_query_id_different_sql_kept(self):
        """同じ queryId でも SQL が異なる場合はどちらも出力する。"""
        lines = [
            "DEBUG com.example.UserMapper.findByCondition - ==>  Preparing: SELECT * FROM users WHERE status = ?",
            "DEBUG com.example.UserMapper.findByCondition - ==> Parameters: active(String)",
            "DEBUG com.example.UserMapper.findByCondition - <== ExplainPlan: Index Scan on users",
            # 動的 SQL で WHERE 句が変わった場合
            "DEBUG com.example.UserMapper.findByCondition - ==>  Preparing: SELECT * FROM users WHERE status = ? AND role = ?",
            "DEBUG com.example.UserMapper.findByCondition - ==> Parameters: active(String), admin(String)",
            "DEBUG com.example.UserMapper.findByCondition - <== ExplainPlan: Seq Scan on users",
        ]
        entries = self._parse(lines)
        self.assertEqual(len(entries), 2)
        self.assertIn("status = ?", entries[0].sql)
        self.assertIn("role = ?", entries[1].sql)

    # ------------------------------------------------------------------
    # 複数の異なるマッパー
    # ------------------------------------------------------------------

    def test_multiple_mappers(self):
        """複数の異なるマッパーが含まれる場合に個別エントリを生成する。"""
        lines = [
            "DEBUG com.example.UserMapper.findById - ==>  Preparing: SELECT * FROM users WHERE id = ?",
            "DEBUG com.example.UserMapper.findById - ==> Parameters: 1(Integer)",
            "DEBUG com.example.UserMapper.findById - <== ExplainPlan: Index Scan on users",
            "DEBUG com.example.OrderMapper.findByUser - ==>  Preparing: SELECT * FROM orders WHERE user_id = ?",
            "DEBUG com.example.OrderMapper.findByUser - ==> Parameters: 1(Integer)",
            "DEBUG com.example.OrderMapper.findByUser - <== ExplainPlan: Seq Scan on orders",
        ]
        entries = self._parse(lines)
        self.assertEqual(len(entries), 2)
        self.assertEqual(entries[0].query_id, "com.example.UserMapper.findById")
        self.assertEqual(entries[1].query_id, "com.example.OrderMapper.findByUser")

    # ------------------------------------------------------------------
    # 内部クラスを含むロガー名（テスト用マッパーなど）
    # ------------------------------------------------------------------

    def test_inner_class_logger(self):
        """$ を含む内部クラスのロガー名を正しく抽出する。"""
        lines = [
            "DEBUG io.github.nakasho.mybatis.explain.ExplainInterceptorTest$Mapper.selectAll - ==>  Preparing: SELECT * FROM users",
            "DEBUG io.github.nakasho.mybatis.explain.ExplainInterceptorTest$Mapper.selectAll - ==> Parameters: ",
            "DEBUG io.github.nakasho.mybatis.explain.ExplainInterceptorTest$Mapper.selectAll - <== ExplainPlan: SELECT\n    FROM PUBLIC.USERS",
        ]
        entries = self._parse(lines)
        self.assertEqual(len(entries), 1)
        self.assertEqual(
            entries[0].query_id,
            "io.github.nakasho.mybatis.explain.ExplainInterceptorTest$Mapper.selectAll"
        )

    # ------------------------------------------------------------------
    # EXPLAIN なし（ExplainInterceptor が無効の場合）
    # ------------------------------------------------------------------

    def test_no_explain(self):
        """ExplainPlan が存在しない場合も SQL とパラメータを収集する。"""
        lines = [
            "DEBUG com.example.UserMapper.findAll - ==>  Preparing: SELECT * FROM users",
            "DEBUG com.example.UserMapper.findAll - ==> Parameters: ",
            "DEBUG com.example.UserMapper.findAll - <==      Total: 5",
        ]
        entries = self._parse(lines)
        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0].sql, "SELECT * FROM users")
        self.assertEqual(entries[0].explain, "")

    # ------------------------------------------------------------------
    # TSV 出力
    # ------------------------------------------------------------------

    def test_tsv_output_header_and_rows(self):
        """TSV 出力のヘッダーと行数を確認する。"""
        entry = QueryEntry(
            query_id="com.example.Mapper.select",
            sql="SELECT 1",
            parameters="",
            explain_lines=["Seq Scan on t"],
        )
        buf = io.StringIO()
        write_tsv([entry], buf)
        lines = buf.getvalue().splitlines()
        self.assertEqual(lines[0], "queryId\tsql\tparameters\texplain")
        self.assertEqual(len(lines), 2)
        cols = lines[1].split("\t")
        self.assertEqual(cols[0], "com.example.Mapper.select")
        self.assertEqual(cols[1], "SELECT 1")
        self.assertEqual(cols[3], "Seq Scan on t")

    def test_tsv_multiline_explain_quoting(self):
        """複数行 explain（LF 含む）が TSV で正しくクォートされる。"""
        entry = QueryEntry(
            query_id="q",
            sql="SELECT 1",
            parameters="",
            explain_lines=["line1", "line2"],
        )
        buf = io.StringIO()
        write_tsv([entry], buf)
        content = buf.getvalue()
        # LF を含むフィールドは " でクォートされる
        self.assertIn('"line1\nline2"', content)

    # ------------------------------------------------------------------
    # ヘルパー
    # ------------------------------------------------------------------

    def _parse(self, lines):
        parser = LogParser()
        for line in lines:
            parser.feed(line)
        return parser.flush()


if __name__ == "__main__":
    unittest.main(verbosity=2)
