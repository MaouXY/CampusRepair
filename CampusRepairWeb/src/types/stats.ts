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

export interface WorkerPerformance {
  workerId: string
  workerName: string
  departmentName: string | null
  completedCount: number
  avgProcessMinutes: number
  avgScore: number
  goodRate: number
  evaluationCount: number
  returnCount: number
  reworkCount: number
  activeCount: number
  overdueCount: number
}

export interface HotspotsResponse {
  days: number
  totalTickets: number
  categories: StatsItem[]
  locations: StatsItem[]
}

export interface MonthlyReport {
  month: string
  createdCount: number
  completedCount: number
  rejectedCount: number
  evaluatingScoreCount: number
  avgProcessMinutes: number
  avgScore: number
  goodRate: number
  overdueRate: number
  topCategories: StatsItem[]
  topLocations: StatsItem[]
  topWorkers: WorkerPerformance[]
  aiSummary: string | null
  aiDegraded: boolean
}
