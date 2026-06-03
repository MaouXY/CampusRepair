import type {
  PageResult,
  AdminTodoOverview,
  TicketAssignRequest,
  TicketCreateRequest,
  TicketDetail,
  TicketEvaluationRequest,
  TicketRejectRequest,
  TicketReturnRequest,
  TicketReworkRequest,
  TicketResultRequest,
  TicketStatus,
  TicketSummary,
  TicketUrgeRequest,
  WorkerTodayOverview,
  WorkerOption,
} from '@/types/ticket'

import request from '@/utils/request'

interface PageQuery {
  page: number
  size: number
  status?: TicketStatus | ''
  overdue?: boolean
  urged?: boolean
}

export function createStudentTicketApi(payload: TicketCreateRequest) {
  return request.post<never, TicketDetail>('/student/tickets', payload)
}

export function listStudentTicketsApi(params: PageQuery) {
  return request.get<never, PageResult<TicketSummary>>('/student/tickets', {
    params,
  })
}

export function getStudentTicketApi(ticketId: string) {
  return request.get<never, TicketDetail>(`/student/tickets/${ticketId}`)
}

export function evaluateTicketApi(
  ticketId: string,
  payload: TicketEvaluationRequest,
) {
  return request.post<never, TicketDetail>(
    `/student/tickets/${ticketId}/evaluation`,
    payload,
  )
}

export function requestReworkTicketApi(
  ticketId: string,
  payload: TicketReworkRequest,
) {
  return request.post<never, TicketDetail>(
    `/student/tickets/${ticketId}/rework`,
    payload,
  )
}

export function resubmitStudentTicketApi(
  ticketId: string,
  payload: TicketCreateRequest,
) {
  return request.post<never, TicketDetail>(
    `/student/tickets/${ticketId}/resubmit`,
    payload,
  )
}

export function listAdminTicketsApi(params: PageQuery) {
  return request.get<never, PageResult<TicketSummary>>('/admin/tickets', {
    params,
  })
}

export function getAdminTicketApi(ticketId: string) {
  return request.get<never, TicketDetail>(`/admin/tickets/${ticketId}`)
}

export function getAdminTodoOverviewApi() {
  return request.get<never, AdminTodoOverview>('/admin/tickets/todo-overview')
}

export function assignTicketApi(ticketId: string, payload: TicketAssignRequest) {
  return request.post<never, TicketDetail>(
    `/admin/tickets/${ticketId}/assign`,
    payload,
  )
}

export function rejectTicketApi(ticketId: string, payload: TicketRejectRequest) {
  return request.post<never, TicketDetail>(
    `/admin/tickets/${ticketId}/reject`,
    payload,
  )
}

export function urgeTicketApi(ticketId: string, payload: TicketUrgeRequest) {
  return request.post<never, TicketDetail>(
    `/admin/tickets/${ticketId}/urge`,
    payload,
  )
}

export function listWorkerOptionsApi() {
  return request.get<never, WorkerOption[]>('/admin/workers/options')
}

export function listWorkerTicketsApi(params: PageQuery) {
  return request.get<never, PageResult<TicketSummary>>('/worker/tickets', {
    params,
  })
}

export function getWorkerTicketApi(ticketId: string) {
  return request.get<never, TicketDetail>(`/worker/tickets/${ticketId}`)
}

export function getWorkerTodayOverviewApi() {
  return request.get<never, WorkerTodayOverview>('/worker/tickets/today-overview')
}

export function acceptTicketApi(ticketId: string) {
  return request.post<never, TicketDetail>(`/worker/tickets/${ticketId}/accept`)
}

export function submitTicketResultApi(
  ticketId: string,
  payload: TicketResultRequest,
) {
  return request.post<never, TicketDetail>(
    `/worker/tickets/${ticketId}/result`,
    payload,
  )
}

export function returnWorkerTicketApi(
  ticketId: string,
  payload: TicketReturnRequest,
) {
  return request.post<never, TicketDetail>(
    `/worker/tickets/${ticketId}/return`,
    payload,
  )
}
