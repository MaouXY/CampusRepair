export interface FileRecord {
  id: string
  originalName: string
  objectKey: string
  bucketName: string
  contentType: string
  sizeBytes: number
  uploaderId: string
  uploaderRole: string
  bizType: string
  bizId: string | null
  publicUrl: string
  createdAt: string
}
