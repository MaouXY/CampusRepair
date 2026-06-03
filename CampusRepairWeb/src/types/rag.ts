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
