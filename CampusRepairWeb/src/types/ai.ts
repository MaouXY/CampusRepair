export interface DispatchCandidate {
  workerId: string
  workerName: string
  departmentName: string
  skillTags: string[]
  activeOrderCount: number
  maxActiveOrders: number
  skillScore: number
  departmentScore: number
  workloadScore: number
  qualityScore: number
  penaltyScore: number
  totalScore: number
  ruleReason: string
  aiRecommended: boolean
}

export type DispatchSuggestionSource = 'AI' | 'AI_FALLBACK' | 'RULE'

export interface DispatchSuggestion {
  ticketId: string
  categoryName: string | null
  requiredSkills: string[]
  expectedDepartment: string | null
  recommendationSource: DispatchSuggestionSource
  aiAnalysisId: string | null
  aiAnalysisStatus: string | null
  recommendedWorkerId: string | null
  recommendedWorkerName: string | null
  recommendedReason: string
  recommendedConfidence: number | null
  dispatchRemark: string | null
  generatedAt: string
  candidates: DispatchCandidate[]
}

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
  dispatchCandidates: DispatchCandidate[]
}
