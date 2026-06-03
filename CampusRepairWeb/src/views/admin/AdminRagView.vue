<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import type { OptionItem } from '@/types/base'
import type { KnowledgeDocument, KnowledgeDocumentRequest } from '@/types/rag'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { listCategoriesApi } from '@/api/base'
import {
  createKnowledgeDocumentApi,
  deleteKnowledgeDocumentApi,
  listKnowledgeDocumentsApi,
  rebuildKnowledgeDocumentApi,
  updateKnowledgeDocumentApi,
} from '@/api/rag'

const loading = ref(false)
const drawerVisible = ref(false)
const submitting = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const categories = ref<OptionItem[]>([])
const documents = ref<KnowledgeDocument[]>([])

const query = reactive({
  page: 1,
  size: 10,
  total: 0,
})

const form = reactive<KnowledgeDocumentRequest>({
  title: '',
  categoryId: null,
  content: '',
  enabled: 1,
})

const rules: FormRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  content: [{ required: true, message: '请输入知识内容', trigger: 'blur' }],
}

onMounted(async () => {
  categories.value = await listCategoriesApi()
  await loadDocuments()
})

async function loadDocuments() {
  loading.value = true
  try {
    const result = await listKnowledgeDocumentsApi(query)
    documents.value = result.items
    query.total = result.total
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { title: '', categoryId: null, content: '', enabled: 1 })
  drawerVisible.value = true
}

function openEdit(row: KnowledgeDocument) {
  editingId.value = row.id
  Object.assign(form, {
    title: row.title,
    categoryId: row.categoryId,
    content: row.content,
    enabled: row.enabled,
  })
  drawerVisible.value = true
}

async function handleSubmit() {
  await formRef.value?.validate()
  submitting.value = true
  try {
    if (editingId.value) await updateKnowledgeDocumentApi(editingId.value, form)
    else await createKnowledgeDocumentApi(form)
    ElMessage.success('保存成功')
    drawerVisible.value = false
    await loadDocuments()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: KnowledgeDocument) {
  await ElMessageBox.confirm(`确认删除“${row.title}”？`, '删除确认', { type: 'warning' })
  await deleteKnowledgeDocumentApi(row.id)
  ElMessage.success('删除成功')
  await loadDocuments()
}

async function handleRebuild(row: KnowledgeDocument) {
  const count = await rebuildKnowledgeDocumentApi(row.id)
  ElMessage.success(`已重建 ${count} 个知识片段`)
  await loadDocuments()
}
</script>

<template>
  <section class="panel">
    <div class="section-heading">
      <div>
        <p class="panel__eyebrow">RAG 知识库</p>
        <h2>维修知识管理</h2>
      </div>
      <el-button type="primary" @click="openCreate">新增知识</el-button>
    </div>

    <el-table :data="documents" v-loading="loading" empty-text="暂无知识文档">
      <el-table-column prop="title" label="标题" min-width="220" />
      <el-table-column label="分类" width="140">
        <template #default="{ row }">
          {{ categories.find((item) => item.id === row.categoryId)?.name || '通用' }}
        </template>
      </el-table-column>
      <el-table-column prop="chunkCount" label="片段数" width="100" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'info'">{{ row.enabled === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="success" @click="handleRebuild(row)">重建</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="table-pagination"
      layout="prev, pager, next, total"
      v-model:current-page="query.page"
      :page-size="query.size"
      :total="query.total"
      @current-change="loadDocuments"
    />

    <el-drawer v-model="drawerVisible" :title="editingId ? '编辑知识' : '新增知识'" size="520px">
      <el-form ref="formRef" label-position="top" :model="form" :rules="rules">
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" maxlength="160" />
        </el-form-item>
        <el-form-item label="关联分类">
          <el-select v-model="form.categoryId" clearable filterable placeholder="通用知识">
            <el-option
              v-for="category in categories"
              :key="category.id"
              :label="category.name"
              :value="category.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="内容" prop="content">
          <el-input v-model="form.content" type="textarea" :rows="12" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <div class="form-actions">
          <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
          <el-button @click="drawerVisible = false">取消</el-button>
        </div>
      </el-form>
    </el-drawer>
  </section>
</template>
