<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'

import {
  analyzeTicketApi,
  getDispatchSuggestionApi,
  getLatestTicketAnalysisApi,
} from '@/api/ai'
import { listCategoriesApi } from '@/api/base'
import {
  assignTicketApi,
  getAdminTicketApi,
  getAdminTodoOverviewApi,
  listAdminTicketsApi,
  listWorkerOptionsApi,
  rejectTicketApi,
  urgeTicketApi,
} from '@/api/ticket'
import type { DispatchSuggestion, TicketAiAnalysis } from '@/types/ai'
import type { OptionItem } from '@/types/base'
import type {
  AdminTodoOverview,
  PageResult,
  TicketAssignRequest,
  TicketDetail,
  TicketStatus,
  TicketSummary,
  WorkerOption,
} from '@/types/ticket'
import {
  formatAiStatus,
  formatDateTime,
  formatFlowAction,
  formatRiskLevel,
  formatTicketPriority,
  formatTicketStatus,
  parseImageUrls,
  priorityOptions,
  ticketStatusOptions as statusOptions,
} from '@/utils/ticketDisplay'

const route = useRoute()
const loading = ref(false)
const detailLoading = ref(false)
const drawerVisible = ref(false)
const submitting = ref(false)
const analyzing = ref(false)
const detail = ref<TicketDetail | null>(null)
const analysis = ref<TicketAiAnalysis | null>(null)
const dispatchSuggestion = ref<DispatchSuggestion | null>(null)
const dispatchLoading = ref(false)
const categories = ref<OptionItem[]>([])
const workers = ref<WorkerOption[]>([])
const todo = ref<AdminTodoOverview | null>(null)

const reportImageUrls = computed(() => parseImageUrls(detail.value?.reportImageUrls))
const resultImageUrls = computed(() => parseImageUrls(detail.value?.resultImageUrls))
const canReviewOrReassign = computed(() =>
  ['PENDING_REVIEW', 'RETURNED'].includes(detail.value?.status || ''),
)
const canUrge = computed(() =>
  ['ASSIGNED', 'PROCESSING', 'WAITING_CONFIRM'].includes(detail.value?.status || ''),
)
const assignButtonText = computed(() =>
  detail.value?.status === 'RETURNED' ? '重新派单' : '审核通过并派单',
)
const dispatchCandidates = computed(() =>
  dispatchSuggestion.value?.candidates?.length
    ? dispatchSuggestion.value.candidates
    : analysis.value?.dispatchCandidates || [],
)
const dispatchSourceLabel = computed(() => {
  switch (dispatchSuggestion.value?.recommendationSource) {
    case 'AI':
      return 'AI 智能派单建议'
    case 'AI_FALLBACK':
      return 'AI 降级 · 规则兜底建议'
    default:
      return '规则评分建议'
  }
})
const dispatchSourceTagType = computed(() => {
  switch (dispatchSuggestion.value?.recommendationSource) {
    case 'AI':
      return 'success'
    case 'AI_FALLBACK':
      return 'warning'
    default:
      return 'info'
  }
})
const dispatchAlertTitle = computed(() => {
  const name = dispatchSuggestion.value?.recommendedWorkerName
  if (!name) return '暂无可推荐维修员'
  const confidence = dispatchSuggestion.value?.recommendedConfidence
  return confidence == null
    ? `推荐维修员：${name}`
    : `推荐维修员：${name}（置信度 ${confidence}）`
})

const pageData = ref<PageResult<TicketSummary>>({
  items: [],
  page: 1,
  size: 10,
  total: 0,
})

const query = reactive<{
  page: number
  size: number
  status: TicketStatus | ''
  overdue?: boolean
  urged?: boolean
}>({
  page: 1,
  size: 10,
  status: route.query.overdue === 'true' || route.query.urged === 'true'
    ? ''
    : (route.query.status as TicketStatus | undefined) || 'PENDING_REVIEW',
  overdue: route.query.overdue === 'true',
  urged: route.query.urged === 'true',
})

const assignForm = reactive<TicketAssignRequest>({
  categoryId: null,
  priority: 'LOW',
  summary: '',
  workerId: null,
  remark: '',
})

onMounted(async () => {
  const [categoryResult, workerResult] = await Promise.all([
    listCategoriesApi(),
    listWorkerOptionsApi(),
  ])
  categories.value = categoryResult
  workers.value = workerResult
  await loadTickets()
})

async function loadTickets() {
  loading.value = true
  try {
    const [pageResult, todoResult] = await Promise.all([
      listAdminTicketsApi(query),
      getAdminTodoOverviewApi(),
    ])
    pageData.value = pageResult
    todo.value = todoResult
  } finally {
    loading.value = false
  }
}

function handleFilterChange() {
  query.page = 1
  query.overdue = false
  query.urged = false
  loadTickets()
}

function applyStatusFilter(status: TicketStatus | '') {
  query.status = status
  query.overdue = false
  query.urged = false
  query.page = 1
  loadTickets()
}

function applyQualityFilter(type: 'overdue' | 'urged') {
  query.status = ''
  query.overdue = type === 'overdue'
  query.urged = type === 'urged'
  query.page = 1
  loadTickets()
}

function applyAnalysisSuggestion(source: TicketAiAnalysis | null) {
  if (!source) return
  if (source.suggestedCategoryId) {
    assignForm.categoryId = source.suggestedCategoryId
  }
  if (source.suggestedPriority) {
    assignForm.priority = source.suggestedPriority
  }
  if (source.faultSummary) {
    assignForm.summary = source.faultSummary
  }
  if (source.suggestedWorkerId && workers.value.some((worker) => worker.id === source.suggestedWorkerId)) {
    assignForm.workerId = source.suggestedWorkerId
  }
  if (source.dispatchRemark) {
    assignForm.remark = source.dispatchRemark
  }
}

function workerSelectLabel(worker: WorkerOption) {
  const skills = worker.skillTags?.length ? worker.skillTags.join('、') : '未配技能'
  return `${worker.realName}（${worker.departmentName}，${worker.activeOrderCount}/${worker.maxActiveOrders}，${skills}）`
}

function candidateScoreText(value: number | null | undefined) {
  return value == null ? '-' : Number(value).toFixed(2)
}

async function loadDispatchSuggestion(ticketId: string) {
  dispatchLoading.value = true
  try {
    dispatchSuggestion.value = await getDispatchSuggestionApi(ticketId)
  } catch {
    dispatchSuggestion.value = null
  } finally {
    dispatchLoading.value = false
  }
}

function applyRecommendedWorker(workerId: string | null) {
  if (!workerId) return
  if (!workers.value.some((worker) => worker.id === workerId)) {
    ElMessage.warning('推荐维修员不在可派单列表中，请手动选择')
    return
  }
  assignForm.workerId = workerId
  ElMessage.success(`已填入推荐维修员：${dispatchSuggestion.value?.recommendedWorkerName || workerId}`)
}

async function openDetail(ticketId: string) {
  drawerVisible.value = true
  detailLoading.value = true
  try {
    analysis.value = null
    dispatchSuggestion.value = null
    detail.value = await getAdminTicketApi(ticketId)
    assignForm.categoryId = detail.value.categoryId
    assignForm.priority = detail.value.priority
    assignForm.summary = detail.value.summary
    assignForm.workerId = detail.value.assignedWorkerId
    assignForm.remark = ''

    if (canReviewOrReassign.value) {
      await loadDispatchSuggestion(ticketId)
      try {
        analysis.value = await getLatestTicketAnalysisApi(ticketId)
        applyAnalysisSuggestion(analysis.value)
      } catch {
        analysis.value = null
      }
    }
  } finally {
    detailLoading.value = false
  }
}

async function handleAnalyze() {
  if (!detail.value) return
  analyzing.value = true
  try {
    analysis.value = await analyzeTicketApi(detail.value.id)
    applyAnalysisSuggestion(analysis.value)
    await loadDispatchSuggestion(detail.value.id)
    ElMessage.success('AI 预分析完成')
  } finally {
    analyzing.value = false
  }
}

async function handleAssign() {
  if (!detail.value) return
  submitting.value = true
  try {
    const wasReturned = detail.value.status === 'RETURNED'
    detail.value = await assignTicketApi(detail.value.id, assignForm)
    ElMessage.success(wasReturned ? '已重新派单' : '已审核通过并派单')
    await loadTickets()
  } finally {
    submitting.value = false
  }
}

async function handleReject() {
  if (!detail.value) return
  const { value } = await ElMessageBox.prompt('请输入驳回原因', '驳回工单', {
    confirmButtonText: '确认驳回',
    cancelButtonText: '取消',
    inputType: 'textarea',
    inputValidator: (input) => !!input?.trim() || '驳回原因不能为空',
  })
  submitting.value = true
  try {
    detail.value = await rejectTicketApi(detail.value.id, { reason: value })
    ElMessage.success('工单已驳回')
    await loadTickets()
  } finally {
    submitting.value = false
  }
}

async function handleUrge() {
  if (!detail.value) return
  const { value } = await ElMessageBox.prompt('请输入督办说明', '工单督办', {
    confirmButtonText: '确认督办',
    cancelButtonText: '取消',
    inputType: 'textarea',
  })
  submitting.value = true
  try {
    detail.value = await urgeTicketApi(detail.value.id, { remark: value || '' })
    ElMessage.success('已记录督办')
    await loadTickets()
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="panel">
    <div class="section-heading">
      <div>
        <p class="panel__eyebrow">后台管理</p>
        <h2>工单管理</h2>
      </div>
      <el-select v-model="query.status" class="status-filter" @change="handleFilterChange">
        <el-option
          v-for="option in statusOptions"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
    </div>

    <div class="todo-grid compact">
      <button type="button" @click="applyStatusFilter('PENDING_REVIEW')">
        <span>待审核</span>
        <strong>{{ todo?.pendingReview ?? 0 }}</strong>
      </button>
      <button type="button" @click="applyStatusFilter('RETURNED')">
        <span>退回待处理</span>
        <strong>{{ todo?.returned ?? 0 }}</strong>
      </button>
      <button type="button" @click="applyQualityFilter('overdue')">
        <span>SLA 超时</span>
        <strong>{{ todo?.overdue ?? 0 }}</strong>
      </button>
      <button type="button" @click="applyQualityFilter('urged')">
        <span>已督办</span>
        <strong>{{ todo?.urged ?? 0 }}</strong>
      </button>
    </div>

    <el-table :data="pageData.items" v-loading="loading" empty-text="暂无工单">
      <el-table-column prop="summary" label="摘要" min-width="220" />
      <el-table-column prop="studentName" label="学生" width="100" />
      <el-table-column prop="locationName" label="地点" width="150" />
      <el-table-column prop="categoryName" label="分类" width="120" />
      <el-table-column label="提交时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="SLA" width="170">
        <template #default="{ row }">
          <el-tag :type="row.slaOverdue ? 'danger' : 'info'">
            {{ row.slaOverdue ? '已超时' : '截止' }} {{ formatDateTime(row.slaDeadlineAt) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="优先级" width="90">
        <template #default="{ row }">{{ formatTicketPriority(row.priority) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template #default="{ row }">
          <span class="status-pill">{{ formatTicketStatus(row.status) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row.id)">
            处理
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      class="table-pagination"
      layout="prev, pager, next, total"
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      :total="pageData.total"
      @current-change="loadTickets"
    />

    <el-drawer v-model="drawerVisible" title="工单审核派单" size="520px">
      <div v-if="detail" v-loading="detailLoading" class="drawer-stack">
        <el-descriptions border :column="1">
          <el-descriptions-item label="摘要">{{ detail.summary }}</el-descriptions-item>
          <el-descriptions-item label="故障描述">{{ detail.description }}</el-descriptions-item>
          <el-descriptions-item label="地点">{{ detail.locationName }}</el-descriptions-item>
          <el-descriptions-item label="联系电话">{{ detail.contactPhone }}</el-descriptions-item>
          <el-descriptions-item label="当前状态">{{ formatTicketStatus(detail.status) }}</el-descriptions-item>
          <el-descriptions-item label="优先级">{{ formatTicketPriority(detail.priority) }}</el-descriptions-item>
          <el-descriptions-item label="维修员">{{ detail.assignedWorkerName || '未派单' }}</el-descriptions-item>
          <el-descriptions-item label="派单时间">{{ formatDateTime(detail.assignedAt) }}</el-descriptions-item>
          <el-descriptions-item label="维修结果" v-if="detail.processResult">{{ detail.processResult }}</el-descriptions-item>
          <el-descriptions-item label="维修备注" v-if="detail.processRemark">{{ detail.processRemark }}</el-descriptions-item>
          <el-descriptions-item label="处理时间">{{ formatDateTime(detail.processedAt) }}</el-descriptions-item>
          <el-descriptions-item label="SLA 截止">{{ formatDateTime(detail.slaDeadlineAt) }}</el-descriptions-item>
          <el-descriptions-item label="退回原因" v-if="detail.returnReason">{{ detail.returnReason }}</el-descriptions-item>
          <el-descriptions-item label="督办记录" v-if="detail.urgedAt">
            {{ formatDateTime(detail.urgedAt) }} · {{ detail.urgeRemark || '已督办' }}
          </el-descriptions-item>
        </el-descriptions>

        <article class="image-viewer">
          <h3>报修图片</h3>
          <el-empty v-if="reportImageUrls.length === 0" description="暂无报修图片" />
          <div v-else class="ticket-image-grid">
            <el-image
              v-for="url in reportImageUrls"
              :key="url"
              class="ticket-image"
              :src="url"
              :preview-src-list="reportImageUrls"
              fit="cover"
            />
          </div>
        </article>

        <article v-if="resultImageUrls.length > 0" class="image-viewer">
          <h3>维修完成图片</h3>
          <div class="ticket-image-grid">
            <el-image
              v-for="url in resultImageUrls"
              :key="url"
              class="ticket-image"
              :src="url"
              :preview-src-list="resultImageUrls"
              fit="cover"
            />
          </div>
        </article>

        <el-form v-if="canReviewOrReassign" label-position="top">
          <div class="form-actions">
            <el-button type="success" :loading="analyzing" @click="handleAnalyze">
              AI 预分析
            </el-button>
          </div>
          <el-descriptions v-if="analysis" border :column="1" class="detail-block">
            <el-descriptions-item label="分析状态">{{ formatAiStatus(analysis.status) }}</el-descriptions-item>
            <el-descriptions-item label="建议摘要">{{ analysis.faultSummary }}</el-descriptions-item>
            <el-descriptions-item label="可能原因">{{ analysis.faultReason }}</el-descriptions-item>
            <el-descriptions-item label="建议方案">{{ analysis.solution }}</el-descriptions-item>
            <el-descriptions-item label="建议维修员">
              {{ workers.find((worker) => worker.id === analysis?.suggestedWorkerId)?.realName || '未建议' }}
            </el-descriptions-item>
            <el-descriptions-item label="建议派单备注">{{ analysis.dispatchRemark || '-' }}</el-descriptions-item>
            <el-descriptions-item label="风险等级">{{ formatRiskLevel(analysis.riskLevel) }}</el-descriptions-item>
            <el-descriptions-item label="置信度">{{ analysis.confidence }}</el-descriptions-item>
          </el-descriptions>
          <section v-if="canReviewOrReassign" class="dispatch-candidates detail-block">
            <div class="section-header">
              <h3>智能派单建议</h3>
              <div class="section-actions">
                <el-tag :type="dispatchSourceTagType" size="small">{{ dispatchSourceLabel }}</el-tag>
                <el-button size="small" :loading="dispatchLoading" @click="loadDispatchSuggestion(detail.id)">
                  刷新建议
                </el-button>
              </div>
            </div>
            <el-alert
              v-if="dispatchSuggestion?.recommendedReason"
              :title="dispatchAlertTitle"
              :description="dispatchSuggestion.recommendedReason"
              type="success"
              :closable="false"
              show-icon
            />
            <el-table v-if="dispatchCandidates.length > 0" :data="dispatchCandidates" size="small" border>
              <el-table-column label="推荐" width="72">
                <template #default="{ row }">
                  <el-tag v-if="row.aiRecommended" type="success" size="small">推荐</el-tag>
                  <span v-else class="muted-text">候选</span>
                </template>
              </el-table-column>
              <el-table-column prop="workerName" label="维修员" width="96" />
              <el-table-column prop="departmentName" label="部门" width="110" />
              <el-table-column label="技能" min-width="150">
                <template #default="{ row }">
                  <el-tag v-for="tag in row.skillTags" :key="tag" class="tag-gap" size="small">{{ tag }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="负载" width="78">
                <template #default="{ row }">{{ row.activeOrderCount }}/{{ row.maxActiveOrders }}</template>
              </el-table-column>
              <el-table-column label="总分" width="78">
                <template #default="{ row }">{{ candidateScoreText(row.totalScore) }}</template>
              </el-table-column>
              <el-table-column label="规则原因" min-width="220">
                <template #default="{ row }">{{ row.ruleReason }}</template>
              </el-table-column>
              <el-table-column label="操作" width="80" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" type="primary" link @click="applyRecommendedWorker(row.workerId)">
                    填入
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
            <p v-else class="muted-text">暂无参与智能派单的候选维修员，请先在基础数据中维护维修员画像与技能标签。</p>
          </section>
          <el-form-item label="确认分类" required>
            <el-select v-model="assignForm.categoryId" filterable>
              <el-option
                v-for="category in categories"
                :key="category.id"
                :label="category.name"
                :value="category.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="优先级" required>
            <el-segmented v-model="assignForm.priority" :options="priorityOptions" />
          </el-form-item>
          <el-form-item label="工单摘要" required>
            <el-input v-model="assignForm.summary" maxlength="200" />
          </el-form-item>
          <el-form-item label="维修员" required>
            <el-select v-model="assignForm.workerId" filterable placeholder="请选择维修员">
              <el-option
                v-for="worker in workers"
                :key="worker.id"
                :label="workerSelectLabel(worker)"
                :value="worker.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="派单备注">
            <el-input v-model="assignForm.remark" type="textarea" :rows="3" />
          </el-form-item>
          <div class="form-actions">
            <el-button type="primary" :loading="submitting" @click="handleAssign">
              {{ assignButtonText }}
            </el-button>
            <el-button :loading="submitting" @click="handleReject">
              驳回
            </el-button>
          </div>
        </el-form>

        <el-alert
          v-else
          title="该工单当前状态不可派单，仅可查看详情。"
          type="info"
          :closable="false"
          show-icon
        />

        <div v-if="canUrge" class="form-actions">
          <el-button type="warning" :loading="submitting" @click="handleUrge">
            督办工单
          </el-button>
        </div>

        <section>
          <h3>流转记录</h3>
          <el-timeline>
            <el-timeline-item
              v-for="flow in detail.flows"
              :key="flow.id"
              :timestamp="formatDateTime(flow.createdAt)"
            >
              {{ formatFlowAction(flow.action) }}：{{ formatTicketStatus(flow.fromStatus) }} -> {{ formatTicketStatus(flow.toStatus) }}
              <p v-if="flow.remark">{{ flow.remark }}</p>
            </el-timeline-item>
          </el-timeline>
        </section>

        <el-alert
          v-if="detail.evaluation"
          :title="`学生评价：${detail.evaluation.score} 分`"
          :description="detail.evaluation.content || '未填写文字评价'"
          type="success"
          show-icon
          :closable="false"
        />
      </div>
    </el-drawer>
  </section>
</template>
