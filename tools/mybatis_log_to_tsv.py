#!/usr/bin/env python3
"""mybatis_log_to_tsv.py - MyBatis デバッグログを TSV に変換する外部ツール

【使い方】
    python tools/mybatis_log_to_tsv.py <ログファイル> [出力TSVファイル]

    <ログファイル>    : UT 実行ログ（テキスト形式）。'-' を指定すると標準入力から読み込む
    [出力TSVファイル] : 省略時は標準出力に TSV を書き出す

【手順例】
    # 1. UT を実行してログをファイルに保存する
    mvn test 2>&1 | tee ut.log

    # 2. ログを TSV に変換する
    python tools/mybatis_log_to_tsv.py ut.log result.tsv

    # 3. TSV をスプレッドシートやエディタで確認する

【出力カラム】
    queryId    : マッパーのステートメント ID（例: com.example.mapper.UserMapper.selectById）
    sql        : 実行 SQL（? プレースホルダー形式）
    parameters : バインドパラメータ（例: 1(Integer), foo(String)）
    explain    : EXPLAIN 結果（複数行は LF で結合）

【対応ログフォーマット】
    - Logback / Log4j2:
        2024-01-15 10:30:00.123 DEBUG com.example.Mapper.select - ==>  Preparing: ...
    - SLF4J Simple / 短縮ロガー名:
        [main] DEBUG c.e.Mapper.select - ==>  Preparing: ...
    - mvn test の標準エラー出力（Surefire のコンソールキャプチャ）

【注意】
    - マルチスレッドのログが混在する場合は先にスレッド名でフィルタしてください。
        grep "\\[my-thread\\]" ut.log | python tools/mybatis_log_to_tsv.py - result.tsv
    - MyBatis のログ実装（logImpl）を有効にしておく必要があります。
    - 本ツールは開発・デバッグ用途を想定しています。
"""

import csv
import re
import sys
from dataclasses import dataclass, field
from typing import Dict, IO, List, Optional


# ---------------------------------------------------------------------------
# データモデル
# ---------------------------------------------------------------------------

@dataclass
class QueryEntry:
    """1 クエリ実行分のログ情報を保持するクラス。"""
    query_id: str = ""
    sql: str = ""
    parameters: str = ""
    explain_lines: List[str] = field(default_factory=list)

    @property
    def explain(self) -> str:
        """複数行の ExplainPlan を LF で結合して返す。"""
        return "\n".join(self.explain_lines)


# ---------------------------------------------------------------------------
# 正規表現
# ---------------------------------------------------------------------------

# ログ行から (ロガー名, メッセージ) を抽出する。
# 対応形式: "... DEBUG|TRACE <logger> - <message>"
_LOG_LINE_RE = re.compile(
    r'(?:DEBUG|TRACE)\s+'   # ログレベル
    r'(\S+)'                # ロガー名（空白を含まない）
    r'\s+-\s+'              # セパレータ " - "
    r'(.+)$'                # メッセージ
)

_PREPARING_RE  = re.compile(r'^==>  Preparing: (.+)$')
_PARAMETERS_RE = re.compile(r'^==> Parameters: ?(.*)$')
_EXPLAIN_RE    = re.compile(r'^<== ExplainPlan: (.+)$')


# ---------------------------------------------------------------------------
# パーサー
# ---------------------------------------------------------------------------

class LogParser:
    """MyBatis デバッグログを行単位で受け取り、QueryEntry に集約する。

    同一ロガー名への連続呼び出し（UT での複数回実行）にも対応する。
    """

    def __init__(self) -> None:
        self._pending: Dict[str, QueryEntry] = {}
        self._completed: List[QueryEntry] = []

    def feed(self, line: str) -> None:
        """1 行のログを処理する。"""
        m = _LOG_LINE_RE.search(line)
        if not m:
            return

        logger_name = m.group(1)
        message     = m.group(2)

        if pm := _PREPARING_RE.match(message):
            # 新しいクエリ開始 — 同じロガーの未完了エントリをフラッシュ
            if logger_name in self._pending:
                self._completed.append(self._pending.pop(logger_name))
            entry = QueryEntry(query_id=logger_name, sql=pm.group(1))
            self._pending[logger_name] = entry

        elif logger_name in self._pending:
            entry = self._pending[logger_name]
            if pm := _PARAMETERS_RE.match(message):
                entry.parameters = pm.group(1)
            elif pm := _EXPLAIN_RE.match(message):
                entry.explain_lines.append(pm.group(1))

    def flush(self) -> List[QueryEntry]:
        """残った保留エントリをすべてフラッシュして完了リストを返す。"""
        for entry in self._pending.values():
            self._completed.append(entry)
        self._pending.clear()
        return self._completed


# ---------------------------------------------------------------------------
# I/O ヘルパー
# ---------------------------------------------------------------------------

def parse_log_file(filepath: str) -> List[QueryEntry]:
    """ログファイルを読み込んで QueryEntry のリストを返す。

    filepath に '-' を指定すると標準入力から読み込む。
    """
    parser = LogParser()
    src: IO
    if filepath == "-":
        src = sys.stdin
        _process_stream(src, parser)
    else:
        with open(filepath, encoding="utf-8", errors="replace") as src:
            _process_stream(src, parser)
    return parser.flush()


def _process_stream(src: IO, parser: LogParser) -> None:
    for line in src:
        parser.feed(line.rstrip("\n\r"))


def write_tsv(entries: List[QueryEntry], output: IO) -> None:
    """QueryEntry リストを TSV 形式で書き出す。"""
    writer = csv.writer(output, delimiter="\t", lineterminator="\n",
                        quoting=csv.QUOTE_MINIMAL)
    writer.writerow(["queryId", "sql", "parameters", "explain"])
    for e in entries:
        writer.writerow([e.query_id, e.sql, e.parameters, e.explain])


# ---------------------------------------------------------------------------
# エントリーポイント
# ---------------------------------------------------------------------------

def main() -> None:
    args = sys.argv[1:]

    if not args or args[0] in ("-h", "--help"):
        print(__doc__)
        sys.exit(0 if args else 1)

    log_file = args[0]

    try:
        entries = parse_log_file(log_file)
    except FileNotFoundError:
        print(f"エラー: ファイルが見つかりません: {log_file}", file=sys.stderr)
        sys.exit(1)

    if len(args) >= 2:
        output_file = args[1]
        with open(output_file, "w", encoding="utf-8", newline="") as f:
            write_tsv(entries, f)
        print(f"{len(entries)} 件のクエリを変換しました → {output_file}", file=sys.stderr)
    else:
        import io
        wrapper = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", newline="")
        write_tsv(entries, wrapper)
        wrapper.flush()


if __name__ == "__main__":
    main()
