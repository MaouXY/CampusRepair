import type {
  AiTokenUsage,
  CorpusImportRequest,
  CorpusImportResult,
  CorpusPreview,
  KnowledgeDocument,
  KnowledgeDocumentPage,
  KnowledgeDocumentRequest,
  KnowledgeDraft,
  KnowledgeDraftPage,
  RagEvalDataset,
  RagEvalRunDetail,
  RagEvalRunPage,
  RagVectorStatus,
} from '@/types/rag'

import request from '@/utils/request'

interface PageQuery {
  page: number
  size: number
}

export function listKnowledgeDocumentsApi(params: PageQuery) {
  return request.get<never, KnowledgeDocumentPage>('/admin/rag/documents', {
    params,
  })
}

export function createKnowledgeDocumentApi(payload: KnowledgeDocumentRequest) {
  return request.post<never, KnowledgeDocument>('/admin/rag/documents', payload)
}

export function updateKnowledgeDocumentApi(
  id: string,
  payload: KnowledgeDocumentRequest,
) {
  return request.put<never, KnowledgeDocument>(
    `/admin/rag/documents/${id}`,
    payload,
  )
}

export function deleteKnowledgeDocumentApi(id: string) {
  return request.delete<never, null>(`/admin/rag/documents/${id}`)
}

export function rebuildKnowledgeDocumentApi(id: string) {
  return request.post<never, number>(`/admin/rag/documents/${id}/rebuild`)
}

export function listRagEvalDatasetsApi() {
  return request.get<never, RagEvalDataset[]>('/admin/rag/eval/datasets')
}

export function listRagEvalRunsApi(params: PageQuery) {
  return request.get<never, RagEvalRunPage>('/admin/rag/eval/runs', { params })
}

export function getRagEvalRunApi(runId: string) {
  return request.get<never, RagEvalRunDetail>(`/admin/rag/eval/runs/${runId}`)
}

export function runRagEvalApi(payload: { datasetName: string; topK: number }) {
  return request.post<never, RagEvalRunDetail>('/admin/rag/eval/runs', payload, {
    timeout: 120000,
  })
}

export function importRagEvalCasesApi(payload: {
  datasetName: string
  source: string
  content: string
}) {
  return request.post<never, number>('/admin/rag/eval/cases/import', payload)
}

export function getAiTokenUsageApi() {
  return request.get<never, AiTokenUsage>('/admin/ai/token-usage')
}

export function listKnowledgeDraftsApi(params: {
  page: number
  size: number
  status?: string
}) {
  return request.get<never, KnowledgeDraftPage>('/admin/rag/drafts', { params })
}

export function generateKnowledgeDraftApi(ticketId: string) {
  return request.post<never, KnowledgeDraft>('/admin/rag/drafts/generate', null, {
    params: { ticketId },
  })
}

export function approveKnowledgeDraftApi(draftId: string, remark: string) {
  return request.post<never, KnowledgeDraft>(
    `/admin/rag/drafts/${draftId}/approve`,
    { remark },
  )
}

export function rejectKnowledgeDraftApi(draftId: string, remark: string) {
  return request.post<never, KnowledgeDraft>(
    `/admin/rag/drafts/${draftId}/reject`,
    { remark },
  )
}

export function getRagVectorStatusApi() {
  return request.get<never, RagVectorStatus>('/admin/rag/vector-status')
}

export function previewCorpusApi(payload: CorpusImportRequest) {
  return request.post<never, CorpusPreview>(
    '/admin/rag/corpus/preview',
    payload,
  )
}

export function importCorpusApi(payload: CorpusImportRequest) {
  return request.post<never, CorpusImportResult>(
    '/admin/rag/corpus/import',
    payload,
  )
}

export function importCorpusBatchApi(payload: {
  batchName: string
  documents: unknown[]
}) {
  return request.post<never, CorpusImportResult[]>(
    '/admin/rag/corpus/import-batch',
    payload,
  )
}
