<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Bell,
  DataBoard,
  Document,
  Grid,
  HomeFilled,
  List,
  Notebook,
  Setting,
  Tools,
} from '@element-plus/icons-vue'

import AppSidebar from '@/components/AppSidebar.vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const authStore = useAuthStore()

const roleLabel = computed(() => {
  if (authStore.role === 'ADMIN') return '管理员'
  if (authStore.role === 'WORKER') return '维修员'
  return '学生'
})

const menus = computed(() => [
  {
    title: '学生服务',
    items: [
      {
        label: '我要报修',
        path: '/student/home',
        icon: HomeFilled,
        role: 'STUDENT',
      },
      {
        label: '我的工单',
        path: '/student/tickets',
        icon: List,
        role: 'STUDENT',
      },
      {
        label: '公告通知',
        path: '/student/notices',
        icon: Bell,
        role: 'STUDENT',
      },
    ],
  },
  {
    title: '维修工作台',
    items: [
      {
        label: '今日任务',
        path: '/worker/workbench',
        icon: Tools,
        role: 'WORKER',
      },
      {
        label: '维修工单',
        path: '/worker/tickets',
        icon: List,
        role: 'WORKER',
      },
    ],
  },
  {
    title: '后台管理',
    items: [
      {
        label: '工单管理',
        path: '/admin/tickets',
        icon: Document,
        role: 'ADMIN',
      },
      {
        label: '管理首页',
        path: '/admin/dashboard',
        icon: Setting,
        role: 'ADMIN',
      },
      {
        label: '基础数据',
        path: '/admin/base-data',
        icon: Grid,
        role: 'ADMIN',
      },
      {
        label: '维修知识库',
        path: '/admin/rag',
        icon: Notebook,
        role: 'ADMIN',
      },
      {
        label: '数据总览',
        path: '/screen/overview',
        icon: DataBoard,
        role: 'ADMIN',
      },
    ],
  },
])

function handleLogout() {
  authStore.logout()
  router.push('/login')
}
</script>

<template>
  <div class="shell" :class="{ 'shell--collapsed': appStore.sidebarCollapsed }">
    <AppSidebar
      :collapsed="appStore.sidebarCollapsed"
      :menus="menus"
      :current-path="route.path"
      :role="authStore.role"
      @toggle="appStore.toggleSidebar()"
    />
    <div class="shell__main">
      <header class="shell__header">
        <div>
          <p class="shell__eyebrow">Campus Repair</p>
          <h1>{{ route.meta.title }}</h1>
        </div>
        <div class="shell__actions">
          <el-dropdown>
            <span class="shell__user">
              {{ authStore.displayName }}
              <small>{{ roleLabel }}</small>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="handleLogout">
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>
      <main class="shell__content">
        <router-view />
      </main>
    </div>
  </div>
</template>
