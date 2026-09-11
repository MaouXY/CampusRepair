import type { DispatchSuggestion, TicketAiAnalysis } from '@/types/ai'

import request from '@/utils/request'

export function analyzeTicketApi(ticketId: string) {
  return request.post<never, TicketAiAnalysis>(
    `/admin/tickets/${ticketId}/ai/analysis`,
    undefined,
    { timeout: 120000 },
  )
}

export function getLatestTicketAnalysisApi(ticketId: string) {
  return request.get<never, TicketAiAnalysis>(
    `/admin/tickets/${ticketId}/ai/analysis/latest`,
  )
}

export function getDispatchSuggestionApi(ticketId: string) {
  return request.get<never, DispatchSuggestion>(
    `/admin/tickets/${ticketId}/dispatch-suggestion`,
  )
}
