#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""独立校验评测集的「零字面重叠」硬指标。

与 corpus/verify-v2-overlap.py 相互独立：本脚本由主流程另行实现，用于交叉验证，
避免「作者自己的校验脚本写错导致假通过」。

规则：
  1. 中文按「先按标点/空白切词块，再对长度 >= 2 的词块切 2-gram」的方式切分；
  2. 对每条 answerable=true 的用例，要求 bigrams(question) ∩ bigrams(目标切片正文) == 空集；
  3. 同时报告问题与「目标切片所属文档的全部切片」的交集大小（仅供参考，不强制为空）。

用法：
  python tools/verify-eval-overlap.py --jsonl corpus/eval-hybrid-v2.jsonl
"""
import argparse
import json
import re
import subprocess
import sys

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
MYSQL_ARGS = [
    "--host=127.0.0.1", "--port=3306", "--user=root", "--password=1829002",
    "--default-character-set=utf8mb4", "-N", "campus_repair",
]

SPLIT_RE = re.compile(r"[\s，。、；：！？（）《》〈〉“”‘’【】〔〕·—…\-—_/\\|~`!@#$%^&*()\[\]{}\"'<>,.;:?+=]+")


def word_blocks(text):
    return [block for block in SPLIT_RE.split(text or "") if block]


def bigrams(text):
    grams = set()
    for block in word_blocks(text):
        if len(block) < 2:
            continue
        for i in range(len(block) - 1):
            grams.add(block[i:i + 2])
    return grams


def query(sql):
    result = subprocess.run([MYSQL] + MYSQL_ARGS + ["--execute=" + sql],
                            capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        raise RuntimeError(result.stderr)
    return [line for line in result.stdout.splitlines() if line.strip()]


def load_case(path):
    cases = []
    with open(path, "r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            cases.append(json.loads(line))
    return cases


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jsonl", required=True)
    parser.add_argument("--show-ok", action="store_true")
    args = parser.parse_args()

    cases = load_case(args.jsonl)
    answerable = [c for c in cases if c.get("answerable", True)]
    unanswerable = [c for c in cases if not c.get("answerable", True)]

    chunk_ids = []
    for case in answerable:
        chunk_ids.extend(case.get("expectedChunkIds") or [])
    if not chunk_ids:
        print("[FAIL] 没有任何 expectedChunkIds，无法做切片级校验")
        return 1

    id_list = ",".join(str(int(cid)) for cid in chunk_ids)
    rows = query(f"SELECT id, document_id, REPLACE(REPLACE(content, CHAR(10), ' '), CHAR(9), ' ') "
                 f"FROM rag_knowledge_chunk WHERE id IN ({id_list});")
    chunk_text = {}
    chunk_doc = {}
    for row in rows:
        parts = row.split("\t")
        if len(parts) >= 3:
            chunk_text[int(parts[0])] = "\t".join(parts[2:])
            chunk_doc[int(parts[0])] = int(parts[1])

    doc_ids = sorted(set(chunk_doc.values()))
    doc_text = {}
    if doc_ids:
        doc_rows = query("SELECT document_id, REPLACE(REPLACE(content, CHAR(10), ' '), CHAR(9), ' ') "
                         f"FROM rag_knowledge_chunk WHERE document_id IN ({','.join(str(d) for d in doc_ids)});")
        for row in doc_rows:
            parts = row.split("\t")
            if len(parts) >= 2:
                doc_text.setdefault(int(parts[0]), []).append("\t".join(parts[1:]))

    print(f"{'question':<44} {'chunkId':>20} {'Q-gram':>7} {'∩chunk':>7} {'∩doc':>6}  verdict")
    print("-" * 100)
    failures = 0
    for case in answerable:
        targets = case.get("expectedChunkIds") or []
        if len(targets) != 1:
            print(f"[WARN] 期望切片数 != 1：{case.get('question')}")
        target = int(targets[0])
        text = chunk_text.get(target)
        if text is None:
            print(f"[FAIL] 切片不存在于数据库：{target}（{case.get('question')}）")
            failures += 1
            continue
        q_grams = bigrams(case.get("question", ""))
        chunk_grams = bigrams(text)
        overlap_chunk = q_grams & chunk_grams
        doc_grams = set()
        for other in doc_text.get(chunk_doc.get(target, -1), []):
            doc_grams |= bigrams(other)
        overlap_doc = q_grams & doc_grams
        verdict = "OK" if not overlap_chunk else "OVERLAP:" + ",".join(sorted(overlap_chunk)[:6])
        if overlap_chunk:
            failures += 1
        if args.show_ok or overlap_chunk:
            print(f"{case.get('question','')[:42]:<44} {target:>20} {len(q_grams):>7} "
                  f"{len(overlap_chunk):>7} {len(overlap_doc):>6}  {verdict}")

    print("-" * 100)
    print(f"answerable 用例：{len(answerable)} 条，零重叠失败：{failures} 条，"
          f"通过率：{(len(answerable) - failures) / max(len(answerable), 1) * 100:.1f}%")
    print(f"不可回答用例：{len(unanswerable)} 条（仅检查字段为空）")
    for case in unanswerable:
        empty = not (case.get("expectedChunkIds") or []) and not (case.get("expectedKeywords") or [])
        print(f"  [{'OK' if empty else 'FAIL'}] {case.get('question')}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
