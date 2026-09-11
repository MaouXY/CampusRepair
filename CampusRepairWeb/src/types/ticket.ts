export type TicketStatus =
  | 'PENDING_REVIEW'
  | 'ASSIGNED'
  | 'PROCESSING'
  | 'WAITING_CONFIRM'
  | 'COMPLETED'
  | 'REJECTED'
  | 'RETURNED'

export type TicketPriority = 'HIGH' | 'MEDIUM' | 'LOW'

export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export interface TicketCreateRequest {
  locationId: string | null
  categoryId: string | null
  description: string
  contactPhone: string
  reportImageFileIds?: string[]
}

export interface TicketAssignRequest {
  categoryId: string | null
  priority: TicketPriority
  summary: string
  workerId: string | null
  remark: string
}

export interface TicketRejectRequest {
  reason: string
}

export interface TicketUrgeRequest {
  remark: string
}

export interface TicketReturnRequest {
  reason: string
}

export interface TicketResultRequest {
  result: string
  remark: string
}

export interface TicketEvaluationRequest {
  score: number
  content: string
}

export interface TicketReworkRequest {
  reason: string
}

export interface TicketSummary {
  id: string
  status: TicketStatus
  priority: TicketPriority
  studentId: string
  studentName: string
  locationId: string
  locationName: string
  categoryId: string
  categoryName: string
  assignedWorkerId: string | null
  assignedWorkerName: string | null
  summary: string
  slaDeadlineAt: string | null
  slaOverdue: boolean
  urgedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface TicketFlow {
  id: string
  fromStatus: TicketStatus | null
  toStatus: TicketStatus
  operatorId: string
  operatorRole: string
  action: string
  remark: string | null
  createdAt: string
}

export interface TicketEvaluation {
  id: string
  score: number
  content: string | null
  createdAt: string
}

export interface TicketDetail extends TicketSummary {
  description: string
  contactPhone: string
  assignedAdminId: string | null
  assignedAdminName: string | null
  assignedAt: string | null
  rejectReason: string | null
  returnReason: string | null
  processResult: string | null
  processRemark: string | null
  processedAt: string | null
  urgedBy: string | null
  urgeRemark: string | null
  reportImageUrls: string | null
  resultImageUrls: string | null
  flows: TicketFlow[]
  evaluation: TicketEvaluation | null
}

export interface WorkerOption {
  id: string
  username: string
  realName: string
  phone: string | null
  departmentName: string
  skillTags: string[]
  activeOrderCount: number
  maxActiveOrders: number
}

export interface AdminTodoOverview {
  pendingReview: number
  returned: number
  overdue: number
  urged: number
  waitingConfirm: number
  latestTickets: TicketSummary[]
}

export interface WorkerTodayOverview {
  assigned: number
  processing: number
  waitingConfirm: number
  overdue: number
  dueTodayTickets: TicketSummary[]
}
