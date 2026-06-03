<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  CircleCheck,
  DataAnalysis,
  Finished,
  Stopwatch,
  TrendCharts,
} from '@element-plus/icons-vue'

import { getStatsOverviewApi } from '@/api/stats'
import type { StatsItem, StatsOverview } from '@/types/stats'

const overview = ref<StatsOverview>({
  todayTickets: 0,
  pendingReview: 0,
  processing: 0,
  completed: 0,
  averageScore: 0,
  categoryDistribution: [],
})

function toNumber(value: unknown) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : 0
}

function normalizeStats(data: StatsOverview): StatsOverview {
  return {
    todayTickets: toNumber(data.todayTickets),
    pendingReview: toNumber(data.pendingReview),
    processing: toNumber(data.processing),
    completed: toNumber(data.completed),
    averageScore: toNumber(data.averageScore),
    categoryDistribution: (data.categoryDistribution ?? []).map((item) => ({
      label: item.label,
      value: toNumber(item.value),
    })),
  }
}

const categoryDistribution = computed<StatsItem[]>(() => overview.value.categoryDistribution)

const totalCategoryTickets = computed(() =>
  categoryDistribution.value.reduce((total, item) => total + toNumber(item.value), 0),
)

const pendingWorkload = computed(
  () => toNumber(overview.value.pendingReview) + toNumber(overview.value.processing),
)

const completionRate = computed(() => {
  const activeTotal =
    toNumber(overview.value.pendingReview) +
    toNumber(overview.value.processing) +
    toNumber(overview.value.completed)
  if (activeTotal === 0) {
    return 0
  }
  return Math.round((toNumber(overview.value.completed) / activeTotal) * 100)
})

const categoryItems = computed(() => {
  const max = Math.max(...categoryDistribution.value.map((item) => toNumber(item.value)), 1)
  return categoryDistribution.value.map((item) => {
    const value = toNumber(item.value)
    return {
      label: item.label,
      value,
      percent: Math.round((value / max) * 100),
      share:
        totalCategoryTickets.value === 0
          ? 0
          : Math.round((value / totalCategoryTickets.value) * 100),
    }
  })
})

const kpiCards = computed(() => [
  {
    label: '今日报修',
    value: toNumber(overview.value.todayTickets),
    unit: '单',
    hint: '当天新增服务请求',
    icon: DataAnalysis,
  },
  {
    label: '处理中',
    value: toNumber(overview.value.processing),
    unit: '单',
    hint: '维修员正在处置',
    icon: Stopwatch,
  },
  {
    label: '待派单',
    value: toNumber(overview.value.pendingReview),
    unit: '单',
    hint: '等待管理员审核派单',
    icon: TrendCharts,
  },
  {
    label: '已完成',
    value: toNumber(overview.value.completed),
    unit: '单',
    hint: '闭环工单总量',
    icon: Finished,
  },
  {
    label: '平均评分',
    value: toNumber(overview.value.averageScore),
    unit: '分',
    hint: '学生服务满意度',
    icon: CircleCheck,
  },
])

onMounted(async () => {
  overview.value = normalizeStats(await getStatsOverviewApi())
})
</script>

<template>
  <section class="screen-page">
    <article class="screen-summary">
      <div>
        <p class="panel__eyebrow">Digital Operations Overview</p>
        <h2>校园报修数据总览</h2>
        <p>
          用实时指标呈现报修受理、派单处理、服务闭环和满意度表现，为领导研判和管理员调度提供依据。
        </p>
      </div>
      <div class="screen-summary__ring" aria-label="工单完成率">
        <strong>{{ completionRate }}%</strong>
        <span>闭环率</span>
      </div>
    </article>

    <div class="screen-kpi-grid">
      <article v-for="item in kpiCards" :key="item.label" class="screen-kpi">
        <div class="screen-kpi__icon">
          <el-icon><component :is="item.icon" /></el-icon>
        </div>
        <p>{{ item.label }}</p>
        <div>
          <strong>{{ item.value }}</strong>
          <span>{{ item.unit }}</span>
        </div>
        <small>{{ item.hint }}</small>
      </article>
    </div>

    <div class="screen-insight-grid">
      <article class="screen-chart">
        <div class="ops-panel__header">
          <div>
            <p class="panel__eyebrow">Category Distribution</p>
            <h3>报修类别分布</h3>
          </div>
          <span class="screen-chart__total">总量 {{ totalCategoryTickets }} 单</span>
        </div>

        <el-empty
          v-if="categoryItems.length === 0"
          description="暂无报修类别数据"
        />
        <div v-else class="screen-bars">
          <div v-for="item in categoryItems" :key="item.label" class="screen-bar">
            <div class="screen-bar__label">
              <span>{{ item.label }}</span>
              <strong>{{ item.value }} 单</strong>
            </div>
            <div class="screen-bar__track">
              <span :style="{ width: `${item.percent}%` }"></span>
            </div>
            <small>占比 {{ item.share }}%</small>
          </div>
        </div>
      </article>

      <aside class="screen-analysis">
        <p class="panel__eyebrow">Management Insight</p>
        <h3>运营研判</h3>
        <div class="screen-analysis__item">
          <span>待处理压力</span>
          <strong>{{ pendingWorkload }} 单</strong>
          <p>用于判断当前审核和维修环节的排队压力。</p>
        </div>
        <div class="screen-analysis__item">
          <span>服务闭环</span>
          <strong>{{ overview.completed }} 单</strong>
          <p>反映已完成维修和确认评价的整体沉淀量。</p>
        </div>
        <div class="screen-analysis__item">
          <span>满意度</span>
          <strong>{{ overview.averageScore || 0 }} 分</strong>
          <p>持续低于 4 分时建议复盘维修质量与响应体验。</p>
        </div>
      </aside>
    </div>
  </section>
</template>
