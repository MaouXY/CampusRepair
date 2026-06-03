import type {
  KnowledgeDocument,
  KnowledgeDocumentPage,
  KnowledgeDocumentRequest,
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
