<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Bell,
  CircleCheck,
  RefreshLeft,
  Tickets,
  Warning,
} from '@element-plus/icons-vue'

import { listPublishedNoticesApi } from '@/api/management'
import { getHotspotsApi, getMonthlyReportApi, getWorkerPerformanceApi } from '@/api/stats'
import { getAdminTodoOverviewApi } from '@/api/ticket'
import type { Notice } from '@/types/management'
import type { HotspotsResponse, MonthlyReport, WorkerPerformance } from '@/types/stats'
import type { AdminTodoOverview, TicketSummary } from '@/types/ticket'
import { formatDateTime, formatTicketStatus } from '@/utils/ticketDisplay'

const router = useRouter()
const notices = ref<Notice[]>([])
const todo = ref<AdminTodoOverview | null>(null)
const workerPerformances = ref<WorkerPerformance[]>([])
const workerLoading = ref(false)
const hotspots = ref<HotspotsResponse | null>(null)
const hotspotsLoading = ref(false)
const monthlyReport = ref<MonthlyReport | null>(null)
const reportLoading = ref(false)

function formatNumber(value: number | null | undefined, digits = 1) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue.toFixed(digits) : '-'
}

function formatPercent(value: number | null | undefined) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? `${(numberValue * 100).toFixed(1)}%` : '-'
}

async function loadWorkerPerformance() {
  workerLoading.value = true
  try {
    workerPerformances.value = (await getWorkerPerformanceApi(30)) || []
  } catch {
    workerPerformances.value = []
  } finally {
    workerLoading.value = false
  }
}

async function loadHotspots() {
  hotspotsLoading.value = true
  try {
    hotspots.value = await getHotspotsApi(30, 5)
  } catch {
    hotspots.value = null
  } finally {
    hotspotsLoading.value = false
  }
}

async function loadMonthlyReport() {
  reportLoading.value = true
  try {
    monthlyReport.value = await getMonthlyReportApi()
  } catch {
    monthlyReport.value = null
  } finally {
    reportLoading.value = false
  }
}

function toNumber(value: unknown) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : 0
}

function normalizeTodo(data: AdminTodoOverview): AdminTodoOverview {
  return {
    pendingReview: toNumber(data.pendingReview),
    returned: toNumber(data.returned),
    overdue: toNumber(data.overdue),
    urged: toNumber(data.urged),
    waitingConfirm: toNumber(data.waitingConfirm),
    latestTickets: (data.latestTickets ?? []) as TicketSummary[],
  }
}

const todoCards = computed(() => [
  {
    label: '待审核',
    value: toNumber(todo.value?.pendingReview),
    hint: '新提交工单',
    icon: Tickets,
    tone: 'primary',
    route: '/admin/tickets?status=PENDING_REVIEW',
  },
  {
    label: '退回待处理',
    value: toNumber(todo.value?.returned),
    hint: '需重新研判',
    icon: RefreshLeft,
    tone: 'warning',
    route: '/admin/tickets?status=RETURNED',
  },
  {
    label: 'SLA 超时',
    value: toNumber(todo.value?.overdue),
    hint: '处置时效风险',
    icon: Warning,
    tone: 'danger',
    route: '/admin/tickets?overdue=true',
  },
  {
    label: '已督办',
    value: toNumber(todo.value?.urged),
    hint: '领导关注事项',
    icon: Bell,
    tone: 'accent',
    route: '/admin/tickets?urged=true',
  },
])

const totalTodo = computed(() =>
  todoCards.value.reduce((total, item) => total + toNumber(item.value), 0),
)

const latestTickets = computed(() => todo.value?.latestTickets ?? [])

const reportMetrics = computed(() => {
  const report = monthlyReport.value
  if (!report) return []
  return [
    { label: '新增工单', value: String(report.createdCount) },
    { label: '完成工单', value: String(report.completedCount) },
    { label: '驳回工单', value: String(report.rejectedCount) },
    { label: '待评价工单', value: String(report.evaluatingScoreCount) },
    { label: '平均处理时长(分钟)', value: formatNumber(report.avgProcessMinutes) },
    { label: '平均满意度', value: formatNumber(report.avgScore, 2) },
    { label: '好评率', value: formatPercent(report.goodRate) },
    { label: '超时率', value: formatPercent(report.overdueRate) },
  ]
})

onMounted(async () => {
  const [noticeResult, todoResult] = await Promise.all([
    listPublishedNoticesApi(),
    getAdminTodoOverviewApi(),
  ])
  notices.value = noticeResult
  todo.value = normalizeTodo(todoResult)
  await Promise.all([loadWorkerPerformance(), loadHotspots(), loadMonthlyReport()])
})
</script>

<template>
  <section class="ops-dashboard">
    <article class="ops-hero">
      <div>
        <p class="panel__eyebrow">Campus Repair Command</p>
        <h2>管理驾驶舱</h2>
        <p>
          聚合审核、退回、超时与督办事项，帮助管理员快速判断今日工单压力和优先处理方向。
        </p>
      </div>
      <div class="ops-hero__metric">
        <span>当前待办</span>
        <strong>{{ totalTodo }}</strong>
        <small>项需要关注</small>
      </div>
    </article>

    <div class="ops-kpi-grid">
      <button
        v-for="item in todoCards"
        :key="item.label"
        type="button"
        class="ops-kpi"
        :class="`ops-kpi--${item.tone}`"
        @click="router.push(item.route)"
      >
        <span class="ops-kpi__icon">
          <el-icon><component :is="item.icon" /></el-icon>
        </span>
        <span class="ops-kpi__body">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <small>{{ item.hint }}</small>
        </span>
      </button>
    </div>

    <div class="ops-dashboard__content">
      <article class="ops-panel ops-panel--queue">
        <div class="ops-panel__header">
          <div>
            <p class="panel__eyebrow">Priority Queue</p>
            <h3>近期工单动态</h3>
          </div>
          <el-button type="primary" plain @click="router.push('/admin/tickets')">
            进入工单管理
          </el-button>
        </div>

        <el-empty v-if="latestTickets.length === 0" description="暂无待关注工单" />
        <ul v-else class="ops-ticket-list">
          <li v-for="ticket in latestTickets" :key="ticket.id">
            <div class="ops-ticket-list__main">
              <strong>{{ ticket.summary }}</strong>
              <p>
                {{ ticket.categoryName }} · {{ ticket.locationName }} ·
                {{ formatDateTime(ticket.createdAt) }}
              </p>
            </div>
            <div class="ops-ticket-list__meta">
              <span class="status-pill">{{ formatTicketStatus(ticket.status) }}</span>
              <small v-if="ticket.slaOverdue">SLA 超时</small>
            </div>
          </li>
        </ul>
      </article>

      <aside class="ops-panel ops-panel--notice">
        <div class="ops-panel__header">
          <div>
            <p class="panel__eyebrow">Notice</p>
            <h3>系统公告</h3>
          </div>
          <el-icon><CircleCheck /></el-icon>
        </div>
        <el-empty v-if="notices.length === 0" description="暂无公告" />
        <ul v-else class="ops-notice-list">
          <li v-for="notice in notices" :key="notice.id">
            <span class="ops-notice-list__dot"></span>
            <div>
              <strong>{{ notice.title }}</strong>
              <p>{{ notice.content }}</p>
            </div>
          </li>
        </ul>
      </aside>
    </div>

    <article class="ops-panel">
      <div class="ops-panel__header">
        <div>
          <p class="panel__eyebrow">Worker Performance</p>
          <h3>维修员绩效与满意度</h3>
        </div>
        <el-button :loading="workerLoading" @click="loadWorkerPerformance">刷新</el-button>
      </div>
      <el-table
        :data="workerPerformances"
        v-loading="workerLoading"
        size="small"
        border
        empty-text="暂无维修员绩效数据"
      >
        <el-table-column prop="workerName" label="维修员" min-width="120" />
        <el-table-column label="部门" width="140">
          <template #default="{ row }">{{ row.departmentName || '未分配' }}</template>
        </el-table-column>
        <el-table-column prop="completedCount" label="完成量" width="90" />
        <el-table-column label="平均处理时长(分钟)" width="160">
          <template #default="{ row }">{{ formatNumber(row.avgProcessMinutes) }}</template>
        </el-table-column>
        <el-table-column label="平均满意度" width="110">
          <template #default="{ row }">{{ formatNumber(row.avgScore, 2) }}</template>
        </el-table-column>
        <el-table-column label="好评率" width="100">
          <template #default="{ row }">{{ formatPercent(row.goodRate) }}</template>
        </el-table-column>
        <el-table-column prop="returnCount" label="退回次数" width="100" />
        <el-table-column prop="activeCount" label="活跃工单" width="100" />
        <el-table-column prop="overdueCount" label="超时工单" width="100" />
      </el-table>
    </article>

    <article class="ops-panel">
      <div class="ops-panel__header">
        <div>
          <p class="panel__eyebrow">Hotspots · 近 30 天</p>
          <h3>高发故障与高发地点</h3>
        </div>
        <span class="muted-text">总工单数：{{ hotspots?.totalTickets ?? 0 }}</span>
      </div>
      <div class="hotspot-grid">
        <div class="detail-block">
          <h4>高发故障分类</h4>
          <el-table :data="hotspots?.categories || []" size="small" border empty-text="暂无分类数据">
            <el-table-column prop="label" label="分类" min-width="160" />
            <el-table-column prop="value" label="工单数" width="100" />
          </el-table>
        </div>
        <div class="detail-block">
          <h4>高发地点</h4>
          <el-table :data="hotspots?.locations || []" size="small" border empty-text="暂无地点数据">
            <el-table-column prop="label" label="地点" min-width="160" />
            <el-table-column prop="value" label="工单数" width="100" />
          </el-table>
        </div>
      </div>
    </article>

    <article class="ops-panel">
      <div class="ops-panel__header">
        <div>
          <p class="panel__eyebrow">Monthly Report</p>
          <h3>
            月度维修报告
            <el-tag v-if="monthlyReport" class="tag-gap" type="info" size="small">
              {{ monthlyReport.month }}
            </el-tag>
            <el-tag v-if="monthlyReport?.aiDegraded" class="tag-gap" type="warning" size="small">
              规则模板兜底
            </el-tag>
          </h3>
        </div>
        <el-button :loading="reportLoading" @click="loadMonthlyReport">刷新</el-button>
      </div>

      <div v-loading="reportLoading">
        <el-empty v-if="!monthlyReport" description="暂无月度报告数据" />
        <template v-else>
          <div class="report-metric-grid">
            <div v-for="item in reportMetrics" :key="item.label" class="report-metric">
              <span>{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
            </div>
          </div>

          <el-alert
            class="detail-block"
            :type="monthlyReport.aiDegraded ? 'warning' : 'success'"
            :closable="false"
            title="AI 智能总结"
            :description="monthlyReport.aiSummary || '暂无 AI 总结'"
            show-icon
          />

          <div class="hotspot-grid">
            <div class="detail-block">
              <h4>高发故障分类 Top</h4>
              <ul v-if="monthlyReport.topCategories?.length" class="report-list">
                <li v-for="item in monthlyReport.topCategories" :key="item.label">
                  <span>{{ item.label }}</span>
                  <strong>{{ item.value }}</strong>
                </li>
              </ul>
              <p v-else class="muted-text">暂无数据</p>
            </div>
            <div class="detail-block">
              <h4>高发地点 Top</h4>
              <ul v-if="monthlyReport.topLocations?.length" class="report-list">
                <li v-for="item in monthlyReport.topLocations" :key="item.label">
                  <span>{{ item.label }}</span>
                  <strong>{{ item.value }}</strong>
                </li>
              </ul>
              <p v-else class="muted-text">暂无数据</p>
            </div>
          </div>

          <div class="detail-block">
            <h4>维修员绩效 Top</h4>
            <ul v-if="monthlyReport.topWorkers?.length" class="report-list">
              <li v-for="worker in monthlyReport.topWorkers" :key="worker.workerId">
                <span>
                  {{ worker.workerName }}
                  <span class="muted-text">{{ worker.departmentName || '未分配' }}</span>
                </span>
                <strong>
                  完成 {{ worker.completedCount }} · 好评率 {{ formatPercent(worker.goodRate) }}
                </strong>
              </li>
            </ul>
            <p v-else class="muted-text">暂无数据</p>
          </div>
        </template>
      </div>
    </article>
  </section>
</template>

<style scoped>
.hotspot-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
}

.hotspot-grid h4 {
  margin: 0 0 10px;
  font-size: 15px;
}

.report-metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}

.report-metric {
  padding: 14px 16px;
  border: 1px solid #e7eef3;
  border-radius: 8px;
  background: #fbfcfd;
}

.report-metric span {
  display: block;
  color: var(--color-muted);
  font-size: 13px;
}

.report-metric strong {
  display: block;
  margin-top: 8px;
  font-size: 24px;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.report-list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.report-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 14px;
  border: 1px solid #e7eef3;
  border-radius: 8px;
  background: #fbfcfd;
}

.report-list strong {
  color: var(--color-muted);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

@media (max-width: 1100px) {
  .hotspot-grid,
  .report-metric-grid {
    grid-template-columns: 1fr;
  }
}
</style>
