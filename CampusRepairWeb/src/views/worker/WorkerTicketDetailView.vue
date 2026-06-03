<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  bindTicketResultImagesApi,
  listTicketFilesApi,
  uploadFileApi,
} from '@/api/file'
import {
  acceptTicketApi,
  getWorkerTicketApi,
  returnWorkerTicketApi,
  submitTicketResultApi,
} from '@/api/ticket'
import type { TicketDetail, TicketResultRequest } from '@/types/ticket'
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
const uploading = ref(false)
const detail = ref<TicketDetail | null>(null)
const reportFiles = ref<string[]>([])
const resultFiles = ref<string[]>([])

const ticketId = computed(() => String(route.params.id))
const canAccept = computed(() => detail.value?.status === 'ASSIGNED')
const canSubmitResult = computed(() => detail.value?.status === 'PROCESSING')
const canReturn = computed(() => detail.value?.status === 'ASSIGNED')
const reportImageUrls = computed(() => (
  reportFiles.value.length > 0 ? reportFiles.value : parseImageUrls(detail.value?.reportImageUrls)
))
const resultImageUrls = computed(() => (
  resultFiles.value.length > 0 ? resultFiles.value : parseImageUrls(detail.value?.resultImageUrls)
))

const resultForm = reactive<TicketResultRequest>({
  result: '',
  remark: '',
})

onMounted(loadDetail)

async function loadDetail() {
  loading.value = true
  try {
    detail.value = await getWorkerTicketApi(ticketId.value)
    const [reportImageFiles, resultImageFiles] = await Promise.all([
      listTicketFilesApi(ticketId.value, 'TICKET_REPORT_IMAGE'),
      listTicketFilesApi(ticketId.value, 'TICKET_RESULT_IMAGE'),
    ])
    reportFiles.value = reportImageFiles.map((file) => file.publicUrl).filter(Boolean)
    resultFiles.value = resultImageFiles.map((file) => file.publicUrl).filter(Boolean)
  } finally {
    loading.value = false
  }
}

async function handleAccept() {
  await ElMessageBox.confirm('确认接单并开始处理该工单？', '接单确认', {
    confirmButtonText: '确认接单',
    cancelButtonText: '取消',
    type: 'warning',
  })
  submitting.value = true
  try {
    detail.value = await acceptTicketApi(ticketId.value)
    ElMessage.success('已接单，工单进入处理中')
  } finally {
    submitting.value = false
  }
}

async function handleSubmitResult() {
  submitting.value = true
  try {
    detail.value = await submitTicketResultApi(ticketId.value, resultForm)
    ElMessage.success('处理结果已提交，等待学生评价')
  } finally {
    submitting.value = false
  }
}

async function handleReturn() {
  const { value } = await ElMessageBox.prompt('请输入退回原因', '退回工单', {
    confirmButtonText: '确认退回',
    cancelButtonText: '取消',
    inputType: 'textarea',
    inputValidator: (input) => !!input?.trim() || '退回原因不能为空',
  })
  submitting.value = true
  try {
    await returnWorkerTicketApi(ticketId.value, { reason: value })
    ElMessage.success('已退回管理员重新处理')
    await router.push('/worker/tickets')
  } finally {
    submitting.value = false
  }
}

async function handleResultUpload(options: { file: File }) {
  uploading.value = true
  try {
    const uploaded = await uploadFileApi(options.file)
    await bindTicketResultImagesApi(ticketId.value, [uploaded.id])
    await loadDetail()
    ElMessage.success('处理图片已上传')
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <section class="panel" v-loading="loading">
    <div class="section-heading">
      <div>
        <p class="panel__eyebrow">维修工单详情</p>
        <h2>{{ detail?.summary || '维修工单详情' }}</h2>
      </div>
      <div class="section-actions">
        <el-button
          v-if="canAccept"
          type="primary"
          :loading="submitting"
          @click="handleAccept"
        >
          接单处理
        </el-button>
        <el-button
          v-if="canReturn"
          type="warning"
          :loading="submitting"
          @click="handleReturn"
        >
          退回工单
        </el-button>
        <el-button @click="router.push('/worker/tickets')">返回列表</el-button>
      </div>
    </div>

    <template v-if="detail">
      <div class="detail-grid">
        <span>状态：{{ formatTicketStatus(detail.status) }}</span>
        <span>优先级：{{ formatTicketPriority(detail.priority) }}</span>
        <span>学生：{{ detail.studentName }}</span>
        <span>地点：{{ detail.locationName }}</span>
        <span>分类：{{ detail.categoryName }}</span>
        <span>联系电话：{{ detail.contactPhone }}</span>
        <span>SLA：{{ detail.slaOverdue ? '已超时' : formatDateTime(detail.slaDeadlineAt) }}</span>
      </div>
      <el-descriptions border :column="1" class="detail-block">
        <el-descriptions-item label="故障描述">{{ detail.description }}</el-descriptions-item>
        <el-descriptions-item label="维修结果" v-if="detail.processResult">
          {{ detail.processResult }}
        </el-descriptions-item>
        <el-descriptions-item label="维修备注" v-if="detail.processRemark">
          {{ detail.processRemark }}
        </el-descriptions-item>
      </el-descriptions>

      <article v-if="canSubmitResult" class="inline-form">
        <h3>提交处理结果</h3>
        <el-upload
          drag
          :show-file-list="false"
          :http-request="handleResultUpload"
          accept="image/jpeg,image/png,image/gif,image/webp"
          :disabled="uploading"
        >
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">拖入处理图片或点击上传</div>
        </el-upload>
        <el-input
          v-model="resultForm.result"
          maxlength="500"
          placeholder="请输入处理结果"
        />
        <el-input
          v-model="resultForm.remark"
          type="textarea"
          :rows="4"
          maxlength="1000"
          show-word-limit
          placeholder="请输入维修备注"
        />
        <el-button type="primary" :loading="submitting" @click="handleSubmitResult">
          提交结果
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
        <h3>处理图片</h3>
        <el-empty v-if="resultImageUrls.length === 0" description="暂无处理图片" />
        <div v-else class="ticket-image-grid">
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
    </template>
  </section>
</template>
