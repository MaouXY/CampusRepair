import type { StatsOverview } from '@/types/stats'

import request from '@/utils/request'

export function getStatsOverviewApi() {
  return request.get<never, StatsOverview>('/admin/stats/overview')
}
