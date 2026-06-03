<script setup lang="ts">
import { Bell, Calendar } from '@element-plus/icons-vue'
import { onMounted, ref } from 'vue'

import { listPublishedNoticesApi } from '@/api/management'
import type { Notice } from '@/types/management'
import { formatDateTime } from '@/utils/ticketDisplay'

const notices = ref<Notice[]>([])
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    notices.value = await listPublishedNoticesApi()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section class="panel notice-center" v-loading="loading">
    <div class="section-heading">
      <div>
        <p class="panel__eyebrow">学生服务</p>
        <h2>公告通知</h2>
      </div>
    </div>

    <el-empty v-if="notices.length === 0" description="暂无公告" />
    <div v-else class="notice-list">
      <article v-for="notice in notices" :key="notice.id" class="notice-item">
        <div class="notice-item__icon">
          <el-icon><Bell /></el-icon>
        </div>
        <div class="notice-item__body">
          <div class="notice-item__header">
            <h3>{{ notice.title }}</h3>
            <span>
              <el-icon><Calendar /></el-icon>
              {{ formatDateTime(notice.updatedAt || notice.createdAt) }}
            </span>
          </div>
          <p>{{ notice.content }}</p>
        </div>
      </article>
    </div>
  </section>
</template>
