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
      <span class="sidebar__mark">修</span>
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
