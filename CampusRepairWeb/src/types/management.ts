import type { PageResult } from '@/types/ticket'

export type NoticeTargetRole = 'ALL' | 'STUDENT' | 'WORKER' | 'ADMIN'

export interface AdminCategory {
  id: string
  name: string
  sortOrder: number
  enabled: number
  createdAt: string
  updatedAt: string
}

export interface AdminCategoryRequest {
  name: string
  sortOrder: number
  enabled: number
}

export interface AdminLocation {
  id: string
  parentId: string | null
  name: string
  sortOrder: number
  enabled: number
  createdAt: string
  updatedAt: string
}

export interface AdminLocationRequest {
  parentId: string | null
  name: string
  sortOrder: number
  enabled: number
}

export interface AdminWorker {
  id: string
  username: string
  realName: string
  phone: string | null
  enabled: number
  departmentName: string
  skillTags: string[]
  dispatchEnabled: number
  maxActiveOrders: number
  createdAt: string
  updatedAt: string
}

export interface AdminWorkerRequest {
  username: string
  password?: string
  realName: string
  phone?: string
  enabled: number
  departmentName: string
  skillTags: string[]
  dispatchEnabled: number
  maxActiveOrders: number
}

export interface Notice {
  id: string
  title: string
  content: string
  targetRole: NoticeTargetRole
  published: number
  sortOrder: number
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface NoticeRequest {
  title: string
  content: string
  targetRole: NoticeTargetRole
  published: number
  sortOrder: number
}

export type ManagementPage<T> = PageResult<T>
