<script setup lang="ts">
import type {
  AdminCategory,
  AdminCategoryRequest,
  AdminLocation,
  AdminLocationRequest,
  AdminWorker,
  AdminWorkerRequest,
  Notice,
  NoticeRequest,
  NoticeStatus,
  NoticeTargetRole,
} from '@/types/management'
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'

import { formatDateTime } from '@/utils/ticketDisplay'

import {
  createAdminCategoryApi,
  createAdminLocationApi,
  createAdminWorkerApi,
  createNoticeApi,
  deleteAdminCategoryApi,
  deleteAdminLocationApi,
  deleteAdminWorkerApi,
  deleteNoticeApi,
  listAdminCategoriesApi,
  listAdminLocationsApi,
  listAdminNoticesApi,
  listAdminWorkersApi,
  updateAdminCategoryApi,
  updateAdminLocationApi,
  updateAdminWorkerApi,
  updateNoticeApi,
} from '@/api/management'

type TabName = 'categories' | 'locations' | 'workers' | 'notices'

const activeTab = ref<TabName>('categories')
const loading = ref(false)
const drawerVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

const categories = ref<AdminCategory[]>([])
const locations = ref<AdminLocation[]>([])
const workers = ref<AdminWorker[]>([])
const notices = ref<Notice[]>([])

const pageState = reactive<Record<TabName, { page: number; size: number; total: number }>>({
  categories: { page: 1, size: 10, total: 0 },
  locations: { page: 1, size: 10, total: 0 },
  workers: { page: 1, size: 10, total: 0 },
  notices: { page: 1, size: 10, total: 0 },
})

const categoryForm = reactive<AdminCategoryRequest>({
  name: '',
  sortOrder: 10,
  enabled: 1,
})
const locationForm = reactive<AdminLocationRequest>({
  parentId: null,
  name: '',
  sortOrder: 10,
  enabled: 1,
})
const workerForm = reactive<AdminWorkerRequest>({
  username: '',
  password: '',
  realName: '',
  phone: '',
  enabled: 1,
  departmentName: '综合维修组',
  skillTags: [],
  dispatchEnabled: 1,
  maxActiveOrders: 5,
})
const noticeForm = reactive<NoticeRequest>({
  title: '',
  content: '',
  targetRole: 'ALL',
  published: 1,
  sortOrder: 10,
  effectiveAt: null,
  expireAt: null,
})

const drawerTitle = computed(() => `${editingId.value ? '编辑' : '新增'}${tabLabel(activeTab.value)}`)
const departmentOptions = ['水电组', '网络组', '空调照明组', '综合维修组']
const skillOptions = ['水电', '网络', '门窗', '空调', '照明', '公共设施']

const currentRows = computed(() => {
  if (activeTab.value === 'categories') return categories.value
  if (activeTab.value === 'locations') return locations.value
  if (activeTab.value === 'workers') return workers.value
  return notices.value
})

const rules = computed<FormRules>(() => {
  if (activeTab.value === 'categories') {
    return { name: [{ required: true, message: '请输入分类名称', trigger: 'blur' }] }
  }
  if (activeTab.value === 'locations') {
    return { name: [{ required: true, message: '请输入地点名称', trigger: 'blur' }] }
  }
  if (activeTab.value === 'workers') {
    return {
      username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
      realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
      departmentName: [{ required: true, message: '请选择所属部门', trigger: 'change' }],
    }
  }
  return {
    title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
    content: [{ required: true, message: '请输入内容', trigger: 'blur' }],
  }
})

const locationOptions = computed(() =>
  locations.value
    .filter((location) => location.id !== editingId.value)
    .map((location) => ({
      label: location.name,
      value: location.id,
    })),
)

function tabLabel(tab: TabName) {
  return {
    categories: '维修分类',
    locations: '维修地点',
    workers: '维修员',
    notices: '公告',
  }[tab]
}

function enabledText(value: number) {
  return value === 1 ? '启用' : '停用'
}

function targetRoleText(value: NoticeTargetRole) {
  return {
    ALL: '全部',
    STUDENT: '学生',
    WORKER: '维修员',
    ADMIN: '管理员',
  }[value]
}

function noticeStatusText(status: NoticeStatus) {
  return {
    DISABLED: '已下架',
    NOT_STARTED: '未生效',
    ACTIVE: '有效中',
    EXPIRED: '已过期',
  }[status] || status
}

function noticeStatusTagType(status: NoticeStatus) {
  if (status === 'ACTIVE') return 'success'
  if (status === 'NOT_STARTED') return 'warning'
  if (status === 'EXPIRED') return 'danger'
  return 'info'
}

function validityText(row: Notice) {
  const from = row.effectiveAt ? formatDateTime(row.effectiveAt) : '立即'
  const to = row.expireAt ? formatDateTime(row.expireAt) : '长期'
  return `${from} ~ ${to}`
}

async function loadCurrent() {
  loading.value = true
  try {
    const pager = pageState[activeTab.value]
    if (activeTab.value === 'categories') {
      const result = await listAdminCategoriesApi(pager)
      categories.value = result.items
      pager.total = result.total
    } else if (activeTab.value === 'locations') {
      const result = await listAdminLocationsApi(pager)
      locations.value = result.items
      pager.total = result.total
    } else if (activeTab.value === 'workers') {
      const result = await listAdminWorkersApi(pager)
      workers.value = result.items
      pager.total = result.total
    } else {
      const result = await listAdminNoticesApi(pager)
      notices.value = result.items
      pager.total = result.total
    }
  } finally {
    loading.value = false
  }
}

function resetForms() {
  Object.assign(categoryForm, { name: '', sortOrder: 10, enabled: 1 })
  Object.assign(locationForm, { parentId: null, name: '', sortOrder: 10, enabled: 1 })
  Object.assign(workerForm, {
    username: '',
    password: '',
    realName: '',
    phone: '',
    enabled: 1,
    departmentName: '综合维修组',
    skillTags: [],
    dispatchEnabled: 1,
    maxActiveOrders: 5,
  })
  Object.assign(noticeForm, {
  title: '',
  content: '',
  targetRole: 'ALL',
  published: 1,
  sortOrder: 10,
  effectiveAt: null,
  expireAt: null,
})
}

function openCreate() {
  editingId.value = null
  resetForms()
  drawerVisible.value = true
}

function openEdit(row: AdminCategory | AdminLocation | AdminWorker | Notice) {
  editingId.value = row.id
  resetForms()
  if (activeTab.value === 'categories') {
    const item = row as AdminCategory
    Object.assign(categoryForm, {
      name: item.name,
      sortOrder: item.sortOrder,
      enabled: item.enabled,
    })
  } else if (activeTab.value === 'locations') {
    const item = row as AdminLocation
    Object.assign(locationForm, {
      parentId: item.parentId,
      name: item.name,
      sortOrder: item.sortOrder,
      enabled: item.enabled,
    })
  } else if (activeTab.value === 'workers') {
    const item = row as AdminWorker
    Object.assign(workerForm, {
      username: item.username,
      password: '',
      realName: item.realName,
      phone: item.phone || '',
      enabled: item.enabled,
      departmentName: item.departmentName || '综合维修组',
      skillTags: item.skillTags || [],
      dispatchEnabled: item.dispatchEnabled ?? 1,
      maxActiveOrders: item.maxActiveOrders ?? 5,
    })
  } else {
    const item = row as Notice
    Object.assign(noticeForm, {
      title: item.title,
      content: item.content,
      targetRole: item.targetRole,
      published: item.published,
      sortOrder: item.sortOrder,
      effectiveAt: item.effectiveAt,
      expireAt: item.expireAt,
    })
  }
  drawerVisible.value = true
}

async function submitForm() {
  await formRef.value?.validate()
  if (activeTab.value === 'categories') {
    if (editingId.value) await updateAdminCategoryApi(editingId.value, categoryForm)
    else await createAdminCategoryApi(categoryForm)
  } else if (activeTab.value === 'locations') {
    if (editingId.value) await updateAdminLocationApi(editingId.value, locationForm)
    else await createAdminLocationApi(locationForm)
  } else if (activeTab.value === 'workers') {
    const payload = { ...workerForm }
    if (!payload.password) delete payload.password
    if (editingId.value) await updateAdminWorkerApi(editingId.value, payload)
    else await createAdminWorkerApi(payload)
  } else if (editingId.value) {
    await updateNoticeApi(editingId.value, noticeForm)
  } else {
    await createNoticeApi(noticeForm)
  }
  ElMessage.success('保存成功')
  drawerVisible.value = false
  await loadCurrent()
}

async function removeRow(row: AdminCategory | AdminLocation | AdminWorker | Notice) {
  await ElMessageBox.confirm(`确认删除“${'name' in row ? row.name : row.title}”？`, '删除确认', {
    type: 'warning',
  })
  if (activeTab.value === 'categories') await deleteAdminCategoryApi(row.id)
  else if (activeTab.value === 'locations') await deleteAdminLocationApi(row.id)
  else if (activeTab.value === 'workers') await deleteAdminWorkerApi(row.id)
  else await deleteNoticeApi(row.id)
  ElMessage.success('删除成功')
  await loadCurrent()
}

function handleTabChange() {
  loadCurrent()
}

function handlePageChange(page: number) {
  pageState[activeTab.value].page = page
  loadCurrent()
}

loadCurrent()
</script>

<template>
  <section class="ticket-workspace">
    <div class="section-heading">
      <div>
        <p class="shell__eyebrow">基础数据</p>
        <h2>系统基础管理</h2>
      </div>
      <el-button type="primary" @click="openCreate">新增{{ tabLabel(activeTab) }}</el-button>
    </div>

    <el-tabs v-model="activeTab" class="management-tabs" @tab-change="handleTabChange">
      <el-tab-pane label="维修分类" name="categories" />
      <el-tab-pane label="维修地点" name="locations" />
      <el-tab-pane label="维修员" name="workers" />
      <el-tab-pane label="公告" name="notices" />
    </el-tabs>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="currentRows" empty-text="暂无数据">
        <el-table-column prop="id" label="ID" width="110" />

        <template v-if="activeTab === 'categories'">
          <el-table-column prop="name" label="分类名称" min-width="180" />
          <el-table-column prop="sortOrder" label="排序" width="100" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.enabled === 1 ? 'success' : 'info'">{{ enabledText(row.enabled) }}</el-tag>
            </template>
          </el-table-column>
        </template>

        <template v-else-if="activeTab === 'locations'">
          <el-table-column prop="name" label="地点名称" min-width="180" />
          <el-table-column prop="parentId" label="上级地点" width="120" />
          <el-table-column prop="sortOrder" label="排序" width="100" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.enabled === 1 ? 'success' : 'info'">{{ enabledText(row.enabled) }}</el-tag>
            </template>
          </el-table-column>
        </template>

        <template v-else-if="activeTab === 'workers'">
          <el-table-column prop="username" label="账号" min-width="160" />
          <el-table-column prop="realName" label="姓名" min-width="140" />
          <el-table-column prop="departmentName" label="部门" min-width="130" />
          <el-table-column label="技能" min-width="180">
            <template #default="{ row }">
              <el-tag v-for="tag in row.skillTags" :key="tag" class="tag-gap" size="small">{{ tag }}</el-tag>
              <span v-if="!row.skillTags?.length" class="muted-text">未配置</span>
            </template>
          </el-table-column>
          <el-table-column label="派单" width="110">
            <template #default="{ row }">
              <el-tag :type="row.dispatchEnabled === 1 ? 'success' : 'info'">
                {{ row.dispatchEnabled === 1 ? '参与' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="负载上限" width="100">
            <template #default="{ row }">{{ row.maxActiveOrders }}</template>
          </el-table-column>
          <el-table-column prop="phone" label="电话" min-width="160" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.enabled === 1 ? 'success' : 'info'">{{ enabledText(row.enabled) }}</el-tag>
            </template>
          </el-table-column>
        </template>

        <template v-else>
          <el-table-column prop="title" label="标题" min-width="180" />
          <el-table-column label="可见角色" width="110">
            <template #default="{ row }">{{ targetRoleText(row.targetRole) }}</template>
          </el-table-column>
          <el-table-column label="有效期" min-width="200">
            <template #default="{ row }">{{ validityText(row) }}</template>
          </el-table-column>
          <el-table-column prop="sortOrder" label="排序" width="90" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="noticeStatusTagType(row.status)">{{ noticeStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
        </template>

        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="removeRow(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="table-pagination"
        layout="prev, pager, next, total"
        :current-page="pageState[activeTab].page"
        :page-size="pageState[activeTab].size"
        :total="pageState[activeTab].total"
        @current-change="handlePageChange"
      />
    </el-card>

    <el-drawer v-model="drawerVisible" :title="drawerTitle" size="420px">
      <el-form
        ref="formRef"
        label-position="top"
        :model="
          activeTab === 'categories'
            ? categoryForm
            : activeTab === 'locations'
              ? locationForm
              : activeTab === 'workers'
                ? workerForm
                : noticeForm
        "
        :rules="rules"
      >
        <template v-if="activeTab === 'categories'">
          <el-form-item label="分类名称" prop="name">
            <el-input v-model="categoryForm.name" maxlength="64" />
          </el-form-item>
          <el-form-item label="排序">
            <el-input-number v-model="categoryForm.sortOrder" :min="0" />
          </el-form-item>
          <el-form-item label="状态">
            <el-switch v-model="categoryForm.enabled" :active-value="1" :inactive-value="0" />
          </el-form-item>
        </template>

        <template v-else-if="activeTab === 'locations'">
          <el-form-item label="上级地点">
            <el-select v-model="locationForm.parentId" clearable filterable placeholder="无上级地点">
              <el-option
                v-for="item in locationOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="地点名称" prop="name">
            <el-input v-model="locationForm.name" maxlength="64" />
          </el-form-item>
          <el-form-item label="排序">
            <el-input-number v-model="locationForm.sortOrder" :min="0" />
          </el-form-item>
          <el-form-item label="状态">
            <el-switch v-model="locationForm.enabled" :active-value="1" :inactive-value="0" />
          </el-form-item>
        </template>

        <template v-else-if="activeTab === 'workers'">
          <el-form-item label="账号" prop="username">
            <el-input v-model="workerForm.username" maxlength="64" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="workerForm.password" show-password placeholder="新增默认 123456，编辑留空不改密码" />
          </el-form-item>
          <el-form-item label="姓名" prop="realName">
            <el-input v-model="workerForm.realName" maxlength="64" />
          </el-form-item>
          <el-form-item label="电话">
            <el-input v-model="workerForm.phone" maxlength="32" />
          </el-form-item>
          <el-form-item label="所属部门" prop="departmentName">
            <el-select v-model="workerForm.departmentName">
              <el-option v-for="item in departmentOptions" :key="item" :label="item" :value="item" />
            </el-select>
          </el-form-item>
          <el-form-item label="技能标签">
            <el-select v-model="workerForm.skillTags" multiple collapse-tags collapse-tags-tooltip>
              <el-option v-for="item in skillOptions" :key="item" :label="item" :value="item" />
            </el-select>
          </el-form-item>
          <el-form-item label="参与智能派单">
            <el-switch v-model="workerForm.dispatchEnabled" :active-value="1" :inactive-value="0" />
          </el-form-item>
          <el-form-item label="负载上限">
            <el-input-number v-model="workerForm.maxActiveOrders" :min="1" :max="20" />
          </el-form-item>
          <el-form-item label="状态">
            <el-switch v-model="workerForm.enabled" :active-value="1" :inactive-value="0" />
          </el-form-item>
        </template>

        <template v-else>
          <el-form-item label="标题" prop="title">
            <el-input v-model="noticeForm.title" maxlength="120" />
          </el-form-item>
          <el-form-item label="内容" prop="content">
            <el-input v-model="noticeForm.content" type="textarea" :rows="6" />
          </el-form-item>
          <el-form-item label="可见角色">
            <el-select v-model="noticeForm.targetRole">
              <el-option label="全部" value="ALL" />
              <el-option label="学生" value="STUDENT" />
              <el-option label="维修员" value="WORKER" />
              <el-option label="管理员" value="ADMIN" />
            </el-select>
          </el-form-item>
          <el-form-item label="排序">
            <el-input-number v-model="noticeForm.sortOrder" :min="0" />
          </el-form-item>
          <el-form-item label="生效时间">
            <el-date-picker
              v-model="noticeForm.effectiveAt"
              type="datetime"
              placeholder="留空表示立即生效"
              value-format="YYYY-MM-DDTHH:mm:ss"
              clearable
            />
          </el-form-item>
          <el-form-item label="过期时间">
            <el-date-picker
              v-model="noticeForm.expireAt"
              type="datetime"
              placeholder="留空表示永不过期"
              value-format="YYYY-MM-DDTHH:mm:ss"
              clearable
            />
          </el-form-item>
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="有效期说明"
            description="到期后公告自动不再下发到学生/维修员端，无需手动下架；管理端仍可见并标注「已过期」。"
          />
          <el-form-item label="发布">
            <el-switch v-model="noticeForm.published" :active-value="1" :inactive-value="0" />
          </el-form-item>
        </template>

        <div class="form-actions">
          <el-button type="primary" @click="submitForm">保存</el-button>
          <el-button @click="drawerVisible = false">取消</el-button>
        </div>
      </el-form>
    </el-drawer>
  </section>
</template>
