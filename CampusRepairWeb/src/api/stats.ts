import type {
  HotspotsResponse,
  MonthlyReport,
  StatsOverview,
  WorkerPerformance,
} from '@/types/stats'

import request from '@/utils/request'

export function getStatsOverviewApi() {
  return request.get<never, StatsOverview>('/admin/stats/overview')
}

export function getWorkerPerformanceApi(days = 30) {
  return request.get<never, WorkerPerformance[]>('/admin/stats/worker-performance', {
    params: { days },
  })
}

export function getHotspotsApi(days = 30, limit = 5) {
  return request.get<never, HotspotsResponse>('/admin/stats/hotspots', {
    params: { days, limit },
  })
}

export function getMonthlyReportApi(month?: string) {
  return request.get<never, MonthlyReport>('/admin/stats/monthly-report', {
    params: month ? { month } : {},
  })
}
