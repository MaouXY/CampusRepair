<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  CircleCheck,
  Stopwatch,
  Tickets,
  Warning,
} from '@element-plus/icons-vue'

import { getWorkerTodayOverviewApi } from '@/api/ticket'
import type { TicketSummary, WorkerTodayOverview } from '@/types/ticket'
import {
  formatDateTime,
  formatTicketPriority,
  formatTicketStatus,
} from '@/utils/ticketDisplay'

const router = useRouter()
const loading = ref(false)
const overview = ref<WorkerTodayOverview | null>(null)

function toNumber(value: unknown) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : 0
}

const tasks = computed(() => overview.value?.dueTodayTickets ?? [])

const totalActive = computed(
  () =>
    toNumber(overview.value?.assigned) +
    toNumber(overview.value?.processing) +
    toNumber(overview.value?.waitingConfirm),
)

const summaryCards = computed(() => [
  {
    label: '待接单',
    value: toNumber(overview.value?.assigned),
    hint: '等待确认接单',
    icon: Tickets,
    route: '/worker/tickets?status=ASSIGNED',
  },
  {
    label: '处理中',
    value: toNumber(overview.value?.processing),
    hint: '正在现场处置',
    icon: Stopwatch,
    route: '/worker/tickets?status=PROCESSING',
  },
  {
    label: '待确认',
    value: toNumber(overview.value?.waitingConfirm),
    hint: '等待学生确认',
    icon: CircleCheck,
    route: '/worker/tickets?status=WAITING_CONFIRM',
  },
  {
    label: 'SLA 超时',
    value: toNumber(overview.value?.overdue),
    hint: '需要优先处理',
    icon: Warning,
    route: '/worker/tickets?overdue=true',
    danger: true,
  },
])

onMounted(loadOverview)

async function loadOverview() {
  loading.value = true
  try {
    overview.value = await getWorkerTodayOverviewApi()
  } finally {
    loading.value = false
  }
}

function openTicket(ticket: TicketSummary) {
  router.push(`/worker/tickets/${ticket.id}`)
}

function openList(route = '/worker/tickets') {
  router.push(route)
}
</script>

<template>
  <section class="worker-dashboard" v-loading="loading">
    <article class="worker-overview">
      <div>
        <p class="panel__eyebrow">维修工作台</p>
        <h2>今日任务</h2>
        <p>
          按接单、处理中、待确认和 SLA 风险汇总今日任务，优先处理需要闭环的工单。
        </p>
      </div>
      <div class="worker-overview__metric">
        <span>当前任务</span>
        <strong>{{ totalActive }}</strong>
        <small>单待推进</small>
      </div>
    </article>

    <div class="worker-summary-grid">
      <button
        v-for="item in summaryCards"
        :key="item.label"
        type="button"
        class="worker-summary"
        :class="{ 'worker-summary--danger': item.danger }"
        @click="openList(item.route)"
      >
        <span class="worker-summary__icon">
          <el-icon><component :is="item.icon" /></el-icon>
        </span>
        <span class="worker-summary__content">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <small>{{ item.hint }}</small>
        </span>
      </button>
    </div>

    <article class="ops-panel worker-task-panel">
      <div class="ops-panel__header">
        <div>
          <p class="panel__eyebrow">按 SLA 截止时间排序</p>
          <h3>今日应处理</h3>
        </div>
        <el-button @click="openList()">全部工单</el-button>
      </div>

      <el-empty v-if="tasks.length === 0" description="暂无今日任务" />
      <ul v-else class="worker-task-list">
        <li v-for="ticket in tasks" :key="ticket.id">
          <div>
            <strong>{{ ticket.summary }}</strong>
            <p>
              {{ formatTicketStatus(ticket.status) }} ·
              {{ formatTicketPriority(ticket.priority) }} ·
              {{ ticket.locationName }}
            </p>
          </div>
          <div class="worker-task-list__meta">
            <span :class="{ 'is-danger': ticket.slaOverdue }">
              SLA {{ ticket.slaOverdue ? '已超时' : formatDateTime(ticket.slaDeadlineAt) }}
            </span>
            <el-button link type="primary" @click="openTicket(ticket)">处理</el-button>
          </div>
        </li>
      </ul>
    </article>
  </section>
</template>
