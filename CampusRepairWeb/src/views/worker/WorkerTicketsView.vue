<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getWorkerTodayOverviewApi, listWorkerTicketsApi } from '@/api/ticket'
import type { PageResult, TicketStatus, TicketSummary, WorkerTodayOverview } from '@/types/ticket'
import {
  formatDateTime,
  formatTicketPriority,
  formatTicketStatus,
  workerTicketStatusOptions as statusOptions,
} from '@/utils/ticketDisplay'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const overview = ref<WorkerTodayOverview | null>(null)
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
}>({
  page: 1,
  size: 10,
  status: (route.query.status as TicketStatus | undefined) || '',
  overdue: route.query.overdue === 'true',
})

onMounted(loadTickets)

async function loadTickets() {
  loading.value = true
  try {
    const [pageResult, overviewResult] = await Promise.all([
      listWorkerTicketsApi(query),
      getWorkerTodayOverviewApi(),
    ])
    pageData.value = pageResult
    overview.value = overviewResult
  } finally {
    loading.value = false
  }
}

function handleFilterChange() {
  query.page = 1
  query.overdue = false
  loadTickets()
}

function applyStatusFilter(status: TicketStatus | '') {
  query.status = status
  query.overdue = false
  query.page = 1
  loadTickets()
}

function applyOverdueFilter() {
  query.status = ''
  query.overdue = true
  query.page = 1
  loadTickets()
}
</script>

<template>
  <section class="panel">
    <div class="section-heading">
      <div>
        <p class="panel__eyebrow">维修工作台</p>
        <h2>维修工单</h2>
      </div>
      <el-select v-model="query.status" class="status-filter" placeholder="全部状态" @change="handleFilterChange">
        <el-option
          v-for="option in statusOptions"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
    </div>

    <div class="todo-grid compact">
      <button type="button" @click="applyStatusFilter('ASSIGNED')">
        <span>待接单</span>
        <strong>{{ overview?.assigned ?? 0 }}</strong>
      </button>
      <button type="button" @click="applyStatusFilter('PROCESSING')">
        <span>处理中</span>
        <strong>{{ overview?.processing ?? 0 }}</strong>
      </button>
      <button type="button" @click="applyStatusFilter('WAITING_CONFIRM')">
        <span>待确认</span>
        <strong>{{ overview?.waitingConfirm ?? 0 }}</strong>
      </button>
      <button type="button" @click="applyOverdueFilter()">
        <span>SLA 超时</span>
        <strong>{{ overview?.overdue ?? 0 }}</strong>
      </button>
    </div>

    <el-table :data="pageData.items" v-loading="loading" empty-text="暂无分配工单">
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
          <el-button link type="primary" @click="router.push(`/worker/tickets/${row.id}`)">
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
  </section>
</template>
