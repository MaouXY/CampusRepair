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
import { getAdminTodoOverviewApi } from '@/api/ticket'
import type { Notice } from '@/types/management'
import type { AdminTodoOverview, TicketSummary } from '@/types/ticket'
import { formatDateTime, formatTicketStatus } from '@/utils/ticketDisplay'

const router = useRouter()
const notices = ref<Notice[]>([])
const todo = ref<AdminTodoOverview | null>(null)

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

onMounted(async () => {
  const [noticeResult, todoResult] = await Promise.all([
    listPublishedNoticesApi(),
    getAdminTodoOverviewApi(),
  ])
  notices.value = noticeResult
  todo.value = normalizeTodo(todoResult)
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
  </section>
</template>
