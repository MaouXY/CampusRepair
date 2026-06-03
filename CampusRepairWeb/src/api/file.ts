import type { FileRecord } from '@/types/file'

import request from '@/utils/request'

export async function uploadFileApi(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post<never, FileRecord>('/files/upload', formData, {
    headers: {
      'Content-Type': 'multipart/form-data',
    },
  })
}

export function bindTicketReportImagesApi(ticketId: string, fileIds: string[]) {
  return request.post<never, FileRecord[]>(
    `/files/tickets/${ticketId}/report-images`,
    { fileIds },
  )
}

export function bindTicketResultImagesApi(ticketId: string, fileIds: string[]) {
  return request.post<never, FileRecord[]>(
    `/files/tickets/${ticketId}/result-images`,
    { fileIds },
  )
}

export function listTicketFilesApi(ticketId: string, bizType: string) {
  return request.get<never, FileRecord[]>(`/files/tickets/${ticketId}`, {
    params: { bizType },
  })
}
