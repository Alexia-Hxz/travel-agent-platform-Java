<script setup lang="ts">
import { Plus, ArrowRight, Calendar, Location, Delete, Search } from '@element-plus/icons-vue'
import { computed, onMounted, reactive, ref } from 'vue'

import { createTrip, deleteTrip, listTrips } from '@/api/trips'
import { useAuth } from '@/composables/useAuth'
import type { Trip, TripCreate } from '@/types/trip'
import { confirmDestructive } from '@/utils/confirm'
import { isDateBefore } from '@/utils/date'

const props = withDefaults(defineProps<{ search?: string }>(), { search: '' })
const emit = defineEmits<{ selectTrip: [trip: Trip]; signOut: [] }>()

const { user } = useAuth()
// Whoever registered with an email sees that; a username account sees the username. The
// point is only that the person can tell which account they are looking at.
const signedInAs = computed(() => {
  const account = user.value
  if (account === null) return ''
  return account.display_name ?? account.username ?? account.email ?? account.phone ?? `账号 #${account.id}`
})

const trips = ref<Trip[]>([])
const filteredTrips = computed(() => trips.value.filter(trip => `${trip.title} ${trip.destination}`.toLowerCase().includes(props.search.toLowerCase())))
const loading = ref(true)
const submitting = ref(false)
const errorMessage = ref('')
const dialogVisible = ref(false)

const form = reactive<TripCreate>({
  title: '',
  destination: '',
  start_date: '',
  end_date: '',
  budget: null,
  adult_male_count: 0,
  adult_female_count: 0,
  child_male_count: 0,
  child_female_count: 0,
})

async function loadTrips(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    trips.value = await listTrips()
  } catch {
    errorMessage.value = '暂时无法加载旅行项目，请确认后端服务已启动。'
  } finally {
    loading.value = false
  }
}

function openCreateDialog(): void {
  Object.assign(form, { title: '', destination: '', start_date: '', end_date: '', budget: null, adult_male_count: 0, adult_female_count: 0, child_male_count: 0, child_female_count: 0 })
  dialogVisible.value = true
}

function isEndDateDisabled(value: Date): boolean {
  // Local calendar fields, NOT toISOString(): a UTC comparison blocked the start date
  // itself, so a one-day trip could not be created. See src/utils/date.ts.
  return Boolean(form.start_date) && isDateBefore(value, form.start_date)
}

async function submitTrip(): Promise<void> {
  if (!form.title || !form.destination || !form.start_date || !form.end_date) {
    errorMessage.value = '请填写旅行名称、目的地和日期。'
    return
  }
  if (form.end_date < form.start_date) {
    errorMessage.value = '结束日期不能早于出发日期。'
    return
  }

  submitting.value = true
  errorMessage.value = ''
  try {
    const trip = await createTrip({ ...form })
    trips.value = [...trips.value, trip]
    dialogVisible.value = false
  } catch {
    errorMessage.value = '创建旅行失败，请检查日期和后端服务。'
  } finally {
    submitting.value = false
  }
}

async function removeTrip(trip: Trip): Promise<void> {
  // Deleting a trip cascades to its places, itinerary items, files and adjustment
  // drafts, and there is no undo, so the consequence is stated before it happens.
  const confirmed = await confirmDestructive(
    `删除「${trip.title}」？`,
    '收藏地点、每日行程、已上传文件和调整记录都会一并删除，且无法恢复。',
    '确认删除',
  )
  if (!confirmed) return

  errorMessage.value = ''
  try {
    await deleteTrip(trip.id)
    trips.value = trips.value.filter((item) => item.id !== trip.id)
  } catch {
    errorMessage.value = '删除旅行失败，请稍后重试。'
  }
}

function formatBudget(budget: number | null): string {
  return budget === null ? '预算待定' : `预算 ${budget.toLocaleString('zh-CN')} 元`
}

onMounted(loadTrips)
</script>

<template>
  <main class="dashboard-shell">
    <header class="dashboard-heading"><div><h1>我的旅行</h1><p>从一个想法，到一段值得期待的旅程。</p></div><el-button type="primary" :icon="Plus" @click="openCreateDialog">新建旅行</el-button></header>
    <p v-if="errorMessage" class="error-message" role="alert">{{ errorMessage }}<el-button text @click="loadTrips">重试</el-button></p>
    <section class="trip-section" aria-label="旅行项目"><div class="section-heading"><span>{{ search ? `「${search}」的搜索结果` : '全部旅行' }}<small>{{ filteredTrips.length }}</small></span><span>按创建时间排列</span></div>
      <div v-if="loading" class="dashboard-skeleton" aria-label="正在加载旅行项目" aria-busy="true"><div v-for="i in 3" :key="i" /></div>
      <div v-else-if="!filteredTrips.length" class="dashboard-empty"><el-icon><Location /></el-icon><h2>{{ search ? '没有找到这段旅行' : '下一段旅程，从这里开始' }}</h2><p>{{ search ? '试试其他旅行名称或目的地。' : '创建一个旅行，把灵感、行程和资料放在一起。' }}</p><el-button v-if="!search" type="primary" :icon="Plus" @click="openCreateDialog">创建我的第一段旅行</el-button></div>
      <div v-else class="trip-list"><article v-for="trip in filteredTrips" :key="trip.id" class="trip-row"><button class="trip-open" @click="emit('selectTrip', trip)"><span class="destination-symbol"><el-icon><Location /></el-icon></span><span class="trip-row-title"><strong>{{ trip.title }}</strong><small>{{ trip.destination }}</small></span><span class="trip-row-date"><el-icon><Calendar /></el-icon>{{ trip.start_date }} 至 {{ trip.end_date }}</span><span class="trip-row-budget">{{ formatBudget(trip.budget) }}</span><span class="trip-row-action">查看行程<el-icon><ArrowRight /></el-icon></span></button><el-button text circle :icon="Delete" type="danger" :aria-label="`删除${trip.title}`" @click="removeTrip(trip)" /></article></div>
    </section>

    <el-dialog v-model="dialogVisible" title="新建旅行" width="min(480px, calc(100vw - 32px))">
      <el-form label-position="top" @submit.prevent="submitTrip">
        <el-form-item label="旅行名称"><el-input v-model="form.title" placeholder="例如：上海国庆游" /></el-form-item>
        <el-form-item label="目的地"><el-input v-model="form.destination" placeholder="例如：上海" /></el-form-item>
        <div class="date-row">
          <el-form-item label="出发日期"><el-date-picker v-model="form.start_date" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="结束日期"><el-date-picker v-model="form.end_date" type="date" value-format="YYYY-MM-DD" :disabled-date="isEndDateDisabled" /></el-form-item>
        </div>
        <el-form-item label="总预算（元，可选）"><el-input-number v-model="form.budget" :min="0" :precision="0" /></el-form-item>
        <h3 class="traveler-heading">同行人数</h3>
        <div class="date-row">
          <el-form-item label="成人男性"><el-input-number v-model="form.adult_male_count" :min="0" :precision="0" /></el-form-item>
          <el-form-item label="成人女性"><el-input-number v-model="form.adult_female_count" :min="0" :precision="0" /></el-form-item>
        </div>
        <div class="date-row">
          <el-form-item label="儿童男性"><el-input-number v-model="form.child_male_count" :min="0" :precision="0" /></el-form-item>
          <el-form-item label="儿童女性"><el-input-number v-model="form.child_female_count" :min="0" :precision="0" /></el-form-item>
        </div>
      </el-form>
      <p v-if="errorMessage" class="error-message" role="alert">{{ errorMessage }}</p>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitTrip">创建旅行</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.dashboard-shell{max-width:1320px;min-height:calc(100vh - 68px);margin:auto;padding:44px 38px 60px}.dashboard-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:42px;gap:20px}.dashboard-heading h1{font-size:30px;letter-spacing:-.03em;margin:0 0 12px}.dashboard-heading p{margin:0;color:#7a8497;font-size:13px}.section-heading{display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid var(--border);padding:0 4px 15px;font-size:13px}.section-heading>span:first-child{font-weight:600}.section-heading>span:last-child{font-size:11px;color:#8a93a4}.section-heading small{background:#e9edf5;padding:2px 6px;margin-left:8px;color:#65728a;font-size:11px;border-radius:5px}.trip-row{display:flex;align-items:center;gap:10px;border-bottom:1px solid var(--border);padding-right:10px}.trip-open{display:flex;align-items:center;gap:20px;flex:1;min-width:0;width:100%;padding:23px 10px;text-align:left;border:0;background:none;cursor:pointer}.trip-row:hover{background:#fff}.destination-symbol{width:44px;height:44px;border-radius:10px;display:grid;place-items:center;flex-shrink:0;background:#eaf0fc;color:#5f80bc;font-size:22px}.trip-row-title{min-width:130px;flex:1}.trip-row-title strong{display:block;font-size:15px;margin-bottom:7px;font-weight:600;overflow-wrap:anywhere}.trip-row-title small{color:#7a859a;font-size:12px}.trip-row-date{display:flex;align-items:center;gap:7px;font-size:12px;color:#7b8598}.trip-row-budget{min-width:135px;font-size:12px;text-align:right;color:#69748a}.trip-row-action{display:flex;align-items:center;gap:12px;color:var(--primary);font-size:12px;margin-left:20px}.dashboard-empty{padding:90px 20px;text-align:center}.dashboard-empty>.el-icon{font-size:34px;color:#8ca8db;margin-bottom:22px}.dashboard-empty h2{font-size:20px;margin:0 0 12px}.dashboard-empty p{font-size:13px;color:#7b859a;margin:0 0 28px}.dashboard-skeleton{padding:24px 0;display:grid;gap:18px}.dashboard-skeleton>div{background:#edf0f5;height:60px;width:80%;border-radius:8px}.error-message{display:flex;align-items:center;gap:12px;font-size:12px;color:#b42b26;padding:10px 14px;border-radius:8px;background:#fff3f2;line-height:1.7}.date-row{display:grid;grid-template-columns:1fr 1fr;gap:12px}.traveler-heading{font-size:14px}.date-row .el-date-editor{width:100%}
@media(max-width:1099px){.trip-row-date{display:none}.dashboard-shell{padding:32px 24px}.trip-row-action{margin-left:5px}}@media(max-width:759px){.dashboard-shell{padding:24px 14px}.dashboard-heading{align-items:flex-start;flex-direction:column;margin-bottom:28px}.dashboard-heading h1{font-size:26px}.dashboard-heading p{line-height:1.7}.trip-open{gap:12px;flex-wrap:wrap;padding:20px 0}.trip-row-budget{display:none}.trip-row-action{display:none}.trip-row-title{min-width:0}.section-heading>span:last-child{display:none}.destination-symbol{width:36px;height:38px}.date-row{grid-template-columns:1fr}}
</style>
