# AI 能力增强说明：混合检索 + token 监控兜底 + RAG 自动化评测

> 本文对应第二轮提质中的三项 AI 能力升级：**混合检索（关键词 + 向量 + RRF）**、**token 实时监控与超限降级兜底**、**RAG 自动化评测**。

## 1. 混合检索 + RRF 融合

### 1.1 为什么需要

原实现的检索是「向量优先，向量没命中才退回关键词」：

- 向量召回擅长语义近似，但对**专有名词、设备编号、楼栋房间号**这类字符串几乎无感；
- 关键词检索（当前为中文 bigram 命中计数）擅长精确词，但不懂近义表达；
- 单一来源一旦失效（Milvus 未启动 / 集合为空），召回质量会整体塌陷。

因此改为**两路同时召回、再融合排序**。

### 1.2 融合算法

采用倒数排名融合（Reciprocal Rank Fusion, RRF，Cormack et al. 2009）：

```text
score(chunk) = Σ_source  weight_source / (k + rank_source(chunk))
```

- 只使用**名次**，不使用原始分数，因此可以把「bigram 命中次数」和「向量余弦相似度」这两种不可比的分数安全融合；
- `k` 默认 60（平滑常数，越大越弱化头部差距）；
- 同一 chunk 被两路同时召回时，两路分数累加，天然被顶到前面；
- 融合结果再交给既有重排（Bocha `gte-rerank`，未配置 API Key 时自动跳过）做最终精排，最后截取 top-k。

实现：`RagFusionService`（纯函数、可单测）；接入点：`RagKnowledgeService.searchDetailed()`。

### 1.3 配置

```yaml
app:
  agent:
    rag:
      hybrid:
        enabled: true          # 关闭后退回「向量优先，再关键词」的旧行为
        rrf-k: 60
        keyword-top-k: 10      # 关键词召回条数
        vector-top-k: 10       # 向量召回条数
        keyword-weight: 1.0
        vector-weight: 1.0
```

### 1.4 可观测性与回退

- 每次检索打印一条汇总日志：`hybridEnabled / vectorEnabled / keywordTopK / vectorTopK / keywordCandidateCount / vectorCandidateCount / overlapCount / fusedCount / returnedCount / topSource / rerankEnabled`；
- `topSource = KEYWORD+VECTOR` 表示该片段被两路同时召回；
- 向量库不可用（异常、未启用、集合未建）时 `vectorCandidateCount = 0`，融合结果自动退化为关键词排序，**不报错、不影响业务**。

### 1.5 测试

- `RagFusionServiceTest`：双路命中排第一、单路名次保持、权重生效、同 chunk 去重与 overlap 统计、无主键 chunk 的内容哈希兜底、空输入；
- `RagHybridSearchIntegrationTest`：用可控向量桩（`@Primary` 子类）验证「向量独有片段被融合进来」「两路命中排第一」「向量关闭时退化为关键词」。

## 2. token 实时监控与降级兜底

### 2.1 目标

避免「提示词太长 / 图片太多 / 当日额度用尽」直接把请求打挂，改为**逐级降级、始终有结果**。

### 2.2 token 估算与统计

- 调用前估算：中日韩字符按 1 字符 ≈ 1 token，其余按 4 字符 ≈ 1 token；每张图片按 800 token 计（`AiTokenEstimator`）；
- 调用后统计：优先进口模型返回的 `tokenUsage`（`input_tokens` / `output_tokens` / `total_tokens`，`token_source=API`）；若接口未返回用量则落估算值（`token_source=ESTIMATED`）；
- 「当日已用 token」直接由 `ai_task_record` 聚合查询得到（`SUM(input+output) WHERE created_at >= 今天0点 AND deleted=0`），**重启后依然准确**，不做内存计数。

### 2.3 四档降级

| 级别 | 触发条件 | 行为 |
| --- | --- | --- |
| `NORMAL` | 提示词与预算都正常 | 完整调用（图片 + 全部知识片段） |
| `DROP_IMAGES` | 提示词预估 > `max-prompt-tokens` 且带图片 | 舍弃图片输入，保留文本与知识片段 |
| `MINIMAL_CONTEXT` | 提示词超限且无图片可丢；或当日用量 ≥ 预算 × `degrade-ratio` | 知识片段压缩为最相关的前 N 条 |
| `RULE_ONLY` | 当日用量 ≥ 预算 × `rule-only-ratio` | **不调用模型**，直接使用规则评分 + 派单候选兜底，工单仍可正常派单 |

降级决策由 `AiTokenMonitor.decide(...)` 纯函数给出（可单测），决策原因写入 `ai_task_record.degrade_reason` 并在返回体/日志中体现。

注意：`RULE_ONLY` 跳过的调用**不写入 token 用量**（只记录提示词预估），否则「当日已用」会被虚增，导致后续请求一直被降级。

### 2.4 配置

```yaml
app:
  ai:
    ark:
      token:
        monitor-enabled: true
        daily-budget: 200000          # 每日 token 预算（输入+输出）
        degrade-ratio: 0.8            # 达到预算 80% 提前压缩上下文
        rule-only-ratio: 1.0          # 达到 100% 完全降级为规则兜底
        max-prompt-tokens: 6000       # 单次提示词预估上限
        minimal-context-chunks: 2     # 压缩后保留的知识片段条数
```

### 2.5 数据库与接口

- 建表脚本：`sql/15_schema_ai_token_monitor.sql`（`ai_task_record` 增加 `prompt_tokens`、`input_tokens`、`output_tokens`、`total_tokens`、`token_source`、`degrade_level`、`degrade_reason`，并为 `created_at` 建索引）；
- 查询接口：`GET /api/v1/admin/ai/token-usage` → 当日预算、已用量、剩余量、使用率、当日调用数、降级调用数、当前降级级别与提示语（仅管理员）。

### 2.6 测试

- `AiTokenEstimatorTest`：中英混排估算、空文本、图片 token；
- `AiTokenMonitorTest`：四档决策、默认配置兜底、关闭监控时始终 NORMAL；
- `AiTokenDegradeIntegrationTest`：正常调用记录 API 用量与 `NORMAL`；`max-prompt-tokens` 调小后验证「图片被真实丢弃」且 `degrade_level=DROP_IMAGES`；`token-usage` 接口权限与数值；
- `AiTokenRuleOnlyIntegrationTest`：预算耗尽时**模型零调用**、任务记为 `DEGRADED`、分析结果回退规则兜底且候选完整。

## 3. RAG 自动化评测

### 3.1 目标

让「RAG 效果好不好」可量化、可回归对比：换切分策略、换 Embedding、调 `rrf-k`、开关重排之后，跑一次评测就能看到指标涨跌。

### 3.2 指标定义

| 指标 | 含义 |
| --- | --- |
| `HitRate` | 至少召回 1 条相关片段的用例占比（含不可回答用例的拒答成功） |
| `Recall@k` | 平均「召回到的相关片段数 / 期望相关总数」 |
| `Precision@k` | 平均「召回到的相关片段数 / 召回条数」 |
| `MRR` | 平均首个相关片段名次的倒数（1/rank） |
| `nDCG@k` | 二值增益的归一化折损累计增益，衡量相关片段是否排得靠前 |
| `RefusalAccuracy` | 不可回答用例（`answerable=0`）的拒答准确率 |

相关性判定：优先按 `expected_doc_ids`（文档级）；为空时按 `expected_keywords`（片段正文包含任一关键词即相关），方便从只有答案文本的数据集导入。

### 3.3 数据表与接口

建表：`sql/16_schema_rag_eval.sql`（`rag_eval_case` / `rag_eval_run` / `rag_eval_case_result`）；
内置评测集：`sql/17_seed_rag_eval_dataset.sql`（8 条中文用例，7 条可回答 + 1 条测拒答）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/admin/rag/eval/datasets` | 评测集列表与用例数统计 |
| GET | `/api/v1/admin/rag/eval/cases?page&size&datasetName` | 用例分页查询 |
| POST | `/api/v1/admin/rag/eval/cases` | 新增单条用例 |
| POST | `/api/v1/admin/rag/eval/cases/import` | 批量导入（JSONL / JSON 数组） |
| DELETE | `/api/v1/admin/rag/eval/cases/{caseId}` | 删除用例 |
| POST | `/api/v1/admin/rag/eval/runs` | 运行评测（`datasetName`、`topK`） |
| GET | `/api/v1/admin/rag/eval/runs` | 历史评测记录（含指标） |
| GET | `/api/v1/admin/rag/eval/runs/{runId}` | 单次评测详情 + 逐条用例结果 + 召回片段快照 |

评测走的是**真实检索链路**（`RagKnowledgeService.searchDetailed`），因此结果同时反映混合检索与重排的最终效果。

### 3.4 导入格式

同一份 JSONL 可直接用于自建集，也可承接开源评测集转换结果（字段别名兼容）：

```jsonl
{"question": "空调不制冷怎么排查？", "expected_keywords": ["空调不制冷", "滤网"], "category_id": null, "task_type": "factual"}
{"query": "水管漏水处理流程", "gold_doc_ids": [60010], "source": "beir-sample"}
{"input": "学校食堂营业时间？", "answerable": false}
```

- 问题字段别名：`question` / `query` / `input`；
- 期望文档别名：`expectedDocIds` / `gold_doc_ids` / `relevant_doc_ids` / `doc_ids`；
- 期望关键词别名：`expectedKeywords` / `expected_keywords` / `gold_answers` / `answer_keywords` / `answer`；
- 其他：`category_id`、`answerable`、`task_type`、`source`、`dataset`；
- 支持 `#` 注释行，单次最多导入 500 条。

### 3.5 可参考的开源评测集

检索类（用于算 HitRate/Recall/MRR/nDCG）：

- BEIR（18 个子集的零样本检索基准，标准 `corpus/queries/qrels` 格式）— [GitHub](https://github.com/beir-cellar/beir)、[论文](https://arxiv.org/abs/2104.08663)
- T2Ranking（清华 THUIR，中文段落检索，SIGIR 2023）— [HuggingFace](https://huggingface.co/datasets/THUIR/T2Ranking)
- DuReader-Retrieval（百度，中文检索/重排）— [HuggingFace](https://huggingface.co/datasets/zyznull/dureader-retrieval-ranking)
- Multi-CPR（阿里，多领域中文检索）— [GitHub](https://github.com/Alibaba-NLP/Multi-CPR)
- MTEB / C-MTEB 中文检索子集（T2Retrieval、DuRetrieval、MMarcoRetrieval 等）— [MTEB](https://github.com/embeddings-benchmark/mteb)

端到端 RAG（含噪声鲁棒、拒答、幻觉）：

- RGB（中英双语，噪声鲁棒 / 负例拒答 / 信息整合 / 反事实鲁棒）— [GitHub](https://github.com/chen700564/RGB)、[论文](https://arxiv.org/abs/2309.01431)
- CRUD-RAG（中科大 IAAR，中文新闻场景的创建/问答/更新/幻觉四类任务）— [GitHub](https://github.com/IAAR-Shanghai/CRUD_RAG)、[论文](https://arxiv.org/abs/2401.17043)
- RAGEval（OpenCompass，场景化端到端，含 FactualCorrectness / Completeness / Hallucination 等指标）— [论文](https://arxiv.org/abs/2408.01262)
- 生成侧指标（Faithfulness、Answer Relevancy、Context Precision/Recall）可参考 RAGAS — [文档](https://docs.ragas.io/en/v0.1.21/getstarted/evaluation.html)；TruLens RAG Triad — [文档](https://www.trulens.org/get_started/core_concepts/rag_triad/)

**落地建议（本毕设采用）**：先用内置 8 条中文用例保证零成本回归；再从 RGB / CRUD-RAG 各抽 10–20 条，转成上面的 JSONL 字段导入，做鲁棒性与拒答的交叉验证；论文中逐条注明数据来源与许可证（上述多数数据集未明确标注许可，需要自行核对）。

### 3.6 测试

- `RagMetricCalculatorTest`：命中与倒数排名、多文档 Recall、关键词判定、全不相关、空召回、top-k 截断、文档ID缺失；
- `RagEvaluationServiceIntegrationTest`：跑通评测并核对指标（HitRate/Recall/MRR/nDCG/拒答准确率 = 1.0）、历史记录与详情查询、空评测集报错、非管理员 403、JSONL 别名导入与分页查询。

## 4. SQL 执行顺序（在原有 01–14 之后）

```text
15_schema_ai_token_monitor.sql     -- token 监控字段
16_schema_rag_eval.sql             -- RAG 评测三张表
17_seed_rag_eval_dataset.sql       -- 内置评测集（8 条）
```

## 5. 一键验证

```bash
cd CampusRepairApi
mvn -o test                        # 后端全量测试（H2 内存库，无需 MySQL/Milvus）
```

管理员登录后可用 Knife4j（`/doc.html`）直接试：

- `GET /api/v1/admin/ai/token-usage`（token 监控面板数据）
- `GET /api/v1/admin/rag/eval/datasets` → `POST /api/v1/admin/rag/eval/runs`（跑评测拿指标）
- `GET /api/v1/admin/tickets/{ticketId}/dispatch-suggestion`（派单建议）
