import axios from 'axios'
import { ElMessage } from 'element-plus'

import router from '@/router'
import { clearToken, getToken } from '@/utils/storage'

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 15000,
})

request.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const payload = response.data as ApiResponse<unknown>
    if (typeof payload?.code === 'number' && payload.code !== 0) {
      ElMessage.error(payload.message || '请求失败')
      return Promise.reject(payload)
    }
    return payload?.data ?? response.data
  },
  (error) => {
    const status = error?.response?.status
    if (status === 401) {
      clearToken()
      ElMessage.error('登录已失效，请重新登录')
      router.push('/login')
    } else if (error?.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请稍后重试')
    } else {
      ElMessage.error(
        error?.response?.data?.message || error.message || '网络异常',
      )
    }
    return Promise.reject(error)
  },
)

export default request
