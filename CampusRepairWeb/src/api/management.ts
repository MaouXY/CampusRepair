import type {
  AdminCategory,
  AdminCategoryRequest,
  AdminLocation,
  AdminLocationRequest,
  AdminWorker,
  AdminWorkerRequest,
  ManagementPage,
  Notice,
  NoticeRequest,
} from '@/types/management'

import request from '@/utils/request'

interface PageQuery {
  page: number
  size: number
}

export function listAdminCategoriesApi(params: PageQuery) {
  return request.get<never, ManagementPage<AdminCategory>>('/admin/categories', {
    params,
  })
}

export function createAdminCategoryApi(payload: AdminCategoryRequest) {
  return request.post<never, AdminCategory>('/admin/categories', payload)
}

export function updateAdminCategoryApi(id: string, payload: AdminCategoryRequest) {
  return request.put<never, AdminCategory>(`/admin/categories/${id}`, payload)
}

export function deleteAdminCategoryApi(id: string) {
  return request.delete<never, null>(`/admin/categories/${id}`)
}

export function listAdminLocationsApi(params: PageQuery) {
  return request.get<never, ManagementPage<AdminLocation>>('/admin/locations', {
    params,
  })
}

export function createAdminLocationApi(payload: AdminLocationRequest) {
  return request.post<never, AdminLocation>('/admin/locations', payload)
}

export function updateAdminLocationApi(id: string, payload: AdminLocationRequest) {
  return request.put<never, AdminLocation>(`/admin/locations/${id}`, payload)
}

export function deleteAdminLocationApi(id: string) {
  return request.delete<never, null>(`/admin/locations/${id}`)
}

export function listAdminWorkersApi(params: PageQuery) {
  return request.get<never, ManagementPage<AdminWorker>>('/admin/workers', {
    params,
  })
}

export function createAdminWorkerApi(payload: AdminWorkerRequest) {
  return request.post<never, AdminWorker>('/admin/workers', payload)
}

export function updateAdminWorkerApi(id: string, payload: AdminWorkerRequest) {
  return request.put<never, AdminWorker>(`/admin/workers/${id}`, payload)
}

export function deleteAdminWorkerApi(id: string) {
  return request.delete<never, null>(`/admin/workers/${id}`)
}

export function listAdminNoticesApi(params: PageQuery) {
  return request.get<never, ManagementPage<Notice>>('/admin/notices', {
    params,
  })
}

export function createNoticeApi(payload: NoticeRequest) {
  return request.post<never, Notice>('/admin/notices', payload)
}

export function updateNoticeApi(id: string, payload: NoticeRequest) {
  return request.put<never, Notice>(`/admin/notices/${id}`, payload)
}

export function deleteNoticeApi(id: string) {
  return request.delete<never, null>(`/admin/notices/${id}`)
}

export function listPublishedNoticesApi() {
  return request.get<never, Notice[]>('/notices')
}
