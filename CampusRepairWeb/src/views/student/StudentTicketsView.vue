<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { listStudentTicketsApi } from '@/api/ticket'
import type { PageResult, TicketStatus, TicketSummary } from '@/types/ticket'
import {
  formatDateTime,
  formatTicketStatus,
  ticketStatusOptions as statusOptions,
} from '@/utils/ticketDisplay'

const router = useRouter()
const loading = ref(false)
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
}>({
  page: 1,
  size: 10,
  status: '',
})

onMounted(loadTickets)

async function loadTickets() {
  loading.value = true
  try {
    pageData.value = await listStudentTicketsApi(query)
  } finally {
    loading.value = false
  }
}

function handleFilterChange() {
  query.page = 1
  loadTickets()
}
</script>

<template>
  <section class="panel">
    <div class="section-heading">
      <div>
        <p class="panel__eyebrow">学生服务</p>
        <h2>我的工单</h2>
      </div>
      <div class="section-actions">
        <el-select v-model="query.status" class="status-filter" @change="handleFilterChange">
          <el-option
            v-for="option in statusOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-button type="primary" @click="router.push('/student/home')">
          新增报修
        </el-button>
      </div>
    </div>

    <el-table :data="pageData.items" v-loading="loading" empty-text="暂无工单">
      <el-table-column prop="summary" label="摘要" min-width="220" />
      <el-table-column prop="locationName" label="地点" width="150" />
      <el-table-column prop="categoryName" label="分类" width="120" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <span class="status-pill">{{ formatTicketStatus(row.status) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="提交时间" width="180">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="router.push(`/student/tickets/${row.id}`)">
            查看
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
