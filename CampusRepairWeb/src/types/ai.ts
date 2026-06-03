export interface TicketAiAnalysis {
  id: string
  ticketId: string
  aiTaskId: string | null
  status: string
  suggestedCategoryId: string | null
  suggestedPriority: 'LOW' | 'MEDIUM' | 'HIGH' | null
  suggestedWorkerId: string | null
  faultSummary: string | null
  faultReason: string | null
  solution: string | null
  dispatchRemark: string | null
  riskLevel: string | null
  confidence: number | null
  rawResponse: string | null
  createdAt: string
}
