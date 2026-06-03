import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import type { LoginForm, UserRole } from '@/types/auth'
import { currentUserApi, loginApi } from '@/api/auth'
import { clearToken, getToken, setToken } from '@/utils/storage'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(getToken())
  const role = ref<UserRole>('STUDENT')
  const username = ref('')
  const displayName = ref('未登录')

  const defaultRoute = computed(() => {
    if (role.value === 'ADMIN') {
      return '/admin/dashboard'
    }
    if (role.value === 'WORKER') {
      return '/worker/workbench'
    }
    return '/student/home'
  })

  async function login(payload: LoginForm) {
    const response = await loginApi(payload)
    token.value = response.token
    role.value = response.role
    username.value = response.username
    displayName.value = response.displayName
    setToken(response.token)
  }

  async function fetchCurrentUser() {
    const response = await currentUserApi()
    role.value = response.role
    username.value = response.username
    displayName.value = response.displayName
  }

  function logout() {
    token.value = ''
    role.value = 'STUDENT'
    username.value = ''
    displayName.value = '未登录'
    clearToken()
  }

  return {
    token,
    role,
    username,
    displayName,
    defaultRoute,
    login,
    fetchCurrentUser,
    logout,
  }
})
