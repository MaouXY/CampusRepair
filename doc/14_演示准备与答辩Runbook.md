# 演示准备与答辩 Runbook

> 面向「项目演示 / 答辩现场」的操作手册：一键启动、演示主线、技术亮点话术、风险预案与排查。
> 所有数字均为本机实测值（2026-09-11），可直接作为讲解依据。

## 1. 一键启动

```powershell
cd F:\javaWeb\毕设接单\CampusRepair
pwsh tools/start-demo.ps1            # 拉起容器 + 后端 + 前端，并做演示前体检
pwsh tools/start-demo.ps1 -CheckOnly # 只体检不启动（演示前 5 分钟用）
pwsh tools/start-demo.ps1 -Stop      # 演示结束后停后端与前端（容器保留）
```

启动后会打印：前端地址、接口文档地址、账号、体检结果（知识库同步数、检索配置、待办数量、当日 AI token 用量）。

| 入口 | 地址 |
| --- | --- |
| 前端界面 | http://127.0.0.1:5173 |
| 接口文档（Knife4j） | http://127.0.0.1:8999/doc.html |
| Milvus / MySQL / RustFS | 19530 / 3306 / 9990（Docker 容器，已设 `--restart unless-stopped`） |

账号（密码均 `123456`）：`admin01`（管理员）、`admin02`（值班管理员）、`worker01`/`worker02`/`worker03`（维修员）、`student01`~`student04`（学生）。

## 2. 演示前 5 分钟检查清单

- [ ] `pwsh tools/start-demo.ps1 -CheckOnly` → 端口全 OK、知识库 `14 文档 / 191 切片 / 失败 0`、检索配置 `hybrid=True 重排=True`
- [ ] 浏览器打开 5173 能登录（建议 Chrome，提前登录一次避免现场输密码）
- [ ] 待办中心有数据：实测「待审核 8 / 退回 1 / 超时 11 / 督办 3 / 待确认 2」
- [ ] AI 可用性：管理端「AI 运行监控」卡片显示当日 token 用量且当前级别为 `NORMAL`
- [ ] 演示用图片准备好（真实 jpg/png，**不要用改名的 txt**，否则会被文件类型校验拒绝）
- [ ] 关闭其它占资源的程序（本机 Docker + MySQL + Milvus + 前后端约 1.5–2GB 内存）

## 3. 演示主线（12 步，含话术与预期现象）

### 3.1 学生端：发起报修

| 步骤 | 操作 | 预期现象 | 可讲的技术点 |
| --- | --- | --- | --- |
| 1 | `student01` 登录 → 提交报修 | 表单含地点、分类、描述、联系电话、**可上传多张现场照片** | 图片走 RustFS 对象存储，返回可访问 URL |
| 2 | 提交后立即返回工单 | 状态 `PENDING_REVIEW`，按优先级自动算出 SLA 截止时间 | 提交是同步的、AI 分析是**异步**的，不阻塞用户 |

### 3.2 管理端：AI 预分析 + 智能派单

| 步骤 | 操作 | 预期现象 | 可讲的技术点 |
| --- | --- | --- | --- |
| 3 | 等 20–30 秒，`admin01` → 工单管理 → 打开刚才的工单 | AI 分析区显示：建议分类/优先级、故障摘要、可能原因、建议方案、风险等级、置信度 | **多模态**：模型同时读报修文字与现场照片 |
| 4 | 看「智能派单建议」区块 | 显示来源标签（`AI` / `AI 降级` / `规则`）、推荐维修员、**推荐原因**、候选 Top3（技能/负载/历史质量/规则总分与逐项理由） | AI 只允许在候选 Top3 内选人，越界自动回退规则第一名 |
| 5 | 点候选行的「填入」→ 提交派单 | 维修员被自动选中，工单状态 `ASSIGNED` | 一键填入 + 统一状态机校验（仅待审核/已退回可派单） |

实测样例（水房渗水 + 旁边有配电箱）：

> 摘要「渗水点位紧邻配电箱，存在漏电安全隐患」｜风险 `HIGH`｜置信度 **0.95**｜
> 建议方案「①关阀止水清理积水 ②紧固接口更换密封件 ③保压试水 30 分钟并检查配电箱绝缘」｜
> 派单备注「涉水电高风险隐患，优先派水电组技能匹配的赵师傅」

### 3.3 维修端：接单与处理

| 步骤 | 操作 | 预期现象 | 可讲的技术点 |
| --- | --- | --- | --- |
| 6 | `worker01` 登录 → 今日任务工作台 | 今日待接单/处理中/超时任务与绩效摘要 | 工作台按紧急度、超时、同地点聚合排序 |
| 7 | 接单 → 提交处理结果（可传结果图） | 状态 `PROCESSING` → `WAITING_CONFIRM` | 派错专业可「退回工单」，管理员重新派单 |

### 3.4 学生端：确认评价（工单闭环）

| 步骤 | 操作 | 预期现象 | 可讲的技术点 |
| --- | --- | --- | --- |
| 8 | `student01` 评价 5 分 | 状态 `COMPLETED`，时间线 5 条流转记录 | 每次状态变化都写流转表，可追溯 |

### 3.5 知识沉淀闭环（第三阶段亮点）

| 步骤 | 操作 | 预期现象 | 可讲的技术点 |
| --- | --- | --- | --- |
| 9 | 等 20 秒 → 管理端 → RAG 管理 → 知识草稿审核 | 出现由**评价 4 分以上工单自动生成**的知识草稿（AI 生成，含适用场景/现象/原因/步骤/注意事项） | 业务产生知识的正循环；AI 不可用时退化为规则模板草稿 |
| 10 | 点「通过」审核入库 | 生成知识文档 + 自动切片 + 同步 Milvus，草稿状态变 `APPROVED` 并显示文档 ID | 同工单草稿复用、重复审核幂等 |

### 3.6 检索与评测（技术深度）

| 步骤 | 操作 | 预期现象 | 可讲的技术点 |
| --- | --- | --- | --- |
| 11 | RAG 管理 → 向量检索状态卡片 | `ARK_OPENAI / doubao-embedding-text-240715 / 2560 维 / Milvus 已启用 / 191 切片已全部同步`；下方显示 4 个检索参数 | 真实向量模型可达性一眼可验 |
| 12 | RAG 管理 → 运行评测（`campus-repair-hybrid-v2`，TopK=5） | 指标卡片：**HitRate 0.7727 / Recall 0.75 / MRR 0.5542 / nDCG 0.6027 / 拒答 1.00** | 见第 4 节：这组数字背后是四组对照实验 |
| 13（可选） | 用 Knife4j 调 `GET /api/v1/admin/rag/search-debug?query=宿舍那台机子开一整晚，屋里还是闷热` | 返回关键词段、向量段、RRF 融合段（含两路名次、重排分、最终名次）、最终结果 | 现场演示「哪一路召回了什么」，回答「为什么混合检索有效」 |
| 14（可选） | 管理端首页 → 月度维修报告 | 关键指标 + **AI 运营分析建议**（不可用时标注「规则模板兜底」） | AI 用于数据洞察，且具备降级能力 |

### 3.7 AI 运行监控与量化收益

| 步骤 | 操作 | 预期现象 | 可讲的技术点 |
| --- | --- | --- | --- |
| 15 | 管理端 → AI 运行监控卡片 | 当日 token 用量/预算、使用率、当日调用与降级次数、当前降级级别 | 实时 token 监控 + 四档降级（正常→丢图→压缩上下文→规则兜底） |

## 4. 量化成果（可直接写进 PPT）

零字面重叠评测集 `campus-repair-hybrid-v2`（22 条口语化报修提问，**问题与目标知识切片无任何字面重叠**），topK=5：

| 配置 | HitRate | Recall@5 | MRR | nDCG@5 | 延迟 |
| --- | --- | --- | --- | --- | --- |
| 纯关键词（关闭向量） | **0.1364** | 0.0500 | 0.0500 | 0.0500 | 648ms |
| 混合 RRF（无重排） | 0.5455 | 0.5000 | 0.2542 | 0.3174 | 243ms |
| **混合 RRF + 重排（默认）** | **0.7727** | **0.7500** | **0.5542** | **0.6027** | 918ms |

三个可直接讲的结论：

1. **语义检索不可替代**：纯关键词在真实口语提问上只命中 1/20（0.1364）——学生说的是「空调不凉快」，语料写的是「制冷效果衰减」，字面无交集。
2. **重排是最大单点增益**：同样候选池下开启重排，HitRate **+0.227**、MRR **+0.300**。
3. **参数有数据支撑**：向量候选数从 10 提到 20 指标反降（0.7727→0.6818），所以保持 10——「不拍脑袋调参」。

复跑命令：`pwsh tools/rag-eval-compare.ps1 -Dataset campus-repair-hybrid-v2 -TopK 5`

## 5. 风险预案（先想好被问到 / 出故障怎么办）

| 风险 | 现场表现 | 预案与话术 |
| --- | --- | --- |
| **网络/DNS 抖动导致 AI 调用失败**（已实测遇到一次） | 工单详情显示「AI暂不可用」但流程照常，派单建议来源为「AI 降级」 | 话术：*"AI 是增强能力而非单点依赖：调用失败会自动降级为规则评分与模板兜底，工单流程不中断。"* 演示前可先点一次「AI 预分析」验证连通性 |
| Milvus 容器崩溃（曾发生） | 检索退化为关键词 | 已为 4 个容器设置 `--restart unless-stopped`；若仍异常，管理端「向量检索状态」会明确提示失败切片数与修复建议 |
| Ark 账号欠费/限流 | AI 全部降级，token 监控显示降级次数上升 | 现场直接打开「AI 运行监控」讲**降级设计**，化风险为亮点 |
| 图片上传被拒（`12002 file type invalid`） | 上传报「文件类型不支持」 | 用真实 jpg/png；PowerShell/curl 手工测试需带 `type=image/png`，否则会被当成 `application/octet-stream` 拒绝 |
| 端口被占（8999/5173） | 后端启动失败 `Port 8999 was already in use` | `pwsh tools/start-demo.ps1 -Stop` 后重启；或用 `Get-NetTCPConnection -LocalPort 8999 -State Listen` 找到进程结束 |
| 演示时评测跑得慢 | 开重排时每条约 1 秒 | 评测集选 22 条的 `campus-repair-hybrid-v2`、TopK=5，全程约 20 秒；不要现场跑大批量 |

## 6. 常见排查命令

```powershell
# 端口占用
Get-NetTCPConnection -LocalPort 8999 -State Listen | Select-Object OwningProcess
# 容器状态与日志
docker ps --format '{{.Names}}  {{.Status}}'
docker logs --tail 50 milvus-standalone
# 后端日志（按启动方式不同文件名不同）
Get-Content CampusRepairApi\target\demo-api.log -Tail 80
# 关键日志场景：rag hybrid search done / rag rerank applied / ticket ai analysis succeeded / rag draft generated
Select-String -Path CampusRepairApi\target\demo-api.log -Pattern 'rag rerank applied|ticket ai analysis succeeded|rag draft generated' | Select-Object -Last 5
# 数据库直查（示例：今日工单与 AI 分析状态）
& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' --host=127.0.0.1 --port=3306 --user=root --password=1829002 --default-character-set=utf8mb4 --table campus_repair --execute="SELECT status, COUNT(*) FROM repair_ticket WHERE created_at >= CURDATE() GROUP BY status;"
```

## 7. 演示数据现状（2026-09-11 实测）

- 今日新增 3 条演示工单（含 1 条带图 + 配电箱漏电风险场景），AI 预分析状态均为 `SUCCESS`，其中一条已走完「派单→接单→处理→评价」全流程并自动沉淀出知识草稿
- 知识库：14 篇文档 / 191 个切片，全部同步到 Milvus
- 评测集：`campus-repair-builtin`（8 条）、`campus-repair-hybrid`（24 条）、`campus-repair-hybrid-v2`（22 条，零字面重叠）
- 待办：待审核 8 / 退回 1 / 超时 11 / 督办 3 / 待确认 2
