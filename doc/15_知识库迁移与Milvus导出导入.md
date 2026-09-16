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

### 2.1 跨电脑迁移（目标机没有 PowerShell 也能做）

仓库里同时提供了**零依赖 Python 版** `tools/milvus_migrate.py`（只用标准库，有 Python 3.8+ 即可）：

```bash
# 源机器（本机）
python tools/milvus_migrate.py export --collection campus_repair_knowledge --output milvus.jsonl

# 把 milvus.jsonl（约 4.4MB）拷到另一台电脑，然后在那边执行
python tools/milvus_migrate.py import --source milvus.jsonl \
    --milvus http://127.0.0.1:19530 --collection campus_repair_knowledge --create
python tools/milvus_migrate.py import --source milvus.jsonl --dry-run   # 只检查
```

实测（本机同一实例搬成副本集合）：导出 191 条 → 目标自动建集合（含 AUTOINDEX/COSINE 索引）+ 分批插入 → `count(*)=191` 校验一致 → 副本已删除。

**目标机器只有老版 Windows PowerShell（5.1）也能跑**（已实测：5.1 与 7 都通过）：

```powershell
# 只要把这一个脚本 + 导出文件拷过去即可（不必克隆整个仓库）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\milvus-import.ps1 `
  -Source milvus-campus_repair_knowledge.jsonl `
  -Collection campus_repair_knowledge -CreateCollection
```
> 注意：脚本内是中文注释与提示，**必须带 UTF-8 BOM** 保存，否则 PowerShell 5.1 会按 ANSI 解码、把引号吃掉报 "The string is missing the terminator"。
> `tools/` 下的脚本都已加 BOM（git 里存的是带 BOM 的 UTF-8），用记事本另存时请选"UTF-8"而不是"ANSI"。


跨电脑必查：目标机 Milvus **版本 ≥ 源端**、`19530` 可达（REST v2 就在这个端口）、embedding 模型与**维度必须一致**（本项目 `doubao-embedding-text-240715` / **2560 维**），否则搬过去检索结果无意义。

### 2.2 为什么不能用 Attu 直接导入这个 JSON

**Attu 没有"上传本地文件导入数据"的功能**，它能做的只有：浏览集合/实体、执行查询、手动逐条 Insert、建集合与索引、查看索引与加载状态。所以：

- ❌ 把 `milvus-*.jsonl` 拖进 Attu → 不存在这个入口；
- ⚠️ Attu 若暴露 **Bulk Insert / Import（批量导入）**，那条路要求文件先放进 **Milvus 能访问的对象存储**（本机是 MinIO 的 `a-bucket`），并符合 Milvus 导入格式规范，链路比直接跑脚本长得多；
- ✅ 正确姿势还是**用脚本导入**（方式 A）；Attu 留给"导入完去肉眼看数据对不对"最合适 —— 连 `127.0.0.1:19530`、认证方式选「无」，就能看到集合实体数与向量字段。

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

## 4. 方式 C：官方 milvus-backup（完整步骤）

### 4.1 版本规则（官方 README 原文要点）

- **最新版 milvus-backup 支持从 Milvus 2.2+ 备份、恢复到 Milvus 2.4+**
- **备份只能恢复到「同版本或更新版本」**的 Milvus（例如从 2.5 备份的不能恢复到 2.4）
- 本机是 Milvus **2.4.9** → 直接用最新版 **v0.6.0**（不需要找老版本 v0.4.x）

### 4.2 Windows 上怎么跑

官方只发布 **Linux / macOS** 二进制（没有 Windows exe），三种办法：

```powershell
# ① 推荐：用 Docker 镜像跑（本机已有 Docker）
docker run --rm --network milvus `
  -v F:\javaWeb\毕设接单\CampusRepair\tools\milvus-backup.yaml:/app/configs/backup.yaml `
  zilliz/milvus-backup:latest list

# ② WSL2 里用 Linux 二进制
#   下载 milvus-backup_0.6.0_Linux_x86_64.tar.gz 解压后执行

# ③ 若不确定镜像里的配置路径，先进去看一眼
docker run --rm --entrypoint sh zilliz/milvus-backup:latest -c 'ls /app/configs; /milvus-backup --help'
```

### 4.3 配置（`tools/milvus-backup.yaml` 已按本机实测参数写好）

关键点：**`milvus.storage` 必须与 Milvus 实际使用的对象存储一致**，否则读不到 segment/binlog：

| 项 | 本机实测值 |
| --- | --- |
| Milvus gRPC | `19530`（REST 也在 19530） |
| MinIO API / 控制台 | `9000` / `9001`（控制台 http://127.0.0.1:9001，minioadmin/minioadmin） |
| bucket / rootPath | `a-bucket` / `files` |
| 账号 | `minioadmin` / `minioadmin` |
| Docker 网络 | Milvus 与 MinIO 同在 `milvus` 网络（容器内可用容器名互访） |

配置里有两段：`milvus.storage`（备份时=源实例存储，恢复时=**目标实例**存储）与 `backup.storage`（备份文件写在哪）。
两段不同就是官方的**跨存储**用法。

> 先在 MinIO 控制台建好备份用桶（如 `milvus-backup`），再执行备份；`a-bucket` 是 Milvus 自己的数据桶，不要往里写备份。

### 4.4 备份（源实例）

```powershell
docker run --rm --network milvus -v <配置目录>:/app/configs zilliz/milvus-backup:latest `
  create -n campus_repair_20260916 --filter campus_repair_knowledge
#   老版本用 -c 指定集合：create -n campus_repair_20260916 -c campus_repair_knowledge
#   不带集合参数就是整实例（会把 book_semantic_db 等其它项目的集合一起备份）
#   按库/集合过滤：--filter campus_repair_knowledge   或  --filter 'db1.*'
docker run --rm --network milvus -v <配置目录>:/app/configs zilliz/milvus-backup:latest list
```

### 4.5 恢复 / 导入（这才是「导入到另一个实例」的步骤）

**① 恢复到本实例、同名集合**（覆盖式，原集合数据会被替换）
```powershell
... restore -n campus_repair_20260916 --filter campus_repair_knowledge
#   老版本：... restore -n campus_repair_20260916 -c campus_repair_knowledge
```

**② 恢复成新集合名**（推荐：不动现有集合，先验证）
```powershell
# 加后缀：集合名变成 campus_repair_knowledge_recover
... restore -n campus_repair_20260916 -s _recover --filter campus_repair_knowledge_recover

# 或重命名：把库1的 coll1 恢复成 库2的 coll1
... restore -n campus_repair_20260916 -r campus_repair_knowledge:cr_knowledge_new --filter cr_knowledge_new
```

**③ 恢复到另一个 Milvus 实例**（跨实例 + 跨存储）
1. 把配置里的 `milvus.address/port` 改成**目标实例**的地址；
2. `milvus.storage` 改成**目标实例自己的对象存储**（必须与那台 Milvus 的配置一致，否则恢复的数据它读不到）；
3. `backup.storage` 保持指向**备份文件所在存储**（源实例的 MinIO；跨机器时确保目标端能访问，比如同一个 MinIO 或 S3）；
4. 如果目标实例的 REST 端口不是 19530，在 `milvus` 段里显式指定 REST 地址；
5. 执行 `restore`，建议先加 `-s _new` 换个集合名，确认无误后再考虑覆盖。
> 记忆口诀：**`milvus.*` 管"往哪台 Milvus 写"，`backup.*` 管"备份文件在哪读"。**

### 4.6 恢复后怎么验证

```powershell
# 目标实例：REST 查实体数（19530 上的 REST v2）
curl -X POST http://<目标>:19530/v2/vectordb/entities/query -H "Content-Type: application/json" `
  -d '{"collectionName":"campus_repair_knowledge","filter":"","outputFields":["count(*)"]}'
# 应用侧：确认同步状态与检索效果
#   GET /api/v1/admin/rag/vector-status
#   GET /api/v1/admin/rag/search-debug?query=宿舍水房一直渗水
```

### 4.7 什么时候该用 milvus-backup

本项目只有 191 条实体、1 个集合，**方式 A/B 更快更简单**；milvus-backup 的价值在于：
整实例一致性快照、多集合批量、大数据量、以及"恢复到同版本或更新版本"的官方兼容性保证。

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
