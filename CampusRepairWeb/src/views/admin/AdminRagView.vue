<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import type { OptionItem } from '@/types/base'
import type {
  AiTokenUsage,
  CorpusImportRequest,
  CorpusImportResult,
  CorpusPreview,
  KnowledgeDocument,
  KnowledgeDocumentRequest,
  KnowledgeDraft,
  RagEvalDataset,
  RagEvalRun,
  RagEvalRunDetail,
  RagVectorStatus,
} from '@/types/rag'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { listCategoriesApi } from '@/api/base'
import {
  approveKnowledgeDraftApi,
  createKnowledgeDocumentApi,
  deleteKnowledgeDocumentApi,
  generateKnowledgeDraftApi,
  getAiTokenUsageApi,
  getRagEvalRunApi,
  getRagVectorStatusApi,
  importCorpusApi,
  importCorpusBatchApi,
  importRagEvalCasesApi,
  listKnowledgeDocumentsApi,
  listKnowledgeDraftsApi,
  listRagEvalDatasetsApi,
  listRagEvalRunsApi,
  previewCorpusApi,
  rebuildKnowledgeDocumentApi,
  rejectKnowledgeDraftApi,
  runRagEvalApi,
  updateKnowledgeDocumentApi,
} from '@/api/rag'
import { formatDateTime } from '@/utils/ticketDisplay'

const loading = ref(false)
const drawerVisible = ref(false)
const submitting = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()
const categories = ref<OptionItem[]>([])
const documents = ref<KnowledgeDocument[]>([])

const tokenUsage = ref<AiTokenUsage | null>(null)
const evalDatasets = ref<RagEvalDataset[]>([])
const evalRuns = ref<RagEvalRun[]>([])
const evalDetail = ref<RagEvalRunDetail | null>(null)
const evalRunning = ref(false)
const importVisible = ref(false)
const importSubmitting = ref(false)

const vectorStatus = ref<RagVectorStatus | null>(null)
const vectorLoading = ref(false)

const corpusVisible = ref(false)
const corpusPreviewing = ref(false)
const corpusImporting = ref(false)
const corpusFormRef = ref<FormInstance>()
const corpusPreviewResult = ref<CorpusPreview | null>(null)
const corpusBatchPreview = ref(false)
const corpusImportResults = ref<CorpusImportResult[]>([])
const corpusMode = ref<'single' | 'batch'>('single')

const drafts = ref<KnowledgeDraft[]>([])
const draftsLoading = ref(false)
const draftStatus = ref('')
const generateTicketId = ref('')
const generating = ref(false)
const draftQuery = reactive({
  page: 1,
  size: 10,
  total: 0,
})

const draftStatusOptions = [
  { label: '全部状态', value: '' },
  { label: '待审核', value: 'PENDING_REVIEW' },
  { label: '已通过', value: 'APPROVED' },
  { label: '已驳回', value: 'REJECTED' },
]

const importForm = reactive({
  datasetName: 'imported-eval',
  source: 'open-dataset-sample',
  content: '',
})
const evalForm = reactive({
  datasetName: '',
  topK: 5,
})

const corpusForm = reactive<CorpusImportRequest>({
  title: '',
  categoryId: null,
  source: null,
  standardNo: null,
  docVersion: null,
  effectiveDate: null,
  docType: null,
  content: '',
  chunkSize: 800,
  chunkOverlap: 120,
  enabled: 1,
  importBatch: null,
})

const corpusRules: FormRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  content: [{ required: true, message: '请输入原文内容', trigger: 'blur' }],
}

const corpusDocTypeOptions = [
  { label: '标准规范（standard）', value: 'standard' },
  { label: '操作手册（manual）', value: 'manual' },
  { label: '校内制度（policy）', value: 'policy' },
  { label: '案例记录（case）', value: 'case' },
]

const corpusBatchMeta = computed(() => {
  const text = corpusForm.content
  if (corpusMode.value !== 'batch' || !text.trim()) return null
  const parsed = parseJsonl(text)
  if (parsed.error || parsed.rows.length === 0) return null
  const titles = new Set<string>()
  let totalChars = 0
  parsed.rows.forEach((row) => {
    const record = row as Record<string, unknown>
    const title = typeof record.title === 'string' ? record.title : ''
    if (title) titles.add(title)
    totalChars += typeof record.content === 'string' ? record.content.length : 0
  })
  return {
    chunkCount: parsed.rows.length,
    documentCount: titles.size,
    totalChars,
  }
})

const evalMetrics = computed(() => evalDetail.value?.run.metrics || null)
const tokenRatioText = computed(() =>
  tokenUsage.value ? `${(tokenUsage.value.usedRatio * 100).toFixed(1)}%` : '-',
)
const tokenLevelTagType = computed(() => {
  switch (tokenUsage.value?.currentLevel) {
    case 'NORMAL':
      return 'success'
    case 'DROP_IMAGES':
      return 'warning'
    case 'MINIMAL_CONTEXT':
      return 'warning'
    default:
      return 'danger'
  }
})

const query = reactive({
  page: 1,
  size: 10,
  total: 0,
})

const form = reactive<KnowledgeDocumentRequest>({
  title: '',
  categoryId: null,
  content: '',
  enabled: 1,
})

const rules: FormRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  content: [{ required: true, message: '请输入知识内容', trigger: 'blur' }],
}

onMounted(async () => {
  categories.value = await listCategoriesApi()
  await Promise.all([
    loadDocuments(),
    loadTokenUsage(),
    loadEvalPanel(),
    loadVectorStatus(),
    loadDrafts(),
  ])
})

async function loadVectorStatus() {
  vectorLoading.value = true
  try {
    vectorStatus.value = await getRagVectorStatusApi()
  } catch {
    vectorStatus.value = null
  } finally {
    vectorLoading.value = false
  }
}

function parseJsonl(text: string): { rows: unknown[]; error: string | null } {
  const rows: unknown[] = []
  const lines = text.split(/\r?\n/)
  for (let index = 0; index < lines.length; index += 1) {
    const raw = lines[index].trim()
    if (!raw || raw.startsWith('#')) continue
    try {
      // 转换脚本会多输出 charCount 字段，后端 DTO 没有该字段，解析时直接剔除
      const parsed: unknown = JSON.parse(raw, (key, value) =>
        key === 'charCount' ? undefined : value,
      )
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        return { rows, error: `第 ${index + 1} 行不是 JSON 对象` }
      }
      rows.push(parsed)
    } catch {
      return { rows, error: `第 ${index + 1} 行 JSON 解析失败，请检查引号与逗号` }
    }
  }
  return { rows, error: null }
}

function openCorpusImport() {
  corpusFormRef.value?.clearValidate()
  corpusPreviewResult.value = null
  corpusImportResults.value = []
  corpusBatchPreview.value = false
  corpusMode.value = 'single'
  corpusVisible.value = true
}

function toIdText(value: unknown): string | null {
  if (typeof value === 'string') return value.trim() || null
  if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  return null
}

function buildCorpusPayload(): CorpusImportRequest {
  return {
    ...corpusForm,
    title: corpusForm.title.trim(),
    content: corpusForm.content,
  }
}

function buildBatchDocuments(): unknown[] | null {
  const parsed = parseJsonl(corpusForm.content)
  if (parsed.error) {
    ElMessage.error(parsed.error)
    return null
  }
  if (parsed.rows.length === 0) {
    ElMessage.warning('JSONL 内容为空，请粘贴转换脚本输出的 JSONL')
    return null
  }
  return parsed.rows.map((row) => {
    const record = row as Record<string, unknown>
    return { ...record, categoryId: toIdText(record.categoryId) }
  })
}

function normalizeCorpusResults(
  result: CorpusImportResult | CorpusImportResult[],
): CorpusImportResult[] {
  return Array.isArray(result) ? result : [result]
}

async function handleCorpusPreview() {
  await corpusFormRef.value?.validate()
  if (corpusMode.value === 'batch') {
    const documents = buildBatchDocuments()
    if (!documents) return
    corpusPreviewResult.value = null
    corpusImportResults.value = []
    corpusBatchPreview.value = true
    return
  }
  corpusPreviewing.value = true
  try {
    corpusPreviewResult.value = await previewCorpusApi(buildCorpusPayload())
    corpusImportResults.value = []
    corpusBatchPreview.value = false
  } catch {
    // 失败提示由请求拦截器统一处理
  } finally {
    corpusPreviewing.value = false
  }
}

function handleCorpusImported(results: CorpusImportResult[]) {
  const documentCount = results.length
  const chunkCount = results.reduce((sum, item) => sum + item.chunkCount, 0)
  ElMessage.success(`导入完成：生成 ${documentCount} 篇知识文档，共 ${chunkCount} 个切片`)
  corpusImportResults.value = results
  corpusBatchPreview.value = false
  corpusPreviewResult.value = null
  corpusForm.content = ''
  corpusVisible.value = false
}

async function handleCorpusImport() {
  await corpusFormRef.value?.validate()
  const payload = buildCorpusPayload()
  let batchDocuments: unknown[] | null = null
  if (corpusMode.value === 'batch') {
    batchDocuments = buildBatchDocuments()
    if (!batchDocuments) return
  }
  corpusImporting.value = true
  try {
    if (batchDocuments) {
      const result = await importCorpusBatchApi({
        batchName: corpusForm.importBatch?.trim() || `corpus-web-${Date.now()}`,
        documents: batchDocuments,
      })
      handleCorpusImported(normalizeCorpusResults(result))
    } else {
      const result = await importCorpusApi(payload)
      handleCorpusImported(normalizeCorpusResults(result))
    }
    await Promise.all([loadDocuments(), loadVectorStatus()])
  } catch {
    // 失败提示由请求拦截器统一处理
  } finally {
    corpusImporting.value = false
  }
}

async function loadDrafts() {
  draftsLoading.value = true
  try {
    const result = await listKnowledgeDraftsApi({
      page: draftQuery.page,
      size: draftQuery.size,
      status: draftStatus.value || undefined,
    })
    drafts.value = result.items || []
    draftQuery.total = result.total || 0
  } catch {
    drafts.value = []
    draftQuery.total = 0
  } finally {
    draftsLoading.value = false
  }
}

function handleDraftFilter() {
  draftQuery.page = 1
  return loadDrafts()
}

async function handleGenerateDraft() {
  const ticketId = generateTicketId.value.trim()
  if (!ticketId) {
    ElMessage.warning('请输入来源工单 ID')
    return
  }
  generating.value = true
  try {
    const draft = await generateKnowledgeDraftApi(ticketId)
    ElMessage.success(`已生成知识草稿：${draft.title}`)
    generateTicketId.value = ''
    draftQuery.page = 1
    await loadDrafts()
  } catch {
    // 失败提示由请求拦截器统一处理
  } finally {
    generating.value = false
  }
}

function draftSourceLabel(source: string) {
  switch (source) {
    case 'AI':
      return 'AI 生成'
    case 'AI_DEGRADED':
      return 'AI 降级'
    case 'RULE':
      return '规则兜底'
    default:
      return source || '-'
  }
}

function draftSourceTagType(source: string) {
  switch (source) {
    case 'AI':
      return 'success'
    case 'AI_DEGRADED':
      return 'warning'
    default:
      return 'info'
  }
}

function draftStatusLabel(status: string) {
  switch (status) {
    case 'PENDING_REVIEW':
      return '待审核'
    case 'APPROVED':
      return '已通过'
    case 'REJECTED':
      return '已驳回'
    default:
      return status || '-'
  }
}

function draftStatusTagType(status: string) {
  switch (status) {
    case 'PENDING_REVIEW':
      return 'warning'
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    default:
      return 'info'
  }
}

async function handleApproveDraft(row: KnowledgeDraft) {
  try {
    const { value } = await ElMessageBox.prompt(
      `确认通过知识草稿“${row.title}”并入库？备注可留空。`,
      '知识草稿审核',
      {
        confirmButtonText: '确认通过',
        cancelButtonText: '取消',
        inputPlaceholder: '审核备注（可留空）',
        inputValue: '',
      },
    )
    await approveKnowledgeDraftApi(row.id, value || '')
    ElMessage.success('已通过并入库')
    await loadDrafts()
  } catch {
    // 用户取消或请求失败
  }
}

async function handleRejectDraft(row: KnowledgeDraft) {
  try {
    const { value } = await ElMessageBox.prompt(
      `确认驳回知识草稿“${row.title}”？备注可留空。`,
      '知识草稿审核',
      {
        confirmButtonText: '确认驳回',
        cancelButtonText: '取消',
        inputPlaceholder: '驳回原因（可留空）',
        inputValue: '',
      },
    )
    await rejectKnowledgeDraftApi(row.id, value || '')
    ElMessage.success('已驳回该草稿')
    await loadDrafts()
  } catch {
    // 用户取消或请求失败
  }
}

async function loadTokenUsage() {
  try {
    tokenUsage.value = await getAiTokenUsageApi()
  } catch {
    tokenUsage.value = null
  }
}

async function loadEvalPanel() {
  const [datasets, runs] = await Promise.all([
    listRagEvalDatasetsApi(),
    listRagEvalRunsApi({ page: 1, size: 5 }),
  ])
  evalDatasets.value = datasets
  evalRuns.value = runs.items
  if (!evalForm.datasetName && datasets.length > 0) {
    evalForm.datasetName = datasets[0].datasetName
  }
}

async function handleRunEval() {
  if (!evalForm.datasetName) {
    ElMessage.warning('请先选择评测集')
    return
  }
  evalRunning.value = true
  try {
    evalDetail.value = await runRagEvalApi({
      datasetName: evalForm.datasetName,
      topK: evalForm.topK,
    })
    ElMessage.success(`评测完成：HitRate ${(evalDetail.value.run.metrics.hitRate * 100).toFixed(1)}%`)
    await Promise.all([loadEvalPanel(), loadTokenUsage()])
  } finally {
    evalRunning.value = false
  }
}

async function handleOpenRun(run: RagEvalRun) {
  evalDetail.value = await getRagEvalRunApi(run.id)
}

async function handleImportCases() {
  if (!importForm.content.trim()) {
    ElMessage.warning('请输入 JSONL 内容')
    return
  }
  importSubmitting.value = true
  try {
    const count = await importRagEvalCasesApi({ ...importForm })
    ElMessage.success(`已导入 ${count} 条评测用例`)
    importVisible.value = false
    importForm.content = ''
    await loadEvalPanel()
  } finally {
    importSubmitting.value = false
  }
}

function formatMetric(value: number | null | undefined, percent = true) {
  if (value == null) return '-'
  return percent ? `${(Number(value) * 100).toFixed(1)}%` : Number(value).toFixed(4)
}

async function loadDocuments() {
  loading.value = true
  try {
    const result = await listKnowledgeDocumentsApi(query)
    documents.value = result.items
    query.total = result.total
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { title: '', categoryId: null, content: '', enabled: 1 })
  drawerVisible.value = true
}

function openEdit(row: KnowledgeDocument) {
  editingId.value = row.id
  Object.assign(form, {
    title: row.title,
    categoryId: row.categoryId,
    content: row.content,
    enabled: row.enabled,
  })
  drawerVisible.value = true
}

async function handleSubmit() {
  await formRef.value?.validate()
  submitting.value = true
  try {
    if (editingId.value) await updateKnowledgeDocumentApi(editingId.value, form)
    else await createKnowledgeDocumentApi(form)
    ElMessage.success('保存成功')
    drawerVisible.value = false
    await loadDocuments()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: KnowledgeDocument) {
  await ElMessageBox.confirm(`确认删除“${row.title}”？`, '删除确认', { type: 'warning' })
  await deleteKnowledgeDocumentApi(row.id)
  ElMessage.success('删除成功')
  await loadDocuments()
}

async function handleRebuild(row: KnowledgeDocument) {
  const count = await rebuildKnowledgeDocumentApi(row.id)
  ElMessage.success(`已重建 ${count} 个知识片段`)
  await loadDocuments()
}
</script>

<template>
  <section class="panel">
    <div class="section-heading">
      <div>
        <p class="panel__eyebrow">AI 运行监控</p>
        <h2>RAG 效果评测与 token 监控</h2>
      </div>
      <el-button @click="importVisible = true">导入评测集</el-button>
    </div>

    <div class="dispatch-candidates detail-block">
      <el-descriptions v-if="tokenUsage" border :column="4">
        <el-descriptions-item label="当日已用 token">{{ tokenUsage.usedTokens }}</el-descriptions-item>
        <el-descriptions-item label="每日预算">{{ tokenUsage.dailyBudget }}</el-descriptions-item>
        <el-descriptions-item label="使用率">{{ tokenRatioText }}</el-descriptions-item>
        <el-descriptions-item label="降级状态">
          <el-tag :type="tokenLevelTagType" size="small">{{ tokenUsage.message }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="当日调用次数">{{ tokenUsage.todayCallCount }}</el-descriptions-item>
        <el-descriptions-item label="其中降级次数">{{ tokenUsage.degradedCallCount }}</el-descriptions-item>
        <el-descriptions-item label="监控开关">{{ tokenUsage.monitorEnabled ? '开启' : '关闭' }}</el-descriptions-item>
        <el-descriptions-item label="剩余额度">{{ tokenUsage.remainingTokens }}</el-descriptions-item>
      </el-descriptions>
      <el-alert v-else type="info" :closable="false" title="暂未获取到 token 监控数据" show-icon />

      <div class="section-actions">
        <el-select v-model="evalForm.datasetName" placeholder="选择评测集" style="width: 220px">
          <el-option
            v-for="dataset in evalDatasets"
            :key="dataset.datasetName"
            :label="`${dataset.datasetName}（${dataset.caseCount} 条）`"
            :value="dataset.datasetName"
          />
        </el-select>
        <span class="muted-text">TopK</span>
        <el-input-number v-model="evalForm.topK" :min="1" :max="20" size="small" />
        <el-button type="primary" :loading="evalRunning" @click="handleRunEval">运行评测</el-button>
        <el-button :loading="evalRunning" @click="loadEvalPanel">刷新记录</el-button>
      </div>

      <el-descriptions v-if="evalMetrics" border :column="4">
        <el-descriptions-item label="用例数">{{ evalMetrics.caseCount }}</el-descriptions-item>
        <el-descriptions-item label="命中率 HitRate">{{ formatMetric(evalMetrics.hitRate) }}</el-descriptions-item>
        <el-descriptions-item label="Recall@k">{{ formatMetric(evalMetrics.recallAtK) }}</el-descriptions-item>
        <el-descriptions-item label="Precision@k">{{ formatMetric(evalMetrics.precisionAtK) }}</el-descriptions-item>
        <el-descriptions-item label="MRR">{{ formatMetric(evalMetrics.mrr, false) }}</el-descriptions-item>
        <el-descriptions-item label="nDCG@k">{{ formatMetric(evalMetrics.ndcgAtK, false) }}</el-descriptions-item>
        <el-descriptions-item label="拒答准确率">{{ formatMetric(evalMetrics.refusalAccuracy) }}</el-descriptions-item>
        <el-descriptions-item label="平均耗时">{{ evalMetrics.avgLatencyMs }} ms</el-descriptions-item>
      </el-descriptions>

      <el-table v-if="evalRuns.length > 0" :data="evalRuns" size="small" border>
        <el-table-column prop="datasetName" label="评测集" min-width="150" />
        <el-table-column prop="topK" label="TopK" width="70" />
        <el-table-column label="HitRate" width="100">
          <template #default="{ row }">{{ formatMetric(row.metrics.hitRate) }}</template>
        </el-table-column>
        <el-table-column label="Recall@k" width="100">
          <template #default="{ row }">{{ formatMetric(row.metrics.recallAtK) }}</template>
        </el-table-column>
        <el-table-column label="MRR" width="90">
          <template #default="{ row }">{{ formatMetric(row.metrics.mrr, false) }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column prop="createdAt" label="时间" min-width="160" />
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleOpenRun(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-table v-if="evalDetail && evalDetail.results.length > 0" :data="evalDetail.results" size="small" border>
        <el-table-column label="命中" width="70">
          <template #default="{ row }">
            <el-tag :type="row.hit ? 'success' : 'danger'" size="small">{{ row.hit ? '命中' : '未命中' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="question" label="评测问题" min-width="240" />
        <el-table-column prop="firstRelevantRank" label="首命中名次" width="100" />
        <el-table-column label="Recall" width="90">
          <template #default="{ row }">{{ formatMetric(row.recall) }}</template>
        </el-table-column>
        <el-table-column label="nDCG" width="90">
          <template #default="{ row }">{{ formatMetric(row.ndcg, false) }}</template>
        </el-table-column>
        <el-table-column prop="latencyMs" label="耗时(ms)" width="90" />
        <el-table-column label="召回片段" min-width="220">
          <template #default="{ row }">
            <span v-if="row.retrievedChunks.length === 0" class="muted-text">无召回</span>
            <el-tag
              v-for="chunk in row.retrievedChunks"
              :key="`${row.caseId}-${chunk.chunkId}`"
              class="tag-gap"
              size="small"
            >
              #{{ chunk.chunkId }} {{ chunk.source }} {{ Number(chunk.score).toFixed(3) }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-drawer v-model="importVisible" title="导入评测集（JSONL）" size="560px">
      <el-form label-position="top">
        <el-form-item label="评测集名称">
          <el-input v-model="importForm.datasetName" />
        </el-form-item>
        <el-form-item label="来源标注">
          <el-input v-model="importForm.source" placeholder="如 RGB / CRUD-RAG / beir-sample" />
        </el-form-item>
        <el-form-item label="JSONL 内容（每行一条，# 开头为注释）">
          <el-input
            v-model="importForm.content"
            type="textarea"
            :rows="10"
            placeholder='{"question": "空调不制冷怎么排查？", "expected_keywords": ["空调不制冷"]}'
          />
        </el-form-item>
        <div class="form-actions">
          <el-button type="primary" :loading="importSubmitting" @click="handleImportCases">导入</el-button>
          <el-button @click="importVisible = false">取消</el-button>
        </div>
      </el-form>
    </el-drawer>

    <el-drawer v-model="corpusVisible" title="导入语料" size="720px">
      <el-form ref="corpusFormRef" label-position="top" :model="corpusForm" :rules="corpusRules">
        <el-form-item label="导入模式">
          <el-radio-group v-model="corpusMode">
            <el-radio-button label="single">整篇导入</el-radio-button>
            <el-radio-button label="batch">JSONL 批量导入</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="标题" prop="title">
          <el-input
            v-model="corpusForm.title"
            maxlength="160"
            placeholder="如 GB 55022-2021 宿舍建筑设计规范"
          />
        </el-form-item>
        <el-form-item label="原文 / JSONL 内容" prop="content">
          <el-input
            v-model="corpusForm.content"
            type="textarea"
            :rows="14"
            :placeholder='
              corpusMode === "batch"
                ? "每行一条章节记录（# 开头为注释行）：{\"title\":\"...\",\"standardNo\":\"...\",\"sectionTitle\":\"3.4 设施设备检查\",\"sectionLevel\":2,\"content\":\"...\"}"
                : "可直接粘贴规范/手册全文，或粘贴转换脚本输出的 JSONL"
            '
          />
        </el-form-item>
        <el-form-item label="来源">
          <el-input v-model="corpusForm.source" placeholder="如 GB 55022-2021 / 后勤管理处维保手册" />
        </el-form-item>
        <el-form-item label="标准号">
          <el-input v-model="corpusForm.standardNo" placeholder="如 GB 55022-2021" />
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="corpusForm.docVersion" placeholder="如 2021" />
        </el-form-item>
        <el-form-item label="生效日期">
          <el-date-picker
            v-model="corpusForm.effectiveDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择生效日期"
          />
        </el-form-item>
        <el-form-item label="文档类型">
          <el-select v-model="corpusForm.docType" clearable placeholder="未指定则由后端推断">
            <el-option
              v-for="option in corpusDocTypeOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="关联分类">
          <el-select v-model="corpusForm.categoryId" clearable filterable placeholder="通用知识">
            <el-option
              v-for="category in categories"
              :key="category.id"
              :label="category.name"
              :value="category.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="切片大小 chunkSize">
          <el-input-number v-model="corpusForm.chunkSize" :min="100" :max="4000" :step="100" />
        </el-form-item>
        <el-form-item label="切片重叠 chunkOverlap">
          <el-input-number v-model="corpusForm.chunkOverlap" :min="0" :max="1000" :step="20" />
        </el-form-item>
        <el-form-item label="导入批次">
          <el-input v-model="corpusForm.importBatch" placeholder="留空则后端自动生成批次号" />
        </el-form-item>
        <div class="section-actions">
          <el-button :loading="corpusPreviewing" @click="handleCorpusPreview">预览切分</el-button>
          <el-button type="primary" :loading="corpusImporting" @click="handleCorpusImport">确认导入</el-button>
          <el-button @click="corpusVisible = false">取消</el-button>
        </div>
      </el-form>
    </el-drawer>

    <div class="section-heading">
      <div>
        <p class="panel__eyebrow">RAG 检索链路</p>
        <h2>向量检索状态</h2>
      </div>
      <el-button :loading="vectorLoading" @click="loadVectorStatus">刷新状态</el-button>
    </div>

    <div class="dispatch-candidates detail-block" v-loading="vectorLoading">
      <el-descriptions v-if="vectorStatus" border :column="4">
        <el-descriptions-item label="Embedding 提供方">
          {{ vectorStatus.embeddingProvider || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="Embedding 模型">
          {{ vectorStatus.embeddingModel || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="向量维度">{{ vectorStatus.dimension }}</el-descriptions-item>
        <el-descriptions-item label="Milvus 向量库">
          <el-tag :type="vectorStatus.milvusEnabled ? 'success' : 'info'" size="small">
            {{ vectorStatus.milvusEnabled ? '开启' : '关闭' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="集合名称">
          {{ vectorStatus.collectionName || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="知识文档数">{{ vectorStatus.documentCount }}</el-descriptions-item>
        <el-descriptions-item label="知识片段数">{{ vectorStatus.chunkCount }}</el-descriptions-item>
        <el-descriptions-item label="已同步片段">{{ vectorStatus.syncedChunkCount }}</el-descriptions-item>
        <el-descriptions-item label="待同步片段">{{ vectorStatus.pendingChunkCount }}</el-descriptions-item>
        <el-descriptions-item label="同步失败片段">{{ vectorStatus.failedChunkCount }}</el-descriptions-item>
        <el-descriptions-item label="混合检索">
          <el-tag :type="vectorStatus.hybridEnabled ? 'success' : 'info'" size="small">
            {{ vectorStatus.hybridEnabled ? '开启' : '关闭' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="RRF K 值">{{ vectorStatus.rrfK }}</el-descriptions-item>
        <el-descriptions-item label="重排">
          <el-tag :type="vectorStatus.rerankEnabled ? 'success' : 'info'" size="small">
            {{ vectorStatus.rerankEnabled ? '开启' : '关闭' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="重排模型">{{ vectorStatus.rerankModel || '-' }}</el-descriptions-item>
        <el-descriptions-item label="重排融合权重 α">{{ vectorStatus.rerankBlendWeight }}</el-descriptions-item>
        <el-descriptions-item label="重排窗口">{{ vectorStatus.rerankWindow }}</el-descriptions-item>
        <el-descriptions-item label="向量最低相似度">{{ vectorStatus.minVectorScore }}</el-descriptions-item>
        <el-descriptions-item label="关键词最低命中数">{{ vectorStatus.minKeywordHits }}</el-descriptions-item>
      </el-descriptions>
      <el-alert
        v-if="vectorStatus"
        :type="vectorStatus.failedChunkCount > 0 ? 'warning' : 'info'"
        :closable="false"
        :title="vectorStatus.message"
        show-icon
      />
      <el-alert
        v-else-if="!vectorLoading"
        type="info"
        :closable="false"
        title="暂未获取到向量检索状态"
        show-icon
      />
    </div>

    <div class="section-heading">
      <div>
        <p class="panel__eyebrow">语料建设</p>
        <h2>语料导入</h2>
      </div>
      <el-button type="primary" @click="openCorpusImport">导入语料</el-button>
    </div>

    <div class="dispatch-candidates detail-block">
      <el-alert
        type="info"
        :closable="false"
        title="点击「导入语料」打开抽屉：可粘贴规范/手册全文由后端清洗并切分章节，或粘贴 convert_corpus.py 输出的 JSONL 按章节批量导入。"
        show-icon
      />

      <el-descriptions v-if="corpusPreviewResult" border :column="4">
        <el-descriptions-item label="章节数">{{ corpusPreviewResult.sectionCount }}</el-descriptions-item>
        <el-descriptions-item label="切片数">{{ corpusPreviewResult.chunkCount }}</el-descriptions-item>
        <el-descriptions-item label="总字数">{{ corpusPreviewResult.totalChars }}</el-descriptions-item>
        <el-descriptions-item label="平均切片字数">
          {{ corpusPreviewResult.avgChunkChars }}
        </el-descriptions-item>
      </el-descriptions>

      <el-table
        v-if="corpusPreviewResult && corpusPreviewResult.sections.length > 0"
        :data="corpusPreviewResult.sections"
        size="small"
        border
      >
        <el-table-column prop="sectionTitle" label="章节标题" min-width="200" />
        <el-table-column prop="charCount" label="字数" width="90" />
        <el-table-column prop="sample" label="内容预览" min-width="260" show-overflow-tooltip />
      </el-table>

      <el-descriptions v-if="corpusBatchPreview && corpusBatchMeta" border :column="3">
        <el-descriptions-item label="待生成文档数">{{ corpusBatchMeta.documentCount }}</el-descriptions-item>
        <el-descriptions-item label="章节数">{{ corpusBatchMeta.chunkCount }}</el-descriptions-item>
        <el-descriptions-item label="总字数">{{ corpusBatchMeta.totalChars }}</el-descriptions-item>
      </el-descriptions>

      <template v-if="corpusImportResults.length > 0">
        <el-descriptions border :column="3">
          <el-descriptions-item label="生成文档数">{{ corpusImportResults.length }}</el-descriptions-item>
          <el-descriptions-item label="切片总数">
            {{ corpusImportResults.reduce((sum, item) => sum + item.chunkCount, 0) }}
          </el-descriptions-item>
          <el-descriptions-item label="导入批次">
            {{ corpusImportResults[0].importBatch }}
          </el-descriptions-item>
        </el-descriptions>
        <div
          v-for="result in corpusImportResults"
          :key="result.documentId"
          class="corpus-result"
        >
          <div class="corpus-result__head">
            {{ result.title }}
            <span class="muted-text">
              {{ result.standardNo || '未标注标准号' }} · {{ result.sectionCount }} 章节 /
              {{ result.chunkCount }} 切片
            </span>
          </div>
          <div>
            <el-tag
              v-for="section in result.sections"
              :key="`${result.documentId}-${section}`"
              class="tag-gap"
              size="small"
            >
              {{ section }}
            </el-tag>
          </div>
        </div>
      </template>
    </div>

    <div class="section-heading">
      <div>
        <p class="panel__eyebrow">知识沉淀</p>
        <h2>知识草稿审核</h2>
      </div>
      <div class="section-actions">
        <el-input
          v-model="generateTicketId"
          placeholder="来源工单 ID"
          style="width: 180px"
          clearable
        />
        <el-button type="primary" :loading="generating" @click="handleGenerateDraft">
          从工单生成草稿
        </el-button>
      </div>
    </div>

    <div class="dispatch-candidates detail-block">
      <div class="section-actions">
        <el-select v-model="draftStatus" style="width: 160px" @change="handleDraftFilter">
          <el-option
            v-for="option in draftStatusOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-button :loading="draftsLoading" @click="loadDrafts">刷新草稿</el-button>
        <span class="muted-text">共 {{ draftQuery.total }} 条草稿</span>
      </div>

      <el-table
        :data="drafts"
        v-loading="draftsLoading"
        size="small"
        border
        empty-text="暂无知识草稿"
      >
        <el-table-column prop="title" label="标题" min-width="220" />
        <el-table-column label="来源工单" width="120">
          <template #default="{ row }">
            <span v-if="row.sourceTicketId">#{{ row.sourceTicketId }}</span>
            <span v-else class="muted-text">手动生成</span>
          </template>
        </el-table-column>
        <el-table-column label="生成方式" width="120">
          <template #default="{ row }">
            <el-tag :type="draftSourceTagType(row.generateSource)" size="small">
              {{ draftSourceLabel(row.generateSource) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="draftStatusTagType(row.status)" size="small">
              {{ draftStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING_REVIEW'">
              <el-button link type="primary" @click="handleApproveDraft(row)">通过</el-button>
              <el-button link type="danger" @click="handleRejectDraft(row)">驳回</el-button>
            </template>
            <span v-else-if="row.status === 'APPROVED'" class="muted-text">
              已入库<template v-if="row.knowledgeDocumentId"> #{{ row.knowledgeDocumentId }}</template>
            </span>
            <span v-else class="muted-text">{{ row.reviewRemark || '已驳回' }}</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="table-pagination"
        layout="prev, pager, next, total"
        v-model:current-page="draftQuery.page"
        :page-size="draftQuery.size"
        :total="draftQuery.total"
        @current-change="loadDrafts"
      />
    </div>

    <div class="section-heading">
      <div>
        <p class="panel__eyebrow">RAG 知识库</p>
        <h2>维修知识管理</h2>
      </div>
      <el-button type="primary" @click="openCreate">新增知识</el-button>
    </div>

    <el-table :data="documents" v-loading="loading" empty-text="暂无知识文档">
      <el-table-column prop="title" label="标题" min-width="220" />
      <el-table-column label="分类" width="140">
        <template #default="{ row }">
          {{ categories.find((item) => item.id === row.categoryId)?.name || '通用' }}
        </template>
      </el-table-column>
      <el-table-column prop="chunkCount" label="片段数" width="100" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'info'">{{ row.enabled === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="success" @click="handleRebuild(row)">重建</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="table-pagination"
      layout="prev, pager, next, total"
      v-model:current-page="query.page"
      :page-size="query.size"
      :total="query.total"
      @current-change="loadDocuments"
    />

    <el-drawer v-model="drawerVisible" :title="editingId ? '编辑知识' : '新增知识'" size="520px">
      <el-form ref="formRef" label-position="top" :model="form" :rules="rules">
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" maxlength="160" />
        </el-form-item>
        <el-form-item label="关联分类">
          <el-select v-model="form.categoryId" clearable filterable placeholder="通用知识">
            <el-option
              v-for="category in categories"
              :key="category.id"
              :label="category.name"
              :value="category.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="内容" prop="content">
          <el-input v-model="form.content" type="textarea" :rows="12" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <div class="form-actions">
          <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
          <el-button @click="drawerVisible = false">取消</el-button>
        </div>
      </el-form>
    </el-drawer>
  </section>
</template>

<style scoped>
.corpus-result {
  display: grid;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.corpus-result__head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  font-weight: 600;
}
</style>
