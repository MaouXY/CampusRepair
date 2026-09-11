#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""make_sample_docx.py —— 用 Python 标准库现场生成一个最小可用的示例 .docx。

用途：为 tools/corpus/convert_corpus.py 提供自测用的 docx 输入，包含
  * <w:tab/>（制表符）与 <w:br/>（软换行）—— 验证转换脚本能正确处理
  * 页眉 word/header1.xml、页脚 word/footer1.xml —— 验证页眉页脚文本被识别为噪声
  * 正文中重复出现的页眉行与页码行 —— 验证频次法剔除
说明：内容全部为虚构的校园后勤维修示例数据，不含任何真实标准正文。

用法（在项目根目录）：
  python tools/corpus/sample/make_sample_docx.py
  # 或指定输出：python tools/corpus/sample/make_sample_docx.py --out 输出路径.docx
"""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

DOC_NAME = "校园报修工单处理办法.docx"

# 页眉 / 页脚部件里的文本（真实的 Word 页眉页脚存在独立部件中）
HEADER_TEXT = "后勤保障处 · 内部资料"
FOOTER_TEXT = "校园后勤保障处 编制"

# 正文段落；"\t" 会写成 <w:tab/>，"\n" 会写成 <w:br/>，"" 表示空段落
BODY_PARAGRAPHS = [
    "校园报修工单处理办法",
    "版本：V2.1（2025 年修订）",
    "",
    "第一章 总则",
    "一、适用范围",
    "本办法适用于本校各校区教学、办公、宿舍区域内的水电、门窗、家具、空调等日常报修工单",
    "的受理、派单、处理与验收管理。",
    "（一）受理时限",
    "报修平台、电话、现场三种渠道提交的工单，受理人员应在",
    "30 分钟内完成受理确认；夜间紧急工单应在 15 分钟内响应。",
    HEADER_TEXT,                    # 页眉文本混进正文（由 docx 页眉部件识别后剔除）
    "12",                           # 页码行
    "1.1 派单规则",
    "按故障类型自动派单：水电类派维修一组，木工类派维修二组，空调类派维保组。",
    "报修工单分为三类：\n紧急工单、\n一般工单。",     # <w:br/> 软换行
    "维修班组：\t维修一组，联系电话：\t8377。",       # <w:tab/> 制表符
    "- 3 -",                        # 页码行
    "1.1.1 超时升级",
    "工单超过 24 小时未处理完毕的，系统自动升级至后勤保障处值班领导，并由值班领导指定",
    "专人跟进。",
    FOOTER_TEXT,                    # 页脚文本混进正文（由 docx 页脚部件识别后剔除）
    "内部资料 请勿外传",             # 高频页眉（正文中出现 3 次 → 频次法剔除）
    "第二章 质量与考核",
    "二、回访与考核",
    "维修完成后 48 小时内应完成回访，回访不满意的工单须重新处理；每月对维修及时率、",
    "返修率进行统计并纳入班组考核。",
    "第 4 页",                       # 页码行
    "内部资料 请勿外传",
    "— 5 —",                         # 页码行
    "内部资料 请勿外传",
]

CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
  <Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>
  <Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>
</Types>
"""

ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>
"""

DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>
</Relationships>
"""

STYLES_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:docDefaults>
    <w:rPrDefault><w:rPr><w:sz w:val="21"/></w:rPr></w:rPrDefault>
  </w:docDefaults>
</w:styles>
"""

DOC_HEAD = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <w:body>
"""
DOC_TAIL = """    <w:sectPr>
      <w:headerReference w:type="default" r:id="rId2"/>
      <w:footerReference w:type="default" r:id="rId3"/>
      <w:pgSz w:w="11906" w:h="16838"/>
      <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/>
    </w:sectPr>
  </w:body>
</w:document>
"""


def esc(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def paragraph_xml(text: str) -> str:
    """把一段文本转成 <w:p>；\\t → <w:tab/>，\\n → <w:br/>。"""
    parts = []
    buf = []

    def flush() -> None:
        if buf:
            parts.append('<w:r><w:t xml:space="preserve">%s</w:t></w:r>' % esc("".join(buf)))
            del buf[:]

    for ch in text:
        if ch == "\t":
            flush()
            parts.append("<w:r><w:tab/></w:r>")
        elif ch == "\n":
            flush()
            parts.append("<w:r><w:br/></w:r>")
        else:
            buf.append(ch)
    flush()
    return "<w:p>%s</w:p>" % "".join(parts) if parts else "<w:p/>"


def simple_part(text: str) -> str:
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
            '<w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
            "%s</w:hdr>\n" % paragraph_xml(text))


def simple_footer(text: str) -> str:
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
            '<w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
            "%s</w:ftr>\n" % paragraph_xml(text))


def build_docx(out_path: Path) -> Path:
    document = DOC_HEAD + "\n".join("    " + paragraph_xml(p) for p in BODY_PARAGRAPHS) + "\n" + DOC_TAIL
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("[Content_Types].xml", CONTENT_TYPES)
        zf.writestr("_rels/.rels", ROOT_RELS)
        zf.writestr("word/document.xml", document)
        zf.writestr("word/_rels/document.xml.rels", DOC_RELS)
        zf.writestr("word/styles.xml", STYLES_XML)
        zf.writestr("word/header1.xml", simple_part(HEADER_TEXT))
        zf.writestr("word/footer1.xml", simple_footer(FOOTER_TEXT))
    return out_path


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="生成示例 docx（无需 python-docx）")
    parser.add_argument("--out", default=None,
                        help="输出路径，默认写到本脚本所在目录下的 %s" % DOC_NAME)
    args = parser.parse_args(argv)

    out_path = Path(args.out) if args.out else Path(__file__).resolve().parent / DOC_NAME
    build_docx(out_path)
    size = out_path.stat().st_size
    print("已生成示例 docx：%s（%d 字节，%d 个正文段落，含 header/footer 部件）"
          % (out_path, size, len(BODY_PARAGRAPHS)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
