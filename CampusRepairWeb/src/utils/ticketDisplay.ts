import type { TicketPriority, TicketStatus } from '@/types/ticket'

export const ticketStatusLabels: Record<TicketStatus, string> = {
  PENDING_REVIEW: '待审核',
  ASSIGNED: '待接单',
  PROCESSING: '处理中',
  WAITING_CONFIRM: '待确认评价',
  COMPLETED: '已完成',
  REJECTED: '已驳回',
  RETURNED: '维修员退回',
}

export const ticketPriorityLabels: Record<TicketPriority, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
}

export const ticketFlowActionLabels: Record<string, string> = {
  CREATE: '提交报修',
  ASSIGN: '审核派单',
  REJECT: '驳回工单',
  ACCEPT: '维修接单',
  SUBMIT_RESULT: '提交处理结果',
  EVALUATE: '学生评价',
  RESUBMIT: '修改后重提',
  REQUEST_REWORK: '申请继续处理',
  WORKER_RETURN: '维修员退回',
  URGE: '管理员督办',
}

export const aiStatusLabels: Record<string, string> = {
  SUCCESS: '分析成功',
  FAILED: '分析失败',
  FALLBACK: '兜底结果',
}

export const riskLevelLabels: Record<string, string> = {
  LOW: '低风险',
  MEDIUM: '中风险',
  HIGH: '高风险',
}

export const ticketStatusOptions: Array<{ label: string; value: TicketStatus | '' }> = [
  { label: '全部状态', value: '' },
  { label: ticketStatusLabels.PENDING_REVIEW, value: 'PENDING_REVIEW' },
  { label: ticketStatusLabels.ASSIGNED, value: 'ASSIGNED' },
  { label: ticketStatusLabels.PROCESSING, value: 'PROCESSING' },
  { label: ticketStatusLabels.WAITING_CONFIRM, value: 'WAITING_CONFIRM' },
  { label: ticketStatusLabels.COMPLETED, value: 'COMPLETED' },
  { label: ticketStatusLabels.REJECTED, value: 'REJECTED' },
  { label: ticketStatusLabels.RETURNED, value: 'RETURNED' },
]

export const workerTicketStatusOptions: Array<{ label: string; value: TicketStatus | '' }> = [
  { label: '全部状态', value: '' },
  { label: ticketStatusLabels.ASSIGNED, value: 'ASSIGNED' },
  { label: ticketStatusLabels.PROCESSING, value: 'PROCESSING' },
  { label: ticketStatusLabels.WAITING_CONFIRM, value: 'WAITING_CONFIRM' },
  { label: ticketStatusLabels.COMPLETED, value: 'COMPLETED' },
]

export const priorityOptions: Array<{ label: string; value: TicketPriority }> = [
  { label: ticketPriorityLabels.HIGH, value: 'HIGH' },
  { label: ticketPriorityLabels.MEDIUM, value: 'MEDIUM' },
  { label: ticketPriorityLabels.LOW, value: 'LOW' },
]

export function formatTicketStatus(status?: TicketStatus | null) {
  return status ? ticketStatusLabels[status] || status : '新建'
}

export function formatTicketPriority(priority?: TicketPriority | null) {
  return priority ? ticketPriorityLabels[priority] || priority : '-'
}

export function formatFlowAction(action?: string | null) {
  return action ? ticketFlowActionLabels[action] || action : '-'
}

export function formatAiStatus(status?: string | null) {
  return status ? aiStatusLabels[status] || status : '-'
}

export function formatRiskLevel(level?: string | null) {
  return level ? riskLevelLabels[level] || level : '-'
}

export function parseImageUrls(value?: string | null) {
  if (!value) {
    return []
  }
  try {
    const parsed = JSON.parse(value)
    if (Array.isArray(parsed)) {
      return parsed.filter((item): item is string => typeof item === 'string' && item.length > 0)
    }
  } catch {
    return value
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean)
  }
  return []
}

export function formatDateTime(value?: string | null) {
  if (!value) {
    return '-'
  }
  const normalized = value.replace('T', ' ')
  const dotIndex = normalized.indexOf('.')
  return dotIndex > -1 ? normalized.slice(0, dotIndex) : normalized
}
