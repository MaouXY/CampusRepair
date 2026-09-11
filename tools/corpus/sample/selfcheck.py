#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""selfcheck.py —— tools/corpus/sample 的自测脚本（只用标准库）。

覆盖内容：
  1) 单元测试：编码探测（UTF-8/BOM/GBK 回退）、docx 解析（<w:tab/> 与 <w:br/>）、
     标题识别与层级、清洗（页码 / 页眉页脚 / 硬换行合并）、章节切分、超长分块
  2) 端到端：真实调用 convert_corpus.py 跑一遍 sample 目录，逐行 json.loads 校验，
     并与 expected.jsonl 做逐行一致性比对
  3) 优雅降级：.doc / .pdf 在缺少解析库且禁用 Word 兜底时应“跳过 + 退出码正确”，不崩溃
  4) Word COM 兜底：真实调用 PowerShell + Word，把 .doc / .pdf 另存为 txt（第 3 条路径），
     并校验注册表临时改动已还原、临时 txt 已删除、无残留 WINWORD / PDFREFLOW 进程
     （检测不到 Word 时自动跳过这一节）

用法（在项目根目录执行）：
  python tools/corpus/sample/selfcheck.py
退出码：0 全部通过；1 存在失败项。
"""

from __future__ import annotations

import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import List, Sequence

SAMPLE_DIR = Path(__file__).resolve().parent
CORPUS_DIR = SAMPLE_DIR.parent
PROJECT_ROOT = CORPUS_DIR.parent.parent
CONVERTER = CORPUS_DIR / "convert_corpus.py"
EXPECTED = SAMPLE_DIR / "expected.jsonl"
DOCX_NAME = "校园报修工单处理办法.docx"
SOURCE = "示例来源"
MAX_CHARS = 1500
RECORD_KEYS = ["title", "source", "standardNo", "docType", "docVersion", "effectiveDate",
               "categoryId", "sectionTitle", "sectionLevel", "content", "charCount"]

# 动态 import 会被解释器写成 __pycache__/*.pyc，落在交付目录里不干净，这里关掉字节码落地
sys.dont_write_bytecode = True


class Checker:
    def __init__(self) -> None:
        self.passed = 0
        self.failed: List[str] = []

    def section(self, title: str) -> None:
        print("\n== %s ==" % title)

    def check(self, cond: bool, label: str, detail: object = "") -> bool:
        if cond:
            self.passed += 1
            print("  [PASS] %s" % label)
        else:
            self.failed.append(label)
            print("  [FAIL] %s   %s" % (label, detail))
        return bool(cond)


def load_module():
    spec = importlib.util.spec_from_file_location("convert_corpus_under_test", str(CONVERTER))
    if spec is None or spec.loader is None:
        raise RuntimeError("无法加载 %s" % CONVERTER)
    module = importlib.util.module_from_spec(spec)
    # dataclass 在 exec 期间需要能从 sys.modules 取到本模块，必须先注册
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def run_cli(input_path: Path, output_path: Path, extra: Sequence[str] = ()) -> subprocess.CompletedProcess:
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    cmd = [sys.executable, str(CONVERTER), "--input", str(input_path), "--output", str(output_path),
           "--source", SOURCE] + list(extra)
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace",
                          env=env, cwd=str(PROJECT_ROOT))


def unit_tests(mod, c: Checker) -> None:
    # ---------------------------------------------------------- 编码探测
    c.section("单元测试 1：编码探测（UTF-8 优先，失败回退 GB18030）")
    gbk_text = "校园后勤维修服务手册：设备巡检与报修响应。"
    text, used, _ = mod.decode_bytes(gbk_text.encode("gbk"), "auto")
    c.check(text == gbk_text, "GBK 字节自动识别为 GB18030 并正确解码", used)
    c.check(used in ("gb18030", "gbk"), "探测结果为 GB 系编码", used)

    utf8_bytes = "\ufeff" + "校园既有建筑设备维护通用规范"
    text, used, _ = mod.decode_bytes(utf8_bytes.encode("utf-8"), "auto")
    c.check(text == "校园既有建筑设备维护通用规范", "UTF-8 BOM 被剥离且正文正确", repr(text[:6]))
    c.check(used == "utf-8-sig", "使用 utf-8-sig 解码", used)

    text, used, _ = mod.decode_bytes(gbk_text.encode("gbk"), "gbk")
    c.check(text == gbk_text, "--encoding gbk 显式指定可用", used)

    # ---------------------------------------------------------- docx 解析
    c.section("单元测试 2：docx 解析（<w:p>/<w:t>/<w:tab/>/<w:br/>）")
    docx_path = SAMPLE_DIR / DOCX_NAME
    if not docx_path.exists():
        gen = importlib.util.spec_from_file_location("make_sample_docx", str(SAMPLE_DIR / "make_sample_docx.py"))
        gen_mod = importlib.util.module_from_spec(gen)
        gen.loader.exec_module(gen_mod)  # type: ignore[union-attr]
        gen_mod.build_docx(docx_path)
        print("  [信息] 示例 docx 不存在，已用 make_sample_docx.py 现场重新生成")
    result = mod.read_docx(docx_path)
    c.check(result.text is not None, "docx 解析成功", result.error)
    body = result.text or ""
    c.check("\t" in body and "维修班组：\t维修一组，联系电话：\t8377。" in body,
            "<w:tab/> 被解析为制表符", repr([l for l in body.splitlines() if "维修班组" in l]))
    c.check("报修工单分为三类：\n紧急工单、\n一般工单。" in body,
            "<w:br/> 被解析为软换行", repr([l for l in body.splitlines() if "工单分为" in l]))
    c.check("一、适用范围" in body, "段落文本按 <w:p> 正确取出")
    noise = result.meta.get("known_noise") or []
    c.check(any("后勤保障处" in str(n) for n in noise), "word/header1.xml 文本被收集为已知噪声行", noise)

    # ---------------------------------------------------------- 标题识别
    c.section("单元测试 3：标题识别与层级")
    yes_cases = [
        ("第一章 总则", 1), ("第3条 巡检要求", 3), ("第二节 巡检周期", 2),
        ("一、适用范围", 1), ("（一）受理时限", 2), ("1. 总则", 1),
        ("1.1 派单规则", 2), ("3.4 设施设备检查", 2), ("1.1.1 超时升级", 3), ("1.1.1.1 补充说明", 4),
        ("附录A 设备巡检要点（示例）", 1), ("附件2 维修记录表", 1), ("2 设施设备检查", 1),
    ]
    for line, level in yes_cases:
        got = mod.detect_heading(line)
        c.check(got is not None and got[0] == level, "识别标题 %r → level %d" % (line, level), got)
    no_cases = [
        "4.1 本规范自发布之日起施行，由后勤保障处负责解释。",
        "30 分钟内完成派单。",
        "本规范适用于校园既有建筑及配套设施的日常维护、",
        "2000.5 万元的改造项目应在当年完成验收。",
        "第 12 页",
    ]
    for line in no_cases:
        got = mod.detect_heading(line)
        c.check(got is None, "不误判为标题：%r" % line, got)
    long_line = "一、" + "设" * 45
    c.check(mod.detect_heading(long_line) is None, "超过 40 字的编号行不算标题")
    got = mod.detect_heading("## 1 服务范围", md_mode=True)
    c.check(got == (1, "1 服务范围"), "markdown 「## 1 服务范围」按正文编号规则取 level 1", got)
    got = mod.detect_heading("### 服务时限", md_mode=True)
    c.check(got == (3, "服务时限"), "markdown 「### 服务时限」按 # 数量取 level 3", got)

    # ---------------------------------------------------------- 清洗
    c.section("单元测试 4：文本清洗（页码 / 页眉页脚 / 硬换行合并）")
    raw = (
        "12\n"                                     # 页码
        "- 12 -\n"                                 # 页码
        "\u2014 12 \u2014\n"                       # 页码（em dash）
        "第 12 页\n"                                # 页码
        "Page 12 of 30\n"                          # 页码（英文）
        "12/30\n"                                  # 页码（比例）
        "内部资料 请勿外传\n"
        "本规范适用于校园既有建筑及配套设施的日常维护、\n"
        "巡检与维修管理。\n"
        "内部资料 请勿外传\n"
        "内部资料 请勿外传\n"
        "The maintenance unit shall establish\n"
        "equipment ledgers for each building.\n"
        "main-\n"
        "tenance records shall be kept.\n"
        "校园既有建筑设备维护通用规范\n"
        "GB/T 55022-2025\n"
        "3 报修与响应\n"
        "正文第一段。\n"
        "内部资料 请勿外传\n"
        "3 报修与响应\n"
        "正文第二段。\n"
        "3 报修与响应\n"
        "正文第三段。\n"
    )
    lines, stats = mod.clean_text(raw, min_repeat=3)
    c.check(stats["pages"] == 6, "6 种页码行全部剔除", stats)
    c.check("内部资料 请勿外传" not in lines, "重复 3 次的页眉行被剔除（频次法）")
    c.check(lines.count("3 报修与响应") == 3, "重复出现的“看起来像标题”的行因是标题被保留（防误删）", lines)
    c.check("本规范适用于校园既有建筑及配套设施的日常维护、巡检与维修管理。" in lines,
            "中文硬换行合并且中间不加空格")
    c.check("The maintenance unit shall establish equipment ledgers for each building." in lines,
            "英文硬换行合并且中间加空格")
    c.check("maintenance records shall be kept." in lines, "英文断词连字符被接合（main- + tenance）")
    c.check("校园既有建筑设备维护通用规范" in lines and "GB/T 55022-2025" in lines,
            "标准号等元数据行不与标题行误合并")

    raw2 = "第一段正文。\n\n\n\n第二段正文。\n\n"
    lines2, _ = mod.clean_text(raw2)
    c.check(lines2 == ["第一段正文。", "", "第二段正文。"], "连续空行压缩为最多一个，首尾空行去除", lines2)

    raw3 = "- 电子产品故障；\n- 个人物品损坏；\n"
    lines3, _ = mod.clean_text(raw3)
    c.check(len(lines3) == 2, "markdown 列表项各自成行，不被合并", lines3)

    # ---------------------------------------------------------- 切分
    c.section("单元测试 5：章节切分")
    sections = mod.split_sections(lines)
    titles = [s[0] for s in sections]
    c.check(titles == ["前言", "3 报修与响应", "3 报修与响应", "3 报修与响应"],
            "首行不是标题 → 生成「前言」；同一标题重复出现时各自成节（标题未被当页眉删除）", titles)
    c.check(all(s[2].strip() for s in sections) and all(s[0] not in s[2].splitlines() for s in sections),
            "每节正文非空，且 content 不含标题行本身", [s[2] for s in sections])

    lines4, _ = mod.clean_text("这是没有标题的开头说明。\n\n1.1 适用范围\n正文内容。\n")
    sections4 = mod.split_sections(lines4)
    c.check(sections4[0][0] == "前言" and sections4[0][1] == 0,
            "首行不是标题时生成「前言」章节（level 0）", sections4)
    c.check(len(sections4) == 2 and sections4[1][0] == "1.1 适用范围", "后续按标题正常切分", sections4)

    empty = mod.split_sections(["1 总则", "1.1 适用范围", "正文。"])
    c.check([s[0] for s in empty] == ["1.1 适用范围"], "没有正文的标题不成节（避免输出空 content）", empty)

    # ---------------------------------------------------------- 分块
    c.section("单元测试 6：超长章节分块")
    para = "本规范适用于校园既有建筑及配套设施的日常维护与巡检管理。" * 10   # 280 字
    content = "\n\n".join([para] * 10)
    chunks = mod.chunk_content(content, MAX_CHARS)
    c.check(len(chunks) >= 2, "超过 %d 字的章节被切分" % MAX_CHARS, len(chunks))
    c.check(all(len(x) <= MAX_CHARS for x in chunks), "每块长度都不超过上限", [len(x) for x in chunks])
    c.check("\n\n".join(chunks) == content, "按自然段边界切分，内容无丢失、无重复")

    long_one = "设" * 4000
    chunks2 = mod.chunk_content(long_one, MAX_CHARS)
    c.check(all(len(x) <= MAX_CHARS for x in chunks2) and "".join(chunks2) == long_one,
            "单段超长时按长度硬切且内容无损", [len(x) for x in chunks2])

    # ---------------------------------------------------------- 字段契约
    c.section("单元测试 7：输出字段契约")
    rec = mod.build_record(doc_name="既有建筑维护与改造通用规范", section_title="3.4 设施设备检查",
                           section_level=2, content="正文内容。", title_suffix="", source="GB 55022-2021",
                           standard_no="GB 55022-2021", doc_type="standard", doc_version="2021",
                           effective_date="2021-04-09", category_id=20001)
    c.check(list(rec.keys()) == RECORD_KEYS, "字段名与顺序符合约定", list(rec.keys()))
    c.check(rec["title"] == "既有建筑维护与改造通用规范 3.4 设施设备检查", "title = 文档名 + sectionTitle", rec["title"])
    c.check(rec["charCount"] == len(rec["content"]), "charCount = content 字符数")
    json.dumps(rec, ensure_ascii=False)
    c.check(True, "记录可被 json.dumps 序列化")


def end_to_end_tests(c: Checker) -> None:
    c.section("端到端测试 1：真实运行 CLI 处理 sample 目录")
    tmpdir = Path(tempfile.mkdtemp(prefix="corpus_selfcheck_"))
    try:
        fresh = tmpdir / "expected.jsonl"
        proc = run_cli(SAMPLE_DIR, fresh)
        print("  命令: python tools/corpus/convert_corpus.py --input tools/corpus/sample "
              "--output tools/corpus/sample/expected.jsonl --source %s" % SOURCE)
        if proc.stdout:
            print("  ---- 脚本输出 ----")
            for line in proc.stdout.strip().splitlines():
                print("  | " + line)
            print("  ------------------")
        c.check(proc.returncode == 0, "退出码为 0", proc.returncode)
        c.check(fresh.exists(), "生成输出文件")
        raw_lines = fresh.read_text(encoding="utf-8").splitlines()

        # 每行都能被 json.loads 解析
        records = []
        bad = []
        for i, line in enumerate(raw_lines, 1):
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as exc:
                bad.append((i, str(exc)))
        c.check(not bad, "每行都能被 json.loads 解析（共 %d 行）" % len(raw_lines), bad[:3])
        if not records:
            return

        c.check(all(isinstance(r, dict) for r in records), "每行都是一个 JSON 对象")
        c.check(all(list(r.keys()) == RECORD_KEYS for r in records), "每行字段名与数量一致")
        c.check(all(r["charCount"] == len(r["content"]) for r in records), "charCount 与 content 一致")
        c.check(all(len(r["content"]) <= MAX_CHARS for r in records), "单条 content 不超过 %d 字" % MAX_CHARS)
        c.check(all(r["source"] == SOURCE for r in records), "--source 生效")
        c.check(all(r["sectionLevel"] in (0, 1, 2, 3, 4, 5, 6) for r in records), "sectionLevel 取值合法")
        c.check(all(not r["content"].startswith(r["sectionTitle"]) for r in records),
                "content 不含标题行本身")
        c.check(len(records) == 25, "输出章节数为 25（md 9 + docx 6 + txt 10）", len(records))

        # 输出文件不允许有 BOM，且以换行结尾
        head = fresh.read_bytes()[:3]
        c.check(head != b"\xef\xbb\xbf", "输出为 UTF-8 无 BOM", head)
        c.check(fresh.read_bytes().endswith(b"\n"), "每行一个 JSON 对象且以换行结尾")

        # 期望输出一致性
        c.check(EXPECTED.exists(), "示例期望输出 expected.jsonl 存在")
        if EXPECTED.exists():
            exp_lines = EXPECTED.read_text(encoding="utf-8").splitlines()
            c.check(exp_lines == raw_lines, "实际输出与 expected.jsonl 逐行完全一致",
                    "行数 exp=%d act=%d" % (len(exp_lines), len(raw_lines)))

        # 清洗效果
        all_content = "\n".join(r["content"] for r in records)
        for noise in ("内部资料 请勿外传", "征求意见稿", "- 3 -", "第 4 页", "\u2014 5 \u2014",
                      "校园后勤保障处 编制", "后勤保障处 \u00b7 内部资料"):
            c.check(noise not in all_content, "噪声行已被清除：%r" % noise)
        page_lines = [l for l in all_content.splitlines()
                      if l.strip() and mod_looks_like_page_number(l)]
        c.check(not page_lines, "正文中不再存在纯页码行", page_lines[:5])

        # 硬换行合并 / tab / br 的实际结果
        by_title = {(r["sectionTitle"]): r for r in records}
        txt_repair = [r for r in records if r["sectionTitle"] == "3.1 报修受理"]
        c.check(txt_repair and "受理人员应在30 分钟内完成派单" in txt_repair[0]["content"],
                "txt 硬换行被合并（中文不加空格）", txt_repair[0]["content"] if txt_repair else None)
        docx_dispatch = [r for r in records if r["sectionTitle"] == "1.1 派单规则"]
        c.check(docx_dispatch and "维修班组： 维修一组，联系电话： 8377。" in docx_dispatch[0]["content"],
                "docx 的 <w:tab/> 转成空格", docx_dispatch[0]["content"] if docx_dispatch else None)
        c.check(docx_dispatch and "报修工单分为三类：" in docx_dispatch[0]["content"]
                and "紧急工单、一般工单。" in docx_dispatch[0]["content"],
                "docx 的 <w:br/> 转换行并正确重新合并", docx_dispatch[0]["content"] if docx_dispatch else None)
        c.check(by_title.get("前言", {}) and "GB/T 55022-2025" in str(by_title["前言"]["content"]),
                "txt 前言保留标准号行")

        # 附录A 分块（续N）
        appendix = [r for r in records if r["sectionTitle"].startswith("附录A")]
        c.check(len(appendix) == 2, "附录A 被拆成 2 条", len(appendix))
        if len(appendix) == 2:
            c.check(appendix[0]["title"].endswith("附录A 设备巡检要点（示例）"),
                    "第 1 条 title 不带续号", appendix[0]["title"])
            c.check(appendix[1]["title"].endswith("（续2）"), "第 2 条 title 追加（续2）", appendix[1]["title"])
            c.check(appendix[0]["sectionTitle"] == appendix[1]["sectionTitle"], "两条 sectionTitle 保持一致")
            c.check(sum(r["charCount"] for r in appendix) > MAX_CHARS, "附录A 总字数超过分块上限",
                    sum(r["charCount"] for r in appendix))
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


def mod_looks_like_page_number(line: str) -> bool:
    """自测里独立实现一份页码判定，避免直接复用被测代码导致“自己证明自己”。"""
    import re
    s = line.strip()
    return bool(re.fullmatch(r"[\-\u2010-\u2015\u2212]{0,2}\s*\d{1,4}\s*[\-\u2010-\u2015\u2212]{0,2}", s)
                or re.fullmatch(r"第\s*\d{1,4}\s*页", s)
                or re.fullmatch(r"\d{1,4}\s*[/\uFF0F]\s*\d{1,4}", s)
                or re.fullmatch(r"(?i)page\s*\d{1,4}(\s*of\s*\d{1,4})?", s))


def degradation_tests(c: Checker) -> None:
    c.section("端到端测试 2：.doc / .pdf 优雅降级与退出码（--no-word，不依赖 Word）")
    tmpdir = Path(tempfile.mkdtemp(prefix="corpus_degrade_"))
    try:
        # 1) 一个正常 txt + 一个 .doc → 应跳过 doc、正常输出、退出码 0
        mixed = tmpdir / "mixed"
        mixed.mkdir()
        (mixed / "正常文档.txt").write_text("1 总则\n维护单位应建立设备台账。\n", encoding="utf-8")
        (mixed / "旧版文档.doc").write_bytes(b"\xd0\xcf\x11\xe0old binary doc stub")
        mixed_out = tmpdir / "mixed.jsonl"
        proc = run_cli(mixed, mixed_out, ["--no-word"])
        c.check(proc.returncode == 0, "有成功文件时退出码为 0（.doc 只跳过）", proc.returncode)
        c.check("跳过" in proc.stdout and "另存为" in proc.stdout, "打印了 .doc 的中文转换提示", proc.stdout[-300:])
        c.check(mixed_out.exists() and len(mixed_out.read_text(encoding="utf-8").strip().splitlines()) == 1,
                "正常文件仍产出了 1 条语料")

        # 2) 只有一个 .doc → 全部失败 → 退出码 1
        only_doc = tmpdir / "only_doc"
        only_doc.mkdir()
        (only_doc / "旧版文档.doc").write_bytes(b"\xd0\xcf\x11\xe0old binary doc stub")
        proc = run_cli(only_doc, tmpdir / "only_doc.jsonl", ["--no-word"])
        c.check(proc.returncode == 1, "全部文件都失败时退出码为 1", proc.returncode)

        # 3) .pdf：缺少 pypdf/pdfminer 时应跳过并给出 pip 提示
        has_pdf_lib = True
        try:
            import pypdf  # noqa: F401
        except ImportError:
            try:
                import pdfminer  # noqa: F401
            except ImportError:
                has_pdf_lib = False
        only_pdf = tmpdir / "only_pdf"
        only_pdf.mkdir()
        (only_pdf / "示例规范.pdf").write_bytes(b"%PDF-1.4\n% stub pdf, not a real document\n")
        proc = run_cli(only_pdf, tmpdir / "only_pdf.jsonl", ["--no-word"])
        if not has_pdf_lib:
            c.check(proc.returncode == 1, "只有 PDF 且解析库缺失时退出码为 1（不崩溃）", proc.returncode)
            c.check("pip install pypdf" in proc.stdout and "pdfminer" in proc.stdout,
                    "打印了中文 pip 安装提示", proc.stdout[-400:])
            c.check("--no-word" in proc.stdout, "禁用 Word 兜底时明确提示已禁用", proc.stdout[-200:])
        else:
            c.check(proc.returncode in (0, 1), "本机已安装 PDF 解析库，仅校验不崩溃", proc.returncode)
            print("  [信息] 本机检测到 pypdf / pdfminer.six，跳过“缺库”断言")
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


def word_fallback_tests(c: Checker, mod) -> None:
    """Word COM 兜底（PDF / .doc 的第三条路径）端到端测试。

    本机没有 pypdf / pdfminer，因此这里正好验证 “PowerShell + Word COM 另存为 txt” 这条链路。
    检测不到 Word 时打印提示并跳过（不判失败），保证自测在无 Word 的机器上依然是绿的。
    """
    c.section("端到端测试 3：Word COM 兜底（PDF / .doc，无需 pip）")
    ps = mod.find_powershell()
    c.check(bool(ps) and Path(ps).is_file(), "找到 PowerShell 可执行文件", ps)
    info = mod.word_info()
    print("  [信息] Word COM 探测：%s" % info.get("message"))
    if not info.get("available"):
        print("  [信息] 本机未检测到 Word.Application COM，跳过 Word 兜底端到端测试")
        return

    tmpdir = Path(tempfile.mkdtemp(prefix="corpus_word_"))
    try:
        src_docx = SAMPLE_DIR / DOCX_NAME
        if not src_docx.exists():
            print("  [信息] 示例 docx 不存在，跳过 Word 兜底端到端测试")
            return

        # 1) 造夹具：用 Word 把示例 docx 另存为 .doc 与 .pdf（同时也验证了另存为接口）
        indir = tmpdir / "input"
        indir.mkdir()
        doc_file = indir / "校园报修工单处理办法（旧版）.doc"
        pdf_file = indir / "校园报修工单处理办法.pdf"
        r_doc = mod.word_save_as(src_docx, doc_file, mod.WD_FORMAT_DOCUMENT97, timeout=180)
        r_pdf = mod.word_save_as(src_docx, pdf_file, mod.WD_FORMAT_PDF, timeout=180)
        ok_fixtures = c.check(r_doc.ok and doc_file.is_file(), "用 Word 生成 .doc 夹具", r_doc.message[:200])
        c.check(r_pdf.ok and pdf_file.is_file(), "用 Word 生成 .pdf 夹具", r_pdf.message[:200])
        if not ok_fixtures:
            print("  [信息] 无法生成 Word 夹具，跳过后续 Word 端到端测试")
            return

        tmp_conv_dir = Path(tempfile.gettempdir()) / "corpus_convert"
        reg_before = mod._reg_read_pdf_warning()
        procs_before = mod._snapshot_word_pids()
        exe = str(info.get("exe") or "")

        # 2) 默认（Word 兜底开启）+ --word-path：.doc 与 .pdf 都应被解析出章节
        out = tmpdir / "word.jsonl"
        extra = ["--word-path", exe] if exe and Path(exe).is_file() else []
        proc = run_cli(indir, out, extra)
        print("  [信息] --word-path %s" % (exe if extra else "（未指定）"))
        c.check(proc.returncode == 0, "Word 兜底路径退出码为 0", proc.returncode)
        c.check("word-com" in proc.stdout, "日志显示使用了 word-com 解析引擎", proc.stdout[-500:])
        c.check("DisableConvertPdfWarning" in proc.stdout or "PDF 转换确认弹窗" in proc.stdout,
                "PDF 转换会绕开确认弹窗（临时设置注册表并还原）", proc.stdout[-700:])
        rows = []
        if out.exists():
            rows = [json.loads(l) for l in out.read_text(encoding="utf-8").splitlines()]
        c.check(len(rows) > 0, "Word 兜底至少产出一条语料", len(rows))
        titles = [r["title"] for r in rows]
        c.check(any("旧版" in t for t in titles), ".doc 经 Word 兜底解析出章节", titles[:3])
        c.check(any("旧版" not in t for t in titles), ".pdf 经 Word 兜底解析出章节", titles[:3])
        c.check(all(r["charCount"] == len(r["content"]) for r in rows), "Word 兜底结果 charCount 正确")
        c.check(not any(ch in c_["content"] for c_ in rows for ch in ("\x0c", "\x07", "\x0b")),
                "Word txt 的分页符 / 单元格标记等控制字符已清理")

        # 3) PDF 通道必须临时改注册表且必须还原
        reg_after = mod._reg_read_pdf_warning()
        c.check(reg_after == reg_before, "DisableConvertPdfWarning 注册表值已还原到调用前状态",
                "before=%s after=%s" % (reg_before, reg_after))

        # 4) 临时 txt 已删除、无残留 Word / PDFREFLOW 进程
        leftovers = sorted(p.name for p in tmp_conv_dir.iterdir()) if tmp_conv_dir.is_dir() else []
        c.check(not [n for n in leftovers if n.startswith("corpus-")],
                "%TEMP%\\corpus_convert 下的临时 txt 已删除", leftovers)
        procs_after = mod._snapshot_word_pids()
        new_pids = sorted(set().union(*procs_after.values()) - set().union(*procs_before.values()))
        c.check(not new_pids, "没有残留的 WINWORD / PDFREFLOW 进程", new_pids)

        # 5) --no-word：两条兜底路径都应关闭
        out2 = tmpdir / "noword.jsonl"
        proc2 = run_cli(indir, out2, ["--no-word"])
        c.check(proc2.returncode == 1, "--no-word 时 .doc/.pdf 全部失败退出码为 1", proc2.returncode)
        c.check("已用 --no-word 禁用 Word 兜底" in proc2.stdout, "--no-word 提示清楚", proc2.stdout[-400:])
        c.check("另存为" in proc2.stdout, "--no-word 时给出“另存为 .docx/.txt”的中文建议")
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


def main() -> int:
    try:
        sys.stdout.reconfigure(errors="replace")  # type: ignore[attr-defined]
    except Exception:
        pass
    print("=" * 72)
    print("tools/corpus 自测 selfcheck.py")
    print("被测脚本：%s" % CONVERTER)
    print("示例目录：%s" % SAMPLE_DIR)
    print("=" * 72)
    if not CONVERTER.exists():
        print("[错误] 找不到 convert_corpus.py")
        return 1

    checker = Checker()
    mod = load_module()
    unit_tests(mod, checker)
    end_to_end_tests(checker)
    degradation_tests(checker)
    word_fallback_tests(checker, mod)

    print("\n" + "=" * 72)
    print("自测结果：通过 %d 项，失败 %d 项" % (checker.passed, len(checker.failed)))
    for item in checker.failed:
        print("  失败：%s" % item)
    print("=" * 72)
    return 0 if not checker.failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
