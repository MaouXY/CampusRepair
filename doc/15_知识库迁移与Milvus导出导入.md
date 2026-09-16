# 知识库迁移：Milvus 集合的导出与导入

> 场景：把本机的向量库搬到另一台机器 / 另一个 Milvus 实例；或者备份下来以防万一。
> 本机现状：Milvus **v2.4.9**（Docker `milvus-standalone`，19530 gRPC + REST v2，9091 仅 /healthz），
> 集合 `campus_repair_knowledge`（191 实体，字段 `id/text/metadata/vector`，dim=2560，metric=COSINE），
> 同一实例上还有其它项目的 `book_semantic_db`——**迁移时只动自己的集合，别碰别人的**。

## 1. 四种方式怎么选

| 方式 | 是否重算向量 | 依赖 | 适用场景 |
| --- | --- | --- | --- |
| **A. JSONL 导出/导入**（本文脚本，已实测） | 否，原样搬运 | 只需 REST v2（19530），零依赖 | 跨实例/跨机器搬指定集合，最通用 |
| **B. 导出 MySQL 知识表 + 目标端重建向量** | 是（重调 embedding） | 项目自带接口 | 只搬本项目知识库，最省事；向量本就是派生数据 |
| **C. 官方 milvus-backup 工具** | 否 | 需目标端能访问同一份备份存储（S3/MinIO） | 整实例/多集合定期备份恢复 |
| **D. 整实例搬迁（复制 etcd + MinIO 卷）** | 否 | 目标 Milvus 版本必须一致 | 把整套环境（含其它项目）原样搬到另一台机器 |

## 2. 方式 A：JSONL 导出与导入（已实测通过）

```powershell
# ① 在源实例导出（含向量，191 条 → 约 4.4MB）
pwsh tools/milvus-export.ps1
#   指定集合与输出：-Collection book_semantic_db -Output backup\book.jsonl

# ② 在目标实例导入（自动按源结构建集合+建索引+加载）
pwsh tools/milvus-import.ps1 -MilvusUrl http://192.168.1.50:19530 `
     -Source backup\milvus-campus_repair_knowledge.jsonl `
     -Collection campus_repair_knowledge -CreateCollection

# 只检查不写入
pwsh tools/milvus-import.ps1 -DryRun
```

脚本要点：

- **导出**：先 `collections/describe` 取字段定义，再分页 `entities/query` 拉全量（默认每页 500，Milvus query 的 offset 上限 16384，更大数据量需改用 pymilvus 的 `query_iterator`）；
  输出 JSONL 首行是 `__schema`（字段、维度、实体数），便于导入端重建同构集合。
- **导入**：按 `__schema` 生成建集合请求（`schema.fields` + `indexParams` AUTOINDEX/COSINE），
  再做 `collections/load`；插入时**用 JSONL 原始实体文本直接拼 data 数组**，避免 PowerShell「单元素数组被解包成标量」把请求体写坏。
- 实测结果：导出 191 条 → 导入到新建集合 `campus_repair_knowledge_copy` → `count(*) = 191`，抽取实体文本一致，随后已删除该副本。

**注意**：跨实例迁移建议 `-Collection` 换个名字或用不同实例，避免目标端已有同名集合导致数据重复（Milvus 不会去重，`id` 相同也只是两条）。

## 3. 方式 B：MySQL 知识表 + 目标端重建（推荐用于本项目）

向量是**由切片文本算出来的派生数据**，所以只要源数据在，目标端重建即可：

```powershell
# 源端：只导出知识相关表（不含工单等业务数据）
mysqldump --host=127.0.0.1 --user=root --password=1829002 --default-character-set=utf8mb4 `
  campus_repair rag_knowledge_document rag_knowledge_chunk rag_knowledge_draft > knowledge.sql

# 目标端：导入上面这份 SQL，然后启动目标环境的后端，调用向量重建接口
#   POST /api/v1/admin/rag/vector-reset     清空本项目集合并按 MySQL 切片全量重建（191 条约 1 分钟）
#   POST /api/v1/admin/rag/documents/{id}/rebuild   只重建某篇文档
```

优点：不依赖 Milvus 版本与存储、无需搬向量文件；缺点：会重新调用 embedding（191 条成本极低）。
`vector-status` 接口可确认「已同步/待同步/失败」数量。

## 4. 方式 C：官方 milvus-backup（整实例备份恢复）

```bash
# 备份端 backup.yaml 需同时配置 Milvus 连接与备份存储（S3/MinIO）
milvus-backup create -n campus_repair_backup --colls campus_repair_knowledge
milvus-backup restore -n campus_repair_backup --colls campus_repair_knowledge   # 在目标实例执行
```
前提：**目标端能访问同一份备份存储**。本机 Milvus 是 Docker 版，MinIO 也是容器内的，跨机器使用时通常要先把 MinIO 的 bucket 目录拷过去。

## 5. 方式 D：整实例搬迁（版本必须一致）

```powershell
docker stop milvus-standalone milvus-etcd milvus-minio
# 复制三个容器的卷（etcd 存元数据、minio 存向量与索引文件）
docker cp milvus-etcd:/etcd-data ./migrate/etcd
docker cp milvus-minio:/minio_data ./migrate/minio
# 目标机器：用相同 milvus 镜像与 compose（挂同样的卷路径）启动即可
```
最省事但最"重"：会连 `book_semantic_db` 等其它集合一起搬；版本不一致（如 2.4 → 2.6）不能直接套用。

## 6. 迁移前后的检查清单

- [ ] Milvus **版本一致**（本机 2.4.9）；跨大版本请用方式 A 或 B
- [ ] embedding **模型与维度一致**（`doubao-embedding-text-240715`，**2560 维**）——不一致必须重新向量化，否则检索结果无意义
- [ ] **集合名隔离**：本机与其它项目共用 Milvus，本项目固定 `campus_repair_knowledge`；导入时不要覆盖 `book_semantic_db`
- [ ] 目标端 `GET /api/v1/admin/rag/vector-status` 显示 `已同步=片段数、失败=0`
- [ ] 用 `GET /api/v1/admin/rag/search-debug?query=...` 抽查一条口语化提问，确认召回正常
- [ ] 顺带提醒：**Attu 客户端版本必须匹配 Milvus**（Attu v3.0 只支持 Milvus 2.6+/3.x，连 2.4 会报 403；请用 Attu v2.4.12，且认证方式选「无」因为本机未开启鉴权）
