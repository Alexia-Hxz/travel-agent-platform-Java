<script setup lang="ts">
import { ArrowRight, Delete, MagicStick, Plus, Search, Refresh } from '@element-plus/icons-vue'
import { computed, nextTick, ref } from 'vue'
import type { Trip } from '@/types/trip'
import type { ItineraryItem } from '@/types/itinerary'
import type { TripFile } from '@/types/tripFile'
import { useTripWorkspace } from '@/composables/useTripWorkspace'
import { usePlanningHandoff } from '@/composables/planningHandoff'
import { estimateItinerary, tripDays } from '@/utils/itineraryBudget'
import { peopleCount, tripDates } from '@/utils/workspace'
import TripHeader from '@/components/TripHeader.vue'
import RecommendationStrip from '@/components/RecommendationStrip.vue'
import DayTabs from '@/components/DayTabs.vue'
import ItineraryTimeline from '@/components/ItineraryTimeline.vue'
import TripMap from '@/components/TripMap.vue'
import TripUtilityBar from '@/components/TripUtilityBar.vue'
import CopilotPanel from '@/components/CopilotPanel.vue'
import WorkspaceDrawer from '@/components/WorkspaceDrawer.vue'
import PlaceThumbnail from '@/components/PlaceThumbnail.vue'
import TripFiles from './TripFiles.vue'
import TripPlanningAgent from './TripPlanningAgent.vue'
import TripAdjustmentAgent from './TripAdjustmentAgent.vue'
import TripOverviewSummary from './TripOverviewSummary.vue'
const props = defineProps<{ trip: Trip }>()
const emit = defineEmits<{ back: []; 'section-closed': [] }>()
const { places, itineraryItems, loading, errorMessage, budgetOverride, placeDialogVisible, itemDialogVisible,
  submittingPlace, submittingItem, placeForm, itemForm, placeDialogError, itemDialogError,
  autoScheduleDialogVisible, autoScheduleLoading, applyingAutoSchedule, autoSchedule, autoScheduleMessage,
  placesMessage, placeTypeLabels, isDateOutsideTrip, loadTripDetails, refreshAfterWrite, openPlaceDialog,
  openItemDialog, submitPlace, submitItineraryItem, removePlace, clearAllPlaces, removeItineraryItem,
  openAutoSchedule, applyAutoSchedule, updateBudget } = useTripWorkspace(props.trip)
const { handoff } = usePlanningHandoff(props.trip.id)
const dates = computed(() => tripDates(props.trip.start_date, props.trip.end_date))
const activeDay = ref(props.trip.start_date), selectedId = ref<number | null>(null), mobileView = ref('timeline')
const files = ref<TripFile[]>([])
const section = ref(''), drawerOpen = ref(false), copilotOpen = ref(false), informationOpen = ref(false)
const query = ref(''), planningQuery = ref(''), adjustmentInstruction = ref('')
const adjustment = ref<InstanceType<typeof TripAdjustmentAgent> | null>(null)
const savedPlaceNames = computed(() => places.value.map(place => place.name))
const enrichedItems = computed(() => itineraryItems.value.map(item => ({ ...item, place: { ...item.place, ...places.value.find(place => place.id === item.place_id) } })))
const dayItems = computed(() => enrichedItems.value.filter(item => item.date === activeDay.value).sort((a,b) => a.start_time.localeCompare(b.start_time) || a.sort_order - b.sort_order))
const currentTrip = computed(() => ({ ...props.trip, budget: budgetOverride.value !== undefined ? budgetOverride.value : props.trip.budget }))
const estimate = computed(() => itineraryItems.value.length ? estimateItinerary(enrichedItems.value.map(item => ({ name: item.place.name, category: item.place.type, quoted_cost: item.place.estimated_cost, price_level: item.place.price_level, quantity: item.place.quantity })), tripDays(props.trip.start_date, props.trip.end_date), peopleCount(props.trip) || 1).total : null)
const sectionTitles: Record<string,string> = { places: '收藏地点', files: '上传资料', planning: 'AI 发现', summary: '行程介绍与总结', templates: '行程模板', inspiration: '旅行灵感', search: '搜索旅行工作台' }
const searchResults = computed(() => places.value.filter(place => `${place.name} ${place.address} ${place.note || ''}`.toLowerCase().includes(query.value.toLowerCase())))
const prompts = computed(() => section.value === 'templates' ? [
  { title:'轻松慢游', description:'每天两三站，给散步和休息留出时间', query:`为${props.trip.destination}设计每天不超过三个地点的轻松行程，预留休息时间。` },
  { title:'亲子时光', description:'适合孩子的活动，搭配方便的交通', query:`为${props.trip.destination}规划亲子友好的行程，减少奔波，考虑同行儿童。` },
  { title:'美食之旅', description:'用三餐串起一座城市', query:`围绕${props.trip.destination}的本地美食安排行程，兼顾景点和用餐预算。` },
] : [
  { title:'在街巷里走走', description:'找一条当地人喜欢的散步路线', query:`推荐${props.trip.destination}有当地生活气息的步行路线，避开拥挤的热门景点。` },
  { title:'给雨天留个计划', description:'博物馆、展览与室内好去处', query:`如果旅行期间下雨，${props.trip.destination}有哪些适合替换的室内活动？` },
  { title:'多一点自然', description:'在城市周边找一处安静的绿意', query:`推荐${props.trip.destination}交通方便、适合放松的公园和自然景点。` },
])
function openSection(name: string) {
  if (name === 'budget' || name === 'editor') { adjustment.value?.openEditor(); return }
  section.value = name; drawerOpen.value = true
}
function discover(value = '') { planningQuery.value = value; openSection('planning') }
function searchWorkspace(value: string) { query.value = value; openSection('search') }
function selectItem(item: ItineraryItem) { selectedId.value = item.id }
function selectDate(day: string) { activeDay.value = day; selectedId.value = null }
async function openAdjustment(instruction: string) { drawerOpen.value = false; copilotOpen.value = true; adjustmentInstruction.value = ''; await nextTick(); adjustmentInstruction.value = instruction; adjustment.value?.focusInput() }
function editItem(item: ItineraryItem) { adjustment.value?.openEditor(item.id) }
function addItem() { if (!places.value.length) { openPlaceDialog(); return }; openItemDialog(activeDay.value) }
/**
 * Arrange the day on screen, in the panel that already knows how to preview and apply an
 * adjustment: the draft it produces replaces this day only, and nothing is written until
 * the traveller accepts it.
 */
async function planThisDay() {
  copilotOpen.value = true
  await nextTick()
  const panel = adjustment.value
  if (!panel) { errorMessage.value = '暂时无法打开调整面板，请稍后重试。'; return }
  await panel.planDay(activeDay.value)
}
defineExpose({ openSection, searchWorkspace })
</script>

<template>
  <main class="detail-shell">
    <div class="workspace-main">
      <div class="workspace-content">
        <TripHeader :trip="currentTrip" :items="enrichedItems" @back="emit('back')" @summary="openSection('summary')" @edit="informationOpen = true" @copilot="copilotOpen = true" />
        <RecommendationStrip :destination="trip.destination" :recommendations="handoff.planRecommendations" @discover="discover" />
        <div v-if="errorMessage" class="workspace-error" role="alert">{{ errorMessage }}<button class="button" @click="loadTripDetails()">重试</button></div>
        <p v-if="autoScheduleMessage" class="workspace-status" role="status">{{ autoScheduleMessage }}</p>
        <div v-if="loading" class="workspace-skeleton" aria-label="正在加载旅行详情" aria-busy="true"><div v-for="i in 6" :key="i" /></div>
        <template v-else>
          <div class="workspace-tools"><span>{{ dayItems.length ? `当天 ${dayItems.length} 站` : '开始安排这一天' }}</span><div><button class="text-action" :disabled="!places.length || autoScheduleLoading" aria-label="Auto-schedule my saved places" @click="openAutoSchedule"><el-icon><MagicStick /></el-icon>{{ autoScheduleLoading ? '正在排程…' : '自动排程' }}</button><button class="text-action" @click="openSection('editor')">编辑行程</button></div></div>
          <div class="mobile-view-toggle" aria-label="工作区视图"><button :aria-pressed="mobileView === 'timeline'" @click="mobileView = 'timeline'">每日行程</button><button :aria-pressed="mobileView === 'map'" @click="mobileView = 'map'">地图路线</button></div>
          <div class="daily-workspace" :class="`mobile-${mobileView}`">
            <div class="timeline-pane"><DayTabs :model-value="activeDay" :dates="dates" @update:model-value="selectDate" @add="addItem" @minimal="planThisDay" /><ItineraryTimeline :items="dayItems" :day="activeDay" :selected-id="selectedId" :has-places="places.length > 0" @select="selectItem" @edit="editItem" @remove="removeItineraryItem" @add="addItem" @schedule="openAutoSchedule" @discover="discover()" /></div>
            <TripMap :items="dayItems" :selected-id="selectedId" :destination="trip.destination" @select="selectItem" />
          </div>
        </template>
      </div>
      <TripUtilityBar :places="places" :files="files" :estimate="estimate" :budget="currentTrip.budget" @open="openSection" />
    </div>
    <CopilotPanel :open="copilotOpen" @close="copilotOpen = false" @editor="openSection('editor')"><TripAdjustmentAgent ref="adjustment" compact :trip-id="trip.id" :initial-instruction="adjustmentInstruction" :itinerary-items="enrichedItems" :places="places" :start-date="trip.start_date" :end-date="trip.end_date" :destination="trip.destination" :people="peopleCount(trip) || 1" :original-budget="currentTrip.budget" :active-date="activeDay" @itinerary-updated="refreshAfterWrite" @places-updated="refreshAfterWrite" @budget-updated="updateBudget" @discover="discover()" @files="openSection('files')" /></CopilotPanel>
    <WorkspaceDrawer :model-value="drawerOpen && section !== 'files'" :title="sectionTitles[section] || '旅行工作台'" @update:model-value="drawerOpen = $event; !$event && emit('section-closed')">
      <section v-show="section === 'places'" class="saved-places-view" aria-label="收藏地点"><div class="drawer-actions"><span>已收藏 {{ places.length }} 个地点</span><el-button :icon="Delete" :disabled="!places.length" aria-label="清空收藏地点" @click="clearAllPlaces">清空收藏</el-button><el-button type="primary" :icon="Plus" @click="openPlaceDialog">添加地点</el-button></div><p v-if="placesMessage" class="workspace-status" role="status">{{ placesMessage }}</p><p v-if="!places.length" class="drawer-empty">还没有收藏地点。添加喜欢的地点，或在 AI 发现中收藏推荐。</p><article v-for="place in places" :key="place.id" class="saved-place-row"><PlaceThumbnail :src="place.image_url" :type="place.type" :name="place.name" /><div><h3>{{ place.name }}<small>{{ placeTypeLabels[place.type] }}</small></h3><p>{{ place.address }}</p><p v-if="place.opening_hours">{{ place.opening_hours }}</p><p v-if="place.note">{{ place.note }}</p><small v-if="place.estimated_cost != null">参考价 ¥{{ place.estimated_cost }}</small></div><el-button text circle type="danger" :icon="Delete" aria-label="删除地点" @click="removePlace(place)" /></article></section>
      <TripPlanningAgent v-if="!loading" v-show="section === 'planning'" :trip-id="trip.id" :destination="trip.destination" :initial-query="planningQuery" :saved-place-names="savedPlaceNames" @places-updated="refreshAfterWrite" @adjustment-requested="openAdjustment" />
      <TripOverviewSummary v-if="!loading" v-show="section === 'summary'" :trip="currentTrip" :places="places" :itinerary-items="enrichedItems" />
      <div v-show="section === 'templates' || section === 'inspiration'" class="prompt-library"><p class="muted">选一个方向，将它带到 AI 发现中，结合你的日期和预算生成计划。</p><button v-for="prompt in prompts" :key="prompt.title" @click="discover(prompt.query)"><span><strong>{{ prompt.title }}</strong><small>{{ prompt.description }}</small></span><el-icon><ArrowRight /></el-icon></button></div>
      <div v-show="section === 'search'" class="workspace-search"><el-input v-model="query" :prefix-icon="Search" placeholder="搜索收藏的地点…" aria-label="搜索当前旅行" /><p class="muted">{{ searchResults.length }} 个收藏地点</p><button v-for="place in searchResults" :key="place.id" class="search-place" @click="openSection('places')"><PlaceThumbnail :src="place.image_url" :type="place.type" :name="place.name"/><span>{{ place.name }}<small>{{ place.address }}</small></span><el-icon><ArrowRight /></el-icon></button><el-button type="primary" :icon="MagicStick" @click="discover(query)">向 AI 提问：{{ query }}</el-button></div>
    </WorkspaceDrawer>
    <TripFiles v-if="!loading" :trip-id="trip.id" embedded :open="drawerOpen && section === 'files'" @update:open="drawerOpen = $event; !$event && emit('section-closed')" @files-updated="files = $event" @places-updated="refreshAfterWrite" />
    <el-dialog v-model="informationOpen" title="旅行信息" width="min(460px, calc(100vw - 24px))"><dl class="trip-info"><dt>旅行名称</dt><dd>{{ trip.title }}</dd><dt>目的地</dt><dd>{{ trip.destination }}</dd><dt>日期</dt><dd>{{ trip.start_date }} 至 {{ trip.end_date }}</dd><dt>同行人数</dt><dd>{{ peopleCount(trip) || '未设置' }}</dd></dl><p class="muted">旅行信息创建后暂不支持修改。行程的日期、时间和预算可在「编辑行程」中调整。</p></el-dialog>
    <el-dialog v-model="placeDialogVisible" title="添加地点" width="min(600px, calc(100vw - 32px))">
      <el-form label-position="top" @submit.prevent="submitPlace">
        <div class="form-grid">
          <el-form-item label="地点名称"><el-input v-model="placeForm.name" /></el-form-item>
          <el-form-item label="地点类型"><el-select v-model="placeForm.type"><el-option v-for="(label, type) in placeTypeLabels" :key="type" :label="label" :value="type" /></el-select></el-form-item>
        </div>
        <el-form-item label="地址"><el-input v-model="placeForm.address" /></el-form-item>
        <div class="form-grid">
          <el-form-item label="纬度"><el-input-number v-model="placeForm.latitude" :min="-90" :max="90" :precision="6" /></el-form-item>
          <el-form-item label="经度"><el-input-number v-model="placeForm.longitude" :min="-180" :max="180" :precision="6" /></el-form-item>
        </div>
        <div class="form-grid">
          <el-form-item label="评分"><el-input-number v-model="placeForm.rating" :min="0" :max="5" :step="0.1" :precision="1" /></el-form-item>
          <el-form-item label="价格等级"><el-input-number v-model="placeForm.price_level" :min="0" :max="5" :precision="0" /></el-form-item>
        </div>
        <el-form-item label="开放时间"><el-input v-model="placeForm.opening_hours" placeholder="例如：09:00-17:00" /></el-form-item>
        <div class="form-grid">
          <el-form-item label="选择理由（可选）">
            <el-input v-model="placeForm.note" placeholder="例如：位置便利，靠近夫子庙" maxlength="600" />
          </el-form-item>
          <el-form-item label="参考价（CNY，可选）">
            <el-input-number v-model="placeForm.estimated_cost" :min="0" :precision="0" />
          </el-form-item>
        </div>
        <el-form-item label="图片链接（可选）"><el-input v-model="placeForm.image_url" /></el-form-item>
        <el-form-item label="来源链接（可选）"><el-input v-model="placeForm.source_url" /></el-form-item>
      </el-form>
      <p v-if="placeDialogError" class="dialog-error" role="alert">{{ placeDialogError }}</p>
      <template #footer>
        <el-button @click="placeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submittingPlace" @click="submitPlace">保存地点</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="itemDialogVisible" title="添加行程项" width="min(520px, calc(100vw - 32px))">
      <el-form label-position="top" @submit.prevent="submitItineraryItem">
        <el-form-item label="日期"><el-date-picker v-model="itemForm.date" type="date" value-format="YYYY-MM-DD" :disabled-date="isDateOutsideTrip" /></el-form-item>
        <el-form-item label="地点"><el-select v-model="itemForm.place_id"><el-option v-for="place in places" :key="place.id" :label="place.name" :value="place.id" /></el-select></el-form-item>
        <div class="form-grid">
          <el-form-item label="开始时间"><el-time-picker v-model="itemForm.start_time" value-format="HH:mm:ss" /></el-form-item>
          <el-form-item label="结束时间"><el-time-picker v-model="itemForm.end_time" value-format="HH:mm:ss" /></el-form-item>
        </div>
        <div class="form-grid">
          <el-form-item label="排序"><el-input-number v-model="itemForm.sort_order" :min="0" :precision="0" /></el-form-item>
          <el-form-item label="备注"><el-input v-model="itemForm.note" /></el-form-item>
        </div>
      </el-form>
      <p v-if="itemDialogError" class="dialog-error" role="alert">{{ itemDialogError }}</p>
      <template #footer>
        <el-button @click="itemDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submittingItem" @click="submitItineraryItem">保存行程项</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="autoScheduleDialogVisible"
      title="建议的行程安排"
      width="min(560px, calc(100vw - 32px))"
    >
      <template v-if="autoSchedule">
        <p class="auto-schedule-intro">
          下面是根据开放时间和已有行程自动找出的空闲时段。确认后才会写入行程。
        </p>
        <ul v-if="autoSchedule.proposed.length" class="auto-schedule-list">
          <li v-for="item in autoSchedule.proposed" :key="`${item.date}-${item.place_id}`">
            <strong>{{ item.date }} {{ item.start_time.slice(0, 5) }}-{{ item.end_time.slice(0, 5) }}</strong>
            <span class="auto-schedule-place">{{ item.place_name }}</span>
            <span class="auto-schedule-note">{{ item.note }}</span>
          </li>
        </ul>
        <p v-else class="empty-state">{{ autoSchedule.message }}</p>

        <div v-if="autoSchedule.skipped.length" class="auto-schedule-skipped">
          <strong>这些地点放不下：</strong>
          <ul class="plain-list">
            <li v-for="entry in autoSchedule.skipped" :key="entry.place_id">
              {{ entry.place_name }}：{{ entry.reason }}
            </li>
          </ul>
        </div>
        <p v-if="autoSchedule.already_scheduled.length" class="auto-schedule-meta">
          已在行程中：{{ autoSchedule.already_scheduled.join('、') }}
        </p>
      </template>
      <template #footer>
        <el-button @click="autoScheduleDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="applyingAutoSchedule"
          :disabled="!autoSchedule || autoSchedule.proposed.length === 0"
          aria-label="Confirm the suggested schedule"
          @click="applyAutoSchedule"
        >确认排入行程</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.detail-shell{display:grid;grid-template-columns:minmax(0,1fr) minmax(340px,25%);align-items:stretch;gap:16px;padding:0 18px 18px 24px;height:calc(100dvh - 68px);min-height:760px}
.workspace-main{min-width:0;min-height:0;display:flex;flex-direction:column}.workspace-content{display:flex;flex-direction:column;flex:1;min-width:0;min-height:0;padding:22px 20px 12px;background:white;border-radius:12px}.workspace-tools{display:flex;align-items:center;justify-content:space-between;margin:0 2px 9px;color:#7a8496;font-size:11px}.workspace-tools>div{display:flex;gap:16px}.text-action{display:flex;gap:5px;align-items:center;border:0;background:none;color:#6c7892;padding:4px 0;cursor:pointer;font-size:11px}.text-action:hover{color:var(--primary)}.text-action:disabled{opacity:.45;cursor:not-allowed}.daily-workspace{display:grid;grid-template-columns:minmax(0,1.12fr) minmax(0,.9fr);gap:16px;flex:1;min-height:0;height:auto;max-height:none}.timeline-pane{display:flex;flex-direction:column;min-width:0;min-height:0;background:white}.workspace-error{display:flex;gap:10px;align-items:center;justify-content:space-between;padding:12px;background:#fff3f2;border-radius:8px;font-size:12px;color:#b42318;margin-bottom:14px}.workspace-status{font-size:12px;color:var(--success);padding:8px 0;margin:0}.workspace-skeleton{min-height:500px;display:grid;gap:18px;padding:25px;background:#fbfcfe}.workspace-skeleton>div{height:55px;border-radius:8px;background:#edf0f5;width:75%}.workspace-skeleton>div:nth-child(even){width:55%}.mobile-view-toggle{display:none}.drawer-actions{display:flex;align-items:center;gap:10px;font-size:12px;margin-bottom:20px;flex-wrap:wrap}.drawer-actions>span{margin-right:auto;color:var(--secondary)}.drawer-empty{font-size:13px;color:var(--secondary);padding:40px 10px;text-align:center;line-height:1.8}.saved-place-row{display:flex;gap:15px;align-items:flex-start;padding:18px 0;border-bottom:1px solid var(--border)}.saved-place-row>div{min-width:0;flex:1}.saved-place-row h3{margin:0 0 7px;font-size:15px}.saved-place-row h3 small{font-size:10px;color:#768298;font-weight:400;margin-left:10px}.saved-place-row p{font-size:12px;color:#737e92;margin:4px 0;line-height:1.7}.saved-place-row small{font-size:11px;color:#69758b}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.form-grid .el-input-number,.form-grid .el-select,.form-grid .el-date-editor{width:100%}.dialog-error{font-size:12px;padding:10px;background:#fff3f2;color:#b42318;border-radius:6px}.auto-schedule-intro,.auto-schedule-note,.auto-schedule-meta{font-size:12px;color:var(--secondary);line-height:1.7}.auto-schedule-list{list-style:none;margin:16px 0;padding:0}.auto-schedule-list li{display:flex;gap:7px;flex-wrap:wrap;padding:12px 0;border-bottom:1px solid var(--border);font-size:13px}.auto-schedule-list strong{color:var(--primary)}.auto-schedule-note{width:100%}.auto-schedule-skipped{font-size:12px;line-height:1.8}.prompt-library>button{display:flex;justify-content:space-between;align-items:center;width:100%;padding:25px 0;border:0;border-bottom:1px solid var(--border);background:none;text-align:left;cursor:pointer}.prompt-library strong{display:block;font-size:16px}.prompt-library small{display:block;margin-top:9px;font-size:12px;color:var(--secondary)}.prompt-library>button:hover{color:var(--primary)}.search-place{display:flex;gap:12px;align-items:center;width:100%;padding:15px 0;border:0;border-bottom:1px solid var(--border);text-align:left;background:none;cursor:pointer}.search-place>span:nth-child(2){flex:1;font-size:14px}.search-place small{display:block;font-size:12px;color:var(--secondary);margin-top:7px}.workspace-search>.el-button{margin-top:24px;max-width:100%;white-space:normal;height:auto;min-height:36px}.trip-info{display:grid;grid-template-columns:85px 1fr;gap:14px;font-size:13px}.trip-info dt{color:var(--secondary)}.trip-info dd{margin:0;overflow-wrap:anywhere}
@media(min-width:1100px) and (max-width:1399px){.detail-shell{grid-template-columns:minmax(0,1fr) 326px;padding-left:18px;gap:12px}.workspace-content{padding:18px 14px 12px}.daily-workspace{gap:10px;grid-template-columns:minmax(0,1.05fr) minmax(0,.9fr);height:auto}}
@media(max-width:1099px){.detail-shell{grid-template-columns:1fr;padding:0 18px 18px}.daily-workspace{height:auto}}
@media(max-width:759px){.detail-shell{padding:0 10px 14px;height:auto;min-height:calc(100vh - 112px)}.workspace-content{padding:16px 12px 8px}.mobile-view-toggle{display:flex;gap:18px;border-bottom:1px solid var(--border);margin-bottom:10px}.mobile-view-toggle>button{padding:10px 0;border:0;background:none;font-size:12px;color:#7a8496}.mobile-view-toggle>button[aria-pressed=true]{color:var(--primary);border-bottom:2px solid var(--primary)}.workspace-content{flex:initial}.daily-workspace{display:block;flex:initial;height:auto;min-height:480px;max-height:none}.timeline-pane{min-height:440px}.mobile-timeline>.trip-map{display:none}.mobile-map>.timeline-pane{display:none}.form-grid{grid-template-columns:1fr}.workspace-tools{margin-top:0}.saved-place-row .place-thumbnail{width:52px;height:48px}}
</style>
