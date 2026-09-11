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

export interface KnowledgeDraft {
  id: string
  sourceTicketId: string | null
  title: string
  content: string
  categoryId: string | null
  status: 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | string
  createdByAi: boolean
  generateSource: string
  reviewRemark: string | null
  reviewedBy: string | null
  reviewedAt: string | null
  knowledgeDocumentId: string | null
  createdAt: string
  updatedAt: string
}

export type KnowledgeDraftPage = PageResult<KnowledgeDraft>

export interface CorpusSectionPreview {
  sectionTitle: string
  sectionLevel: number
  charCount: number
  sample: string
}

export interface CorpusPreview {
  title: string
  sectionCount: number
  chunkCount: number
  totalChars: number
  avgChunkChars: number
  sections: CorpusSectionPreview[]
}

export interface CorpusImportResult {
  documentId: string
  title: string
  standardNo: string | null
  sectionCount: number
  chunkCount: number
  importBatch: string
  sections: string[]
}

export interface CorpusImportRequest {
  title: string
  categoryId: string | null
  source: string | null
  standardNo: string | null
  docVersion: string | null
  effectiveDate: string | null
  docType: string | null
  content: string
  chunkSize: number
  chunkOverlap: number
  enabled: number
  importBatch: string | null
}

export interface RagVectorStatus {
  embeddingProvider: string
  embeddingModel: string
  dimension: number
  milvusEnabled: boolean
  collectionName: string
  documentCount: number
  chunkCount: number
  syncedChunkCount: number
  pendingChunkCount: number
  failedChunkCount: number
  hybridEnabled: boolean
  rrfK: number
  rerankEnabled: boolean
  rerankModel: string
  rerankBlendWeight: number
  rerankWindow: number
  minVectorScore: number
  minKeywordHits: number
  message: string
}
