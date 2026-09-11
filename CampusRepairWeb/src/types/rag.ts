import type { PageResult } from '@/types/ticket'

export interface KnowledgeDocument {
  id: string
  title: string
  categoryId: string | null
  content: string
  enabled: number
  chunkCount: number
  createdAt: string
  updatedAt: string
}

export interface KnowledgeDocumentRequest {
  title: string
  categoryId: string | null
  content: string
  enabled: number
}

export type KnowledgeDocumentPage = PageResult<KnowledgeDocument>

export interface RagEvalDataset {
  datasetName: string
  caseCount: number
  answerableCaseCount: number
  unanswerableCaseCount: number
}

export interface RagEvalMetrics {
  caseCount: number
  answerableCaseCount: number
  unanswerableCaseCount: number
  hitRate: number
  recallAtK: number
  precisionAtK: number
  mrr: number
  ndcgAtK: number
  refusalAccuracy: number
  avgLatencyMs: number
}

export interface RagEvalRun {
  id: string
  datasetName: string
  topK: number
  status: string
  errorMessage: string | null
  metrics: RagEvalMetrics
  triggeredBy: string | null
  createdAt: string
  finishedAt: string | null
}

export interface RagEvalRetrievedChunk {
  chunkId: string | null
  documentId: string | null
  title: string | null
  score: number
  source: string
}

export interface RagEvalCaseResult {
  caseId: string
  question: string
  hit: boolean
  firstRelevantRank: number | null
  recall: number
  precisionScore: number
  reciprocalRank: number
  ndcg: number
  latencyMs: number
  retrievedChunks: RagEvalRetrievedChunk[]
}

export interface RagEvalRunDetail {
  run: RagEvalRun
  results: RagEvalCaseResult[]
}

export type RagEvalRunPage = PageResult<RagEvalRun>

export interface AiTokenUsage {
  date: string
  monitorEnabled: boolean
  dailyBudget: number
  usedTokens: number
  remainingTokens: number
  usedRatio: number
  todayCallCount: number
  degradedCallCount: number
  currentLevel: string
  message: string
}
