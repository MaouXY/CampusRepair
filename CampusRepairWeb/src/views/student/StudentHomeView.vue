<script setup lang="ts">
import { ElMessage } from 'element-plus'
import type { UploadUserFile } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { listCategoriesApi, listLocationTreeApi } from '@/api/base'
import { uploadFileApi } from '@/api/file'
import { createStudentTicketApi } from '@/api/ticket'
import type { LocationTreeItem, OptionItem } from '@/types/base'
import type { TicketCreateRequest } from '@/types/ticket'

const MAX_IMAGE_SIZE = 10 * 1024 * 1024
const ALLOWED_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']

const router = useRouter()
const categories = ref<OptionItem[]>([])
const locations = ref<LocationTreeItem[]>([])
const imageFiles = ref<UploadUserFile[]>([])
const submitting = ref(false)

const form = reactive<TicketCreateRequest>({
  locationId: null,
  categoryId: null,
  description: '',
  contactPhone: '',
})

onMounted(async () => {
  const [categoryResult, locationResult] = await Promise.all([
    listCategoriesApi(),
    listLocationTreeApi(),
  ])
  categories.value = categoryResult
  locations.value = locationResult
})

async function handleSubmit() {
  submitting.value = true
  try {
    const files = collectSelectedImages()
    if (!files) {
      return
    }
    const uploadedFileIds: string[] = []

    for (const file of files) {
      const uploaded = await uploadFileApi(file)
      uploadedFileIds.push(uploaded.id)
    }

    const payload: TicketCreateRequest = {
      ...form,
      reportImageFileIds: uploadedFileIds.length > 0 ? uploadedFileIds : undefined,
    }
    const detail = await createStudentTicketApi(payload)

    ElMessage.success(
      uploadedFileIds.length > 0
        ? '报修和图片已提交，等待管理员审核'
        : '报修提交成功，等待管理员审核',
    )
    await router.push(`/student/tickets/${detail.id}`)
  } finally {
    submitting.value = false
  }
}

function collectSelectedImages() {
  const files = imageFiles.value
    .map((file) => file.raw)
    .filter((file): file is File => file instanceof File)

  for (const file of files) {
    if (!validateImage(file)) {
      return null
    }
  }
  return files
}

function beforeImageSelect(file: File) {
  return validateImage(file)
}

function validateImage(file: File) {
  if (!ALLOWED_IMAGE_TYPES.includes(file.type)) {
    ElMessage.warning('只能上传 JPG、PNG、GIF 或 WebP 图片')
    return false
  }
  if (file.size > MAX_IMAGE_SIZE) {
    ElMessage.warning('单张图片不能超过 10MB')
    return false
  }
  return true
}
</script>

<template>
  <section class="ticket-workspace">
    <article class="panel">
      <div class="section-heading">
        <div>
          <p class="panel__eyebrow">学生服务</p>
          <h2>提交报修</h2>
        </div>
        <el-button @click="router.push('/student/tickets')">我的工单</el-button>
      </div>

      <el-form label-position="top" @submit.prevent="handleSubmit">
        <div class="form-grid">
          <el-form-item label="报修地点" required>
            <el-cascader
              v-model="form.locationId"
              :options="locations"
              :props="{ value: 'id', label: 'name', children: 'children', emitPath: false }"
              filterable
              clearable
              placeholder="请选择地点"
            />
          </el-form-item>
          <el-form-item label="报修分类" required>
            <el-select
              v-model="form.categoryId"
              filterable
              placeholder="请选择分类"
            >
              <el-option
                v-for="category in categories"
                :key="category.id"
                :label="category.name"
                :value="category.id"
              />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="联系电话" required>
          <el-input v-model="form.contactPhone" placeholder="请输入联系电话" />
        </el-form-item>
        <el-form-item label="故障描述" required>
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="6"
            maxlength="1000"
            show-word-limit
            placeholder="请描述故障现象、具体位置和影响范围"
          />
        </el-form-item>
        <el-form-item label="故障图片">
          <el-upload
            class="ticket-image-upload"
            v-model:file-list="imageFiles"
            drag
            multiple
            :auto-upload="false"
            :limit="5"
            :before-upload="beforeImageSelect"
            accept="image/jpeg,image/png,image/gif,image/webp"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖入图片或点击选择</div>
            <template #tip>
              <div class="ticket-image-upload__tip">
                最多 5 张，支持 JPG、PNG、GIF、WebP，单张不超过 10MB。图片全部上传成功后才会创建工单。
              </div>
            </template>
          </el-upload>
        </el-form-item>
        <div class="form-actions">
          <el-button
            type="primary"
            :loading="submitting"
            @click="handleSubmit"
          >
            提交报修
          </el-button>
        </div>
      </el-form>
    </article>
  </section>
</template>
