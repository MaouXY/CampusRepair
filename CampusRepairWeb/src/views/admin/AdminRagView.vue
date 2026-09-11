<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import type { OptionItem } from '@/types/base'
import type {
  AiTokenUsage,
  KnowledgeDocument,
  KnowledgeDocumentRequest,
  RagEvalDataset,
  RagEvalRun,
  RagEvalRunDetail,
} from '@/types/rag'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { listCategoriesApi } from '@/api/base'
import {
  createKnowledgeDocumentApi,
  deleteKnowledgeDocumentApi,
  getAiTokenUsageApi,
  getRagEvalRunApi,
  importRagEvalCasesApi,
  listKnowledgeDocumentsApi,
  listRagEvalDatasetsApi,
  listRagEvalRunsApi,
  rebuildKnowledgeDocumentApi,
  runRagEvalApi,
  updateKnowledgeDocumentApi,
} from '@/api/rag'

const loading = ref(false)
const drawerVisible = ref(false)
const submitting = ref(false)
const editingId = ref<number | null>(null)
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
const importForm = reactive({
  datasetName: 'imported-eval',
  source: 'open-dataset-sample',
  content: '',
})
const evalForm = reactive({
  datasetName: '',
  topK: 5,
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
  await Promise.all([loadDocuments(), loadTokenUsage(), loadEvalPanel()])
})

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
