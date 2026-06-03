<script setup lang="ts">
import type { Component } from 'vue'
import { Expand, Fold } from '@element-plus/icons-vue'
import { computed } from 'vue'

defineEmits<{
  toggle: []
}>()

const props = defineProps<{
  collapsed: boolean
  currentPath: string
  role: string
  menus: Array<{
    title: string
    items: Array<{
      label: string
      path: string
      icon: Component
      role: string
    }>
  }>
}>()

const visibleMenus = computed(() =>
  props.menus
    .map((group) => ({
      ...group,
      items: group.items.filter((entry) => entry.role === props.role),
    }))
    .filter((group) => group.items.length > 0),
)
</script>

<template>
  <aside class="sidebar" :class="{ 'sidebar--collapsed': collapsed }">
    <div class="sidebar__brand">
      <div class="sidebar__mark">
        <svg
          viewBox="0 0 24 24"
          width="20"
          height="20"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
        </svg>
      </div>
      <div class="sidebar__brand-text">
        <strong>校园报修</strong>
        <p>工单服务平台</p>
      </div>
      <el-tooltip
        :content="collapsed ? '展开侧边栏' : '收起侧边栏'"
        placement="right"
      >
        <button
          class="sidebar__toggle"
          type="button"
          :aria-label="collapsed ? '展开侧边栏' : '收起侧边栏'"
          @click="$emit('toggle')"
        >
          <el-icon><component :is="collapsed ? Expand : Fold" /></el-icon>
        </button>
      </el-tooltip>
    </div>

    <section v-for="group in visibleMenus" :key="group.title" class="sidebar__group">
      <p class="sidebar__title">{{ group.title }}</p>
      <router-link
        v-for="item in group.items"
        :key="item.path"
        :to="item.path"
        class="sidebar__link"
        :class="{ 'is-active': currentPath === item.path }"
        :title="collapsed ? item.label : undefined"
      >
        <el-icon><component :is="item.icon" /></el-icon>
        <span>{{ item.label }}</span>
      </router-link>
    </section>
  </aside>
</template>
