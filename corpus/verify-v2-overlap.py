#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify-v2-overlap.py —— 校园智能报修系统 RAG 检索评测集「零字面重叠」校验脚本

用途
----
校验 corpus/eval-hybrid-v2.jsonl 中每一条 answerable 用例，其 question 与
**目标切片正文（直接取自 MySQL 的 rag_knowledge_chunk.content）** 的中文 bigram
交集必须为空集。这样纯关键词检索在结构上不可能命中目标切片（关键词得分 = 0
会被过滤），只有向量语义检索才可能命中，评测才能体现混合检索/向量检索的价值。

bigram 规则（问题与切片正文共用同一套函数，保证口径一致）
--------------------------------------------------------
1. 预处理：把正文中字面存储的转义换行 `\\n` / `\\r` / `\\t` 还原成分隔符。
2. 切分：把所有空白与标点替换为分隔符，按分隔符切出「词块」。
   标点集合覆盖中文标点（，。、；：！？（）《》「」『』【】·—… 等）与 ASCII 标点。
3. 切 bigram：对每个长度 ≥ 2 的词块，取相邻 2 个字符构成一个 bigram。
   长度 = 1 的词块不产生 bigram。
4. 交集：bigrams(question) ∩ bigrams(target_chunk_text)，必须为空集。

统计与输出
----------
- 总条数 / answerable 条数 / 零重叠通过率（必须 100%）
- 每条：chunkId、问题 bigram 数、与目标切片交集（必须 0）、与目标切片所属文档
  全部切片的交集大小（仅统计，不要求为空）

依赖：仅 Python 标准库（通过 subprocess 调用 mysql.exe 读取切片正文）。
"""

import argparse
import json
import os
import re
import subprocess
import sys
import unicodedata

DEFAULT_JSONL = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                             "eval-hybrid-v2.jsonl")
DEFAULT_MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"

# ---------------------------------------------------------------- bigram 规则

# 中文标点 + 全角符号 + ASCII 标点，全部视为分隔符
PUNCT_CHARS = (
    "，。、；：！？（）《》「」『』【】〔〕〈〉《》·—…～‘’“”〝〞"
    "﹏﹑﹔﹕﹐﹖﹗﹙﹚﹛﹜﹝﹞"
    + ",.;:!?()[]{}<>\"'`\\|/-_+=*&^%$#@~"
)

_SEP_RE = re.compile("[" + re.escape(PUNCT_CHARS) + r"\s]+")
_ESC_RE = re.compile(r"\\[nrt]")  # 库内容里以字面 \n 形式存储的换行
MIN_BLOCK = 2


def to_blocks(text):
    """标点/空白 → 分隔符，返回词块列表（问题与切片正文共用）。"""
    if text is None:
        return []
    text = _ESC_RE.sub(" ", text)
    return [b for b in _SEP_RE.split(text) if b]


def bigrams(text):
    """中文 bigram 集合：先切词块，再对长度 ≥ 2 的词块切相邻 2 字。"""
    out = set()
    for block in to_blocks(text):
        if len(block) < MIN_BLOCK:
            continue
        for i in range(len(block) - 1):
            out.add(block[i:i + 2])
    return out


# ------------------------------------------------------------------ MySQL 读取

class Db(object):
    def __init__(self, mysql, host, port, user, password, database):
        self.mysql = mysql
        self.args = [mysql, "--host=" + host, "--port=" + str(port),
                     "--user=" + user, "--password=" + password,
                     "--default-character-set=utf8mb4", database, "-N"]

    def json_rows(self, sql):
        """执行 SQL，按行解析 JSON_OBJECT 输出（避免 content 换行破坏按行解析）。"""
        try:
            proc = subprocess.run(self.args + ["--execute=" + sql],
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        except FileNotFoundError:
            raise SystemExit("[FATAL] 找不到 mysql 客户端：%s\n"
                             "        请用 --mysql 指定 mysql.exe 的完整路径。" % self.mysql)
        if proc.returncode != 0:
            err = proc.stderr.decode("utf-8", "replace").strip()
            raise SystemExit("[FATAL] mysql 执行失败 (exit %d)\n%s\nSQL: %s"
                             % (proc.returncode, err, sql))
        rows = []
        for line in proc.stdout.decode("utf-8", "replace").splitlines():
            line = line.strip()
            if line.startswith("{"):
                rows.append(json.loads(line))
        return rows

    def chunks(self, chunk_ids):
        sql = ("SELECT JSON_OBJECT('id',id,'document_id',document_id,"
               "'sec',section_title,'text',content) FROM rag_knowledge_chunk "
               "WHERE deleted=0 AND id IN (%s);"
               % ",".join(str(i) for i in chunk_ids))
        return {r["id"]: r for r in self.json_rows(sql)}

    def doc_texts(self, document_ids):
        sql = ("SELECT JSON_OBJECT('document_id',document_id,'text',content) "
               "FROM rag_knowledge_chunk WHERE deleted=0 AND document_id IN (%s);"
               % ",".join(str(i) for i in document_ids))
        out = {}
        for r in self.json_rows(sql):
            out.setdefault(r["document_id"], []).append(r["text"])
        return out


# ------------------------------------------------------------------ 显示宽度

def w(text):
    """按终端等宽显示宽度计算长度（CJK 记 2）。"""
    return sum(2 if unicodedata.east_asian_width(c) in "WF" else 1 for c in str(text))


def pad(text, width, align="<"):
    text = str(text)
    fill = " " * max(0, width - w(text))
    return (text + fill) if align == "<" else (fill + text)


def rule(char="-", n=100):
    return char * n


# ---------------------------------------------------------------------- 主流程

def main():
    # Windows 控制台默认按 GBK 输出中文会乱码，统一切到 UTF-8
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

    ap = argparse.ArgumentParser(description="校验 eval-hybrid-v2.jsonl 的零 bigram 重叠")
    ap.add_argument("--jsonl", default=DEFAULT_JSONL, help="评测集路径")
    ap.add_argument("--mysql", default=DEFAULT_MYSQL, help="mysql.exe 路径")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", default=3306)
    ap.add_argument("--user", default="root")
    ap.add_argument("--password", default="1829002")
    ap.add_argument("--database", default="campus_repair")
    ap.add_argument("--expect-total", type=int, default=22, help="期望总条数（0 = 不校验）")
    ap.add_argument("--expect-answerable", type=int, default=20, help="期望可回答条数（0 = 不校验）")
    ap.add_argument("--show-doc-overlap", action="store_true",
                    help="额外打印与目标文档的交集 bigram 明细")
    args = ap.parse_args()

    problems = []

    # ---- 读取评测集 ----
    raw = open(args.jsonl, "rb").read()
    if raw[:3] == b"\xef\xbb\xbf":
        problems.append("评测集带 UTF-8 BOM，应为无 BOM")
    cases = []
    for lineno, line in enumerate(raw.decode("utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            cases.append((lineno, json.loads(line)))
        except ValueError as exc:
            problems.append("第 %d 行不是合法 JSON：%s" % (lineno, exc))

    answerable = [(n, c) for n, c in cases if c.get("answerable") is True]
    unanswerable = [(n, c) for n, c in cases if c.get("answerable") is not True]

    print(rule("=", 108))
    print("RAG 检索评测集「零字面重叠」校验  eval-hybrid-v2")
    print("评测集：%s" % args.jsonl)
    print("库    ：%s@%s:%s/%s" % (args.user, args.host, args.port, args.database))
    print("bigram 规则：标点/空白为分隔符 → 切词块 → 对长度≥2 的词块切相邻 2 字"
          "（问题与切片正文同一函数）")
    print(rule("=", 108))

    # ---- 结构校验 ----
    if args.expect_total and len(cases) != args.expect_total:
        problems.append("总条数 %d ≠ 期望 %d" % (len(cases), args.expect_total))
    if args.expect_answerable and len(answerable) != args.expect_answerable:
        problems.append("answerable 条数 %d ≠ 期望 %d" % (len(answerable), args.expect_answerable))
    if not unanswerable:
        problems.append("缺少 answerable=false 的不可回答用例")
    for n, c in answerable:
        ids = c.get("expectedChunkIds") or []
        if len(ids) != 1:
            problems.append("第 %d 行 answerable 用例的 expectedChunkIds 应恰好 1 个目标切片，实为 %d"
                            % (n, len(ids)))
        if c.get("expectedKeywords"):
            problems.append("第 %d 行 answerable 用例的 expectedKeywords 应为空数组" % n)
        if not c.get("question"):
            problems.append("第 %d 行缺少 question" % n)
    for n, c in unanswerable:
        if c.get("expectedChunkIds"):
            problems.append("第 %d 行不可回答用例的 expectedChunkIds 应为空数组" % n)
        if c.get("expectedKeywords"):
            problems.append("第 %d 行不可回答用例的 expectedKeywords 应为空数组" % n)
        if not c.get("question"):
            problems.append("第 %d 行缺少 question" % n)

    # ---- 取切片正文 ----
    db = Db(args.mysql, args.host, args.port, args.user, args.password, args.database)
    ids = [c["expectedChunkIds"][0] for _, c in answerable if c.get("expectedChunkIds")]
    chunks = db.chunks(ids) if ids else {}
    for (n, c), cid in zip(answerable, ids):
        if cid not in chunks:
            problems.append("第 %d 行的目标切片 %s 在库中不存在或 deleted=1" % (n, cid))

    doc_ids = sorted({chunks[i]["document_id"] for i in ids if i in chunks})
    doc_texts = db.doc_texts(doc_ids) if doc_ids else {}
    doc_bigrams = {}
    for d, texts in doc_texts.items():
        s = set()
        for t in texts:
            s |= bigrams(t)
        doc_bigrams[d] = s

    # ---- 逐条校验 ----
    print()
    print(pad("序号", 5) + pad("chunkId", 20) + pad("问题bigram", 11)
          + pad("∩目标(必须0)", 14) + pad("∩目标文档", 11) + "说明")
    print(rule("-", 108))

    zero_pass = 0
    doc_overlap_notes = []
    for idx, (n, c) in enumerate(answerable, 1):
        cid = (c.get("expectedChunkIds") or [None])[0]
        ch = chunks.get(cid)
        if not ch:
            print(pad(idx, 5) + pad(cid, 20) + pad("-", 11) + pad("切片缺失", 14)
                  + pad("-", 11) + "无法校验")
            continue
        qb = bigrams(c["question"])
        tb = bigrams(ch["text"])
        inter = qb & tb
        dset = doc_bigrams.get(ch["document_id"], set())
        dinter = qb & dset
        if not inter:
            zero_pass += 1
        if dinter:
            doc_overlap_notes.append((idx, cid, sorted(dinter)))
        print(pad(idx, 5) + pad(cid, 20) + pad(len(qb), 11) + pad(len(inter), 14)
              + pad(len(dinter), 11)
              + ("通过（与目标切片零重叠）" if not inter
                 else "不通过！重叠 bigram = %s" % "、".join(sorted(inter))))
        if inter:
            problems.append("第 %d 行（chunkId=%s）与目标切片存在 %d 个 bigram 重叠：%s"
                            % (n, cid, len(inter), "、".join(sorted(inter))))

    total_a = len(answerable)
    rate = (100.0 * zero_pass / total_a) if total_a else 0.0

    # ---- 不可回答用例确认 ----
    print()
    print(rule("-", 108))
    print("不可回答用例确认（answerable=false，期望命中为空）：")
    all_texts = db.json_rows(
        "SELECT JSON_OBJECT('id',c.id,'text',c.content) FROM rag_knowledge_chunk c "
        "JOIN rag_knowledge_document d ON d.id=c.document_id "
        "WHERE c.deleted=0 AND d.deleted=0;")
    corpus_bigrams = [(r["id"], bigrams(r["text"])) for r in all_texts]
    for n, c in unanswerable:
        qb = bigrams(c["question"])
        best = 0
        for _, cb in corpus_bigrams:
            best = max(best, len(qb & cb))
        ids_ok = list(c.get("expectedChunkIds") or []) == []
        kw_ok = list(c.get("expectedKeywords") or []) == []
        print("  · %s" % c["question"])
        print("      expectedChunkIds=[] -> %s ；expectedKeywords=[] -> %s ；"
              "全库 %d 个切片中最高的 bigram 交集 = %d（无实质命中，需走拒答/转人工）"
              % ("OK" if ids_ok else "FAIL", "OK" if kw_ok else "FAIL",
                 len(corpus_bigrams), best))

    # ---- 汇总 ----
    print()
    print(rule("=", 108))
    print("总条数            ：%d" % len(cases))
    print("answerable 条数   ：%d（每条 1 个目标切片、expectedKeywords 为空）" % total_a)
    print("不可回答条数      ：%d" % len(unanswerable))
    print("覆盖文档数        ：%d" % len({c["targetSection"].split(" / ")[0]
                                       for _, c in answerable if c.get("targetSection")}))
    per_doc = {}
    for _, c in answerable:
        key = (c.get("targetSection") or "?/?").split(" / ")[0]
        per_doc[key] = per_doc.get(key, 0) + 1
    for k in sorted(per_doc):
        print("   %s : %d 条" % (pad(k, 26), per_doc[k]))
    print("零重叠通过         ：%d / %d" % (zero_pass, total_a))
    print("零重叠通过率       ：%.2f%%%s" % (rate, "  （要求 100%）" if rate == 100.0 else ""))
    if args.show_doc_overlap and doc_overlap_notes:
        print()
        print("附：与目标切片所属文档全部切片的 bigram 交集（仅统计，不要求为空）")
        for idx, cid, ov in doc_overlap_notes:
            print("   #%-3d %s -> %d 个：%s" % (idx, cid, len(ov), "、".join(ov)))
    print(rule("=", 108))

    if rate != 100.0:
        problems.append("零重叠通过率 %.2f%% 未达到 100%%" % rate)
    if problems:
        print("校验结论：未通过（%d 项问题）" % len(problems))
        for p in problems:
            print("  [X] %s" % p)
        return 1
    print("校验结论：全部通过 —— 每条 answerable 问题与目标切片的中文 bigram 交集均为空集，"
          "纯关键词检索在结构上无法命中目标切片。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
