import type {
  AiTokenUsage,
  KnowledgeDocument,
  KnowledgeDocumentPage,
  KnowledgeDocumentRequest,
  RagEvalDataset,
  RagEvalRunDetail,
  RagEvalRunPage,
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
