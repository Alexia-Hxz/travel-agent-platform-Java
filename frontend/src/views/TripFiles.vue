<script setup lang="ts">
import { Delete, Download, MagicStick, Refresh, Search, UploadFilled, View } from '@element-plus/icons-vue'
import type { UploadRequestOptions } from 'element-plus'
import { onMounted, reactive, ref, watch } from 'vue'
import WorkspaceDrawer from '@/components/WorkspaceDrawer.vue'

import { confirmVisionAnalysis, deleteTripFile, downloadTripFile, listTripFiles, parseTripFile, previewVisionAnalysis, uploadTripFile } from '@/api/files'
import { getKnowledgeStatus, rebuildKnowledgeIndex, searchKnowledge } from '@/api/knowledge'
import type { TripFile } from '@/types/tripFile'
import type { KnowledgeSearchResult, KnowledgeStatus } from '@/types/knowledge'
import type { VisionItinerarySuggestion, VisionPlaceSuggestion, VisionPreview } from '@/types/vision'
import CollapsiblePanel from '@/components/CollapsiblePanel.vue'
import { defaultOpenForCount } from '@/composables/panelState'

const props = defineProps<{ tripId: number; embedded?: boolean; open?: boolean }>()
const emit = defineEmits<{ 'places-updated': []; 'files-updated': [files: TripFile[]]; 'update:open': [value: boolean] }>()

const files = ref<TripFile[]>([])
watch(files, value => emit('files-updated', value))
const loading = ref(true)
const uploading = ref(false)
const downloadingFileId = ref<number | null>(null)
const errorMessage = ref('')
const resultFile = ref<TripFile | null>(null)
const resultVisible = ref(false)
const visionVisible = ref(false)
const visionLoading = ref(false)
const visionSaving = ref(false)
const visionFile = ref<TripFile | null>(null)
const visionPreview = ref<VisionPreview | null>(null)
const savePlace = ref(false)
const saveItinerary = ref(false)
const placeDraft = reactive<VisionPlaceSuggestion>({ name: '', type: 'attraction', address: '', latitude: null, longitude: null, image_url: null, rating: null, price_level: null, opening_hours: null, source_url: null })
const itineraryDraft = reactive<VisionItinerarySuggestion>({ date: '', start_time: '09:00', end_time: '10:00', note: null })
const maximumFileSize = 10 * 1024 * 1024
// The document index: what is searchable, and a way to ask it a question directly so that
// retrieval is visible rather than only implied by the assistant's wording.
const indexStatus = ref<KnowledgeStatus | null>(null)
const rebuildLoading = ref(false)
const rebuildMessage = ref('')
const searchQuery = ref('')
const searchLoading = ref(false)
const searchResult = ref<KnowledgeSearchResult | null>(null)

async function loadIndexStatus(): Promise<void> {
  try {
    indexStatus.value = await getKnowledgeStatus(props.tripId)
  } catch {
    // A trip whose index cannot be read is not an error worth an alert: the files below
    // still list, parse and download, and the search box reports its own failure.
    indexStatus.value = null
  }
}

async function rebuildIndex(): Promise<void> {
  rebuildLoading.value = true
  errorMessage.value = ''
  rebuildMessage.value = ''
  try {
    const result = await rebuildKnowledgeIndex(props.tripId)
    const parts = [`已重建索引：${result.indexed_files} 份资料、${result.indexed_chunks} 段可检索`]
    if (result.skipped_files > 0) parts.push(`${result.skipped_files} 份因文本过少被跳过`)
    if (result.failed_files > 0) parts.push(`${result.failed_files} 份失败`)
    rebuildMessage.value = `${parts.join('；')}。`
    await Promise.all([loadIndexStatus(), loadFiles()])
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '重建索引失败 / Rebuild failed。'
  } finally {
    rebuildLoading.value = false
  }
}

async function runSearch(): Promise<void> {
  const query = searchQuery.value.trim()
  if (!query) return
  searchLoading.value = true
  errorMessage.value = ''
  searchResult.value = null
  try {
    searchResult.value = await searchKnowledge(props.tripId, query)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '资料检索失败 / Search failed。'
  } finally {
    searchLoading.value = false
  }
}

function indexLabel(file: TripFile): string {
  if (file.index_status === 'indexed') return `已索引 ${file.indexed_chunks} 段`
  if (file.index_status === 'skipped') return '未建立索引（可检索文本过少）'
  if (file.index_status === 'failed') return '索引失败'
  return '尚未索引'
}

async function loadFiles(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    files.value = await listTripFiles(props.tripId)
  } catch {
    errorMessage.value = '无法加载旅行文件 / Unable to load uploaded files。'
  } finally {
    loading.value = false
  }
}

function validateFile(file: File): boolean {
  if (file.size > maximumFileSize) {
    errorMessage.value = '文件不能超过 10 MiB / Files must not exceed 10 MiB。'
    return false
  }
  return true
}

async function download(file: TripFile): Promise<void> {
  downloadingFileId.value = file.id
  errorMessage.value = ''
  try {
    await downloadTripFile(props.tripId, file.id, file.original_filename)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '下载文件失败 / Download failed。'
  } finally {
    downloadingFileId.value = null
  }
}

async function uploadFile(options: UploadRequestOptions): Promise<void> {
  uploading.value = true
  errorMessage.value = ''
  try {
    const uploaded = await uploadTripFile(props.tripId, options.file)
    files.value = [...files.value, uploaded]
    options.onSuccess(uploaded)
  } catch (error) {
    errorMessage.value = error instanceof Error
      ? `上传失败 / Upload failed：${error.message}`
      : '上传失败 / Upload failed，请检查文件格式和大小。'
    const uploadError = Object.assign(error instanceof Error ? error : new Error('上传失败 / Upload failed。'), {
      status: 0,
      method: options.method,
      url: options.action,
    })
    options.onError(uploadError)
  } finally {
    uploading.value = false
  }
}

async function removeFile(fileId: number): Promise<void> {
  errorMessage.value = ''
  try {
    await deleteTripFile(props.tripId, fileId)
    files.value = files.value.filter((file) => file.id !== fileId)
    // Deleting the file also removes its vectors; the summary must not keep counting them.
    await loadIndexStatus()
  } catch {
    errorMessage.value = '删除文件失败 / Unable to delete this file。'
  }
}

async function parseFile(file: TripFile): Promise<void> {
  try {
    const parsed = await parseTripFile(props.tripId, file.id)
    files.value = files.value.map((item) => (item.id === parsed.id ? parsed : item))
    resultFile.value = parsed
    resultVisible.value = true
    // Parsing indexes the document, so the summary above the list is now out of date.
    await loadIndexStatus()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '解析文件失败 / Unable to parse this file。'
  }
}

function supportsVision(file: TripFile): boolean {
  return file.content_type.startsWith('image/')
}

async function analyzeWithAgent(file: TripFile): Promise<void> {
  visionLoading.value = true
  errorMessage.value = ''
  visionFile.value = file
  visionPreview.value = null
  try {
    const preview = await previewVisionAnalysis(props.tripId, file.id)
    visionPreview.value = preview
    savePlace.value = preview.place !== null
    saveItinerary.value = preview.place !== null && preview.itinerary !== null
    Object.assign(placeDraft, preview.place ?? { name: '', type: 'attraction', address: '', latitude: null, longitude: null, image_url: null, rating: null, price_level: null, opening_hours: null, source_url: null })
    Object.assign(itineraryDraft, preview.itinerary ?? { date: '', start_time: '09:00', end_time: '10:00', note: null })
    visionVisible.value = true
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '图片分析失败 / Unable to analyze this image。'
  } finally {
    visionLoading.value = false
  }
}

function updateItinerarySelection(value: boolean): void {
  if (value) savePlace.value = true
}

async function saveVisionSuggestions(): Promise<void> {
  if (!visionFile.value || !visionPreview.value) return
  visionSaving.value = true
  errorMessage.value = ''
  try {
    await confirmVisionAnalysis(props.tripId, visionFile.value.id, {
      save_place: savePlace.value,
      save_itinerary: saveItinerary.value,
      place: savePlace.value ? { ...placeDraft } : null,
      itinerary: saveItinerary.value ? { ...itineraryDraft, note: itineraryDraft.note?.trim() || null } : null,
    })
    visionVisible.value = false
    emit('places-updated')
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '保存建议失败 / Unable to save the reviewed suggestions。'
  } finally {
    visionSaving.value = false
  }
}

function formatFileSize(size: number): string {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KiB`
  return `${(size / (1024 * 1024)).toFixed(1)} MiB`
}

onMounted(() => {
  loadFiles()
  loadIndexStatus()
})
</script>

<template>
  <WorkspaceDrawer :enabled="embedded ?? false" :model-value="open ?? false" title="上传资料" @update:model-value="emit('update:open', $event)">
  <section class="files-section" aria-label="旅行文件 / Uploaded files">
    <CollapsiblePanel
      class="files-panel"
      panel-key="files"
      :scope="tripId"
      label="TRAVEL FILES · 旅行文件"
      title="旅行文件 / Travel files"
      heading-tag="h2"
      :count="files.length > 0 ? `${files.length} 个文件` : null"
      :default-open="defaultOpenForCount(files.length)"
      :auto-open-token="files.length"
    >
      <template #actions>
        <el-button :icon="Refresh" :loading="rebuildLoading" @click="rebuildIndex">重建索引 / Rebuild index</el-button>
        <el-upload
          :show-file-list="false"
          :http-request="uploadFile"
          :before-upload="validateFile"
          accept=".jpg,.jpeg,.png,.webp,.pdf"
        >
          <el-button type="primary" :icon="UploadFilled" :loading="uploading">上传文件 / Upload file</el-button>
        </el-upload>
      </template>
      <template #status>
        <p v-if="errorMessage" class="error-message" role="alert">{{ errorMessage }}</p>
      </template>

      <p v-if="indexStatus" class="index-summary">
        <template v-if="indexStatus.available">
          向量检索 / Semantic index：{{ indexStatus.indexed_files }} 份资料 · {{ indexStatus.indexed_chunks }} 段可检索 ·
          {{ indexStatus.embedding_model }}（{{ indexStatus.embedding_dim }} 维 · {{ indexStatus.backend }}）
        </template>
        <template v-else>向量检索 / Semantic index：不可用</template>
      </p>
      <p v-if="indexStatus && !indexStatus.available" class="error-message" role="alert">
        向量库不可用，上传的资料暂时无法被检索到：{{ indexStatus.error }}
      </p>
      <p v-if="rebuildMessage" class="index-summary">{{ rebuildMessage }}</p>

      <div class="knowledge-search">
        <el-input
          v-model="searchQuery"
          placeholder="直接检索已上传的资料 / Search your uploaded documents"
          clearable
          @keyup.enter="runSearch"
        />
        <el-button type="primary" :icon="Search" :loading="searchLoading" @click="runSearch">检索 / Search</el-button>
      </div>
      <div v-if="searchResult" class="knowledge-results">
        <p v-if="searchResult.status !== 'ok'" class="empty-state">{{ searchResult.reason ?? '没有找到相关资料片段。' }}</p>
        <article v-for="chunk in searchResult.chunks" :key="chunk.chunk_id" class="knowledge-chunk">
          <p class="knowledge-source">
            {{ chunk.source_label }} · 第 {{ chunk.chunk_index + 1 }} 段 · 相似度 {{ chunk.similarity.toFixed(3) }}
          </p>
          <pre>{{ chunk.text }}</pre>
        </article>
      </div>

      <p v-if="loading" class="empty-state">正在加载文件 / Loading files...</p>
      <p v-else-if="files.length === 0" class="empty-state">还没有上传文件 / No files have been uploaded.</p>
      <div v-else class="file-list">
        <article v-for="file in files" :key="file.id" class="file-row">
          <div>
            <h3>{{ file.original_filename }}</h3>
            <p>{{ file.content_type }} · {{ formatFileSize(file.file_size) }}</p>
            <p>解析状态 / Parse status：{{ file.parse_status }}</p>
            <p>
              索引状态 / Index：{{ indexLabel(file) }}<span v-if="file.index_error"> · {{ file.index_error }}</span>
            </p>
          </div>
          <div class="file-actions">
            <el-button
              text
              type="primary"
              :icon="Download"
              :loading="downloadingFileId === file.id"
              title="下载文件 / Download file"
              aria-label="下载文件 / Download file"
              @click="download(file)"
            >下载</el-button>
            <el-button text type="primary" :icon="View" @click="parseFile(file)">解析文件 / Parse file</el-button>
            <el-button v-if="supportsVision(file)" text type="primary" :icon="MagicStick" :loading="visionLoading && visionFile?.id === file.id" @click="analyzeWithAgent(file)">图片 Agent 分析 / Analyze with agent</el-button>
            <el-button circle text type="danger" :icon="Delete" title="删除文件 / Delete file" aria-label="删除文件 / Delete file" @click="removeFile(file.id)" />
          </div>
        </article>
      </div>
    </CollapsiblePanel>
    <el-dialog v-model="resultVisible" title="解析结果 / Parsed file result" width="min(680px, calc(100vw - 32px))">
      <template v-if="resultFile"><pre v-if="resultFile.parsed_text">{{ resultFile.parsed_text }}</pre><dl v-if="resultFile.parsed_result"><template v-for="(values, key) in resultFile.parsed_result" :key="key"><dt>{{ key }}</dt><dd>{{ values.join(', ') || '-' }}</dd></template></dl></template>
    </el-dialog>
    <el-dialog v-model="visionVisible" title="多模态 Agent 预览 / Multimodal agent preview" width="min(680px, calc(100vw - 32px))">
      <template v-if="visionPreview">
        <p class="vision-summary">{{ visionPreview.summary }}</p>
        <p class="vision-meta">识别类型 / Detected type：{{ visionPreview.document_type }}<span v-if="visionPreview.reference_number"> · 编号 / Reference：{{ visionPreview.reference_number }}</span></p>
        <el-checkbox v-if="visionPreview.place" v-model="savePlace">保存地点 / Save place</el-checkbox>
        <div v-if="savePlace && visionPreview.place" class="vision-form">
          <div class="form-grid"><el-input v-model="placeDraft.name" aria-label="Suggested place name" /><el-select v-model="placeDraft.type"><el-option label="Attraction" value="attraction" /><el-option label="Hotel" value="hotel" /><el-option label="Restaurant" value="restaurant" /><el-option label="Airport" value="airport" /><el-option label="Station" value="station" /></el-select></div>
          <el-input v-model="placeDraft.address" aria-label="Suggested place address" />
        </div>
        <el-checkbox v-if="visionPreview.itinerary" v-model="saveItinerary" :disabled="!savePlace" @change="updateItinerarySelection">保存行程 / Save itinerary item</el-checkbox>
        <div v-if="saveItinerary && visionPreview.itinerary" class="vision-form">
          <div class="form-grid"><el-date-picker v-model="itineraryDraft.date" type="date" value-format="YYYY-MM-DD" /><el-time-picker v-model="itineraryDraft.start_time" value-format="HH:mm" /><el-time-picker v-model="itineraryDraft.end_time" value-format="HH:mm" /></div>
          <el-input v-model="itineraryDraft.note" placeholder="行程备注 / Itinerary note" />
        </div>
        <p v-if="visionPreview.raw_text" class="vision-text">{{ visionPreview.raw_text }}</p>
      </template>
      <template #footer><el-button @click="visionVisible = false">取消 / Cancel</el-button><el-button type="primary" :loading="visionSaving" :disabled="!savePlace && !saveItinerary" @click="saveVisionSuggestions">确认保存 / Confirm save</el-button></template>
    </el-dialog>
  </section>
  </WorkspaceDrawer>
</template>

<style scoped>
.files-section { max-width: 1120px; margin: 40px auto 0; padding-top: 32px; border-top: 1px solid #e7e9ee; }
.section-heading { display: flex; align-items: end; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.section-label { margin: 0 0 6px; color: #6b7890; font-size: 12px; font-weight: 700; letter-spacing: 0; }
h2, h3, p { margin-top: 0; }
h2 { margin-bottom: 0; font-size: 22px; }
.empty-state { padding: 20px 0; margin-bottom: 0; color: #6b7280; }
.error-message { margin-bottom: 16px; padding: 12px 16px; border-left: 3px solid #c94747; background: #fff4f3; color: #9f2f2f; }
.file-list { border-top: 1px solid #e7e9ee; }
.file-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px 0; border-bottom: 1px solid #e7e9ee; }
.file-row h3 { margin-bottom: 5px; font-size: 16px; }
.file-row p { margin-bottom: 0; color: #6b7280; font-size: 14px; }
.file-actions { display: flex; align-items: center; gap: 4px; }
.file-actions a { display: inline-flex; padding: 8px; color: #246bfd; font-size: 18px; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; padding: 12px; background: #f1f4f1; } dt { font-weight: 700; } dd { margin: 4px 0 10px; }
.vision-summary { margin-bottom: 6px; font-weight: 600; } .vision-meta, .vision-text { color: #6b7280; font-size: 14px; } .vision-form { display: grid; gap: 12px; margin: 12px 0 18px; } .form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; } .form-grid :deep(.el-date-editor), .form-grid :deep(.el-select) { width: 100%; }
.index-summary { margin-bottom: 12px; color: #4b5563; font-size: 14px; }
.knowledge-search { display: flex; gap: 8px; margin: 4px 0 16px; }
.knowledge-results { margin-bottom: 16px; }
.knowledge-chunk { padding: 10px 0; border-bottom: 1px solid #e7e9ee; }
.knowledge-source { margin-bottom: 6px; color: #246bfd; font-size: 13px; }
.knowledge-chunk pre { margin-bottom: 0; }
</style>
