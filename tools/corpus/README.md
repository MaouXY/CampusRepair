# 文档语料转换脚本（tools/corpus）

把**规范 / 手册 / 办法 / 案例**类文档（`.txt` `.md` `.docx` `.pdf`）转换成
「一条记录 = 一个章节」的 **JSONL** 语料，供后端 RAG 知识库批量导入。

- 核心功能**只用 Python 标准库**，Anaconda 自带 Python 直接跑，不用建虚拟环境、不用装依赖。
- 输出 `UTF-8 无 BOM`，每行一个 JSON 对象，字段名与后端
  `CorpusSectionItem`（`CampusRepairApi/src/main/java/com/maou/apptemplateapi/module/rag/dto/CorpusSectionItem.java`）对齐。
- 自带示例输入 / 期望输出 / 自测脚本，`sample/selfcheck.py` 一条命令验证 91 项。

---

## 1. 目录结构

```
tools/corpus/
├─ convert_corpus.py                       # 主脚本（唯一需要运行的文件）
├─ README.md                               # 本文档
└─ sample/                                 # 示例输入、期望输出、自测
   ├─ 校园既有建筑设备维护通用规范.txt        #   UTF-8，含页眉页脚 / 页码 / 硬换行 / 超长附录
   ├─ 校园后勤维修服务手册.md                 #   Markdown，含 # 标题与列表
   ├─ 校园报修工单处理办法.docx               #   由 make_sample_docx.py 生成（含 <w:tab/>、<w:br/>、页眉页脚部件）
   ├─ make_sample_docx.py                   #   用标准库 zipfile 现场生成最小 docx
   ├─ expected.jsonl                        #   期望输出（25 行，脚本实际跑出来的结果）
   └─ selfcheck.py                          #   自测脚本（91 项断言）
```

> 示例内容全部为**虚构的校园后勤维修 / 设备维保数据**，不含任何真实标准正文。

---

## 2. 环境与依赖

| 格式 | 依赖 | 说明 |
| --- | --- | --- |
| `.txt` / `.text` | 无 | 自动探测编码：UTF-8（含 BOM）优先，失败回退 `gb18030`（GBK 超集），最后 `utf-8(replace)` 兜底并告警 |
| `.md` / `.markdown` | 无 | 额外识别 Markdown `#`~`######` 标题；不做 Markdown 语法清理 |
| `.docx` / `.docm` | 无 | `zipfile` + `xml.etree` 直接解析 `word/document.xml`，按 `<w:p>` 取 `<w:t>`；`<w:tab/>` → 空格，`<w:br/>` → 换行；顺带读取 `word/header*.xml`、`word/footer*.xml` 作为“已知噪声行” |
| `.pdf` | **零 pip** | 三条路径依次尝试：`pypdf` → `pdfminer.six` → **Word COM**（PowerShell + Microsoft Word 另存为文本）。三条都走不通才跳过并给中文建议 |
| `.doc` / `.rtf` | **零 pip** | 直接走 Word COM 另存为文本；Word 不可用时提示“请另存为 .docx 或 .txt”并跳过 |
| `.wps` | 不支持 | WPS 私有格式（本机也没有 WPS），提示“请另存为 .docx 或 .txt”，记为 skipped |

> 也就是说：**只要装了 Microsoft Word，本脚本连 PDF 都能转，不需要 pip 装任何东西**。
> 只有“既没装 Word、也不想装 pip 包”时，PDF 才会被跳过。

```powershell
# 完全可选：装了 PDF 解析库会走纯 Python 路径（更快，且不启动 Word）
D:\Anaconda3\python.exe -m pip install pypdf
D:\Anaconda3\python.exe -m pip install pdfminer.six
```

**本机实测环境**：Anaconda Python 3.12.4（`D:\Anaconda3\python.exe`）；
`pypdf` / `pdfminer` **未安装**；Microsoft Word **16.0.19725.20530**（`C:\Program Files\Microsoft Office\root\Office16\WINWORD.EXE`，`Word.Application` COM 可用）。
因此本机 `.txt` / `.md` / `.docx` 走纯标准库，**`.pdf` 与 `.doc` 走 Word COM 兜底**（见第 3 节与第 7 节示例 ④）。

> 控制台中文乱码时：先执行 `chcp 65001`，或在当前会话设置 `$env:PYTHONIOENCODING="utf-8"` 再运行。
> 脚本本身会按控制台编码输出，并对无法编码的字符自动降级为 `?`，不会因为打印报错而中断。

---

## 3. PDF / .doc 的三条解析路径（优先级与前提条件）

### `.pdf`：pypdf → pdfminer.six → Word COM

| 优先级 | 路径 | 前提条件 | 行为 / 耗时 | 结果质量 |
| --- | --- | --- | --- | --- |
| 1 | **pypdf**（纯 Python） | `pip install pypdf` | 进程内解析，最快 | 文本型 PDF 好；扫描版提取为空 → 落到下一条 |
| 2 | **pdfminer.six**（纯 Python） | `pip install pdfminer.six` | 进程内解析，较慢但更抗畸形 PDF | 同上 |
| 3 | **Word COM**（无需 pip） | ① 安装 Microsoft Word（`Word.Application` COM 可注册）；② Windows PowerShell 可用；③ `HKCU\...\Word\Options` 可写（脚本只在转换 PDF 期间临时改一个键，见下） | 启动 Word 另存为 txt，单个文件约 5~15 秒 | Word 会“重排”PDF，标题层级可能被并进正文（见第 11 节），但正文文字完整 |

三条都不可用时：打印中文提示（含 `pip install pypdf`）并记为该文件 **skipped**；
只有当**所有**文件都失败时退出码才为 1。

Word 兜底的具体实现（`word_convert_to_text` / `word_save_as`）：

```
Word.Application（Visible=false, DisplayAlerts=0）
  → Documents.Open(<文件>, ConfirmConversions=$false, ReadOnly=$true)
  → SaveAs2(<%TEMP%\corpus_convert\corpus-xxxxxxxxxx.txt>, 7)   # 7 = wdFormatUnicodeText，UTF-16LE 防乱码
  → Close(0) → Quit(0)
  → Python 读取该 txt（自动识别 UTF-16 BOM）→ 立即删除临时文件
```

### `.doc` / `.rtf`：Word COM 优先

`.doc` 是二进制旧格式，标准库无法解析，因此直接走 Word COM 另存为 txt；
`.rtf` 也是 Word 能可靠打开的格式，一并处理。Word 不可用（或加了 `--no-word`）时：
打印“请先用 Word / WPS 另存为 .docx 或 .txt”并记为 skipped。
`.wps` 不参与兜底（Word 常常打不开 WPS 私有格式）。

### 为什么 PDF 转换必须临时改一个注册表键（重要）

Word 打开 PDF 时会另起一个 **`PDFREFLOW.exe`** 进程，并弹出模态确认框
（“Word 现在将把您的 PDF 转换为可编辑的 Word 文档…”）。
这个弹窗**属于另一个进程**，`DisplayAlerts=0` 管不到它，会导致 `Documents.Open` 永久挂起
（实测：不加处理时 120 秒超时；设置后同一文件 5.5 秒转换完成）。

脚本的处理方式：

1. 转换 PDF 前，用 Python 标准库 `winreg` 读取并临时写入
   `HKCU\Software\Microsoft\Office\16.0\Word\Options\DisableConvertPdfWarning = 1`；
2. **无论成功、失败还是超时，都会在 `finally` 里恢复原值**（原来没有这个值就删掉它）
   —— 因为还原动作在 Python 侧而不是被超时杀掉的 PowerShell 子进程里，所以一定执行；
3. 启动时会打印一行提示，说明这次转换临时改过、已还原；
4. 不希望脚本碰注册表就加 `--no-word-regfix`（此时 PDF 转换可能要人工点确认，通常会走到超时并被跳过）。

### 进程清理与“不影响你正在用的 Word”

- 每次转换前后都会用 `tasklist` 记录 `WINWORD.EXE` / `PDFREFLOW.EXE` 的进程号，
  转换结束后**只结束本次新产生的**进程（Word 的 `Quit` 后经常残留隐藏进程，堆积会把后续转换卡死 —— 实测踩过）；
- 如果你在跑脚本前**已经打开了 Word**，脚本只关闭本次打开的那份文档，**不会调用 `Quit`**，
  也不会结束你的 Word 进程（日志会提示“检测到已有 Word 在运行”）；
- 单次调用超时会先清理残留进程再**自动重试一次**（最坏耗时 ≈ 2 × `--word-timeout`）。

---

## 4. 参数说明

以下命令均在**项目根目录** `CampusRepair` 下执行（脚本内部只用相对路径与命令行参数，不含任何绝对路径）。

```powershell
D:\Anaconda3\python.exe tools\corpus\convert_corpus.py --input <文件或目录> --output <输出.jsonl> [其他参数]
```

| 参数 | 必填 | 默认 | 含义 |
| --- | --- | --- | --- |
| `--input` | ✅ | — | 输入文件或目录；目录默认递归子目录，自动忽略 `~$*`、`.` 开头文件与 `__pycache__` |
| `--output` | ✅ | — | 输出 JSONL 路径（UTF-8 无 BOM，每行一个 JSON），父目录不存在会自动创建 |
| `--source` | | 文档名 | 写入 `source` 字段，如 `"GB 55022-2021"`、`"校园后勤维修服务手册"` |
| `--category-id` | | `null` | 后端分类 ID（整数），如 `20001`；同时用于 RAG 检索时的分类过滤 |
| `--doc-type` | | 自动推断 | `standard` / `manual` / `policy` / `case`；不给时按文件名 + `--source` 关键词推断，并在日志里打印推断结果 |
| `--standard-no` | | `null` | 标准号，如 `"GB 55022-2021"`；后端按 `standardNo\|title` 分组，给了更聚拢 |
| `--doc-version` | | `null` | 文档版本，如 `"2021"` |
| `--effective-date` | | `null` | 生效日期，接受 `2021-04-09` / `20210409` / `2021/4/9`，统一归一为 `YYYY-MM-DD`（后端是 `LocalDate`，必须是这个格式） |
| `--encoding` | | `auto` | 输入编码：`auto` / `utf-8` / `gbk` / `gb18030` / …；显式指定解码失败时会自动回退并告警 |
| `--doc-name` | | 文件名（去扩展名） | `title` 前缀用的文档名；**多文件输入时建议省略**，只用文件名 |
| `--max-chars` | | `1500` | 单条 `content` 最大字符数，超出按自然段切分并在 `title` 追加 `（续N）` |
| `--min-header-repeat` | | `3` | 页眉页脚判定阈值：同一行出现次数 ≥ 该值且长度 < 40 视为页眉页脚 |
| `--keep-noise` | | 关 | 关闭页码 / 页眉页脚剔除（排错时对照原文用） |
| `--no-word` | | 关 | 禁用 Word COM 兜底（此时 PDF / `.doc` 在缺库时直接跳过） |
| `--word-path` | | 自动探测 | 可选：指定 `WINWORD.EXE` 绝对路径。**COM 调用始终使用系统注册的 Word**，该参数用于预检、版本展示与诊断（给了它即使 `GetTypeFromProgID` 探测失败也会尝试调用；路径不存在会告警） |
| `--word-timeout` | | `120` | Word COM 单文件转换超时秒数；超时后会清理残留进程并重试一次 |
| `--no-word-regfix` | | 关 | 转换 PDF 时**不**临时设置 `DisableConvertPdfWarning`（默认会临时设置并立即还原） |
| `--no-normalize-space` | | 关 | 不把制表符、全角空格、连续空格归一为单个半角空格 |
| `--no-recursive` | | 关 | 目录输入时不递归子目录 |

`--help` 可查看完整帮助；`--version` 打印版本号。

**docType 自动推断规则**（按顺序命中，第一个命中生效）：

| 顺序 | 类型 | 命中关键词（文件名 + `--source`） |
| --- | --- | --- |
| 1 | `standard` | 规范、标准、规程、通则、技术规定、gb、jgj、cjj、db、t/cecs |
| 2 | `policy` | 办法、规定、制度、条例、章程、细则、通知、意见、方案、政策、管理 |
| 3 | `case` | 案例、记录、工单、报告、总结、台账、汇编 |
| 4 | `manual`（兜底） | 手册、说明书、指南、操作、维保、使用说明、白皮书 |

---

## 5. 清洗规则（7 步，按顺序执行）

1. **统一换行 + 去零宽字符**：`\r\n`、`\r` → `\n`；删除 `\u200b-\u200f`、`\u2028`、`\u2029`、`\u2060`、`\ufeff`。
2. **去页码行**：`12`、`- 12 -`、`— 12 —`、`第 12 页`、`第 12 页 / 共 30 页`、`12/30`、`Page 12 of 30`、`共 12 页`。
3. **去页眉页脚重复行**：同一行出现次数 ≥ `--min-header-repeat`（默认 3）且长度 < 40 → 删除。
   - **防误删**：命中标题规则的行（如标准里每页都出现的“第二章 设备维护”）**不删**，避免把真标题当页眉删掉。
   - docx 的 `word/header*.xml` / `word/footer*.xml` 文本会额外作为候选噪声行（同样要求“长度 < 40 且不是标题”），
     用于清洗那些“从 docx 导出成 txt 时混进正文”的页眉页脚。
4. **合并被硬换行切断的段落**：当前行不以 `。；：！？）》”` 结尾，且下一行不是标题行 → 合并；中文不加空格，英文加空格。
   额外三条防误合并规则（都写进了自测）：
   - 当前行本身是标题行 → 不合并（否则标题会被吃进正文）；
   - 下一行是“元数据行”（标准号 `GB/T 55022-2025`、日期 `2025-03-01`、`版本：V1.3`、`编制单位：…` 等）→ 不合并；
   - 当前行或下一行是列表 / 引用 / 表格行（`- `、`1)`、`> `、`|` 开头）→ 不合并。
   - 细节：英文断词连字符会接合（`main-` + `tenance` → `maintenance`）。
5. **压缩空行**：连续空行最多保留 1 个，删除行首尾空白。
6. **空格归一**（可用 `--no-normalize-space` 关闭）：全角空格 `\u3000`、制表符 `\t`、不换行空格 `\u00a0`、连续空格 → 单个半角空格。
7. **超长章节分块**：`content` 超过 `--max-chars`（默认 1500）时按自然段边界拆成多行，每行 `title` 追加 `（续2）`、`（续3）`…，
   `sectionTitle` 与 `sectionLevel` 保持一致；单段仍超长才退化为按长度硬切（保证不丢字）。

---

## 6. 章节识别规则

标题行长度 **≤ 40 字**（超长一律当正文，避免把正文里的编号误判成标题），
且不以 `。！？；` 结尾（以句末标点结尾的是条款正文，不是标题）。

| 标题形式 | 示例 | `sectionLevel` |
| --- | --- | --- |
| 附录 / 附件 | `附录A 设备巡检要点（示例）`、`附件2 维修记录表` | 1 |
| 第X章 | `第一章 总则` | 1 |
| 第X节 | `第二节 巡检周期` | 2 |
| 第X条 | `第3条 巡检要求` | 3 |
| `一、` | `一、适用范围` | 1 |
| `（一）` | `（一）受理时限` | 2 |
| `1.` / `1、` | `1. 总则` | 1 |
| `1.1` | `3.4 设施设备检查` | 2（= 编号段数） |
| `1.1.1` | `1.1.1 超时升级` | 3 |
| `1.1.1.1` | `1.1.1.1 补充说明` | 4 |
| 单级数字 + 空格（GB 体例章标题） | `2 设施设备检查` | 1 |
| Markdown `#` | `## 1 服务范围` → 先按正文编号规则取 1；`### 服务时限` → 无编号时取 `#` 个数 3 | 1~6 |

其他约定：

- 文档开头到第一个标题之间的内容 → `sectionTitle = "前言"`，`sectionLevel = 0`。
- **正文为空的标题不成节**（例如 `2 设施设备检查` 后面紧接 `2.1 巡检周期`），避免产出空 `content`；
  这类层级信息由子章节自己的 `sectionTitle` 表达。
- 条款型编号行（如 `2.1.1 供配电系统每季度至少巡检一次。`，以 `。` 结尾）按**正文**处理，
  保留在其父章节（`2.1 巡检周期`）的 `content` 里，**不会**被当成标题而丢失内容。

---

## 7. 示例命令（含真实运行结果）

### ① 试跑示例目录（自测用，产出 `sample/expected.jsonl`）

```powershell
D:\Anaconda3\python.exe tools\corpus\convert_corpus.py --input tools\corpus\sample --output tools\corpus\sample\expected.jsonl --source 示例来源
```

实际输出：

```
========================================================================
文档语料转换 convert_corpus.py v1.0.0
输入：tools\corpus\sample
输出：tools\corpus\sample\expected.jsonl
========================================================================
[信息] 忽略 2 个不支持的文件：make_sample_docx.py、selfcheck.py
[完成] 校园后勤维修服务手册.md → 9 章节（解析：utf-8-sig；剔除页码 0 行 / 页眉页脚 0 行；合并硬换行 2 处；docType=manual）
[完成] 校园报修工单处理办法.docx → 6 章节（解析：docx(zipfile+xml)；剔除页码 4 行 / 页眉页脚 5 行；合并硬换行 5 处；docType=policy）
[完成] 校园既有建筑设备维护通用规范.txt → 10 章节（解析：utf-8-sig；剔除页码 6 行 / 页眉页脚 6 行；合并硬换行 34 处；docType=standard）
------------------------------------------------------------------------
汇总
  处理文件数    : 3（成功 3，跳过 0）
  跳过文件数    : 0
  输出章节数    : 25
  平均字符数    : 120.3
  总字符数      : 3008
  输出文件      : tools\corpus\sample\expected.jsonl
------------------------------------------------------------------------
```

### ② 单份规范，参数给全（`GB 55022-2021` 风格）

```powershell
D:\Anaconda3\python.exe tools\corpus\convert_corpus.py `
  --input "tools\corpus\sample\校园既有建筑设备维护通用规范.txt" `
  --output "$env:TEMP\gb55022.jsonl" `
  --source "GB 55022-2021" --category-id 20001 --doc-type standard `
  --standard-no "GB 55022-2021" --doc-version "2021" --effective-date 2021-04-09
```

输出第一行（`content` 已截断）：

```json
{
  "title": "校园既有建筑设备维护通用规范 前言",
  "source": "GB 55022-2021",
  "standardNo": "GB 55022-2021",
  "docType": "standard",
  "docVersion": "2021",
  "effectiveDate": "2021-04-09",
  "categoryId": 20001,
  "sectionTitle": "前言",
  "sectionLevel": 0,
  "content": "校园既有建筑设备维护通用规范\nGB/T 55022-2025…",
  "charCount": 30
}
```

汇总行：`处理文件数 : 1（成功 1，跳过 0）/ 输出章节数 : 10 / 平均字符数 : 226.4`，
最后一行 title 为 `校园既有建筑设备维护通用规范 附录A 设备巡检要点（示例）（续2）`（超长章节已分块）。

### ③ 一个目录批量转换（一个月的原文丢进来即可）

```powershell
D:\Anaconda3\python.exe tools\corpus\convert_corpus.py `
  --input "doc\规范原文" --output "tools\corpus\out\corpus-2025-06.jsonl" `
  --category-id 20001 --source "校园后勤维修知识库"
```

### ④ PDF / .doc 走 Word COM 兜底（本机真实输出，未装 pypdf、未装 pdfminer）

```powershell
# 目录里放一个 .doc 和一个 .pdf（由示例 docx 用 Word 另存而来）
D:\Anaconda3\python.exe tools\corpus\convert_corpus.py `
  --input "$env:TEMP\word_e2e\input" --output "$env:TEMP\word_e2e\out.jsonl" --source 示例来源
```

```
========================================================================
文档语料转换 convert_corpus.py v1.1.0
输入：C:\Users\...\Temp\word_e2e\input
输出：C:\Users\...\Temp\word_e2e\out.jsonl
========================================================================
[信息] Word COM 兜底：可用（Word 16.0.19725.20530，C:\Program Files\Microsoft Office\root\Office16\WINWORD.EXE）
[信息] 校园报修工单处理办法.pdf：Word COM 兜底：校园报修工单处理办法.pdf → C:\Users\...\Temp\corpus_convert\corpus-1608f71e08.txt（转换完成已删除）；为绕开 Word 的 PDF 转换确认弹窗（PDFREFLOW 的模态框会永久卡住自动化），转换期间临时设置了 HKCU\Software\Microsoft\Office\16.0\Word\Options\DisableConvertPdfWarning=1，现已还原；已结束本次新产生的 Word 进程 24792（避免隐藏进程堆积）
[完成] 校园报修工单处理办法.pdf → 4 章节（解析：word-com(wdFormatUnicodeText/gb18030)；剔除页码 4 行 / 页眉页脚 3 行；合并硬换行 4 处；docType=policy）
[信息] 校园报修工单处理办法（旧版）.doc：Word COM 兜底：校园报修工单处理办法（旧版）.doc → C:\Users\...\Temp\corpus_convert\corpus-3b9e28a757.txt（转换完成已删除）；已结束本次新产生的 Word 进程 55692（避免隐藏进程堆积）
[完成] 校园报修工单处理办法（旧版）.doc → 6 章节（解析：word-com(wdFormatUnicodeText/gb18030)；剔除页码 4 行 / 页眉页脚 3 行；合并硬换行 5 处；docType=policy）
------------------------------------------------------------------------
汇总
  处理文件数    : 2（成功 2，跳过 0）
  跳过文件数    : 0
  输出章节数    : 10
  平均字符数    : 78.5
  总字符数      : 785
  输出文件      : C:\Users\...\Temp\word_e2e\out.jsonl
------------------------------------------------------------------------
```

退出码 `0`；`%TEMP%\corpus_convert\` 下的临时 txt 已删除、目录已回收，
注册表回到调用前状态（原来没有 `DisableConvertPdfWarning` 就删掉它），没有残留 `WINWORD.EXE` / `PDFREFLOW.exe`。

把 Word 兜底关掉（`--no-word`）时同一份输入的表现：

```
[信息] Word COM 兜底：已通过 --no-word 禁用
[跳过] 校园报修工单处理办法.pdf —— PDF 解析库缺失（pip install pypdf 或 pdfminer.six 可避免这一提示）。
      详细原因：pypdf 未安装（No module named 'pypdf'）；pdfminer.six 未安装（No module named 'pdfminer'）
      已用 --no-word 禁用 Word 兜底；建议另存为 .txt 后重试。
[跳过] 校园报修工单处理办法（旧版）.doc —— .doc 为旧版二进制格式，本脚本不直接解析（已用 --no-word 禁用 Word 兜底）。请先用 Word / WPS 另存为 .docx 或 .txt 后重试。
…
汇总  处理文件数 : 2（成功 0，跳过 2）   输出章节数 : 0     退出码 1（全部失败）
```

### ⑤ 排错模式：不改动原文，只看切分对不对

```powershell
# 关闭页码/页眉页脚剔除，并把分块上限调到 800，先看前 3 行结果
D:\Anaconda3\python.exe tools\corpus\convert_corpus.py `
  --input "tools\corpus\sample\校园既有建筑设备维护通用规范.txt" `
  --output "$env:TEMP\debug.jsonl" --keep-noise --max-chars 800 --source "调试"
Get-Content "$env:TEMP\debug.jsonl" -TotalCount 3 -Encoding UTF8
```

### ⑥ 一键自测

```powershell
D:\Anaconda3\python.exe tools\corpus\sample\selfcheck.py
```

---

## 8. 输出字段说明

每行一个 JSON 对象，共 11 个字段（顺序固定，字段名与后端 `CorpusSectionItem` 对齐）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `title` | string | `文档名 + 空格 + sectionTitle`；分块续篇追加 `（续N）`；文档名与章节名相同时不重复拼接 |
| `source` | string | `--source`，未给时用文档名 |
| `standardNo` | string \| null | `--standard-no` |
| `docType` | string | `standard` / `manual` / `policy` / `case` |
| `docVersion` | string \| null | `--doc-version` |
| `effectiveDate` | string \| null | `YYYY-MM-DD`（后端是 `java.time.LocalDate`，必须是这个格式） |
| `categoryId` | number \| null | `--category-id` |
| `sectionTitle` | string | 章节标题（不含续号，分块后各条保持一致） |
| `sectionLevel` | number | 章节层级：0 前言、1 章/一、/附录/单级编号、2 节/（一）/`x.y`、3 条/`x.y.z`、4+ 更深层级 |
| `content` | string | 清洗后的章节正文，**不含标题行本身**；段内保留 `\n`，段落间保留一个空行（`\n\n`） |
| `charCount` | number | `content` 的字符数（= `len(content)`） |

真实样例（`tools\corpus\sample\expected.jsonl`，`content` 已截断）：

```json
{"title": "校园后勤维修服务手册 1.1 受理范围", "source": "示例来源", "standardNo": null, "docType": "manual", "docVersion": null, "effectiveDate": null, "categoryId": null, "sectionTitle": "1.1 受理范围", "sectionLevel": 2, "content": "教学楼、办公楼、宿舍楼、食堂、体育馆内的给排水、照明、门窗、家具、空调及公共区域…", "charCount": 63}
{"title": "校园既有建筑设备维护通用规范 2.1 巡检周期", "source": "示例来源", "standardNo": null, "docType": "standard", "docVersion": null, "effectiveDate": null, "categoryId": null, "sectionTitle": "2.1 巡检周期", "sectionLevel": 2, "content": "2.1.1 供配电系统每季度至少巡检一次，遇大风、暴雨等极端天气后应立即开展专项检查。\n2.1.2 给排水系统每月巡检一次…", "charCount": 138}
```

---

## 9. 与后端导入接口对接

后端接口（已与仓库实现核对）：

```
POST /api/v1/admin/rag/corpus/import-batch
Authorization: Bearer <管理员 JWT>
Content-Type: application/json; charset=utf-8

{"batchName": "2025-06 规范语料第一批", "documents": [ ...JSONL 里的一行一行放进来... ]}
```

对应实现：`AdminRagCorpusController#importBatch`（`@RequestMapping("/api/v1/admin/rag/corpus")`）→
`RagCorpusImportService#importBatch` → `CorpusBatchImportRequest` + `CorpusSectionItem`。
服务端口见 `CampusRepairApi/src/main/resources/application.yaml`（`server.port: 8999`）。

**Python 一行不改直接对接**（只用标准库，和本脚本一个风格）：

```python
import json, urllib.request

BASE = "http://127.0.0.1:8999"
TOKEN = "<管理员 JWT>"          # 登录管理员账号后拿到的 token
BATCH_SIZE = 1000               # 后端单次上限 2000 条，留一半余量更稳

docs = []
with open("tools/corpus/out/corpus-2025-06.jsonl", encoding="utf-8") as fh:
    for line in fh:
        item = json.loads(line)
        item.pop("charCount", None)   # CorpusSectionItem 未声明该字段（Spring Boot 默认忽略未知字段，去掉更保险）
        docs.append(item)

for i in range(0, len(docs), BATCH_SIZE):
    payload = {"batchName": "2025-06 规范语料-%d" % (i // BATCH_SIZE + 1),
               "documents": docs[i:i + BATCH_SIZE]}
    req = urllib.request.Request(
        BASE + "/api/v1/admin/rag/corpus/import-batch",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8",
                 "Authorization": "Bearer " + TOKEN},
        method="POST")
    with urllib.request.urlopen(req) as resp:
        print("第 %d 批：" % (i // BATCH_SIZE + 1), resp.read().decode("utf-8")[:200])
```

对接注意事项（均来自后端实现）：

1. **需要管理员身份**：`importBatch` 内部 `requireAdmin(...)`，非管理员会直接被拒。
2. **单次最多 2000 条**（`MAX_BATCH_ITEMS = 2000`），超出部分会被**静默截断**（只打 warn 日志），所以务必分批。
3. **空 `content` 的记录会被跳过**，因此本脚本默认不输出空正文章节。
4. **后端按 `standardNo|title` 分组**（`groupKey`：`standardNo` 为空时只用 `title`），**一个分组生成一份知识文档**。
   本脚本的 `title = 文档名 + 章节名`，所以默认结果是「一个章节 = 一份知识文档」。
   如果希望「一份规范 = 一份知识文档」，在导入前把同一份文件里所有记录的 `title` 改成同一个值（文档名）即可，
   后端会把它们聚合为一份文档、多个章节（`sectionTitle` 保留章节信息，不影响检索）。
   两种聚合粒度都能用，按后端/前端展示习惯选；本脚本不改 `title`，保持“章节可独立检索、可独立下线”的粒度。
5. **`effectiveDate` 必须是 `YYYY-MM-DD`**（`LocalDate`），本脚本已做归一化；不给则输出 `null`。
6. **`sectionLevel` 为 `null` 时后端按 2 处理**；本脚本前言固定输出 `0`，其余为 1~4，均为合法值。
7. `charCount` 是给人工核对/统计用的字段：后端 `CorpusSectionItem` 未声明它，Spring Boot 默认
   `FAIL_ON_UNKNOWN_PROPERTIES=false`，多了也不会报错；若后续改成严格模式，用上面代码里的 `pop` 去掉即可。

---

## 10. 自测：怎么验证的

自测脚本 `tools/corpus/sample/selfcheck.py`（只用标准库，**109 项断言，全部通过**，本机耗时约 32 秒）覆盖四类：

**① 单元测试**（直接 `import` 主脚本调用内部函数）

- 编码探测：GBK 字节 → 自动识别为 `gb18030`；UTF-8 BOM 被剥离且 `used == "utf-8-sig"`；`--encoding gbk` 显式指定可用。
- docx 解析：`"维修班组：\t维修一组，联系电话：\t8377。"` 证明 `<w:tab/>` 变成制表符；
  `"报修工单分为三类：\n紧急工单、\n一般工单。"` 证明 `<w:br/>` 变成换行；header 部件文本被收集为噪声行。
- 标题识别：13 个正例（含 `附录A …（示例）`、`3.4 设施设备检查` → level 2、`1.1.1.1` → level 4）+ 5 个反例
  （`4.1 本规范自发布之日起施行，…。`、`30 分钟内完成派单。`、`第 12 页` 等都不算标题）+ 超 40 字不算标题 + Markdown `#` 规则。
- 清洗：6 种页码行全部剔除；重复 3 次的页眉行被剔除；**重复出现的标题行因“是标题”被保留**；
  中文硬换行合并不加空格 / 英文加空格 / `main-`+`tenance` 接合；标准号行不与标题误合并；空行压缩；列表项各自成行。
- 切分与分块：同一标题重复出现各自成节；空正文章节被跳过；首行非标题生成「前言」(level 0)；
  超 1500 字按自然段分块且 `"\n\n".join(chunks) == 原文`（内容无丢失、无重复）；单段超长按长度硬切不丢字。
- 字段契约：字段名与顺序、`title` 拼接、`charCount == len(content)`、可 `json.dumps`。

**② 端到端**（真实 `subprocess` 调用 CLI，处理 `sample/` 目录）

- **每行都能被 `json.loads` 解析**：逐行 `json.loads`，任何一行抛 `JSONDecodeError` 即失败 ——
  自测里就是 `for line in raw_lines: json.loads(line)`，并在开头打印「每行都能被 json.loads 解析（共 25 行）」。
- 每行字段名/数量一致、`charCount` 一致、`source` 生效、`sectionLevel` 合法、`content` 不含标题行本身。
- 输出文件 **UTF-8 无 BOM**（首 3 字节 ≠ `EF BB BF`）且以 `\n` 结尾。
- **与 `expected.jsonl` 逐行完全一致**（保证同样的输入永远产出同样的结果）。
- 清洗效果：`内部资料 请勿外传`、`征求意见稿`、`- 3 -`、`第 4 页`、`— 5 —`、`校园后勤保障处 编制`、`后勤保障处 · 内部资料` 均不出现在任何 `content` 里；
  再用自测**独立实现**的页码正则扫一遍全部 `content`，确认没有残留纯页码行。
- docx 的 tab / br、txt 的硬换行合并、`附录A` 拆成 2 条且第 2 条 `title` 以 `（续2）` 结尾、`sectionTitle` 一致。

**③ 优雅降级**（临时目录里造 `.doc` / 假 `.pdf`，统一加 `--no-word`，不依赖 Word）

- 正常文件 + `.doc` → 退出码 `0`，打印“请先另存为 .docx / .txt”，正常文件仍产出语料。
- **只有一个 `.doc`**（全部失败）→ 退出码 `1`。
- 只有一个 `.pdf` 且本机没装解析库 → 退出码 `1`、打印 `pip install pypdf` / `pdfminer.six` 提示、**不抛异常**。
- 加 `--no-word` 时会明确提示“已用 --no-word 禁用 Word 兜底”。

**④ Word COM 兜底**（真实调用 PowerShell + Word，把示例 docx 另存成 `.doc` / `.pdf` 当夹具）

- 找到 PowerShell 可执行文件（本机 `powershell.exe` 不在 PATH，脚本按候选绝对路径找到了它）。
- 用 `SaveAs2` 生成 `.doc`（fmt 0）与 `.pdf`（fmt 17）夹具 → 两个文件都被 Word 兜底解析出章节，
  日志里出现 `解析：word-com(wdFormatUnicodeText/...)`。
- `--word-path` 传真实 `WINWORD.EXE` 路径可正常转换。
- Word txt 里的分页符 `\x0c`、单元格标记 `\x07`、软换行 `\x0b` 已被清理。
- **`DisableConvertPdfWarning` 注册表值在转换前后完全一致**（用 `winreg` 读回比对，确认“临时改 + 还原”）。
- `%TEMP%\corpus_convert` 下没有残留临时 txt；**没有残留 `WINWORD.EXE` / `PDFREFLOW.exe` 进程**（转换前后用 `tasklist` 比对 PID）。
- `--no-word` 时 `.doc`/`.pdf` 全部跳过、退出码 `1`，并给出“另存为 .docx / .txt”的建议。
- 若机器上检测不到 Word，这一节打印提示并自动跳过（不会把自测跑红）。

实际结果：

```
========================================================================
tools/corpus 自测 selfcheck.py
被测脚本：F:\javaWeb\毕设接单\CampusRepair\tools\corpus\convert_corpus.py
示例目录：F:\javaWeb\毕设接单\CampusRepair\tools\corpus\sample
========================================================================
…
== 端到端测试 3：Word COM 兜底（PDF / .doc，无需 pip） ==
  [PASS] 找到 PowerShell 可执行文件
  [信息] Word COM 探测：可用（Word 16.0.19725.20530，C:\Program Files\Microsoft Office\root\Office16\WINWORD.EXE）
  [PASS] 用 Word 生成 .doc 夹具
  [PASS] 用 Word 生成 .pdf 夹具
  [信息] --word-path C:\Program Files\Microsoft Office\root\Office16\WINWORD.EXE
  [PASS] Word 兜底路径退出码为 0
  [PASS] 日志显示使用了 word-com 解析引擎
  [PASS] PDF 转换会绕开确认弹窗（临时设置注册表并还原）
  [PASS] Word 兜底至少产出一条语料
  [PASS] .doc 经 Word 兜底解析出章节
  [PASS] .pdf 经 Word 兜底解析出章节
  [PASS] Word 兜底结果 charCount 正确
  [PASS] Word txt 的分页符 / 单元格标记等控制字符已清理
  [PASS] DisableConvertPdfWarning 注册表值已还原到调用前状态
  [PASS] %TEMP%\corpus_convert 下的临时 txt 已删除
  [PASS] 没有残留的 WINWORD / PDFREFLOW 进程
  [PASS] --no-word 时 .doc/.pdf 全部失败退出码为 1
  [PASS] --no-word 提示清楚
  [PASS] --no-word 时给出“另存为 .docx/.txt”的中文建议

========================================================================
自测结果：通过 109 项，失败 0 项
========================================================================
```

手动核验 JSONL 是否行行合法（不依赖自测脚本）：

```powershell
D:\Anaconda3\python.exe -c "import json,io; rows=[json.loads(l) for l in io.open('tools/corpus/sample/expected.jsonl',encoding='utf-8')]; print('行数', len(rows), '字段', list(rows[0].keys()))"
# 行数 25 字段 ['title','source','standardNo','docType','docVersion','effectiveDate','categoryId','sectionTitle','sectionLevel','content','charCount']
```

示例目录跑出来的 25 条章节一览（用于人工比对切分是否正确）：

```
 1  L1  校园后勤维修服务手册                               22 字  manual
 2  L2  1.1 受理范围                                      63 字  manual
 3  L2  1.2 不受理范围                                    74 字  manual
 4  L2  2.1 线上报修                                      41 字  manual
 5  L2  2.2 电话报修                                      42 字  manual
 6  L2  3.1 一般维修                                      41 字  manual
 7  L2  3.2 紧急维修                                      45 字  manual
 8  L2  4.1 收费说明                                      40 字  manual
 9  L2  4.2 服务监督                                      52 字  manual
10  L0  前言                                              28 字  policy
11  L1  一、适用范围                                      56 字  policy
12  L2  （一）受理时限                                    57 字  policy
13  L2  1.1 派单规则                                      80 字  policy
14  L3  1.1.1 超时升级                                    47 字  policy
15  L1  二、回访与考核                                    56 字  policy
16  L0  前言                                              30 字  standard
17  L2  1.1 适用范围                                      71 字  standard
18  L2  1.2 基本要求                                      72 字  standard
19  L2  2.1 巡检周期                                     138 字  standard   ← 条款型 2.1.x 保留在父章节正文里
20  L2  2.2 检查记录                                      54 字  standard
21  L2  3.1 报修受理                                      59 字  standard
22  L2  3.2 响应时限                                      55 字  standard
23  L1  4 附则                                            27 字  standard
24  L1  附录A 设备巡检要点（示例）                       1306 字  standard   ← 超长章节第 1 块
25  L1  附录A 设备巡检要点（示例）                        452 字  standard   ← title 带（续2），sectionTitle 不变
```

---

## 11. 已知限制与常见问题

- **页码误删的风险**：连续 1~4 位纯数字的独立行会被当页码删掉。若正文里有“单独成行的数字”（如表格里只剩一个数量值），
  用 `--keep-noise` 先看原文，或改用 `--min-header-repeat` 调整。
- **Word 读 PDF 会“重排”**：Word 把 PDF 转成可编辑文档时按自己的理解重建段落，
  原本独立成行的标题常常被并进正文（实测同一份 docx 导出 PDF 后再转回文本，
  章节数从 6 条变成 4 条，`第一章 总则一、适用范围本办法适用于…` 粘成一行）。
  文字内容不丢，但**标题层级会变差**。要高质量切分，优先喂 `.docx` / `.txt` 原文；
  要求更高时用 `pip install pypdf` 走纯 Python 路径。
- **Word 兜底的耗时**：每个 PDF / `.doc` 约 5~15 秒；单个文件超时后会自动清理残留进程并重试一次，
  最坏耗时 ≈ 2 × `--word-timeout`（默认 240 秒）；批量转换建议只对少量 PDF 使用，或先装 pypdf。
- **Word 兜底会临时改一个注册表键**（仅 PDF）：`HKCU\...\Word\Options\DisableConvertPdfWarning`，
  转换后立即还原（详见第 3 节）。不接受改注册表就加 `--no-word-regfix`，或直接 `--no-word`。
- **脚本运行时不要手工编辑 Word 文档**：脚本不会 Quit 你已经打开的 Word
  （会检测到，只关闭自己打开的文档），但 Word 是单实例自动化服务器，
  万一中途被强制中断，请检查任务管理器里是否有残留的 `WINWORD.EXE` / `PDFREFLOW.exe`。
- **扫描版（图片型）PDF 无法解析**：需要先 OCR；Word 也只会得到空文本（脚本会提示“转换后 txt 为空”）。
- **`.wps` 不支持**：WPS 私有格式，请另存为 `.docx` / `.txt`（本机没装 WPS，未做验证）。
- **PDF 的换行很碎**：PDF 提取出来的文本常按视觉行断行，本脚本的硬换行合并能处理大部分情况，
  但双栏排版、表格会被压成流水文本；要求高的规范建议用 docx / txt 原文。
- **条款密集的标准**：`x.y.z` 条款行若以 `。` 结尾会作为正文保留（不切分），
  这样不会丢内容，但一条 `content` 里可能包含多条条款；需要“一条条款一个 chunk”时，请先用后端
  `RagCorpusSplitter` 的二次切分能力，或把条款行改写成“编号 + 标题”形式。
- **`--doc-name` 多文件**：多文件输入时所有记录会共用同一个文档名，脚本会打警告，建议省略该参数。
- **重复运行幂等**：同一个输入 + 同一组参数 → 输出逐字节一致（自测里有这条断言），便于重跑与 diff。
  例外：Word 兜底的 PDF 结果取决于 Word 版本与重排行为，换机器可能不同。

---

## 12. 相关文件

| 文件 | 说明 |
| --- | --- |
| `tools/corpus/convert_corpus.py` | 转换脚本（核心为标准库；PDF 走 pypdf → pdfminer → Word COM 三条路径） |
| `tools/corpus/sample/make_sample_docx.py` | 用 `zipfile` 现场生成最小 docx（含 `<w:tab/>`、`<w:br/>`、页眉页脚部件） |
| `tools/corpus/sample/selfcheck.py` | 自测脚本（109 项断言，退出码即结果；含 Word COM 兜底端到端测试） |
| `tools/corpus/sample/expected.jsonl` | 示例目录的期望输出（25 行） |
| `CampusRepairApi/.../module/rag/dto/CorpusSectionItem.java` | 后端对应的导入 DTO（字段以此为准） |
