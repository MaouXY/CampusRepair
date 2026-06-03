import type { CurrentUserResponse, LoginForm, LoginResponse } from '@/types/auth'

import request from '@/utils/request'

export function loginApi(payload: LoginForm) {
  return request.post<never, LoginResponse>('/auth/login', payload)
}

export function currentUserApi() {
  return request.get<never, CurrentUserResponse>('/auth/me')
}
