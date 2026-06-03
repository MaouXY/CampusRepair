import { createRouter, createWebHistory } from 'vue-router'

import AppShell from '@/layouts/AppShell.vue'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/login/LoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/',
      redirect: '/student/home',
    },
    {
      path: '/',
      component: AppShell,
      children: [
        {
          path: 'student/home',
          name: 'student-home',
          component: () => import('@/views/student/StudentHomeView.vue'),
          meta: { role: 'STUDENT', title: '学生报修' },
        },
        {
          path: 'student/tickets',
          name: 'student-tickets',
          component: () => import('@/views/student/StudentTicketsView.vue'),
          meta: { role: 'STUDENT', title: '我的工单' },
        },
        {
          path: 'student/notices',
          name: 'student-notices',
          component: () => import('@/views/student/StudentNoticesView.vue'),
          meta: { role: 'STUDENT', title: '公告通知' },
        },
        {
          path: 'student/tickets/:id',
          name: 'student-ticket-detail',
          component: () => import('@/views/student/StudentTicketDetailView.vue'),
          meta: { role: 'STUDENT', title: '工单详情' },
        },
        {
          path: 'worker/workbench',
          name: 'worker-workbench',
          component: () => import('@/views/worker/WorkerHomeView.vue'),
          meta: { role: 'WORKER', title: '维修工作台' },
        },
        {
          path: 'worker/tickets',
          name: 'worker-tickets',
          component: () => import('@/views/worker/WorkerTicketsView.vue'),
          meta: { role: 'WORKER', title: '维修处理' },
        },
        {
          path: 'worker/tickets/:id',
          name: 'worker-ticket-detail',
          component: () => import('@/views/worker/WorkerTicketDetailView.vue'),
          meta: { role: 'WORKER', title: '维修工单详情' },
        },
        {
          path: 'admin/dashboard',
          name: 'admin-dashboard',
          component: () => import('@/views/admin/AdminHomeView.vue'),
          meta: { role: 'ADMIN', title: '管理后台' },
        },
        {
          path: 'admin/tickets',
          name: 'admin-tickets',
          component: () => import('@/views/admin/AdminTicketsView.vue'),
          meta: { role: 'ADMIN', title: '工单管理' },
        },
        {
          path: 'admin/base-data',
          name: 'admin-base-data',
          component: () => import('@/views/admin/AdminBaseDataView.vue'),
          meta: { role: 'ADMIN', title: '基础数据' },
        },
        {
          path: 'admin/rag',
          name: 'admin-rag',
          component: () => import('@/views/admin/AdminRagView.vue'),
          meta: { role: 'ADMIN', title: '维修知识库' },
        },
        {
          path: 'screen/overview',
          name: 'screen-overview',
          component: () => import('@/views/screen/ScreenView.vue'),
          meta: { role: 'ADMIN', title: '数据总览' },
        },
      ],
    },
  ],
  scrollBehavior() {
    return { top: 0 }
  },
})

router.beforeEach(async (to) => {
  const authStore = useAuthStore()

  if (to.meta.title) {
    document.title = `${String(to.meta.title)} - ${import.meta.env.VITE_APP_TITLE || '校园报修系统'}`
  }

  if (to.meta.public) {
    return true
  }

  if (!authStore.token) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (!authStore.username) {
    try {
      await authStore.fetchCurrentUser()
    } catch {
      authStore.logout()
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }

  const requiredRole = to.meta.role as string | undefined
  if (requiredRole && authStore.role !== requiredRole) {
    return { path: authStore.defaultRoute }
  }

  return true
})

export default router
