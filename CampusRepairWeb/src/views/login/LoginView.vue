<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import type { LoginForm } from '@/types/auth'

const authStore = useAuthStore()
const router = useRouter()
const route = useRoute()
const submitting = ref(false)

const form = reactive<LoginForm>({
  username: 'student01',
  password: '123456',
})

const demoAccounts = [
  { label: '学生账号', username: 'student01' },
  { label: '维修员账号', username: 'worker01' },
  { label: '管理员账号', username: 'admin01' },
]

function useDemoAccount(username: string) {
  form.username = username
  form.password = '123456'
}

async function handleLogin() {
  submitting.value = true
  try {
    await authStore.login(form)
    ElMessage.success('登录成功')
    const redirect =
      typeof route.query.redirect === 'string'
        ? route.query.redirect
        : authStore.defaultRoute
    await router.push(redirect)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <section class="login-hero">
      <div class="login-hero__logo">
        <svg
          viewBox="0 0 24 24"
          width="48"
          height="48"
          fill="none"
          stroke="#f0c36a"
          stroke-width="2.2"
          stroke-linecap="round"
          stroke-linejoin="round"
          style="filter: drop-shadow(0 4px 12px rgba(240, 195, 106, 0.3)); margin-bottom: 4px;"
        >
          <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
        </svg>
      </div>
      <p class="login-hero__eyebrow">Campus Repair</p>
      <h1>校园报修管理系统</h1>
      <p class="login-hero__text">
        面向学生报修、管理员派单和维修员处理的统一工单平台。
      </p>
      <div class="login-hero__chips">
        <button
          v-for="account in demoAccounts"
          :key="account.username"
          type="button"
          @click="useDemoAccount(account.username)"
        >
          {{ account.label }}
        </button>
      </div>
    </section>

    <section class="login-card">
      <div class="login-card__header">
        <h2>账号登录</h2>
        <p>请使用校内账号登录，系统将按角色进入对应工作台。</p>
      </div>

      <el-form label-position="top" @submit.prevent="handleLogin">
        <el-form-item label="账号">
          <el-input v-model="form.username" placeholder="请输入账号" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="请输入密码"
          />
        </el-form-item>
        <el-button
          class="login-card__submit"
          type="primary"
          :loading="submitting"
          @click="handleLogin"
        >
          登录系统
        </el-button>
      </el-form>
    </section>
  </div>
</template>
