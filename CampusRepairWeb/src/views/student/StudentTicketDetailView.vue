<script setup lang="ts">
import { ElMessage } from 'element-plus'
import type { UploadUserFile } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { listCategoriesApi, listLocationTreeApi } from '@/api/base'
import { listTicketFilesApi, uploadFileApi } from '@/api/file'
import {
  evaluateTicketApi,
  getStudentTicketApi,
  requestReworkTicketApi,
  resubmitStudentTicketApi,
} from '@/api/ticket'
import type { LocationTreeItem, OptionItem } from '@/types/base'
import type {
  TicketCreateRequest,
  TicketDetail,
  TicketEvaluationRequest,
  TicketReworkRequest,
} from '@/types/ticket'
import {
  formatDateTime,
  formatFlowAction,
  formatTicketPriority,
  formatTicketStatus,
  parseImageUrls,
} from '@/utils/ticketDisplay'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const submitting = ref(false)
const detail = ref<TicketDetail | null>(null)
const categories = ref<OptionItem[]>([])
const locations = ref<LocationTreeItem[]>([])
const reportFiles = ref<string[]>([])
const resultFiles = ref<string[]>([])
const resubmitImageFiles = ref<UploadUserFile[]>([])
const resubmitImagesTouched = ref(false)

const ticketId = computed(() => String(route.params.id))
const canEvaluate = computed(() => detail.value?.status === 'WAITING_CONFIRM')
const canResubmit = computed(() => detail.value?.status === 'REJECTED')
const reportImageUrls = computed(() => (
  reportFiles.value.length > 0 ? reportFiles.value : parseImageUrls(detail.value?.reportImageUrls)
))
const resultImageUrls = computed(() => (
  resultFiles.value.length > 0 ? resultFiles.value : parseImageUrls(detail.value?.resultImageUrls)
))

const evaluationForm = reactive<TicketEvaluationRequest>({
  score: 5,
  content: '',
})

const reworkForm = reactive<TicketReworkRequest>({
  reason: '',
})

const resubmitForm = reactive<TicketCreateRequest>({
  locationId: null,
  categoryId: null,
  description: '',
  contactPhone: '',
  reportImageFileIds: undefined,
})

onMounted(async () => {
  await Promise.all([loadOptions(), loadDetail()])
})

async function loadOptions() {
  const [categoryResult, locationResult] = await Promise.all([
    listCategoriesApi(),
    listLocationTreeApi(),
  ])
  categories.value = categoryResult
  locations.value = locationResult
}

async function loadDetail() {
  loading.value = true
  try {
    detail.value = await getStudentTicketApi(ticketId.value)
    const [reportImageFiles, resultImageFiles] = await Promise.all([
      listTicketFilesApi(ticketId.value, 'TICKET_REPORT_IMAGE'),
      listTicketFilesApi(ticketId.value, 'TICKET_RESULT_IMAGE'),
    ])
    reportFiles.value = reportImageFiles.map((file) => file.publicUrl).filter(Boolean)
    resultFiles.value = resultImageFiles.map((file) => file.publicUrl).filter(Boolean)
    resubmitForm.locationId = detail.value.locationId
    resubmitForm.categoryId = detail.value.categoryId
    resubmitForm.description = detail.value.description
    resubmitForm.contactPhone = detail.value.contactPhone
  } finally {
    loading.value = false
  }
}

async function handleRework() {
  if (!reworkForm.reason.trim()) {
    ElMessage.warning('请填写需要继续处理的原因')
    return
  }
  submitting.value = true
  try {
    detail.value = await requestReworkTicketApi(ticketId.value, {
      reason: reworkForm.reason.trim(),
    })
    reworkForm.reason = ''
    ElMessage.success('已申请继续处理，维修员会在原工单上继续处理')
  } finally {
    submitting.value = false
  }
}

async function handleEvaluate() {
  submitting.value = true
  try {
    detail.value = await evaluateTicketApi(ticketId.value, evaluationForm)
    ElMessage.success('评价已提交，工单已完成')
  } finally {
    submitting.value = false
  }
}

async function handleResubmit() {
  submitting.value = true
  try {
    const payload: TicketCreateRequest = { ...resubmitForm }
    if (resubmitImagesTouched.value) {
      const files = resubmitImageFiles.value
        .map((file) => file.raw)
        .filter((file): file is File => file instanceof File)
      const uploadedFiles = []
      for (const file of files) {
        uploadedFiles.push(await uploadFileApi(file))
      }
      payload.reportImageFileIds = uploadedFiles.map((file) => file.id)
    } else {
      payload.reportImageFileIds = undefined
    }
    await resubmitStudentTicketApi(ticketId.value, payload)
    await loadDetail()
    resubmitImagesTouched.value = false
    resubmitImageFiles.value = []
    ElMessage.success('已重新提交，等待管理员审核')
  } finally {
    submitting.value = false
  }
}

function beforeImageSelect(file: File) {
  const allowedTypes = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
  if (!allowedTypes.includes(file.type)) {
    ElMessage.warning('只能上传 JPG、PNG、GIF 或 WebP 图片')
    return false
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('单张图片不能超过 5MB')
    return false
  }
  return true
}

function markResubmitImagesTouched() {
  resubmitImagesTouched.value = true
}

</script>

<template>
  <section class="panel" v-loading="loading">
    <div class="section-heading">
      <div>
        <p class="panel__eyebrow">工单详情</p>
        <h2>{{ detail?.summary || '工单详情' }}</h2>
      </div>
      <el-button @click="router.push('/student/tickets')">返回列表</el-button>
    </div>

    <template v-if="detail">
      <div class="detail-grid">
        <span>状态：{{ formatTicketStatus(detail.status) }}</span>
        <span>优先级：{{ formatTicketPriority(detail.priority) }}</span>
        <span>地点：{{ detail.locationName }}</span>
        <span>分类：{{ detail.categoryName }}</span>
        <span>维修员：{{ detail.assignedWorkerName || '未派单' }}</span>
        <span>联系电话：{{ detail.contactPhone }}</span>
      </div>
      <el-descriptions border :column="1" class="detail-block">
        <el-descriptions-item label="故障描述">{{ detail.description }}</el-descriptions-item>
        <el-descriptions-item label="驳回原因" v-if="detail.rejectReason">
          {{ detail.rejectReason }}
        </el-descriptions-item>
        <el-descriptions-item label="维修结果" v-if="detail.processResult">
          {{ detail.processResult }}
        </el-descriptions-item>
        <el-descriptions-item label="维修备注" v-if="detail.processRemark">
          {{ detail.processRemark }}
        </el-descriptions-item>
      </el-descriptions>

      <article v-if="canResubmit" class="inline-form">
        <h3>修改后重新提交</h3>
        <div class="form-grid">
          <el-form-item label="报修地点" required>
            <el-cascader
              v-model="resubmitForm.locationId"
              :options="locations"
              :props="{ value: 'id', label: 'name', children: 'children', emitPath: false }"
              filterable
              clearable
            />
          </el-form-item>
          <el-form-item label="报修分类" required>
            <el-select v-model="resubmitForm.categoryId" filterable>
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
          <el-input v-model="resubmitForm.contactPhone" maxlength="32" />
        </el-form-item>
        <el-form-item label="故障描述" required>
          <el-input
            v-model="resubmitForm.description"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="报修图片">
          <el-upload
            class="ticket-image-upload"
            v-model:file-list="resubmitImageFiles"
            drag
            multiple
            :auto-upload="false"
            :limit="5"
            :before-upload="beforeImageSelect"
            accept="image/jpeg,image/png,image/gif,image/webp"
            @change="markResubmitImagesTouched"
            @remove="markResubmitImagesTouched"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖入新图片或点击选择</div>
            <template #tip>
              <div class="ticket-image-upload__tip">
                未重新选择时保留原报修图片；选择新图片后将替换原图片。最多 5 张，单张不超过 5MB。
              </div>
            </template>
          </el-upload>
        </el-form-item>
        <el-button type="primary" :loading="submitting" @click="handleResubmit">
          重新提交
        </el-button>
      </article>

      <article class="image-viewer">
        <h3>报修图片</h3>
        <el-empty v-if="reportImageUrls.length === 0" description="暂无报修图片" />
        <div v-else class="ticket-image-grid">
          <el-image
            v-for="url in reportImageUrls"
            :key="url"
            class="ticket-image"
            :src="url"
            :preview-src-list="reportImageUrls"
            fit="cover"
          />
        </div>
      </article>

      <article v-if="resultImageUrls.length > 0" class="image-viewer">
        <h3>维修完成图片</h3>
        <div class="ticket-image-grid">
          <el-image
            v-for="url in resultImageUrls"
            :key="url"
            class="ticket-image"
            :src="url"
            :preview-src-list="resultImageUrls"
            fit="cover"
          />
        </div>
      </article>

      <h3>流转记录</h3>
      <el-timeline>
        <el-timeline-item
          v-for="flow in detail.flows"
          :key="flow.id"
          :timestamp="formatDateTime(flow.createdAt)"
        >
          {{ formatFlowAction(flow.action) }}：{{ formatTicketStatus(flow.fromStatus) }} -> {{ formatTicketStatus(flow.toStatus) }}
          <p v-if="flow.remark">{{ flow.remark }}</p>
        </el-timeline-item>
      </el-timeline>

      <article v-if="canEvaluate" class="inline-form">
        <h3>确认维修结果</h3>
        <el-rate v-model="evaluationForm.score" />
        <el-input
          v-model="evaluationForm.content"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="请输入评价内容"
        />
        <div class="form-actions">
          <el-button type="primary" :loading="submitting" @click="handleEvaluate">
            确认完成并评价
          </el-button>
        </div>
        <el-input
          v-model="reworkForm.reason"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="如果维修未完成，请填写需要继续处理的原因"
        />
        <div class="form-actions">
          <el-button :loading="submitting" @click="handleRework">
            申请继续处理
          </el-button>
        </div>
      </article>

      <el-alert
        v-if="detail.evaluation"
        :title="`已评价：${detail.evaluation.score} 分`"
        :description="`${detail.evaluation.content || '未填写文字评价'} · ${formatDateTime(detail.evaluation.createdAt)}`"
        type="success"
        show-icon
        :closable="false"
      />
    </template>
  </section>
</template>
