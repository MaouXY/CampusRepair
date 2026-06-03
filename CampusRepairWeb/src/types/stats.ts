export interface StatsItem {
  label: string
  value: number
}

export interface StatsOverview {
  todayTickets: number
  pendingReview: number
  processing: number
  completed: number
  averageScore: number
  categoryDistribution: StatsItem[]
}
