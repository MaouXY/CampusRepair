#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""convert_corpus.py —— 规范 / 手册类文档 → 后端可直接导入的 JSONL 语料。

零第三方依赖（核心功能只用 Python 标准库）：
  .txt / .md  直接读取；自动探测编码（UTF-8 优先，失败回退 GBK / GB18030）
  .docx       zipfile + xml.etree 解析 word/document.xml，按 <w:p> 取 <w:t>，
              支持 <w:tab/>（转空格）与 <w:br/>（转换行），无需 python-docx
  .pdf        三条路径：优先 pypdf，其次 pdfminer.six，最后 Word COM 兜底
               （PowerShell + Word.Application 另存为 Unicode txt，无需 pip）；
               三条都走不通时打印中文提示并记为该文件 skipped（不崩溃）
  .doc / .rtf  旧格式：优先 Word COM 另存为 txt；Word 不可用时提示先另存为 .docx / .txt
               并记为 skipped（.wps 是 WPS 私有格式，不参与兜底）

输出：UTF-8 无 BOM，每行一个 JSON 对象，字段为
  title / source / standardNo / docType / docVersion / effectiveDate /
  categoryId / sectionTitle / sectionLevel / content / charCount

示例（Windows / Anaconda）：
  D:\\Anaconda3\\python.exe tools\\corpus\\convert_corpus.py ^
      --input tools\\corpus\\sample --output tools\\corpus\\sample\\expected.jsonl ^
      --source 示例来源
"""

from __future__ import annotations

import argparse
import codecs
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import uuid
import zipfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

VERSION = "1.1.0"

# ---------------------------------------------------------------- 常量与正则

W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
W_P = "{%s}p" % W_NS
W_T = "{%s}t" % W_NS
W_TAB = "{%s}tab" % W_NS
W_BR = "{%s}br" % W_NS
W_CR = "{%s}cr" % W_NS
W_PPR = "{%s}pPr" % W_NS
W_RPR = "{%s}rPr" % W_NS

EXT_TEXT = {".txt", ".text", ".md", ".markdown"}
EXT_DOCX = {".docx", ".docm"}
EXT_PDF = {".pdf"}
EXT_LEGACY = {".doc", ".wps", ".rtf"}
# 旧版格式里 Word 能直接打开的（.wps 是 WPS 私有格式，Word 常常打不开，不参与兜底）
EXT_WORD_OPENABLE = {".doc", ".rtf"}
EXT_SUPPORTED = EXT_TEXT | EXT_DOCX | EXT_PDF
# 旧版格式也会被“认领”：不解析，但会打印“请先另存为 .docx / .txt”的提示并记为 skipped
EXT_RECOGNISED = EXT_SUPPORTED | EXT_LEGACY

MAX_TITLE_LEN = 40          # 标题行最大长度（字符），超过视为正文
DEFAULT_MAX_CHARS = 1500    # 单条 content 最大字符数
PREAMBLE_TITLE = "前言"
PREAMBLE_LEVEL = 0          # 0 表示前言 / 前置内容

# Word COM 兜底用到的 wdSaveFormat 常量
WD_FORMAT_DOCUMENT97 = 0        # wdFormatDocument97：旧版 .doc
WD_FORMAT_UNICODE_TEXT = 7      # wdFormatUnicodeText：UTF-16LE，避免中文乱码
WD_FORMAT_TEXT = 2              # wdFormatText：系统编码 txt（不推荐，中文易乱码）
WD_FORMAT_PDF = 17              # wdFormatPDF
WORD_DEFAULT_TIMEOUT = 120      # 秒

# 零宽字符与行分隔符
RE_ZERO_WIDTH = re.compile("[\u200b-\u200f\u2028\u2029\u2060\ufeff]")

# 页码行：12 / - 12 - / — 12 — / 第 12 页 / 12/30 / Page 12 of 30 / 共 12 页
_DASH = "\u2010-\u2015\u2212"
RE_PAGE_BARE = re.compile(r"^[-\u2010-\u2015\u2212]{0,2}\s*\d{1,4}\s*[-\u2010-\u2015\u2212]{0,2}$")
RE_PAGE_CN = re.compile(r"^第\s*\d{1,4}\s*页(\s*[/／、共]\s*\d{1,4}\s*页?)?$")
RE_PAGE_RATIO = re.compile(r"^\d{1,4}\s*[/／]\s*\d{1,4}$")
RE_PAGE_EN = re.compile(r"^(page|p\.?)\s*\d{1,4}(\s*(of|/)\s*\d{1,4})?$", re.IGNORECASE)
RE_PAGE_TOTAL_CN = re.compile(r"^共\s*\d{1,4}\s*页$")
PAGE_NUM_PATTERNS = (RE_PAGE_BARE, RE_PAGE_CN, RE_PAGE_RATIO, RE_PAGE_EN, RE_PAGE_TOTAL_CN)

# 句末标点：行尾是这些字符时认为该行是一个完整句子，不再与下一行合并
CN_SENT_END = "。；：！？）》”』】…"
ASCII_SENT_END = ".!?;:"
# 标题行的“把句子误判成标题”防护：只有以句末标点结尾（且不是括号/引号结尾）才否决。
# 注意不能把 ）》” 算进去，否则“附录A 设备巡检要点（示例）”“第三章 设备维护（修订）”会被漏判。
HEADING_STOP_END = "。！？；"
# 合并英文硬换行时，这些字符后面 / 前面不加空格
NO_SPACE_AFTER = "([{\u201c\"'"
NO_SPACE_BEFORE = ")]}\u201d\"'.,;:!?\uff0c\u3002\uff1b\uff1a\uff01\uff1f\u3001"

# 中文数字
CN_NUM = "零〇一二三四五六七八九十百千两"

# 标题识别
RE_CHAPTER = re.compile(r"^第\s*(?P<n>[0-9%s]{1,6})\s*(?P<kind>[章节条])(?P<rest>.*)$" % CN_NUM)
RE_APPENDIX = re.compile(r"^附[录件]\s*[0-9A-Za-z%s]{1,4}(?P<rest>.*)$" % CN_NUM)
RE_CN_L1 = re.compile(r"^[%s]{1,3}[、.．]\s*\S" % CN_NUM)
RE_CN_L2 = re.compile(r"^[（(][%s]{1,3}[)）]\s*\S" % CN_NUM)
RE_DOTTED = re.compile(r"^(?P<num>\d{1,3}(?:\.\d{1,3}){1,4})[.．、]?\s*(?P<rest>\S.*)$")
RE_NUM_DOT = re.compile(r"^(?P<num>\d{1,3})[.．、]\s*(?P<rest>\S.*)$")
RE_NUM_SPACE = re.compile(r"^(?P<num>\d{1,3})\s+(?P<rest>\S.*)$")
RE_MD_ATX = re.compile(r"^(#{1,6})\s+(\S.*?)\s*$")
KIND_LEVEL = {"章": 1, "节": 2, "条": 3}

# 元数据行（标准号 / 版本 / 日期 / 编号）：不参与硬换行合并，避免“标题+标准号”被粘成一行
RE_META_STDNO = re.compile(r"^[A-Za-z][A-Za-z/.\-\s]{0,14}\d{1,6}(?:[-\u2010-\u2015./]\d{1,4}){0,3}[A-Za-z]?$")
RE_META_DATE = re.compile(r"^\d{4}\s*[-\u2010-\u2015./年]\s*\d{1,2}\s*[-\u2010-\u2015./月]\s*\d{1,2}\s*日?$")
RE_META_LABEL = re.compile(
    r"^(编\s*号|文件编号|文档编号|密级|版本|版次|修订|状态|类别|发布日期|实施日期|生效日期"
    r"|发布单位|主编单位|编制单位|编写单位|起草单位|审核|会签|批准|日期|适用对象)\s*[：:].*$"
)

# 列表 / 引用 / 表格行：不参与硬换行合并
RE_LISTISH = re.compile(r"^(?:[-*+\u2022\u00b7\u25aa\u25e6]\s+|\d{1,2}[)）]\s*|>\s*|\|)")

# 全角空格 / 制表符归一
RE_MULTI_SPACE = re.compile(r"[ \t\u00a0\u3000]+")

DOC_TYPE_KEYWORDS = (
    ("standard", ("规范", "标准", "规程", "通则", "技术规定", "gb", "jgj", "cjj", "db", "t/cecs")),
    ("policy", ("办法", "规定", "制度", "条例", "章程", "细则", "通知", "意见", "方案", "政策", "管理")),
    ("case", ("案例", "记录", "工单", "报告", "总结", "台账", "汇编", "案例集")),
    ("manual", ("手册", "说明书", "指南", "操作", "维保", "使用说明", "白皮书")),
)


# ---------------------------------------------------------------- 基础输出

def safe_console() -> None:
    """Windows 控制台多为 GBK，避免打印生僻字符时抛 UnicodeEncodeError。"""
    for stream in (sys.stdout, sys.stderr):
        try:
            enc = (getattr(stream, "encoding", "") or "").lower().replace("-", "")
            if enc and enc not in ("utf8", "utf8sig"):
                stream.reconfigure(errors="replace")  # type: ignore[attr-defined]
        except Exception:
            pass


def warn(msg: str) -> None:
    print("[警告] " + msg, file=sys.stderr)


def info(msg: str) -> None:
    print("[信息] " + msg)


# ---------------------------------------------------------------- 读取层

@dataclass
class ReadResult:
    text: Optional[str] = None
    error: Optional[str] = None            # 非空 ⇒ 该文件 skipped
    warning: Optional[str] = None
    meta: Dict[str, object] = field(default_factory=dict)


@dataclass
class WordFallback:
    """Word COM 兜底配置（PDF / .doc 的第三条路径，无需 pip）。"""
    enabled: bool = True
    word_path: Optional[str] = None
    timeout: int = WORD_DEFAULT_TIMEOUT


def decode_bytes(data: bytes, encoding: str = "auto") -> Tuple[str, str, Optional[str]]:
    """按指定或自动探测的编码解码。返回 (text, used_encoding, warning)。"""
    enc = (encoding or "auto").strip()
    if enc.lower() != "auto":
        try:
            return data.decode(enc), enc, None
        except LookupError:
            text, used, _ = decode_bytes(data, "auto")
            return text, used, "未知编码 %s，已改用自动探测（%s）" % (enc, used)
        except UnicodeDecodeError:
            text, used, _ = decode_bytes(data, "auto")
            return text, used, "按 %s 解码失败，已自动改用 %s" % (enc, used)

    if data.startswith(codecs.BOM_UTF16_LE) or data.startswith(codecs.BOM_UTF16_BE):
        try:
            return data.decode("utf-16"), "utf-16", None
        except UnicodeDecodeError:
            pass
    # UTF-8 优先（utf-8-sig 兼容 BOM），失败回退 GB18030（GBK 超集）
    for cand in ("utf-8-sig", "gb18030"):
        try:
            return data.decode(cand), cand, None
        except UnicodeDecodeError:
            continue
    return (
        data.decode("utf-8", "replace"),
        "utf-8(replace)",
        "UTF-8 与 GB18030 解码均失败，已用 utf-8(replace) 兜底，请检查源文件编码",
    )


def read_text_file(path: Path, encoding: str = "auto") -> ReadResult:
    try:
        data = path.read_bytes()
    except OSError as exc:
        return ReadResult(error="读取失败：%s" % exc)
    if not data.strip():
        return ReadResult(error="文件为空")
    text, used, warning = decode_bytes(data, encoding)
    if not text.strip():
        return ReadResult(error="解码后没有有效文本")
    return ReadResult(text=text, warning=warning, meta={"encoding": used})


def _docx_part_text(xml_bytes: bytes) -> str:
    """把一段 OOXML（document / header / footer）转成纯文本，一段 <w:p> 一行。"""
    root = ET.fromstring(xml_bytes)
    paragraphs: List[str] = []
    for para in root.iter(W_P):
        chunks: List[str] = []

        def walk(node: ET.Element) -> None:
            for child in node:
                tag = child.tag
                if tag in (W_PPR, W_RPR):      # 段落/字符属性里也有 <w:tab/>，必须跳过
                    continue
                if tag == W_T:
                    chunks.append(child.text or "")
                elif tag == W_TAB:
                    chunks.append("\t")        # <w:tab/> → 制表符（后续归一为空格）
                elif tag in (W_BR, W_CR):
                    chunks.append("\n")        # <w:br/> 软换行 → 换行
                else:
                    walk(child)

        walk(para)
        paragraphs.append("".join(chunks))
    return "\n".join(paragraphs)


def read_docx(path: Path) -> ReadResult:
    """只用标准库解析 .docx（zipfile + xml.etree）。"""
    try:
        with zipfile.ZipFile(path) as zf:
            names = zf.namelist()
            lowered = {n.lower(): n for n in names}
            if "word/document.xml" not in lowered:
                return ReadResult(error="不是有效的 .docx（缺少 word/document.xml），若是旧版 .doc 请先另存为 .docx")
            try:
                text = _docx_part_text(zf.read(lowered["word/document.xml"]))
            except ET.ParseError as exc:
                return ReadResult(error="word/document.xml 解析失败：%s" % exc)

            # word/header*.xml、word/footer*.xml 中的文本作为“已知噪声行”候选
            known_noise: List[str] = []
            for low, real in sorted(lowered.items()):
                if re.fullmatch(r"word/(header|footer)\d*\.xml", low):
                    try:
                        known_noise.extend(_docx_part_text(zf.read(real)).splitlines())
                    except ET.ParseError:
                        continue
            if not text.strip():
                return ReadResult(error="docx 正文为空")
            return ReadResult(text=text, meta={"known_noise": known_noise, "engine": "docx(zipfile+xml)"})
    except zipfile.BadZipFile:
        return ReadResult(error=".docx 不是有效的 zip 容器（可能是旧版 .doc 改名而来，请另存为 .docx）")
    except OSError as exc:
        return ReadResult(error="读取失败：%s" % exc)


def read_pdf(path: Path, word: Optional["WordFallback"] = None) -> ReadResult:
    """PDF 三条路径：pypdf → pdfminer.six → Word COM（另存为 txt）。"""
    word = word or WordFallback()
    problems: List[str] = []

    PdfReader = None
    try:
        from pypdf import PdfReader  # type: ignore
    except ImportError as exc:
        problems.append("pypdf 未安装（%s）" % exc)
    if PdfReader is not None:
        try:
            reader = PdfReader(str(path))
            pages = [(pg.extract_text() or "") for pg in reader.pages]
            text = "\n".join(pages)
            if text.strip():
                return ReadResult(text=text, meta={"engine": "pypdf", "pages": len(pages)})
            problems.append("pypdf 未提取到文本（可能是扫描版 PDF）")
        except Exception as exc:                      # noqa: BLE001 - 第三方库异常统一降级
            problems.append("pypdf 解析失败：%s" % exc)

    extract_text = None
    try:
        from pdfminer.high_level import extract_text  # type: ignore
    except ImportError as exc:
        problems.append("pdfminer.six 未安装（%s）" % exc)
    if extract_text is not None:
        try:
            text = extract_text(str(path))
            if text and text.strip():
                return ReadResult(text=text, meta={"engine": "pdfminer.six"})
            problems.append("pdfminer.six 未提取到文本（可能是扫描版 PDF）")
        except Exception as exc:                      # noqa: BLE001
            problems.append("pdfminer.six 解析失败：%s" % exc)

    no_lib = all(("未安装" in p) for p in problems) and problems
    if no_lib or not problems:
        lib_hint = (
            "PDF 解析库缺失（pip install pypdf 或 pdfminer.six 可避免这一提示）。\n"
            "      详细原因：" + "；".join(problems)
        )
    else:
        lib_hint = (
            "pypdf / pdfminer.six 未能提取到文本（可能是扫描版 PDF）。\n"
            "      详细原因：" + "；".join(problems)
        )

    # 第三条路径：Word COM（无需 pip）
    if word.enabled:
        info = word_info(word.word_path)
        if info.get("available") or (word.word_path and os.path.isfile(word.word_path)):
            result = word_convert_to_text(path, word_path=word.word_path, timeout=word.timeout)
            if result.text is not None:
                result.meta = dict(result.meta)
                result.meta["lib_problems"] = problems
                return result
            return ReadResult(error=lib_hint + "\n      Word COM 兜底也失败：" + str(result.error))
        return ReadResult(error=(
            lib_hint + "\n      Word COM 兜底不可用（%s）。\n"
            "      建议：先用 Word / WPS 打开该 PDF，另存为 .txt 或 .docx 后重新运行本脚本。"
            % info.get("message")
        ))
    return ReadResult(error=lib_hint + "\n      已用 --no-word 禁用 Word 兜底；建议另存为 .txt 后重试。")


def read_legacy(path: Path, word: Optional["WordFallback"] = None) -> ReadResult:
    """旧版 .doc / .rtf：Word COM 可打开的直接转换，否则提示另存为 .docx / .txt。"""
    word = word or WordFallback()
    ext = path.suffix.lower()
    if word.enabled and ext in EXT_WORD_OPENABLE:
        info = word_info(word.word_path)
        if info.get("available") or (word.word_path and os.path.isfile(word.word_path)):
            result = word_convert_to_text(path, word_path=word.word_path, timeout=word.timeout)
            if result.text is not None:
                return result
            return ReadResult(error=(
                "%s 为旧版格式，Word COM 兜底转换失败：%s\n"
                "        建议：用 Word 手动打开后另存为 .docx 或 .txt。" % (ext, result.error)
            ))
        reason = "Word COM 不可用（%s）" % info.get("message")
    elif word.enabled:
        reason = "%s 不是 Word 能可靠打开的格式" % ext
    else:
        reason = "已用 --no-word 禁用 Word 兜底"
    return ReadResult(error=(
        "%s 为旧版二进制格式，本脚本不直接解析（%s）。请先用 Word / WPS 另存为 .docx 或 .txt 后重试。"
        % (ext, reason)
    ))


# ---------------------------------------------------------------- Word COM 兜底（无需 pip）

# powershell.exe 常常不在 PATH 里（本机 pwsh 7 在 PATH、Windows PowerShell 5.1 不在），
# 因此显式列出候选路径，避免 subprocess 直接 FileNotFoundError。
PS_CANDIDATES = (
    os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "System32",
                 "WindowsPowerShell", "v1.0", "powershell.exe"),
    os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "SysWOW64",
                 "WindowsPowerShell", "v1.0", "powershell.exe"),
    "powershell.exe",
    "pwsh.exe",
    os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"), "PowerShell", "7", "pwsh.exe"),
)

# 探测 Word COM：先用 GetTypeFromProgID（很快，不会启动 Word），再取版本与安装目录
# 输出用 | 分隔（Word 安装目录含空格，不能用空格分词）
PS_WORD_INFO = (
    "$ErrorActionPreference='Stop';"
    "$t=[Type]::GetTypeFromProgID('Word.Application');"
    "if($t -eq $null){Write-Output 'WORD-NA';exit 0};"
    "$w=$null;"
    "try{$w=New-Object -ComObject Word.Application;$v=$w.Version;$p='';"
    "try{$p=[string]$w.Path}catch{};Write-Output ('WORD-OK|'+$v+'|'+$p)}"
    "catch{Write-Output ('WORD-ERR|'+$_.Exception.Message)}"
    "finally{if($w){try{$w.Quit(0)}catch{}}}"
)

_PS_CACHE: Dict[str, Optional[str]] = {}
_WORD_INFO_CACHE: Dict[str, Dict[str, object]] = {}


def find_powershell() -> Optional[str]:
    """返回可用的 PowerShell 可执行文件路径（带缓存）。"""
    key = "__ps__"
    if key in _PS_CACHE:
        return _PS_CACHE[key]
    found: Optional[str] = None
    for cand in PS_CANDIDATES:
        if os.sep in cand:
            if os.path.isfile(cand):
                found = cand
                break
        else:
            hit = shutil.which(cand)
            if hit:
                found = hit
                break
    _PS_CACHE[key] = found
    return found


def _ps_quote(value: object) -> str:
    """PowerShell 单引号字符串（单引号需写成两个）。"""
    return "'" + str(value).replace("'", "''") + "'"


def word_info(word_path: Optional[str] = None) -> Dict[str, object]:
    """探测 Word COM 是否可用。返回 {available, version, exe, message}（带缓存）。"""
    key = word_path or ""
    if key in _WORD_INFO_CACHE:
        return _WORD_INFO_CACHE[key]

    info: Dict[str, object] = {"available": False, "version": "", "exe": "", "message": ""}
    ps = find_powershell()
    if ps is None:
        info["message"] = "找不到 PowerShell（已尝试 powershell.exe / pwsh.exe），无法调用 Word COM"
        _WORD_INFO_CACHE[key] = info
        return info

    try:
        proc = subprocess.run([ps, "-NoProfile", "-NonInteractive", "-Command", PS_WORD_INFO],
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
        out = decode_bytes(proc.stdout or b"", "auto")[0].strip()
    except subprocess.TimeoutExpired:
        info["message"] = "探测 Word COM 超时（60 秒）"
        _WORD_INFO_CACHE[key] = info
        return info
    except OSError as exc:
        info["message"] = "无法启动 PowerShell：%s" % exc
        _WORD_INFO_CACHE[key] = info
        return info

    if out.startswith("WORD-OK"):
        parts = out.split("|")
        version = parts[1].strip() if len(parts) > 1 else ""
        install_dir = parts[2].strip() if len(parts) > 2 else ""
        exe = os.path.join(install_dir, "WINWORD.EXE") if install_dir else ""
        if word_path and os.path.isfile(word_path):
            exe = word_path                      # 用户指定优先展示
        info.update(available=True, version=version, exe=exe)
        info["message"] = "可用（Word %s%s）" % (version, "，" + exe if exe else "")
    else:
        detail = out.split("|", 1)[1].strip() if out.startswith("WORD-ERR") and "|" in out else ""
        info["message"] = "不可用：未检测到 Word.Application COM 组件%s" % ("（" + detail + "）" if detail else "")
        if word_path:
            if os.path.isfile(word_path):
                info["message"] += "；--word-path 显示的 WINWORD.EXE 存在（%s），仍会尝试调用" % word_path
            else:
                info["message"] += "；--word-path 指定的文件不存在：%s" % word_path
    _WORD_INFO_CACHE[key] = info
    return info


def word_com_available(word_path: Optional[str] = None) -> bool:
    return bool(word_info(word_path).get("available"))


def word_save_as(src: Path, out: Path, fmt: int = WD_FORMAT_UNICODE_TEXT, *,
                 word_path: Optional[str] = None, timeout: int = WORD_DEFAULT_TIMEOUT) -> Tuple[bool, str]:
    """用 Word COM 把 src 另存为 out（fmt 见 wdSaveFormat）。返回 (是否成功, 说明)。"""
    src, out = Path(src), Path(out)
    ps = find_powershell()
    if ps is None:
        return False, "找不到 PowerShell（已尝试 powershell.exe / pwsh.exe）"

    script = (
        "$ErrorActionPreference='Stop';"
        "$confirm=$false;$readonly=$true;$ok=$false;$err='';$doc=$null;"
        "$w=New-Object -ComObject Word.Application;"
        "$w.Visible=$false;$w.DisplayAlerts=0;"
        "try{"
        "$doc=$w.Documents.Open(%s,[ref]$confirm,[ref]$readonly);"
        "$doc.SaveAs2(%s,%d);"
        "$doc.Close(0);$doc=$null;$ok=$true"
        "}catch{$err=$_.Exception.Message}"
        "finally{if($doc){try{$doc.Close(0)}catch{}};if($w){try{$w.Quit(0)}catch{}}}"
        "if($ok){Write-Output 'CONVERT-OK'}else{Write-Output ('CONVERT-FAIL: '+$err);exit 1}"
    ) % (_ps_quote(src), _ps_quote(out), int(fmt))

    try:
        proc = subprocess.run([ps, "-NoProfile", "-NonInteractive", "-Command", script],
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    except subprocess.TimeoutExpired:
        return False, ("Word COM 调用超时（> %d 秒）。如任务管理器里残留 WINWORD.EXE 进程，请手动结束；"
                       "也可先用 Word 打开该文件另存为 .docx / .txt" % timeout)
    except OSError as exc:
        return False, "无法启动 PowerShell：%s" % exc

    text = decode_bytes(proc.stdout or b"", "auto")[0].strip()
    if proc.returncode == 0 and "CONVERT-OK" in text and out.is_file():
        return True, text
    detail = text
    if detail.startswith("CONVERT-FAIL:"):
        detail = detail[len("CONVERT-FAIL:"):].strip()
    return False, (detail or "PowerShell 退出码 %s" % proc.returncode)


def _word_temp_dir() -> Path:
    """临时 txt 输出目录：%TEMP%\\corpus_convert\\"""
    path = Path(tempfile.gettempdir()) / "corpus_convert"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _strip_word_artifacts(text: str) -> str:
    """清理 Word 纯文本导出里的控制字符：分页符 \\x0c、软换行 \\x0b、单元格标记 \\x07。"""
    text = text.replace("\x0c", "\n").replace("\x0b", "\n").replace("\x07", " ").replace("\x00", "")
    return re.sub(r"[\x01-\x08\x0e-\x1f]", "", text)


def word_convert_to_text(src: Path, *, word_path: Optional[str] = None,
                         timeout: int = WORD_DEFAULT_TIMEOUT) -> ReadResult:
    """PDF / .doc 的第三条兜底路径：调用 Word COM 另存为 Unicode txt，读完即删。"""
    info = word_info(word_path)
    if not info.get("available") and not (word_path and os.path.isfile(word_path)):
        return ReadResult(error=(
            "PDF / .doc 无法解析，且 Word COM 兜底不可用：%s\n"
            "        建议：用 Word 或 WPS 打开该文件，另存为 .docx 或 .txt 后重新运行本脚本。"
            % info.get("message")
        ))

    tmp_dir = _word_temp_dir()
    out_path = tmp_dir / ("corpus-%s.txt" % uuid.uuid4().hex[:10])
    try:
        ok, message = word_save_as(src, out_path, WD_FORMAT_UNICODE_TEXT,
                                  word_path=word_path, timeout=timeout)
        if not ok:
            return ReadResult(error=(
                "Word COM 转换失败：%s\n"
                "        建议：用 Word 手动打开该文件，另存为 .docx 或 .txt 后重新运行本脚本。" % message
            ))
        data = out_path.read_bytes()
        if not data.strip():
            return ReadResult(error="Word COM 转换后 txt 为空（可能是扫描版 PDF，需要 OCR）")
        text, used, warning = decode_bytes(data, "auto")
        text = _strip_word_artifacts(text)
        if not text.strip():
            return ReadResult(error="Word COM 转换后没有有效文本")
        return ReadResult(text=text, warning=warning, meta={
            "engine": "word-com(wdFormatUnicodeText/%s)" % used,
            "word_note": "Word COM 兜底：%s → %s（转换完成已删除）" % (src.name, out_path),
        })
    except OSError as exc:
        return ReadResult(error="Word COM 临时文件读写失败：%s" % exc)
    finally:
        try:
            if out_path.is_file():
                out_path.unlink()
            if tmp_dir.is_dir() and not any(tmp_dir.iterdir()):
                tmp_dir.rmdir()
        except OSError:
            pass


def read_any(path: Path, encoding: str = "auto", word: Optional["WordFallback"] = None) -> ReadResult:
    ext = path.suffix.lower()
    if ext in EXT_TEXT:
        return read_text_file(path, encoding)
    if ext in EXT_DOCX:
        return read_docx(path)
    if ext in EXT_PDF:
        return read_pdf(path, word)
    if ext in EXT_LEGACY:
        return read_legacy(path, word)
    return ReadResult(error="不支持的文件类型：%s" % (ext or "(无扩展名)"))


# ---------------------------------------------------------------- 标题识别

def _is_sentence_end(line: str) -> bool:
    """用于硬换行合并判断：这一行是否已经是一个完整句子（句末标点 / 顿号结束等）。"""
    if not line:
        return True
    return line[-1] in CN_SENT_END or line[-1] in ASCII_SENT_END


def _is_sentence_like(line: str) -> bool:
    """用于标题判断：以句末标点结尾的行属于正文句子，不算标题。"""
    return bool(line) and line[-1] in HEADING_STOP_END


def _has_inner_sentence_punct(line: str) -> bool:
    return any(ch in line for ch in "。；，！？：,;!?")


def detect_heading(line: str, md_mode: bool = False) -> Optional[Tuple[int, str]]:
    """识别标题行，返回 (层级, 标题文本)；不是标题返回 None。

    层级约定：章 / 一、 / 附录 / 附件 / 单级编号 = 1；节 / （一） / x.y = 2；
    条 / x.y.z = 3；x.y.z.w = 4。Markdown 的 # 只有在正文规则匹配不上时才用作层级。
    """
    s = (line or "").strip()
    if not s or len(s) > MAX_TITLE_LEN:
        return None

    if md_mode:
        m = RE_MD_ATX.match(s)
        if m:
            text = m.group(2).strip()
            if not text or len(text) > MAX_TITLE_LEN:
                return None
            inner = detect_heading(text, md_mode=False)
            if inner:
                return inner
            return (min(len(m.group(1)), 6), text)

    # 第X章 / 第X节 / 第X条
    m = RE_CHAPTER.match(s)
    if m and not _is_sentence_like(s):
        return (KIND_LEVEL[m.group("kind")], s)

    # 附录X / 附件X
    if RE_APPENDIX.match(s) and not _is_sentence_like(s):
        return (1, s)

    # （一）……
    if RE_CN_L2.match(s) and not _is_sentence_like(s):
        return (2, s)
    # 一、……
    if RE_CN_L1.match(s) and not _is_sentence_like(s):
        return (1, s)

    # 1.1 / 1.1.1 / 1.1.1.1（层级 = 编号段数）
    m = RE_DOTTED.match(s)
    if m and s[-1] not in "。！？，；":
        return (len(m.group("num").split(".")), s)

    # 1. 标题
    m = RE_NUM_DOT.match(s)
    if m and not _is_sentence_end(s) and s[-1] not in "，、":
        return (1, s)

    # 1 标题（GB 体例的章标题）：要求整行不含句读，避免把“30 分钟内完成派单。”误判为标题
    m = RE_NUM_SPACE.match(s)
    if m and not _has_inner_sentence_punct(s):
        return (1, s)

    return None


# ---------------------------------------------------------------- 清洗

def _normalise_line(line: str, normalize_space: bool) -> str:
    if normalize_space:
        line = line.replace("\u3000", " ")
    line = line.strip()
    if normalize_space:
        line = RE_MULTI_SPACE.sub(" ", line).strip()
    return line


def _is_metadata_line(line: str) -> bool:
    """标准号 / 日期 / “版本：xxx” 这类元数据行：不参与硬换行合并。"""
    if not line:
        return False
    if RE_META_STDNO.match(line) or RE_META_DATE.match(line) or RE_META_LABEL.match(line):
        return True
    # 纯 ASCII 短行（含数字），如 "V1.3"、"Rev.B 2025"
    if len(line) <= 24 and line.isascii() and any(ch.isdigit() for ch in line):
        return True
    return False


def _is_listish(line: str) -> bool:
    return bool(RE_LISTISH.match(line))


def _join_wrapped(cur: str, nxt: str) -> Optional[str]:
    """把被硬换行切断的两行接起来；返回 None 表示按“英文断词连字符”规则拼接。"""
    if (cur.endswith("-") and len(cur) >= 2
            and cur[-2].isascii() and cur[-2].isalpha()
            and nxt[:1].isascii() and nxt[:1].isalpha()):
        return None  # 由调用方去掉连字符直接拼
    a, b = cur[-1], nxt[0]
    if ord(a) < 128 and ord(b) < 128 and a not in NO_SPACE_AFTER and b not in NO_SPACE_BEFORE:
        return cur + " " + nxt
    return cur + nxt


def clean_text(
    raw: str,
    *,
    normalize_space: bool = True,
    strip_noise: bool = True,
    min_repeat: int = 3,
    extra_noise: Optional[Sequence[str]] = None,
    md_mode: bool = False,
) -> Tuple[List[str], Dict[str, int]]:
    """文本清洗，返回 (行列表, 统计)。统计含 pages/noise/merged。"""
    stats = {"pages": 0, "noise": 0, "merged": 0}

    # 1) 统一换行符 + 去零宽字符
    text = raw.replace("\r\n", "\n").replace("\r", "\n")
    text = RE_ZERO_WIDTH.sub("", text)
    lines = [_normalise_line(l, normalize_space) for l in text.split("\n")]

    noise_set = set()
    if strip_noise:
        # 3) 页眉页脚重复行：出现 ≥ min_repeat 次、长度 < 40、且不是标题
        counter: Dict[str, int] = {}
        for l in lines:
            if l and len(l) < MAX_TITLE_LEN:
                counter[l] = counter.get(l, 0) + 1
        for l, c in counter.items():
            if c >= min_repeat and not detect_heading(l, md_mode):
                noise_set.add(l)
        # docx 的 header/footer 文本额外视为候选噪声（仍需满足 长度<40 且不是标题）
        for l in (extra_noise or ()):
            l = _normalise_line(l, normalize_space)
            if l and len(l) < MAX_TITLE_LEN and not detect_heading(l, md_mode):
                noise_set.add(l)

    # 2) 去页码行 / 页眉页脚行
    kept: List[str] = []
    for l in lines:
        if strip_noise and l:
            if any(p.match(l) for p in PAGE_NUM_PATTERNS):
                stats["pages"] += 1
                continue
            if l in noise_set:
                stats["noise"] += 1
                continue
        kept.append(l)

    # 4) 合并被硬换行切断的段落
    merged: List[str] = []
    i = 0
    while i < len(kept):
        cur = kept[i]
        if not cur:
            merged.append("")
            i += 1
            continue
        while i + 1 < len(kept):
            nxt = kept[i + 1]
            if not nxt:
                break
            if detect_heading(cur, md_mode) or detect_heading(nxt, md_mode):
                break                                     # 标题不参与合并
            if _is_sentence_end(cur):
                break                                     # 已是完整句子，属于自然段边界
            if _is_metadata_line(cur) or _is_metadata_line(nxt):
                break                                     # 元数据行不参与合并
            if _is_listish(cur) or _is_listish(nxt):
                break                                     # 列表 / 表格行各自成行
            joined = _join_wrapped(cur, nxt)
            cur = (cur[:-1] + nxt) if joined is None else joined
            stats["merged"] += 1
            i += 1
        merged.append(cur)
        i += 1

    # 5) 压缩连续空行（最多 1 个）并去掉首尾空行
    out: List[str] = []
    for l in merged:
        if not l:
            if not out or out[-1] == "":
                continue
        out.append(l)
    while out and out[-1] == "":
        out.pop()
    return out, stats


# ---------------------------------------------------------------- 切分

def split_sections(lines: Sequence[str], md_mode: bool = False) -> List[Tuple[str, int, str]]:
    """按标题行切分章节，返回 [(section_title, section_level, content)]，跳过空正文章节。"""
    raw: List[Dict[str, object]] = []
    for line in lines:
        head = detect_heading(line, md_mode)
        if head:
            raw.append({"title": head[1], "level": head[0], "body": []})
        else:
            if not raw:
                raw.append({"title": PREAMBLE_TITLE, "level": PREAMBLE_LEVEL, "body": []})
            raw[-1]["body"].append(line)  # type: ignore[union-attr]

    sections: List[Tuple[str, int, str]] = []
    for item in raw:
        body = list(item["body"])  # type: ignore[arg-type]
        while body and not body[0]:
            body.pop(0)
        while body and not body[-1]:
            body.pop()
        content = "\n".join(body)
        if content.strip():
            sections.append((str(item["title"]), int(item["level"]), content))
    return sections


def _hard_split(block: str, max_chars: int) -> List[str]:
    return [block[i:i + max_chars] for i in range(0, len(block), max_chars)] or [""]


def chunk_content(content: str, max_chars: int) -> List[str]:
    """超过 max_chars 时按自然段边界切分；单段仍超长则退化为按长度硬切。"""
    if len(content) <= max_chars:
        return [content]

    blocks: List[str] = []
    for para in content.split("\n\n"):
        if len(para) <= max_chars:
            blocks.append(para)
            continue
        for line in para.split("\n"):                 # 再按单行拆
            blocks.extend(_hard_split(line, max_chars))

    chunks: List[str] = []
    buf = ""
    for block in blocks:
        if not block:
            continue
        if not buf:
            buf = block
        elif len(buf) + 2 + len(block) <= max_chars:
            buf = buf + "\n\n" + block
        else:
            chunks.append(buf)
            buf = block
    if buf:
        chunks.append(buf)
    return chunks or [content]


# ---------------------------------------------------------------- 文件收集

def infer_doc_type(name: str, source: Optional[str]) -> str:
    haystack = ("%s %s" % (name, source or "")).lower()
    for doc_type, keywords in DOC_TYPE_KEYWORDS:
        for kw in keywords:
            if kw in haystack:
                return doc_type
    return "manual"


def normalise_date(value: Optional[str]) -> Optional[str]:
    if not value:
        return None
    s = value.strip()
    m = re.fullmatch(r"(\d{4})\s*[-\u2010-\u2015./]?\s*(\d{1,2})\s*[-\u2010-\u2015./]?\s*(\d{1,2})", s)
    if m:
        return "%s-%02d-%02d" % (m.group(1), int(m.group(2)), int(m.group(3)))
    return s


def collect_inputs(input_path: Path, recursive: bool, output_path: Path) -> Tuple[List[Path], List[str]]:
    """返回 (待处理文件列表, 被忽略的文件名列表)。"""
    candidates: List[Path] = []
    if input_path.is_file():
        candidates = [input_path]
    else:
        it = input_path.rglob("*") if recursive else input_path.glob("*")
        candidates = [p for p in it if p.is_file()]

    files: List[Path] = []
    ignored: List[str] = []
    out_resolved = output_path.resolve()
    for p in sorted(candidates, key=lambda x: str(x).lower()):
        name = p.name
        if name.startswith("~$") or name.startswith(".") or "__pycache__" in p.parts:
            continue
        try:
            if p.resolve() == out_resolved:
                continue
        except OSError:
            pass
        if p.suffix.lower() in EXT_RECOGNISED:
            files.append(p)
        else:
            ignored.append(name)
    return files, ignored


# ---------------------------------------------------------------- 主流程

def build_record(
    *,
    doc_name: str,
    section_title: str,
    section_level: int,
    content: str,
    title_suffix: str,
    source: str,
    standard_no: Optional[str],
    doc_type: str,
    doc_version: Optional[str],
    effective_date: Optional[str],
    category_id: Optional[int],
) -> Dict[str, object]:
    # title = 文档名 + sectionTitle（两者相同时不重复拼接）
    base = doc_name if section_title == doc_name else ("%s %s" % (doc_name, section_title)).strip()
    return {
        "title": base + title_suffix,
        "source": source,
        "standardNo": standard_no,
        "docType": doc_type,
        "docVersion": doc_version,
        "effectiveDate": effective_date,
        "categoryId": category_id,
        "sectionTitle": section_title,
        "sectionLevel": section_level,
        "content": content,
        "charCount": len(content),
    }


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="convert_corpus.py",
        description="把规范 / 手册类文档（txt / md / docx / pdf）转换成后端可导入的 JSONL 语料。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例：\n"
            "  python convert_corpus.py --input docs --output out/corpus.jsonl --source \"GB 55022-2021\"\n"
            "      --category-id 20001 --doc-type standard --standard-no \"GB 55022-2021\" --doc-version 2021\n"
            "      --effective-date 2021-04-09\n"
        ),
    )
    parser.add_argument("--input", required=True, help="输入文件或目录（目录默认递归子目录）")
    parser.add_argument("--output", required=True, help="输出 JSONL 路径（UTF-8 无 BOM）")
    parser.add_argument("--source", default=None, help='来源名称，如 "GB 55022-2021"；缺省用文档名')
    parser.add_argument("--category-id", type=int, default=None, help="后端分类 ID，如 20001")
    parser.add_argument("--doc-type", choices=("standard", "manual", "policy", "case"), default=None,
                        help="文档类型；缺省按文件名 / --source 关键词推断")
    parser.add_argument("--standard-no", default=None, help='标准号，如 "GB 55022-2021"')
    parser.add_argument("--doc-version", default=None, help='文档版本，如 "2021"')
    parser.add_argument("--effective-date", default=None, help="生效日期，如 2021-04-09")
    parser.add_argument("--encoding", default="auto",
                        help="输入编码：auto / utf-8 / gbk / gb18030 / ...，默认 auto（UTF-8 优先，回退 GB18030）")
    parser.add_argument("--doc-name", default=None, help="title 前缀使用的文档名；缺省用输入文件名（不含扩展名）")
    parser.add_argument("--max-chars", type=int, default=DEFAULT_MAX_CHARS,
                        help="单条 content 最大字符数，超出按自然段切分并在 title 追加（续N），默认 %d" % DEFAULT_MAX_CHARS)
    parser.add_argument("--min-header-repeat", type=int, default=3,
                        help="页眉页脚判定阈值：同一行出现次数 ≥ 该值且长度 < 40 视为页眉页脚，默认 3")
    parser.add_argument("--keep-noise", action="store_true", help="不剔除页码 / 页眉页脚（排错用）")
    parser.add_argument("--no-word", action="store_true",
                        help="禁用 Word COM 兜底（默认启用：PDF / .doc 在 pypdf、pdfminer 不可用时改用 Word 另存为 txt）")
    parser.add_argument("--word-path", default=None,
                        help="可选：WINWORD.EXE 绝对路径。COM 调用始终使用系统注册的 Word，该参数用于预检、版本展示与诊断")
    parser.add_argument("--word-timeout", type=int, default=WORD_DEFAULT_TIMEOUT,
                        help="Word COM 单文件转换超时秒数，默认 %d" % WORD_DEFAULT_TIMEOUT)
    parser.add_argument("--no-normalize-space", dest="normalize_space", action="store_false",
                        help="不把制表符 / 全角空格归一为普通空格")
    parser.add_argument("--no-recursive", dest="recursive", action="store_false", help="目录输入时不递归子目录")
    parser.add_argument("--version", action="version", version="convert_corpus.py %s" % VERSION)
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    safe_console()
    args = build_arg_parser().parse_args(argv)

    input_path = Path(args.input).expanduser()
    output_path = Path(args.output).expanduser()
    if not input_path.exists():
        print("[错误] 输入路径不存在：%s" % input_path, file=sys.stderr)
        return 2
    if args.max_chars <= 0:
        print("[错误] --max-chars 必须是正整数", file=sys.stderr)
        return 2
    if args.min_header_repeat < 2:
        print("[错误] --min-header-repeat 至少为 2", file=sys.stderr)
        return 2
    if args.word_timeout <= 0:
        print("[错误] --word-timeout 必须是正整数", file=sys.stderr)
        return 2
    word = WordFallback(enabled=not args.no_word, word_path=args.word_path, timeout=args.word_timeout)
    if args.word_path and not os.path.isfile(args.word_path):
        warn("--word-path 指定的文件不存在：%s（COM 调用仍使用系统注册的 Word）" % args.word_path)

    files, ignored = collect_inputs(input_path, args.recursive, output_path)
    print("=" * 72)
    print("文档语料转换 convert_corpus.py v%s" % VERSION)
    print("输入：%s" % input_path)
    print("输出：%s" % output_path)
    print("=" * 72)
    if ignored:
        info("忽略 %d 个不支持的文件：%s" % (len(ignored), "、".join(ignored[:10]) + (" 等" if len(ignored) > 10 else "")))
    if not files:
        print("[错误] 没有找到可处理的文件（支持：.txt / .md / .docx / .pdf；.doc 需先另存为 .docx / .txt）",
              file=sys.stderr)
        return 2
    # 只有在输入里真的存在 PDF / 旧版格式时，才去探测 Word（探测会启动一次 Word，约 1~3 秒）
    if word.enabled and any(p.suffix.lower() in (EXT_PDF | EXT_LEGACY) for p in files):
        info("Word COM 兜底：%s" % word_info(word.word_path).get("message"))
    elif not word.enabled and any(p.suffix.lower() in (EXT_PDF | EXT_LEGACY) for p in files):
        info("Word COM 兜底：已通过 --no-word 禁用")

    effective_date = normalise_date(args.effective_date)
    if args.effective_date and not effective_date:
        warn("--effective-date 为空，将输出 null")
    records: List[Dict[str, object]] = []
    ok_files = 0
    skipped: List[str] = []
    total_chars = 0
    single_input = len(files) == 1

    for path in files:
        doc_name = args.doc_name if args.doc_name else path.stem
        source = args.source if args.source else doc_name
        if args.doc_type:
            doc_type = args.doc_type
        else:
            doc_type = infer_doc_type(path.name, args.source)
        result = read_any(path, args.encoding, word)

        if result.text is None:
            reason = result.error or "未知原因"
            skipped.append(path.name)
            print("[跳过] %s —— %s" % (path.name, reason))
            continue
        if result.meta.get("word_note"):
            info("%s：%s" % (path.name, result.meta["word_note"]))
        if result.warning:
            warn("%s：%s" % (path.name, result.warning))

        md_mode = path.suffix.lower() in (".md", ".markdown")
        lines, stats = clean_text(
            result.text,
            normalize_space=args.normalize_space,
            strip_noise=not args.keep_noise,
            min_repeat=args.min_header_repeat,
            extra_noise=result.meta.get("known_noise"),  # type: ignore[arg-type]
            md_mode=md_mode,
        )
        sections = split_sections(lines, md_mode)
        if not sections:
            skipped.append(path.name)
            print("[跳过] %s —— 清洗后没有可用章节（可能整篇都是页眉页脚或空白）" % path.name)
            continue

        file_records: List[Dict[str, object]] = []
        for section_title, section_level, content in sections:
            chunks = chunk_content(content, args.max_chars)
            for idx, chunk in enumerate(chunks, start=1):
                suffix = "" if idx == 1 else "（续%d）" % idx
                file_records.append(build_record(
                    doc_name=doc_name,
                    section_title=section_title,
                    section_level=section_level,
                    content=chunk,
                    title_suffix=suffix,
                    source=source,
                    standard_no=args.standard_no,
                    doc_type=doc_type,
                    doc_version=args.doc_version,
                    effective_date=effective_date,
                    category_id=args.category_id,
                ))
                total_chars += len(chunk)
        records.extend(file_records)
        ok_files += 1
        engine = result.meta.get("engine") or result.meta.get("encoding") or path.suffix.lstrip(".")
        print("[完成] %s → %d 章节（解析：%s；剔除页码 %d 行 / 页眉页脚 %d 行；合并硬换行 %d 处；docType=%s）"
              % (path.name, len(file_records), engine, stats["pages"], stats["noise"], stats["merged"], doc_type))
        if args.doc_name and not single_input:
            warn("--doc-name 已应用到全部 %d 个文件，多文件输入时建议省略该参数" % len(files))

    try:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with open(output_path, "w", encoding="utf-8", newline="\n") as fh:
            for rec in records:
                fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
    except OSError as exc:
        print("[错误] 写入输出文件失败：%s" % exc, file=sys.stderr)
        return 3

    avg = (total_chars / len(records)) if records else 0.0
    print("-" * 72)
    print("汇总")
    print("  处理文件数    : %d（成功 %d，跳过 %d）" % (len(files), ok_files, len(skipped)))
    print("  跳过文件数    : %d%s" % (len(skipped), ("（" + "、".join(skipped) + "）") if skipped else ""))
    print("  输出章节数    : %d" % len(records))
    print("  平均字符数    : %.1f" % avg)
    print("  总字符数      : %d" % total_chars)
    print("  输出文件      : %s" % output_path)
    print("-" * 72)

    if records:
        return 0
    print("[错误] 所有文件都处理失败，未输出任何语料。", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
