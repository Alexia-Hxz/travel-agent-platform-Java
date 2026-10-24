<script setup lang="ts">
import { Check, Close, Delete, MagicStick, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Paperclip, Position, TopRight } from '@element-plus/icons-vue'
import AppLogo from '@/components/AppLogo.vue'
import WorkspaceDrawer from '@/components/WorkspaceDrawer.vue'
import AdjustmentPreview from '@/components/AdjustmentPreview.vue'

import { applyAdjustment, createAdjustmentDraft, rejectAdjustment } from '@/api/adjustments'
import { resolvePlaceImage } from '@/api/assets'
import { updatePlace } from '@/api/itinerary'
import { usePlanningHandoff, type PlannedRecommendation } from '@/composables/planningHandoff'
import { defaultOpenForCount } from '@/composables/panelState'
import type {
  AdjustmentBudgetRow,
  AdjustmentDraft,
  AdjustmentOperation,
  AdjustmentOperationKind,
  AdjustmentPlanItem,
} from '@/types/adjustment'
import type { ItineraryItem, Place, PlaceCreate, PlaceType } from '@/types/itinerary'
import { estimateItinerary, tripDays, type BudgetSubject } from '@/utils/itineraryBudget'
import { approximateCoordinates } from '@/utils/workspace'
import CollapsiblePanel from '@/components/CollapsiblePanel.vue'
/**
 * The daily-itinerary editor.
 *
 * It is driven by three things, and the user can mix them freely:
 *
 *  1. the calendar itself -- delete an item, move it to another day, change its time, all
 *     by clicking, with no need to describe the change in words;
 *  2. the planning agent's own recommendations, offered with the same prices the cards
 *     showed, plus a free-text form for a place or dish the user found themselves;
 *  3. the conversational box, for anything the two above cannot express.
 *
 * Whatever route is taken, the pending changes become operations, the operations become a
 * draft, and the draft is what gets applied -- so nothing is written until the preview has
 * been read and confirmed.
 */

const props = withDefaults(
  defineProps<{
    tripId: number
    initialInstruction?: string
    itineraryItems?: ItineraryItem[]
    places?: Place[]
    startDate?: string
    endDate?: string
    destination?: string
    compact?: boolean
    people?: number
    originalBudget?: number | null
    /** The day the itinerary is showing: candidates default to it unless the question names one. */
    activeDate?: string
  }>(),
  { initialInstruction: '', itineraryItems: () => [], places: () => [], startDate: '', endDate: '', destination: '', compact: false, people: 1, originalBudget: null, activeDate: '' },
)
const emit = defineEmits<{ 'itinerary-updated': []; 'budget-updated': [number | null]; 'places-updated': []; discover: []; files: [] }>()

const instruction = ref(props.initialInstruction)
// Set only by the day button: which day to arrange, and the signal that this request is a
// single-day plan rather than an edit to interpret.
const dayPlanDate = ref('')
// Set only by a "find me places" request: ask for new places that answer the question, and
// offer each one as a candidate to tick. Also set when the typed request reads like one, so
// typing "有没有更适合亲子的" works the same as pressing the shortcut.
const discoverMode = ref(false)
// Live search is on by default: asking for places the trip does not have yet is exactly the
// case where it helps, and the traveller should not have to remember a checkbox for it.
const includeWeb = ref(true)
const creating = ref(false)
const applying = ref(false)
const rejecting = ref(false)
/** A direct "save to the itinerary" write is in flight. */
const saving = ref(false)
const errorMessage = ref('')
const statusMessage = ref('')
const draft = ref<AdjustmentDraft | null>(null)
const selectedOperationIndexes = ref<number[]>([])
const failedImages = ref(new Set<string>())

/** Itinerary item ids the user has crossed out but not yet applied. */
const pendingRemovals = ref<number[]>([])
/** Retiming/moving of existing items, keyed by item id. */
const pendingMoves = ref<Record<number, { date: string; start_time: string; end_time: string }>>({})
/** Additions built in the panel, before they become operations. */
const pendingAdditions = ref<PendingAddition[]>([])
/** Prices and counts the user corrected inside the preview, keyed by operation index. */
const editedOperationPrices = ref<Record<number, { unit_price: number; quantity: number }>>({})

const showAddForm = ref(false)
const addForm = ref<PendingAddition>(emptyAddition())
const replaceTargetId = ref<number | null>(null)

interface PendingAddition {
  key: string
  name: string
  category: PlaceType
  date: string
  start_time: string
  end_time: string
  unit_price: number | null
  quantity: number
  note: string | null
  reason: string | null
  image_url: string | null
  /** Rough position, carried over from a plan recommendation or a saved place, for the map. */
  latitude: number | null
  longitude: number | null
  /** Set when it came from the planning panel, whose price is already agreed. */
  from_plan: boolean
}

const { handoff, setAdjustmentSummary } = usePlanningHandoff(props.tripId)

watch(
  () => props.initialInstruction,
  (value) => {
    if (value) instruction.value = value
  },
)

function timeLabel(value: string | null | undefined): string {
  return value?.slice(0, 5) ?? ''
}

/**
 * A timeline row's picture, which is never allowed to be a broken icon.
 *
 * The row used to hide its picture once the browser reported an error, which turned a dead
 * provider link into a row that silently had no picture. It now falls back to the category
 * placeholder, and the failure is remembered per row rather than per URL: two rows can show the
 * same photo, and one of them failing says nothing about the other.
 */
function imageKey(item: AdjustmentPlanItem): string {
  return `${item.place_name}|${item.image_url ?? ''}`
}

function itemImage(item: AdjustmentPlanItem): string {
  return resolvePlaceImage(
    { image_url: item.image_url, type: item.type },
    () => !failedImages.value.has(imageKey(item)),
  ).src
}

function markItemImageFailed(item: AdjustmentPlanItem): void {
  failedImages.value = new Set(failedImages.value).add(imageKey(item))
}

const PLACE_TYPE_LABELS: Record<string, string> = {
  attraction: '景点',
  restaurant: '餐厅',
  hotel: '酒店',
  airport: '机场',
  station: '车站',
}

function categoryLabel(category: string): string {
  return PLACE_TYPE_LABELS[category] ?? category ?? '景点'
}

// ---------------------------------------------------------------- the calendar

const days = computed(() => {
  const start = props.startDate
  const end = props.endDate
  if (!start || !end) return []
  const list: string[] = []
  const cursor = new Date(`${start}T00:00:00Z`)
  const last = new Date(`${end}T00:00:00Z`)
  if (Number.isNaN(cursor.getTime()) || Number.isNaN(last.getTime()) || last < cursor) return []
  while (cursor <= last) {
    list.push(cursor.toISOString().slice(0, 10))
    cursor.setUTCDate(cursor.getUTCDate() + 1)
  }
  return list
})

const totalPeople = computed(() => {
  const profile = draft.value?.traveler_profile
  const people = Number(profile?.total_people ?? props.people)
  return people > 0 ? people : 1
})

const effectiveDays = computed(() => tripDays(props.startDate, props.endDate))

/** Every item in the calendar, with any pending retiming already applied. */
const calendarItems = computed(() => {
  return props.itineraryItems
    .map((item) => {
      const move = pendingMoves.value[item.id]
      return {
        id: item.id,
        date: move?.date ?? item.date,
        start_time: move?.start_time ?? item.start_time,
        end_time: move?.end_time ?? item.end_time,
        place: item.place,
      }
    })
    .sort((a, b) => `${a.date}${a.start_time}`.localeCompare(`${b.date}${b.start_time}`))
})

/**
 * The items that will still exist once the pending edits are applied.
 *
 * This is what the estimate is built from -- a crossed-out item keeps its row on screen
 * (so it can be un-crossed) but loses its money here, which is exactly the correction the
 * old budget was missing.
 */
const effectiveItems = computed(() => calendarItems.value.filter((item) => !isRemoved(item.id)))

function itemsOfDay(day: string) {
  return calendarItems.value.filter((item) => item.date === day)
}

function activeItemsOfDay(day: string) {
  return effectiveItems.value.filter((item) => item.date === day)
}

/**
 * Everything the folded editor header has to account for: the items still on the calendar
 * plus the additions waiting to be saved, so the count cannot read "0 项" while a pending
 * addition is sitting inside.
 */
const activeItemCount = computed(() =>
  days.value.reduce(
    (total, day) => total + activeItemsOfDay(day).length + additionsOfDay(day).length,
    0,
  ),
)

function isRemoved(itemId: number): boolean {
  return pendingRemovals.value.includes(itemId)
}

function toggleRemoval(itemId: number): void {
  const next = new Set(pendingRemovals.value)
  if (next.has(itemId)) next.delete(itemId)
  else next.add(itemId)
  pendingRemovals.value = [...next]
}

function moveOf(itemId: number): { date: string; start_time: string; end_time: string } | null {
  return pendingMoves.value[itemId] ?? null
}

function setMoveField(itemId: number, field: 'date' | 'start_time' | 'end_time', value: string): void {
  const item = props.itineraryItems.find((entry) => entry.id === itemId)
  if (!item) return
  const current = pendingMoves.value[itemId] ?? {
    date: item.date,
    start_time: item.start_time,
    end_time: item.end_time,
  }
  const next = { ...current, [field]: value }
  // Only a real change counts as a move, so an untouched item is not re-written.
  const unchanged =
    next.date === item.date && next.start_time === item.start_time && next.end_time === item.end_time
  const moves = { ...pendingMoves.value }
  if (unchanged) delete moves[itemId]
  else moves[itemId] = next
  pendingMoves.value = moves
}

function resetMove(itemId: number): void {
  const moves = { ...pendingMoves.value }
  delete moves[itemId]
  pendingMoves.value = moves
}

// ---------------------------------------------------------------- additions

const hasStructuredEdits = computed(
  () =>
    pendingRemovals.value.length > 0 ||
    Object.keys(pendingMoves.value).length > 0 ||
    pendingAdditions.value.length > 0,
)

function emptyAddition(): PendingAddition {
  return {
    key: '',
    name: '',
    category: 'attraction',
    date: '',
    start_time: '09:00',
    end_time: '11:00',
    unit_price: null,
    quantity: 1,
    note: null,
    reason: null,
    image_url: null,
    latitude: null,
    longitude: null,
    from_plan: false,
  }
}

/** The times of day the add form offers before the user picks their own. */
const SLOT_PRESETS: Array<{ label: string; start_time: string; end_time: string }> = [
  { label: '上午 09:00-11:30', start_time: '09:00', end_time: '11:30' },
  { label: '午餐 12:00-13:30', start_time: '12:00', end_time: '13:30' },
  { label: '下午 14:00-17:00', start_time: '14:00', end_time: '17:00' },
  { label: '晚餐 18:00-19:30', start_time: '18:00', end_time: '19:30' },
  { label: '晚上 20:00-21:30', start_time: '20:00', end_time: '21:30' },
]

function openAddForm(options: { date?: string; start_time?: string; end_time?: string; replaceItemId?: number | null } = {}): void {
  addForm.value = {
    ...emptyAddition(),
    key: `add-${Date.now()}`,
    date: options.date ?? days.value[0] ?? props.startDate,
    start_time: options.start_time ?? '09:00',
    end_time: options.end_time ?? '11:00',
  }
  replaceTargetId.value = options.replaceItemId ?? null
  showAddForm.value = true
}

/** Prefill the form from a plan recommendation, keeping the price it was shown with. */
function pickRecommendation(candidate: PlannedRecommendation): void {
  const position = approximateCoordinates(candidate)
  addForm.value = {
    ...addForm.value,
    name: candidate.name,
    category: (['attraction', 'hotel', 'restaurant', 'airport', 'station'].includes(candidate.category)
      ? candidate.category
      : 'attraction') as PlaceType,
    unit_price: candidate.unit_price ?? candidate.estimated_cost ?? null,
    quantity: candidate.quantity ?? 1,
    reason: candidate.reason ?? null,
    image_url: candidate.image_url ?? null,
    latitude: position.latitude,
    longitude: position.longitude,
    from_plan: true,
  }
}

/** Prefill the form from a saved place that is not on the calendar yet. */
function pickSavedPlace(place: Place): void {
  const position = approximateCoordinates(place)
  addForm.value = {
    ...addForm.value,
    name: place.name,
    category: place.type,
    unit_price: place.estimated_cost ?? null,
    quantity: place.quantity ?? 1,
    reason: place.note ?? null,
    image_url: place.image_url,
    latitude: position.latitude,
    longitude: position.longitude,
    from_plan: false,
  }
}

const unscheduledPlaces = computed(() => {
  const scheduled = new Set(props.itineraryItems.map((item) => item.place_id))
  return props.places.filter((place) => !scheduled.has(place.id) && place.name !== addForm.value.name)
})

const plannedCandidates = computed(() => {
  const shown = new Set(props.itineraryItems.map((item) => item.place.name.trim()))
  return (handoff.value.planRecommendations ?? []).filter((candidate) => !shown.has(candidate.name.trim()))
})

function confirmAddition(): void {
  if (!addForm.value.name.trim()) {
    errorMessage.value = '请填写要新增的项目名称。'
    return
  }
  if (replaceTargetId.value !== null) toggleRemoval(replaceTargetId.value)
  pendingAdditions.value = [...pendingAdditions.value, { ...addForm.value, name: addForm.value.name.trim() }]
  showAddForm.value = false
  replaceTargetId.value = null
  statusMessage.value = `已加入待应用的改动：${addForm.value.name.trim()}`
}

function cancelAddition(): void {
  showAddForm.value = false
  replaceTargetId.value = null
}

function removeAddition(key: string): void {
  pendingAdditions.value = pendingAdditions.value.filter((addition) => addition.key !== key)
}

/**
 * The not-yet-saved additions that belong to one day, so they are edited on the calendar
 * itself instead of in a separate list.
 *
 * A newly added project is a change to the itinerary like any other: the user has to be
 * able to move it, retime it or drop it in the same place they do that for everything
 * else. Kept in a list of their own it was invisible next to the real days -- it showed up
 * as one small line above the budget, offering nothing but "remove".
 */
function additionsOfDay(day: string): PendingAddition[] {
  return pendingAdditions.value.filter((addition) => addition.date === day)
}

/** Retime or re-day a pending addition, exactly like an existing itinerary item. */
function setAdditionField(key: string, field: 'date' | 'start_time' | 'end_time', value: string): void {
  pendingAdditions.value = pendingAdditions.value.map((addition) =>
    addition.key === key ? { ...addition, [field]: value } : addition,
  )
}

/** Correct the price or count of a pending addition before it is saved. */
function setAdditionPrice(key: string, field: 'unit_price' | 'quantity', value: number | undefined): void {
  const addition = pendingAdditions.value.find((entry) => entry.key === key)
  if (!addition) return
  const next =
    typeof value === 'number' && Number.isFinite(value)
      ? field === 'quantity'
        ? Math.max(1, Math.round(value))
        : Math.max(0, Math.round(value))
      : addition[field]
  pendingAdditions.value = pendingAdditions.value.map((entry) =>
    entry.key === key ? { ...entry, [field]: next } : entry,
  )
}

function replaceItem(item: { id: number; date: string; start_time: string; end_time: string }): void {
  openAddForm({
    date: item.date,
    start_time: timeLabel(item.start_time),
    end_time: timeLabel(item.end_time),
    replaceItemId: item.id,
  })
}

// ---------------------------------------------------------------- operations

function additionToOperation(addition: PendingAddition, index: number): AdjustmentOperation {
  const quantityUnit = addition.category === 'hotel' || addition.category === 'restaurant' ? addition.quantity : null
  return {
    kind: 'add_place_and_item',
    item_id: null,
    place_id: null,
    place: {
      name: addition.name,
      type: addition.category,
      address: props.destination ? `${props.destination} · 地址待确认` : '地址待确认',
      latitude: addition.latitude,
      longitude: addition.longitude,
      // Only an absolute photo URL can be stored, so the bundled placeholder is dropped.
      image_url: addition.image_url && /^https?:\/\//i.test(addition.image_url) ? addition.image_url : null,
      rating: null,
      price_level: null,
      opening_hours: null,
      source_url: null,
      note: addition.note ?? addition.reason ?? null,
      estimated_cost: addition.unit_price === null ? null : addition.unit_price * Math.max(1, quantityUnit ?? 1),
      quantity: quantityUnit,
      preferred_date: addition.date,
    },
    date: addition.date,
    start_time: `${addition.start_time}:00`.slice(0, 8),
    end_time: `${addition.end_time}:00`.slice(0, 8),
    note: addition.note ?? addition.reason ?? null,
    sort_order: index,
  }
}

/** The edits made in the calendar, as the operations the backend validates. */
const pendingOperations = computed<AdjustmentOperation[]>(() => {
  const operations: AdjustmentOperation[] = []
  for (const itemId of pendingRemovals.value) {
    operations.push({
      kind: 'remove_item',
      item_id: itemId,
      place_id: null,
      place: null,
      date: null,
      start_time: null,
      end_time: null,
      note: null,
      sort_order: 0,
    })
  }
  for (const [rawId, move] of Object.entries(pendingMoves.value)) {
    operations.push({
      kind: 'update_item',
      item_id: Number(rawId),
      place_id: null,
      place: null,
      date: move.date,
      start_time: `${move.start_time}:00`.slice(0, 8),
      end_time: `${move.end_time}:00`.slice(0, 8),
      note: null,
      sort_order: 0,
    })
  }
  pendingAdditions.value.forEach((addition, index) => operations.push(additionToOperation(addition, index)))
  return operations
})

function describeOperation(operation: AdjustmentOperation): string {
  if (operation.kind === 'set_budget') {
    return operation.budget_amount === null || operation.budget_amount === undefined
      ? '调整旅行预算（金额待确认）'
      : `将旅行总预算调整为 ${operation.budget_amount} CNY`
  }
  if (operation.kind === 'remove_item') {
    const item = props.itineraryItems.find((entry) => entry.id === operation.item_id)
    return item ? `删除「${item.place.name}」（${item.date}）` : `删除行程项 #${operation.item_id}`
  }
  if (operation.kind === 'update_item') {
    const item = props.itineraryItems.find((entry) => entry.id === operation.item_id)
    const who = item ? `「${item.place.name}」` : `行程项 #${operation.item_id}`
    return `把${who}改到 ${operation.date} ${timeLabel(operation.start_time)} - ${timeLabel(operation.end_time)}`
  }
  const placeName = operation.place?.name ?? `收藏地点 #${operation.place_id}`
  return `${operation.date} ${timeLabel(operation.start_time)} - ${timeLabel(operation.end_time)}，${placeName}`
}

function operationLabel(operation: AdjustmentOperation): string {
  if (operation.kind === 'set_budget') return '调整旅行预算'
  if (operation.kind === 'remove_item') return '删除行程项'
  if (operation.kind === 'update_item') return '移动或改时间'
  if (operation.kind === 'add_place_and_item') return '新增地点并加入行程'
  return '新增行程'
}

// ---------------------------------------------------------------- draft

function operationDescription(operation: AdjustmentOperation): string {
  if (operation.kind === 'set_budget') {
    return operation.budget_amount === null || operation.budget_amount === undefined
      ? '调整旅行预算（金额待确认）'
      : `将旅行总预算调整为 ${operation.budget_amount} CNY`
  }
  if (operation.kind === 'remove_item') return `移除行程项 #${operation.item_id}`
  if (operation.kind === 'update_item') return `移动行程项 #${operation.item_id} 的日期或时间`
  const placeName = operation.place?.name ?? `收藏地点 #${operation.place_id}`
  return `${operation.date} ${timeLabel(operation.start_time)} - ${timeLabel(operation.end_time)}，${placeName}`
}

function selectAllRecommendations(checked: boolean): void {
  selectedOperationIndexes.value =
    checked && draft.value ? draft.value.operations.map((_, index) => index) : []
}

/**
 * A replacement is one decision made of two operations (remove + add). Selecting one half
 * alone would delete an item without putting anything in its place.
 */
function replacementPairIndexes(index: number): number[] {
  if (!draft.value) return [index]
  const operations = draft.value.operations
  const pair = (left: number, right: number): boolean => {
    const remove = operations[left]
    const add = operations[right]
    return (
      remove?.kind === 'remove_item' &&
      add?.kind === 'add_place_and_item' &&
      Boolean(add.note) &&
      Boolean(add.note?.includes('替换') || add.note?.toLowerCase().includes('replace'))
    )
  }
  if (index > 0 && pair(index - 1, index)) return [index - 1, index]
  if (index < operations.length - 1 && pair(index, index + 1)) return [index, index + 1]
  return [index]
}

function toggleRecommendation(index: number, checked: boolean): void {
  const indexes = replacementPairIndexes(index)
  const selected = new Set(selectedOperationIndexes.value)
  indexes.forEach((item) => (checked ? selected.add(item) : selected.delete(item)))
  selectedOperationIndexes.value = [...selected].sort((a, b) => a - b)
}

/** A short description of the clicks that produced the draft, used when the box is empty. */
function structuredInstruction(): string {
  const parts: string[] = []
  if (pendingRemovals.value.length > 0) parts.push(`删除 ${pendingRemovals.value.length} 个行程项`)
  if (Object.keys(pendingMoves.value).length > 0) parts.push(`移动或改时间 ${Object.keys(pendingMoves.value).length} 项`)
  if (pendingAdditions.value.length > 0) {
    parts.push(`新增 ${pendingAdditions.value.map((addition) => addition.name).join('、')}`)
  }
  return parts.length > 0 ? `在编辑面板上调整行程：${parts.join('；')}。` : ''
}

function candidatesForBackend() {
  return (handoff.value.planRecommendations ?? []).map((candidate) => ({
    name: candidate.name,
    category: candidate.category,
    reason: candidate.reason ?? null,
    estimated_cost: candidate.estimated_cost ?? null,
    unit_price: candidate.unit_price ?? null,
    quantity: candidate.quantity ?? null,
    quantity_unit: candidate.quantity_unit ?? null,
    image_url: candidate.image_url ?? null,
    signature_dish: candidate.signature_dish ?? null,
    rating: candidate.rating ?? null,
  }))
}

async function planDay(day: string): Promise<void> {
  // One day, arranged from what the trip already has. This only PREPARES the request: the
  // day button used to fire the whole search the moment it was clicked, which locked the
  // panel for twenty seconds with nothing to read or change. The traveller now gets the
  // request written out, edits it if they want, and sends it themselves.
  dayPlanDate.value = day
  instruction.value = `为 ${day} 这一天做一份极简计划：两处景点、三餐，不要和其它天重复。`
  statusMessage.value = '已填好极简规划这一天的请求，可以直接发送，也可以先修改。'
  await focusInput()
}

/** Drop a prepared request the traveller decided against. */
function cancelDayPlan(): void {
  dayPlanDate.value = ''
  instruction.value = ''
  statusMessage.value = '已取消极简规划这一天。'
}

/** Whether a typed request is asking for new places rather than for an edit. */
function looksLikeDiscovery(text: string): boolean {
  const body = text.trim()
  if (!body) return false
  const asking = /推荐|有没有|有没|找几|给我几|有什么|还有|别的|其他|另外/.test(body)
  const about = /景点|地方|去处|项目|活动|餐厅|美食|小吃|吃|玩|逛|体验/.test(body)
  return asking && about
}

/** Whether a typed request is asking for one day to be arranged from scratch. */
function looksLikeDayPlan(text: string): boolean {
  const body = text.trim()
  if (!body) return false
  // Answering this by hand in the ordinary adjustment produced a plan of whatever the model
  // felt like -- three meals, invented times. It has to reach the day planner instead.
  return /极简|简单安排|排一下这一?天|规划这一?天|安排这一?天|这一天的计划|当天行程|这一天排/.test(body)
}

/** Prepare a "find me places" request: written out, editable, sent only when the traveller sends it. */
async function promptDiscovery(question = '有没有更适合亲子游玩的项目或者景点，给我推荐几个？'): Promise<void> {
  dayPlanDate.value = ''
  discoverMode.value = true
  instruction.value = question
  statusMessage.value = '已填好找地点的请求，可以直接发送，也可以改成你想要的方向。'
  await focusInput()
}

/** Drop a prepared search the traveller decided against. */
function cancelDiscovery(): void {
  discoverMode.value = false
  instruction.value = ''
  statusMessage.value = '已取消找地点。'
}

// Per-candidate adjustments made inside the Copilot: the time or the day of one candidate.
// Kept by operation index so the row the traveller typed in is the row that changes.
const inlineEdits = ref<Record<number, { date?: string; start_time?: string; end_time?: string }>>({})

function inlineEdit(index: number): { date?: string; start_time?: string; end_time?: string } {
  return inlineEdits.value[index] ?? {}
}

function setInlineValue(index: number, field: 'date' | 'start_time' | 'end_time', value: string): void {
  inlineEdits.value = { ...inlineEdits.value, [index]: { ...inlineEdit(index), [field]: value } }
}

function isSelectedOperation(index: number): boolean {
  return selectedOperationIndexes.value.includes(index)
}

/** Removing a candidate is un-ticking it: nothing is written unless it stays ticked. */
function toggleSelectedOperation(index: number): void {
  selectedOperationIndexes.value = isSelectedOperation(index)
    ? selectedOperationIndexes.value.filter((value) => value !== index)
    : [...selectedOperationIndexes.value, index].sort((first, second) => first - second)
}

async function createDraft(): Promise<void> {
  if (creating.value || applying.value || rejecting.value || saving.value) return
  const typed = instruction.value.trim()
  const structured = hasStructuredEdits.value
  if (!typed && !structured) {
    errorMessage.value = '请在行程上做出改动，或写下你想怎么调整。'
    return
  }
  // A typed "极简规划这一天" is answered by the day planner too, and the panel has to say so
  // while it runs -- otherwise the request looks like an ordinary adjustment.
  if (!dayPlanDate.value && looksLikeDayPlan(typed) && props.activeDate) dayPlanDate.value = props.activeDate
  creating.value = true
  errorMessage.value = ''
  statusMessage.value = ''
  const previousEstimate = currentEstimate.value
  try {
    const createdDraft = await createAdjustmentDraft(props.tripId, {
      instruction: props.compact && draft.value?.status === 'pending' && typed ? `先前请求：${draft.value.instruction}\n待确认的方案：${draft.value.summary}\n在此基础上继续调整：${typed}` : typed || structuredInstruction(),
      include_web: includeWeb.value,
      // Sending the operations is what makes the model stay out of a change the user
      // already spelled out by clicking. If they ALSO typed something, the backend keeps
      // the clicks and lets the model handle whatever the clicks did not decide.
      operations: structured ? pendingOperations.value : undefined,
      structured_only: structured && !typed,
      candidates: candidatesForBackend(),
      // Only the day button sends these; every other request is the ordinary adjustment.
      ...(dayPlanDate.value
        ? { mode: 'day_plan' as const, date: dayPlanDate.value }
        : looksLikeDayPlan(typed)
          ? { mode: 'day_plan' as const, date: props.activeDate || undefined }
          : discoverMode.value || looksLikeDiscovery(typed)
            ? { mode: 'discover' as const, date: props.activeDate || undefined }
            : {}),
    })
    draft.value = createdDraft
    beforeEstimate.value = previousEstimate
    // The prepared request has been answered, so it stops being "waiting to be sent".
    dayPlanDate.value = ''
    discoverMode.value = false
    editedOperationPrices.value = {}
    if (props.compact) { conversation.value.push({ role: 'user', text: typed || structuredInstruction() }, { role: 'assistant', text: createdDraft.summary }); instruction.value = '' }
    selectedOperationIndexes.value = draft.value.operations.map((_, index) => index)
    if (draft.value.final_summary) setAdjustmentSummary(draft.value.final_summary)
    statusMessage.value = props.compact ? '' : '草案已生成，确认无误后再应用。'
    if (props.compact) {
      await nextTick()
      if (conversationRoot.value) conversationRoot.value.scrollTop = conversationRoot.value.scrollHeight
    }
  } catch {
    errorMessage.value = '无法生成调整草案，请确认后端服务已启动后重试。'
  } finally {
    creating.value = false
  }
}

/** The draft's operations with any price or count the user corrected in the preview. */
function editedOperations(): AdjustmentOperation[] {
  if (!draft.value) return []
  const edits = editedOperationPrices.value
  if (Object.keys(edits).length === 0 && Object.keys(inlineEdits.value).length === 0) return []
  return draft.value.operations.map((operation, index) => {
    const edit = edits[index]
    // A candidate the traveller retimed or moved inside the panel travels with that edit, so
    // applying writes the time they chose rather than the one that was suggested.
    const inline = inlineEdits.value[index]
    const base = inline
      ? { ...operation, date: inline.date || operation.date, start_time: inline.start_time || operation.start_time, end_time: inline.end_time || operation.end_time }
      : operation
    if (!edit || !operation.place) return base
    const quantity = operation.place.quantity === null || operation.place.quantity === undefined ? null : edit.quantity
    const unit = edit.unit_price
    return {
      ...base,
      place: {
        ...operation.place,
        estimated_cost: unit === null ? null : unit * Math.max(1, quantity ?? 1),
        quantity,
      },
    }
  })
}

function priceEditFor(row: AdjustmentBudgetRow): { unit_price: number; quantity: number } | undefined {
  if (row.operation_index === null || row.operation_index === undefined) return undefined
  return editedOperationPrices.value[row.operation_index]
}

function unitPriceOf(row: AdjustmentBudgetRow): number {
  return priceEditFor(row)?.unit_price ?? row.unit_price ?? row.amount
}

function quantityOfRow(row: AdjustmentBudgetRow): number {
  return priceEditFor(row)?.quantity ?? row.quantity ?? 1
}

function rowAmount(row: AdjustmentBudgetRow): number {
  const edit = priceEditFor(row)
  if (!edit) return row.amount
  return edit.unit_price * Math.max(1, edit.quantity)
}

/**
 * Only a row whose operation will create a brand-new place can be repriced here, because
 * the price is part of that operation. A place that is already saved is repriced in the
 * "已收藏地点的价格" panel instead, which writes straight to the place.
 */
function canEditRow(row: AdjustmentBudgetRow): boolean {
  const index = row.operation_index
  if (index === null || index === undefined) return false
  if (row.source !== 'quoted' && row.source !== 'level') return false
  return draft.value?.operations[index]?.kind === 'add_place_and_item'
}

/** The price of a saved place is not part of any operation, so it is edited elsewhere. */
function rowIsSavedPlace(row: AdjustmentBudgetRow): boolean {
  const index = row.operation_index
  if (index === null || index === undefined) return false
  const kind = draft.value?.operations[index]?.kind
  return (kind === 'add_item' || kind === 'update_item') && Boolean(row.place_id)
}

function setRowPrice(row: AdjustmentBudgetRow, value: number | undefined): void {
  if (row.operation_index === null || row.operation_index === undefined) return
  const unit = typeof value === 'number' && Number.isFinite(value) ? Math.max(0, Math.round(value)) : row.unit_price ?? row.amount
  editedOperationPrices.value = {
    ...editedOperationPrices.value,
    [row.operation_index]: { unit_price: unit, quantity: quantityOfRow(row) },
  }
}

function setRowQuantity(row: AdjustmentBudgetRow, value: number | undefined): void {
  if (row.operation_index === null || row.operation_index === undefined) return
  const quantity = typeof value === 'number' && Number.isFinite(value) ? Math.max(1, Math.round(value)) : quantityOfRow(row)
  editedOperationPrices.value = {
    ...editedOperationPrices.value,
    [row.operation_index]: { unit_price: unitPriceOf(row), quantity },
  }
}

/** The figure the panel shows while the user is still choosing; backend confirms it. */
const localEstimate = computed(() => {
  const subjects: BudgetSubject[] = effectiveItems.value.map((item) => ({
    name: item.place.name,
    category: item.place.type,
    quoted_cost: item.place.estimated_cost,
    price_level: item.place.price_level,
    quantity: item.place.quantity,
  }))
  for (const addition of pendingAdditions.value) {
    // What the form collects is a UNIT price ("每晚价格" / "单价"), while a quoted cost
    // covers the whole count -- the same conversion `additionToOperation` does.
    const counted = addition.category === 'hotel' || addition.category === 'restaurant'
    const quoted =
      addition.unit_price === null
        ? null
        : addition.unit_price * (counted ? Math.max(1, addition.quantity) : 1)
    subjects.push({
      name: addition.name,
      category: addition.category,
      quoted_cost: quoted,
      quantity: addition.quantity,
    })
  }
  return estimateItinerary(subjects, effectiveDays.value, totalPeople.value)
})

const draftRows = computed<AdjustmentBudgetRow[]>(() => (draft.value?.budget.breakdown ?? []) as AdjustmentBudgetRow[])
const draftTotal = computed(() => {
  if (!draft.value) return localEstimate.value.total
  const base = draft.value.budget.total_estimate ?? 0
  const diff = draftRows.value.reduce((sum, row) => sum + (rowAmount(row) - row.amount), 0)
  return base + diff
})

const budgetRows = computed<AdjustmentBudgetRow[]>(() => {
  if (draft.value && draftRows.value.length > 0) return draftRows.value
  return [
    {
      category: 'transport',
      name: '市内交通基础估算',
      amount: localEstimate.value.transport,
      source: 'baseline',
      unit_price: localEstimate.value.transport,
      quantity: 1,
    },
    ...localEstimate.value.lines.map((line) => ({
      category: line.category,
      name: line.name,
      amount: line.amount,
      unit_price: line.unit_price,
      quantity: line.quantity,
      quantity_unit: line.quantity_unit,
      source: line.source,
    })),
  ] as AdjustmentBudgetRow[]
})

const currency = computed(() => draft.value?.budget.currency ?? 'CNY')
const tripBudget = computed(() => draft.value?.budget.trip_budget ?? props.originalBudget)
const remaining = computed(() =>
  tripBudget.value === null ? null : tripBudget.value - draftTotal.value,
)

function rowSourceLabel(row: AdjustmentBudgetRow): string {
  if (row.source === 'baseline') return '基础'
  if (row.source === 'quoted') return '你确认过的价格'
  return '按价格等级估算'
}

async function confirmDraft(): Promise<void> {
  if (applying.value || rejecting.value || draft.value?.status !== 'pending') return
  if (!draft.value || selectedOperationIndexes.value.length === 0) {
    errorMessage.value = '请至少选择一项要应用的改动。'
    return
  }
  applying.value = true
  errorMessage.value = ''
  try {
    const edits = editedOperations()
    const edited = edits.length > 0 ? selectedOperationIndexes.value.map((index) => edits[index]) : undefined
    await applyAdjustment(
      props.tripId,
      draft.value.id,
      edited ? undefined : selectedOperationIndexes.value,
      edited,
    )
    draft.value = { ...draft.value, status: 'applied' }
    // The calendar is now the applied one, so the local edit list is cleared.
    pendingRemovals.value = []
    pendingMoves.value = {}
    pendingAdditions.value = []
    editedOperationPrices.value = {}
    statusMessage.value = '已应用，上面的每日行程已更新。'
    emit('itinerary-updated')
    emit('places-updated')
    if (draft.value.budget.trip_budget !== undefined) emit('budget-updated', draft.value.budget.trip_budget ?? null)
  } catch {
    errorMessage.value = '应用草案失败，当前行程没有被修改。'
  } finally {
    applying.value = false
  }
}

/**
 * Write the pending on-calendar edits straight into the real itinerary.
 *
 * The draft-and-apply ceremony exists for text requests, where the model may propose
 * something the user has to read before agreeing to. Edits made by clicking need no such
 * review: the user is looking at exactly what will be written. Without this button the
 * only way to save a new project was to generate a draft and then apply it, which is why
 * an addition looked stranded above the budget with a lone "remove".
 *
 * The write still goes through the validated draft-and-apply path, so nothing bypasses the
 * checks (trip date range, item ownership, price rules); it is simply done in one click.
 */
async function saveToItinerary(): Promise<void> {
  const typed = instruction.value.trim()
  const structured = hasStructuredEdits.value
  if (!structured) {
    errorMessage.value = '还没有要保存的改动：先在行程上删除、移动或新增项目。'
    return
  }
  saving.value = true
  errorMessage.value = ''
  statusMessage.value = ''
  try {
    const created = await createAdjustmentDraft(props.tripId, {
      instruction: typed || structuredInstruction(),
      include_web: includeWeb.value,
      operations: pendingOperations.value,
      structured_only: structured && !typed,
      candidates: candidatesForBackend(),
    })
    if (created.operations.length === 0) {
      errorMessage.value = '没有可保存的改动，请检查你选择的日期和时段。'
      return
    }
    await applyAdjustment(props.tripId, created.id)
    // The apply response is only an acknowledgement, so the created draft is what the
    // panel keeps: it already holds the operations and the recalculated budget.
    const applied: AdjustmentDraft = { ...created, status: 'applied' }
    draft.value = applied
    pendingRemovals.value = []
    pendingMoves.value = {}
    pendingAdditions.value = []
    editedOperationPrices.value = {}
    statusMessage.value = '已保存到每日行程。'
    if (applied.final_summary) setAdjustmentSummary(applied.final_summary)
    emit('itinerary-updated')
    emit('places-updated')
    if (applied.budget.trip_budget !== undefined) emit('budget-updated', applied.budget.trip_budget ?? null)
  } catch {
    errorMessage.value = '保存失败，行程没有被修改。'
  } finally {
    saving.value = false
  }
}

async function rejectDraft(): Promise<void> {
  if (!draft.value || draft.value.status !== 'pending' || rejecting.value || applying.value) return
  rejecting.value = true
  errorMessage.value = ''
  try {
    draft.value = await rejectAdjustment(props.tripId, draft.value.id)
    statusMessage.value = '已丢弃草案，行程和预算都没有变化。'
  } catch {
    errorMessage.value = '拒绝草案失败，请稍后重试。'
  } finally {
    rejecting.value = false
  }
}

function resetEdits(): void {
  pendingRemovals.value = []
  pendingMoves.value = {}
  pendingAdditions.value = []
  statusMessage.value = '已取消本次未应用的改动。'
}

/**
 * Correct the price of a place that is already saved. Without this the price was
 * write-once: the only way to fix a wrong number was to delete the place and add it again.
 */
const rawPrices = ref<Record<number, number | null>>({})
const savingPrice = ref<number | null>(null)

function rawPriceOf(place: Place): number | null {
  return place.id in rawPrices.value ? rawPrices.value[place.id] : place.estimated_cost ?? null
}

async function saveRawPrice(place: Place): Promise<void> {
  savingPrice.value = place.id
  errorMessage.value = ''
  try {
    await updatePlace(props.tripId, place.id, { estimated_cost: rawPriceOf(place) })
    statusMessage.value = `已更新「${place.name}」的价格。`
    emit('places-updated')
  } catch {
    errorMessage.value = '更新价格失败，请稍后重试。'
  } finally {
    savingPrice.value = null
  }
}

const savedPlacesWithPrice = computed(() => props.places.slice(0, 40))

const editorOpen = ref(false)
const composer = ref<HTMLTextAreaElement | null>(null)
const conversationRoot = ref<HTMLElement | null>(null)
let conversationResize: ResizeObserver | undefined
onMounted(() => {
  if (!props.compact || typeof ResizeObserver === 'undefined' || !conversationRoot.value) return
  conversationResize = new ResizeObserver(() => {
    if (draft.value && conversationRoot.value) conversationRoot.value.scrollTop = conversationRoot.value.scrollHeight
  })
  conversationResize.observe(conversationRoot.value)
})
onBeforeUnmount(() => conversationResize?.disconnect())
const conversation = ref<Array<{ role: 'user' | 'assistant'; text: string }>>([])
const showConversation = ref(false)
const beforeEstimate = ref<number | null>(null)
const currentEstimate = computed(() => props.itineraryItems.length ? estimateItinerary(props.itineraryItems.map(item => ({ name: item.place.name, category: item.place.type, quoted_cost: item.place.estimated_cost, price_level: item.place.price_level, quantity: item.place.quantity })), effectiveDays.value, props.people).total : null)
const draftDescriptions = computed(() => draft.value?.operations.map(describeOperation) ?? [])
const compactTotal = computed(() => draft.value?.budget.total_estimate === undefined ? null : draftTotal.value)
async function focusInput() { await nextTick(); composer.value?.focus() }
async function openEditor(itemId?: number) {
  editorOpen.value = true
  if (itemId !== undefined) {
    await nextTick()
    const item = props.itineraryItems.find(item => item.id === itemId)
    if (item) statusMessage.value = `正在编辑「${item.place.name}」：在对应日期下修改时间，再保存。`
  }
}
function promptAdjustment(text: string) { instruction.value = text; void focusInput() }
function composerKey(event: KeyboardEvent) { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); void createDraft() } }
defineExpose({ openEditor, focusInput, planDay })

</script>

<template>

  <div v-if="compact" class="compact-adjustment">
    <div v-if="conversation.length && draft" class="copilot-summary"><p class="copilot-last-request" :title="conversation.filter(message => message.role === 'user').at(-1)?.text"><strong>你的要求</strong><span>{{ conversation.filter(message => message.role === 'user').at(-1)?.text }}</span></p><p class="copilot-answer">{{ draft.summary }}</p><button type="button" class="copilot-history-toggle" @click="showConversation = !showConversation">{{ showConversation ? '收起对话记录' : '查看对话记录' }}</button></div>
    <div ref="conversationRoot" class="copilot-conversation" aria-live="polite" aria-relevant="additions text">
      <div v-if="!conversation.length" class="copilot-welcome"><div class="assistant-introduction"><span class="assistant-avatar"><AppLogo /></span><div><p>你好，我是你的旅行搭子。</p><p>一起把{{ destination }}之旅，安排成你喜欢的样子。</p></div></div><p class="copilot-context">我会参考你的 {{ days.length }} 天行程、{{ places.length }} 个收藏地点和旅行预算。</p><div class="copilot-prompts"><button @click="promptAdjustment('我希望下午的行程更轻松一些，同时帮我降低一点预算。')">让下午更轻松，预算更友好<el-icon><TopRight /></el-icon></button><button @click="promptAdjustment('帮我检查每天的行程，减少不必要的来回奔波。')">减少绕路，留多一点休息时间<el-icon><TopRight /></el-icon></button><button @click="promptDiscovery()">找几个适合亲子的地方<el-icon><TopRight /></el-icon></button><button @click="promptDiscovery('给我推荐几个适合雨天或者室内的项目，大概四五个。')">雨天/室内备选<el-icon><TopRight /></el-icon></button><button @click="emit('discover')">发现当地值得一去的地方<el-icon><TopRight /></el-icon></button></div><p class="copilot-reassurance">调整会先生成草案，等你确认后再应用。</p></div>
      <template v-if="!draft || showConversation"><div v-for="(message, index) in conversation" :key="index" class="chat-message" :class="message.role"><span v-if="message.role === 'assistant'" class="assistant-avatar"><AppLogo /></span><p>{{ message.text }}</p></div></template>
      <div v-if="creating" class="copilot-thinking" role="status"><span class="thinking-dot" />{{ dayPlanDate ? `正在为 ${dayPlanDate} 检索素材并排这一天的行程，约 20 秒；完成后先生成草案，你确认后再应用…` : discoverMode ? '正在按你的要求检索新的地点并核价配图，约 20–60 秒；完成后逐个列出来给你挑…' : '正在结合行程和预算整理方案…' }}</div>
      <div v-if="draft && draft.status === 'pending' && draft.operations.some((operation) => operation.kind === 'add_place_and_item')" class="copilot-status" role="group" aria-label="逐条调整推荐地点">
        <strong>逐条调整</strong>　每条候选都可以改日期和时间；点「移除」就不要这一条，点「恢复」再放回来。改完点下面的「应用选中」才会写入行程。
        <div v-for="(operation, index) in draft.operations" :key="`inline-${index}`">
          <template v-if="operation.kind === 'add_place_and_item' && operation.place">
            <span :style="isSelectedOperation(index) ? 'display:inline-flex;align-items:center;gap:6px;margin:4px 0;flex-wrap:wrap' : 'display:inline-flex;align-items:center;gap:6px;margin:4px 0;flex-wrap:wrap;opacity:.45;text-decoration:line-through'">
              <span>{{ operation.place.name }}</span>
              <input type="date" :aria-label="`${operation.place.name} 的日期`" :value="inlineEdit(index).date || operation.date || ''" @change="setInlineValue(index, 'date', ($event.target as HTMLInputElement).value)" />
              <input type="time" :aria-label="`${operation.place.name} 的开始时间`" :value="(inlineEdit(index).start_time || operation.start_time || '').slice(0, 5)" @change="setInlineValue(index, 'start_time', ($event.target as HTMLInputElement).value)" />
              <span>至</span>
              <input type="time" :aria-label="`${operation.place.name} 的结束时间`" :value="(inlineEdit(index).end_time || operation.end_time || '').slice(0, 5)" @change="setInlineValue(index, 'end_time', ($event.target as HTMLInputElement).value)" />
              <button type="button" class="copilot-history-toggle" @click="toggleSelectedOperation(index)">{{ isSelectedOperation(index) ? '移除' : '恢复' }}</button>
            </span>
          </template>
        </div>
      </div>
      <AdjustmentPreview v-if="draft" :draft="draft" :descriptions="draftDescriptions" :before="beforeEstimate" :after="compactTotal" :applying="applying" :rejecting="rejecting" :can-apply="selectedOperationIndexes.length > 0" :full-selection="selectedOperationIndexes.length === draft.operations.length" @apply="confirmDraft" @reject="rejectDraft" @continue="focusInput" @details="openEditor()" />
      <p v-if="errorMessage" class="copilot-error" role="alert">{{ errorMessage }}</p><p v-if="statusMessage" class="copilot-status" role="status">{{ statusMessage }}</p>
    </div>
    <p v-if="dayPlanDate" class="copilot-status" role="status"><strong>极简规划这一天 · {{ dayPlanDate }}</strong>　两处景点＋三餐，不与其他天重复；下面这段请求可以直接发送，也可以先改再发送。<button type="button" class="copilot-history-toggle" @click="cancelDayPlan">取消</button></p>
    <p v-else-if="discoverMode" class="copilot-status" role="status"><strong>找地点 · 建议放到 {{ activeDate || '当前这天' }}</strong>　我会去找几个你行程里还没有的地方，逐个给你挑；勾选后再应用，不勾的不会动你的行程。<button type="button" class="copilot-history-toggle" @click="cancelDiscovery">取消</button></p>
    <form class="copilot-composer" @submit.prevent="createDraft"><label class="sr-only" :for="`copilot-input-${tripId}`">告诉 AI 你想怎样调整行程</label><textarea :id="`copilot-input-${tripId}`" ref="composer" v-model="instruction" rows="3" placeholder="告诉我你的想法，比如「更文艺一些」「增加亲子活动」…" :disabled="creating" @keydown="composerKey" /><div class="composer-actions"><button class="icon-button" type="button" aria-label="上传旅行资料" @click="emit('files')"><el-icon><Paperclip /></el-icon></button><button type="submit" class="send-message" :disabled="!instruction.trim() || creating || applying || rejecting" :aria-label="creating ? '正在生成调整草案' : '发送给 AI'"><el-icon><Position /></el-icon></button></div></form><p class="composer-hint">Enter 发送 · Shift + Enter 换行</p>
  </div>
  <WorkspaceDrawer v-model="editorOpen" :enabled="compact" title="编辑行程与预算" size="min(860px, 100vw)">
  <section class="adjustment-section" aria-label="行程调整 Agent">
    <div class="section-heading">
      <div>
        <p class="section-label">ITINERARY EDITOR · 每日行程编辑器</p>
        <h2>编辑每日行程（对话式）</h2>
        <p class="section-note">
          直接在上面“每日行程”里删除、移动、改时间或新增项目，也可以用下面的自然语言说明要求；
          所有改动先变成草案，确认后才写入行程，预算会跟着你实际增删的项目重算。
        </p>
      </div>
      <el-icon class="agent-icon"><MagicStick /></el-icon>
    </div>

    <p v-if="errorMessage" class="error-message" role="alert">{{ errorMessage }}</p>
    <p v-if="statusMessage" class="status-message" role="status">{{ statusMessage }}</p>

    <CollapsiblePanel
      class="editor-panel"
      aria-label="每日行程编辑器"
      panel-key="day-editor"
      :scope="tripId"
      title="第 1 步 · 在行程上直接改"
      heading-tag="h3"
      :count="`${days.length} 天 · ${activeItemCount} 项`"
      :default-open="defaultOpenForCount(activeItemCount)"
    >
      <template #actions>
        <el-button :icon="Plus" aria-label="新增项目" @click="openAddForm()">新增项目</el-button>
        <el-button
          type="primary"
          :icon="Check"
          :loading="saving"
          :disabled="!hasStructuredEdits"
          aria-label="保存到每日行程"
          @click="saveToItinerary"
        >保存到每日行程</el-button>
        <el-button
          v-if="hasStructuredEdits"
          :icon="Refresh"
          aria-label="取消未应用的改动"
          @click="resetEdits"
        >取消改动</el-button>
      </template>

      <p class="draft-note">带删除线的项目将被移除，新增的项目会出现在对应日期里；改动会显示在右侧预算里，但还没有保存。</p>

      <p v-if="days.length === 0" class="empty-state">这个旅行项目还没有日期范围。</p>
      <div v-else class="day-list">
        <article v-for="day in days" :key="day" class="day-card">
          <header class="day-card-heading">
            <strong>{{ day }}</strong>
            <span>{{ activeItemsOfDay(day).length + additionsOfDay(day).length }} 项</span>
          </header>
          <p v-if="itemsOfDay(day).length === 0 && additionsOfDay(day).length === 0" class="empty-state">这一天还没有安排。</p>
          <ul v-else class="day-items">
            <li
              v-for="item in itemsOfDay(day)"
              :key="item.id"
              class="day-item"
              :class="{ 'is-removed': isRemoved(item.id) }"
            >
              <div class="day-item-main">
                <span class="day-item-time">{{ timeLabel(item.start_time) }} - {{ timeLabel(item.end_time) }}</span>
                <strong>{{ item.place.name }}</strong>
                <small>{{ categoryLabel(item.place.type) }}</small>
                <em v-if="moveOf(item.id)" class="pending-badge">已改时间，待应用</em>
              </div>
              <div class="day-item-actions">
                <el-button size="small" text aria-label="替换这个行程项" @click="replaceItem(item)">替换</el-button>
                <el-button
                  size="small"
                  text
                  :aria-label="isRemoved(item.id) ? '恢复这个行程项' : '删除这个行程项'"
                  @click="toggleRemoval(item.id)"
                >{{ isRemoved(item.id) ? '恢复' : '删除' }}</el-button>
                <el-icon class="row-icon"><Delete /></el-icon>
              </div>
              <div class="move-row">
                <label>
                  <span>日期</span>
                  <input
                    type="date"
                    :value="moveOf(item.id)?.date ?? item.date"
                    :aria-label="`移动 ${item.place.name} 的日期`"
                    @change="(event) => setMoveField(item.id, 'date', (event.target as HTMLInputElement).value)"
                  >
                </label>
                <label>
                  <span>开始</span>
                  <input
                    type="time"
                    :value="timeLabel(moveOf(item.id)?.start_time ?? item.start_time)"
                    :aria-label="`调整 ${item.place.name} 的开始时间`"
                    @change="(event) => setMoveField(item.id, 'start_time', (event.target as HTMLInputElement).value)"
                  >
                </label>
                <label>
                  <span>结束</span>
                  <input
                    type="time"
                    :value="timeLabel(moveOf(item.id)?.end_time ?? item.end_time)"
                    :aria-label="`调整 ${item.place.name} 的结束时间`"
                    @change="(event) => setMoveField(item.id, 'end_time', (event.target as HTMLInputElement).value)"
                  >
                </label>
                <el-button
                  v-if="moveOf(item.id)"
                  size="small"
                  text
                  aria-label="撤销时间改动"
                  @click="resetMove(item.id)"
                >撤销</el-button>
              </div>
            </li>
            <li
              v-for="addition in additionsOfDay(day)"
              :key="addition.key"
              class="day-item is-added"
            >
              <div class="day-item-main">
                <span class="day-item-time">{{ addition.start_time }} - {{ addition.end_time }}</span>
                <strong>{{ addition.name }}</strong>
                <small>{{ categoryLabel(addition.category) }}</small>
                <em class="pending-badge">新增，待保存</em>
              </div>
              <div class="day-item-actions">
                <label class="inline-price">
                  <span>单价</span>
                  <input
                    type="number"
                    min="0"
                    class="inline-number"
                    :value="addition.unit_price ?? ''"
                    :aria-label="`${addition.name} 的单价`"
                    @change="(event) => setAdditionPrice(addition.key, 'unit_price', Number((event.target as HTMLInputElement).value))"
                  >
                </label>
                <label
                  v-if="addition.category === 'hotel' || addition.category === 'restaurant'"
                  class="inline-price"
                >
                  <span>次数</span>
                  <input
                    type="number"
                    min="1"
                    max="99"
                    class="inline-number"
                    :value="addition.quantity"
                    :aria-label="`${addition.name} 的次数`"
                    @change="(event) => setAdditionPrice(addition.key, 'quantity', Number((event.target as HTMLInputElement).value))"
                  >
                </label>
                <el-button size="small" text type="danger" aria-label="移除待新增项目" @click="removeAddition(addition.key)">移除</el-button>
              </div>
              <div class="move-row">
                <label>
                  <span>日期</span>
                  <input
                    type="date"
                    :value="addition.date"
                    :aria-label="`${addition.name} 的日期`"
                    @change="(event) => setAdditionField(addition.key, 'date', (event.target as HTMLInputElement).value)"
                  >
                </label>
                <label>
                  <span>开始</span>
                  <input
                    type="time"
                    :value="addition.start_time"
                    :aria-label="`${addition.name} 的开始时间`"
                    @change="(event) => setAdditionField(addition.key, 'start_time', (event.target as HTMLInputElement).value)"
                  >
                </label>
                <label>
                  <span>结束</span>
                  <input
                    type="time"
                    :value="addition.end_time"
                    :aria-label="`${addition.name} 的结束时间`"
                    @change="(event) => setAdditionField(addition.key, 'end_time', (event.target as HTMLInputElement).value)"
                  >
                </label>
              </div>
            </li>
          </ul>
        </article>
      </div>

      <section v-if="showAddForm" class="add-form" aria-label="新增项目表单">
        <h4>{{ replaceTargetId === null ? '新增一个项目' : '替换为新的项目' }}</h4>

        <div v-if="plannedCandidates.length > 0" class="candidate-picker">
          <p class="candidate-hint">
            从上面规划建议里挑一个（价格就是卡片上的价格，可以改）：
          </p>
          <div class="candidate-chips">
            <button
              v-for="candidate in plannedCandidates"
              :key="candidate.name"
              type="button"
              class="candidate-chip"
              :aria-label="`选择推荐项目 ${candidate.name}`"
              @click="pickRecommendation(candidate)"
            >
              <strong>{{ candidate.name }}</strong>
              <span>{{ categoryLabel(candidate.category) }}</span>
              <span v-if="candidate.estimated_cost !== null && candidate.estimated_cost !== undefined">
                约 {{ candidate.estimated_cost }} CNY
              </span>
            </button>
          </div>
        </div>
        <p v-else class="candidate-hint">
          还没有规划建议可以挑选：先在上面生成规划，或直接自己填写下面这一项。
        </p>

        <div v-if="unscheduledPlaces.length > 0" class="saved-picker">
          <span class="picker-label">或从收藏地点里选：</span>
          <el-select
            placeholder="收藏地点"
            aria-label="从收藏地点选择"
            :model-value="''"
            @change="(value: number) => { const place = unscheduledPlaces.find((entry) => entry.id === value); if (place) pickSavedPlace(place) }"
          >
            <el-option
              v-for="place in unscheduledPlaces"
              :key="place.id"
              :label="`${place.name}（${categoryLabel(place.type)}）`"
              :value="place.id"
            />
          </el-select>
        </div>

        <div class="form-grid">
          <label>
            <span>名称</span>
            <input v-model="addForm.name" aria-label="新增项目名称" placeholder="例如：鸭血粉丝汤 / 夫子庙">
          </label>
          <label>
            <span>类型</span>
            <select v-model="addForm.category" aria-label="新增项目类型">
              <option value="attraction">景点</option>
              <option value="restaurant">餐厅</option>
              <option value="hotel">酒店</option>
              <option value="airport">机场</option>
              <option value="station">车站</option>
            </select>
          </label>
        </div>
        <div class="form-grid">
          <label>
            <span>日期</span>
            <select v-model="addForm.date" aria-label="新增项目日期">
              <option v-for="day in days" :key="day" :value="day">{{ day }}</option>
            </select>
          </label>
          <label>
            <span>常用时段</span>
            <select
              aria-label="常用时段"
              :value="`${addForm.start_time}-${addForm.end_time}`"
              @change="(event) => {
                const preset = SLOT_PRESETS.find((entry) => `${entry.start_time}-${entry.end_time}` === (event.target as HTMLSelectElement).value)
                if (preset) { addForm.start_time = preset.start_time; addForm.end_time = preset.end_time }
              }"
            >
              <option v-for="preset in SLOT_PRESETS" :key="preset.label" :value="`${preset.start_time}-${preset.end_time}`">
                {{ preset.label }}
              </option>
            </select>
          </label>
        </div>
        <div class="form-grid">
          <label>
            <span>开始时间</span>
            <input v-model="addForm.start_time" type="time" aria-label="新增项目开始时间">
          </label>
          <label>
            <span>结束时间</span>
            <input v-model="addForm.end_time" type="time" aria-label="新增项目结束时间">
          </label>
        </div>
        <div class="form-grid">
          <label>
            <span>{{ addForm.category === 'hotel' ? '每晚价格' : '单价' }}（CNY）</span>
            <input
              type="number"
              min="0"
              aria-label="新增项目单价"
              :value="addForm.unit_price ?? ''"
              @input="(event) => { const raw = (event.target as HTMLInputElement).value; addForm.unit_price = raw === '' ? null : Math.max(0, Number(raw)) }"
            >
          </label>
          <label v-if="addForm.category === 'hotel' || addForm.category === 'restaurant'">
            <span>{{ addForm.category === 'hotel' ? '住几晚' : '吃几次' }}</span>
            <input
              type="number"
              min="1"
              max="99"
              aria-label="新增项目次数"
              :value="addForm.quantity"
              @input="(event) => { addForm.quantity = Math.max(1, Number((event.target as HTMLInputElement).value) || 1) }"
            >
          </label>
        </div>
        <p v-if="addForm.unit_price === null" class="form-hint">
          留空也可以：预算会按{{ categoryLabel(addForm.category) }}的中档价格估算。
        </p>

        <div class="editor-commands">
          <el-button type="primary" :icon="Check" aria-label="确认新增项目" @click="confirmAddition">加入待应用改动</el-button>
          <el-button :icon="Close" aria-label="取消新增项目" @click="cancelAddition">取消</el-button>
        </div>
      </section>
    </CollapsiblePanel>

    <CollapsiblePanel
      class="budget-panel"
      aria-label="调整后的预算估算"
      panel-key="adjustment-budget"
      :scope="tripId"
      title="调整后的预算估算"
      heading-tag="h3"
      :count="`${draftTotal} ${currency}`"
      :default-open="defaultOpenForCount(budgetRows.length)"
      :auto-open-token="hasStructuredEdits"
    >
      <p class="budget-total">
        <strong>{{ draftTotal }}</strong> {{ currency }}
        <span v-if="remaining !== null" :class="{ 'over-budget': remaining < 0 }">
          （旅行预算 {{ tripBudget }}，剩余 {{ remaining }}）
        </span>
      </p>
      <p class="draft-note">
        逐项列出「每日行程」里实际安排的项目：删掉的项目不出现在这里，也就不再计费；新增项目按你填的价格或中档价估算。
      </p>
      <table class="budget-table">
        <thead>
          <tr>
            <th scope="col">项目</th>
            <th scope="col">单价</th>
            <th scope="col">次数</th>
            <th scope="col">金额</th>
            <th scope="col">来源</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in budgetRows" :key="`${row.name}-${index}`">
            <td>
              <strong>{{ row.name }}</strong>
              <small v-if="row.date">{{ row.date }} {{ row.start_time ?? '' }}</small>
            </td>
            <td>
              <input
                v-if="canEditRow(row)"
                type="number"
                min="0"
                class="inline-number"
                :aria-label="`${row.name} 单价`"
                :value="unitPriceOf(row)"
                @change="(event) => setRowPrice(row, Number((event.target as HTMLInputElement).value))"
              >
              <span v-else>{{ row.unit_price ?? row.amount }}</span>
            </td>
            <td>
              <input
                v-if="canEditRow(row) && row.quantity_unit"
                type="number"
                min="1"
                max="99"
                class="inline-number"
                :aria-label="`${row.name} 次数`"
                :value="quantityOfRow(row)"
                @change="(event) => setRowQuantity(row, Number((event.target as HTMLInputElement).value))"
              >
              <span v-else>{{ row.quantity_unit ? quantityOfRow(row) : '—' }}</span>
            </td>
            <td>{{ rowAmount(row) }} {{ currency }}</td>
            <td>
              {{ rowSourceLabel(row) }}
              <small v-if="rowIsSavedPlace(row)">在“已收藏地点的价格”里改价</small>
            </td>
          </tr>
        </tbody>
      </table>
      <ul v-if="(draft?.budget.excluded_allowances ?? []).length > 0" class="excluded-list">
        <li v-for="allowance in draft?.budget.excluded_allowances ?? []" :key="allowance.name">
          {{ allowance.name }}：{{ allowance.amount }} {{ currency }}（未计入总额）
        </li>
      </ul>
      <p v-if="draft?.budget.note" class="budget-note">{{ draft.budget.note }}</p>
      <p v-else class="budget-note">
        这是本地预估；生成草案后由后端按同一套规则核定，金额不会两处不一致。
      </p>
    </CollapsiblePanel>

    <CollapsiblePanel
      v-if="props.places.length > 0"
      class="price-panel"
      aria-label="已收藏地点的价格"
      panel-key="saved-place-prices"
      :scope="tripId"
      title="已收藏地点的价格"
      heading-tag="h3"
      :count="`${props.places.length} 项`"
      :default-open="defaultOpenForCount(props.places.length)"
    >
      <p class="draft-note">这些地点不一定要排进行程；这里的价格决定了它们被排进行程时计入多少。</p>
      <ul class="price-list">
        <li v-for="place in savedPlacesWithPrice" :key="place.id">
          <strong>{{ place.name }}</strong>
          <span>{{ categoryLabel(place.type) }}</span>
          <input
            type="number"
            min="0"
            class="inline-number"
            :aria-label="`${place.name} 的价格`"
            :value="rawPriceOf(place) ?? ''"
            @input="(event) => { const raw = (event.target as HTMLInputElement).value; rawPrices = { ...rawPrices, [place.id]: raw === '' ? null : Math.max(0, Number(raw)) } }"
          >
          <el-button
            size="small"
            text
            :loading="savingPrice === place.id"
            :aria-label="`保存 ${place.name} 的价格`"
            @click="saveRawPrice(place)"
          >保存价格</el-button>
        </li>
      </ul>
    </CollapsiblePanel>

    <section class="chat-panel" aria-label="用自然语言调整行程">
      <h3>第 2 步 · 用对话补充要求（可选）</h3>
      <form class="adjustment-form" @submit.prevent="createDraft">
        <el-input
          v-model="instruction"
          type="textarea"
          :rows="3"
          aria-label="行程调整指令"
          placeholder="例如：把第二天下午换成室内的项目，晚饭想吃本地小吃；或：2026-10-02 14:00-16:00 安排外滩观景"
        />
        <div class="adjustment-actions">
          <el-checkbox v-model="includeWeb">使用联网搜索</el-checkbox>
          <el-button native-type="submit" type="primary" :icon="Search" :loading="creating">
            生成调整草案{{ hasStructuredEdits ? '（含上面选中的改动）' : '' }}
          </el-button>
        </div>
      </form>
    </section>

    <CollapsiblePanel
      v-if="draft"
      class="draft-result"
      aria-label="行程调整草案"
      panel-key="draft"
      :scope="tripId"
      :label="draft.status === 'pending' ? '待确认' : draft.status === 'applied' ? '已应用' : '已拒绝'"
      title="行程调整草案 / Draft"
      heading-tag="h3"
      :count="`${draft.operations.length} 项改动`"
      :default-open="false"
      :auto-open-token="draft.id"
    >
      <template #actions>
        <div v-if="draft.status === 'pending'" class="draft-commands">
          <el-button
            type="primary"
            :icon="Check"
            :loading="applying"
            :disabled="selectedOperationIndexes.length === 0"
            aria-label="Apply selected recommendations"
            @click="confirmDraft"
          >应用选中的推荐</el-button>
          <el-button
            :icon="Close"
            :loading="rejecting"
            aria-label="拒绝调整草案"
            @click="rejectDraft"
          >拒绝</el-button>
        </div>
      </template>

      <p class="draft-summary">{{ draft.summary }}</p>
      <p class="draft-instruction">你的要求：{{ draft.instruction }}</p>

      <CollapsiblePanel
        class="draft-subsection"
        panel-key="draft-operations"
        :scope="tripId"
        title="可应用的改动"
        heading-tag="h4"
        :count="`${draft.operations.length} 项`"
      >
        <p v-if="draft.operations.length === 0" class="empty-state">
          没有识别出可执行的改动。可以直接在上面勾选要删除的项目、改动时间，或自己填写一个新项目。
        </p>
        <div v-else class="selection-actions">
          <el-checkbox
            :model-value="selectedOperationIndexes.length === draft.operations.length"
            aria-label="Select all recommendations"
            @change="selectAllRecommendations"
          >全选</el-checkbox>
          <span>可单独勾选要应用的改动；替换操作会作为一个整体被选中。</span>
        </div>
        <ul v-if="draft.operations.length > 0" class="operation-list">
          <li v-for="(operation, index) in draft.operations" :key="`${operation.kind}-${index}`">
            <input
              type="checkbox"
              :checked="selectedOperationIndexes.includes(index)"
              :aria-label="`Select recommendation ${index}`"
              @change="(event) => toggleRecommendation(index, (event.target as HTMLInputElement).checked)"
            />
            <strong>{{ operation.kind === 'remove_item' || operation.kind === 'update_item' ? describeOperation(operation) : operationLabel(operation) }}</strong>
            <span v-if="operation.kind !== 'remove_item' && operation.kind !== 'update_item'">{{ operationDescription(operation) }}</span>
            <small v-if="operation.note">{{ operation.note }}</small>
          </li>
        </ul>
      </CollapsiblePanel>

      <CollapsiblePanel
        class="draft-subsection"
        aria-label="应用后的每日行程预览"
        panel-key="draft-preview"
        :scope="tripId"
        title="应用后的每日行程预览 / Draft preview"
        heading-tag="h4"
        :count="draft.daily_plan?.length ? `${draft.daily_plan.length} 天` : null"
        :default-open="defaultOpenForCount(draft.daily_plan?.length ?? 0)"
      >
        <p class="draft-note">
          这是“如果现在应用本草案”的样子，不是已保存的每日行程。整段行程都列出来，改动的部分有标记。
        </p>
        <p v-if="!(draft.daily_plan?.length)" class="empty-state">尚未生成时间线。</p>
        <div v-for="day in (draft.daily_plan ?? [])" :key="day.date" class="day-plan">
          <strong>{{ day.date }}</strong>
          <p v-if="day.items.length === 0" class="empty-state">这一天没有安排。</p>
          <ul v-else class="plain-list">
            <li v-for="item in day.items" :key="`${item.start_time}-${item.place_name}`">
              <img
                class="day-plan-image"
                :src="itemImage(item)"
                :alt="item.place_name"
                loading="lazy"
                @error="markItemImageFailed(item)"
              >
              <span>{{ timeLabel(item.start_time) }} - {{ timeLabel(item.end_time) }}</span>：{{ item.place_name }}
              <span v-if="item.changed === 'added'" class="change-badge change-added">新增</span>
              <span v-else-if="item.changed === 'moved'" class="change-badge change-moved">已移动</span>
              <small v-if="item.note">{{ item.note }}</small>
            </li>
          </ul>
        </div>
      </CollapsiblePanel>

      <CollapsiblePanel
        class="draft-subsection final-summary"
        aria-label="本次调整的草案说明"
        panel-key="draft-note"
        :scope="tripId"
        title="本次调整的草案说明 / Draft note"
        heading-tag="h4"
      >
        <p>{{ draft.final_summary || draft.summary }}</p>
      </CollapsiblePanel>

      <div class="draft-grid">
        <CollapsiblePanel
          panel-key="draft-conflicts"
          :scope="tripId"
          title="时间冲突"
          heading-tag="h4"
          :count="draft.conflicts.length > 0 ? `${draft.conflicts.length} 项` : '无冲突'"
        >
          <p v-if="draft.conflicts.length === 0" class="empty-state">未发现时间重叠。</p>
          <ul v-else class="plain-list">
            <li v-for="conflict in draft.conflicts" :key="conflict.message">{{ conflict.message }}</li>
          </ul>
        </CollapsiblePanel>
        <CollapsiblePanel
          panel-key="draft-budget-delta"
          :scope="tripId"
          title="预算变化"
          heading-tag="h4"
        >
          <p class="budget-delta">
            应用前 {{ draft.budget.total_estimate ?? 0 }} {{ currency }} → 当前表内 {{ draftTotal }} {{ currency }}
          </p>
          <p v-if="draft.budget.itemized_summary" class="budget-formula">{{ draft.budget.itemized_summary }}</p>
        </CollapsiblePanel>
      </div>
    </CollapsiblePanel>
  </section>
  </WorkspaceDrawer>
</template>

<style scoped>
.adjustment-section { max-width: 1120px; margin: 40px auto 0; padding-top: 32px; border-top: 1px solid #e7e9ee; }
.section-heading, .adjustment-actions, .draft-heading, .draft-commands, .editor-heading, .budget-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.section-label, .draft-status { margin: 0 0 6px; color: #6b7890; font-size: 12px; font-weight: 700; letter-spacing: 0; }
.section-note { margin: 6px 0 0; max-width: 760px; color: #788398; font-size: 13px; }
.draft-note { margin: 0 0 10px; color: #b8560f; font-size: 12px; }
h2, h3, h4, p { margin-top: 0; }
h2 { margin-bottom: 0; font-size: 22px; }
h3 { margin-bottom: 10px; font-size: 16px; }
h4 { margin-bottom: 12px; font-size: 15px; }
.agent-icon { color: #246bfd; font-size: 26px; }
.editor-panel, .budget-panel, .price-panel, .chat-panel, .adjustment-form, .draft-result { margin-top: 20px; }
.editor-panel, .budget-panel, .price-panel, .chat-panel { padding-top: 18px; border-top: 1px solid #e7e9ee; }
.editor-commands { display: flex; gap: 8px; }
.adjustment-actions { margin-top: 12px; }
.draft-result { border-top: 1px solid #e7e9ee; }
.draft-heading { align-items: flex-start; padding: 16px 0; border-bottom: 1px solid #e7e9ee; }
.draft-summary { margin: 0; color: #246bfd; font-weight: 600; }
.draft-instruction { margin: 6px 0 0; color: #788398; font-size: 13px; }
.status-applied { color: #16a34a; }
.status-rejected { color: #9f2f2f; }
.draft-subsection { margin-top: 24px; }
.day-list { display: grid; gap: 14px; }
.day-card { padding: 12px 14px; border: 1px solid #e7e9ee; border-radius: 10px; background: #fcfdff; }
.day-card-heading { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 8px; color: #246bfd; font-size: 13px; }
.day-items { margin: 0; padding: 0; list-style: none; }
.day-item { padding: 10px 0; border-top: 1px dashed #e7e9ee; }
.day-item:first-child { border-top: 0; }
.day-item.is-removed { opacity: 0.55; }
.day-item.is-removed .day-item-main strong { text-decoration: line-through; }
/*
 * A not-yet-saved addition sits in the same list as the real items, so it needs to read as
 * pending at a glance: a tinted row instead of a separate list the user has to look for.
 */
.day-item.is-added { border-left: 3px solid #e59a4a; padding-left: 10px; background: #fffaf3; }
.day-item-main { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.day-item-time { color: #788398; font-size: 13px; }
.day-item-main small { color: #8a968f; font-size: 12px; }
.day-item-actions { display: flex; align-items: center; gap: 4px; margin-top: 4px; }
.row-icon { color: #c94747; font-size: 13px; }
.move-row { display: flex; align-items: flex-end; gap: 10px; margin-top: 8px; flex-wrap: wrap; }
.move-row label, .form-grid label, .saved-picker label { display: flex; flex-direction: column; gap: 4px; color: #788398; font-size: 12px; }
.move-row input, .form-grid input, .form-grid select, .inline-number {
  padding: 6px 8px; border: 1px solid #d5ded7; border-radius: 6px; background: #fff; color: #111827; font-size: 13px;
}
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 12px; }
.add-form { margin-top: 16px; padding: 16px; border: 1px solid #e1e7f2; border-radius: 10px; background: #f8faff; }
.candidate-picker { margin-bottom: 14px; }
.candidate-hint { margin: 0 0 8px; color: #6b7280; font-size: 13px; }
.candidate-chips { display: flex; flex-wrap: wrap; gap: 8px; max-height: 190px; overflow-y: auto; }
.candidate-chip { display: flex; flex-direction: column; gap: 2px; padding: 8px 10px; border: 1px solid #cdd9d1; border-radius: 8px; background: #fff; color: #111827; font-size: 12px; text-align: left; cursor: pointer; }
.candidate-chip:hover { border-color: #246bfd; background: #f1f7f3; }
.candidate-chip span { color: #788398; }
.saved-picker { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.picker-label { color: #6b7280; font-size: 13px; }
.form-hint, .budget-note { margin: 0 0 12px; color: #788398; font-size: 12px; }
.addition-list { margin: 12px 0 0; padding: 0; list-style: none; }
.addition-list li { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-top: 1px dashed #e7e9ee; color: #6b7280; font-size: 13px; }
.inline-price { display: flex; align-items: center; gap: 4px; color: #788398; font-size: 12px; }
.inline-price .inline-number { width: 74px; }
.pending-badge { padding: 2px 8px; border-radius: 999px; background: #fff2e2; color: #b8560f; font-size: 12px; font-style: normal; }
.addition-price { color: #111827; font-weight: 600; }
.budget-total { margin: 0; color: #111827; font-size: 15px; }
.budget-total strong { font-size: 26px; }
.budget-total .over-budget { color: #9f2f2f; }
.budget-table { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 13px; }
.budget-table th, .budget-table td { padding: 8px 6px; border-bottom: 1px solid #e9edf4; text-align: left; vertical-align: middle; }
.budget-table th { color: #6b7890; font-size: 12px; font-weight: 700; }
.budget-table td small { display: block; color: #8a968f; font-size: 12px; }
.inline-number { width: 92px; }
.price-list { margin: 0; padding: 0; list-style: none; }
.price-list li { display: flex; align-items: center; gap: 12px; padding: 6px 0; border-bottom: 1px dashed #e9edf4; font-size: 13px; color: #6b7280; }
.excluded-list { margin: 10px 0 0; padding-left: 20px; color: #788398; font-size: 13px; }
.operation-list, .plain-list { margin: 0; padding-left: 20px; color: #6b7280; }
.operation-list li, .plain-list li { margin-bottom: 8px; }
.selection-actions { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; color: #6b7280; font-size: 13px; }
.operation-list strong { margin-right: 8px; color: #111827; }
.operation-list small { display: block; margin-top: 3px; color: #6b7784; }
.day-plan { margin-bottom: 12px; }
.day-plan-image { width: 72px; height: 52px; margin-right: 8px; vertical-align: middle; border-radius: 6px; object-fit: cover; background: #e9edf4; }
.change-badge { margin-left: 8px; padding: 1px 7px; border-radius: 999px; font-size: 12px; }
.change-added { background: #e6f4ec; color: #16a34a; }
.change-moved { background: #eef1fb; color: #3b4b8f; }
.draft-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-top: 24px; }
.draft-grid section { padding-top: 16px; border-top: 1px solid #e7e9ee; }
.draft-grid p { margin-bottom: 6px; color: #6b7280; font-size: 14px; line-height: 1.5; }
.budget-value { color: #111827 !important; font-size: 24px !important; font-weight: 700; }
.budget-delta { color: #111827 !important; font-weight: 600; }
.budget-formula { color: #788398 !important; font-size: 13px !important; }
.empty-state { color: #6b7280; font-size: 14px; }
.error-message { margin-top: 16px; padding: 12px 16px; border-left: 3px solid #c94747; background: #fff4f3; color: #9f2f2f; }
.status-message { margin-top: 16px; padding: 12px 16px; border-left: 3px solid #16a34a; background: #f2fbf5; color: #16a34a; }
@media (max-width: 760px) {
  .section-heading, .adjustment-actions, .draft-heading, .editor-heading, .budget-heading { align-items: flex-start; flex-direction: column; }
  .draft-grid, .form-grid { grid-template-columns: 1fr; gap: 18px; }
}
</style>

<style scoped>
.compact-adjustment{display:flex;flex-direction:column;min-height:0}.copilot-last-request{display:flex;align-items:baseline;gap:8px;margin:0 0 10px;padding:9px 10px;border-radius:8px;background:#edf3ff;color:#314668;font-size:11px;line-height:1.5}.copilot-last-request strong{flex-shrink:0;color:#246bfd}.copilot-last-request span{overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.copilot-conversation{flex:1;min-height:0;overflow:auto;padding:0 1px 14px;scrollbar-width:thin}.assistant-introduction{display:flex;align-items:flex-start;gap:10px}.assistant-avatar{display:grid;place-items:center;flex-shrink:0;width:33px;height:33px;border-radius:50%;background:#eef0ff}.assistant-avatar .app-logo{width:22px;height:22px}.assistant-introduction p{margin:0 0 7px;font-size:13px;line-height:1.8}.assistant-introduction p+p{font-size:12px;color:#707b92}.copilot-context{margin:20px 0 18px;font-size:12px;line-height:1.8;color:#778298}.copilot-prompts{display:flex;flex-direction:column;gap:10px}.copilot-prompts>button{display:flex;gap:12px;align-items:center;justify-content:space-between;border:1px solid #e8edf6;border-radius:8px;padding:13px 12px;background:#fff;cursor:pointer;text-align:left;font-size:11px;color:#5b6781;line-height:1.5}.copilot-prompts>button:hover{border-color:#bfd1fb;color:var(--primary);background:#f8faff}.copilot-reassurance{margin:22px 0 0;font-size:10px;line-height:1.7;color:#8590a3}.chat-message{display:flex;align-items:flex-start;gap:9px;margin-bottom:18px}.chat-message p{white-space:pre-wrap;margin:0;padding:12px 13px;border-radius:10px;font-size:13px;line-height:1.85;background:#f6f7fa;overflow-wrap:anywhere}.chat-message.user{margin-left:40px}.chat-message.user p{background:#edf3ff}.copilot-composer{border:1px solid #e6eaf2;border-radius:10px;padding:12px 11px 9px;margin-top:14px}.copilot-composer:focus-within{border-color:#9ebeff}.copilot-composer textarea{display:block;width:100%;resize:vertical;min-height:59px;max-height:180px;border:0;outline:none;font-size:13px;line-height:1.9;color:var(--text);background:white}.copilot-composer textarea::placeholder{color:#8a93a5}.composer-actions{display:flex;align-items:center;gap:8px;margin-top:6px}.composer-actions .icon-button{font-size:21px;width:24px}.send-message{display:grid;place-items:center;width:33px;height:33px;margin-left:auto;border:0;border-radius:50%;background:var(--primary);color:white;font-size:19px;cursor:pointer}.send-message:disabled{background:#adc8ff;cursor:not-allowed}.web-search-toggle{display:flex;align-items:center;gap:4px;color:#7f8a9d;font-size:10px;cursor:pointer}.web-search-toggle input{accent-color:var(--primary);width:12px;height:12px}.composer-hint{font-size:9px;color:#828c9f;text-align:center;margin:9px 0 0}.copilot-error{font-size:11px;color:#bc2d28;background:#fff3f2;padding:10px;border-radius:6px;line-height:1.7}.copilot-status{font-size:10px;color:#64758b;line-height:1.7;margin:12px 0 0}.copilot-thinking{font-size:11px;color:#7165c2;display:flex;gap:8px;align-items:center;padding:12px 0}.thinking-dot{height:6px;width:6px;background:#8a77ee;border-radius:50%}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap;border:0}
.copilot-summary{margin-bottom:12px}.copilot-summary .copilot-last-request{margin-bottom:8px}.copilot-answer{font-size:11px;line-height:1.6;color:#53607a;margin:0;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}.copilot-history-toggle{border:0;background:none;color:var(--primary);padding:3px 0 0;font-size:10px;cursor:pointer}
.compact-adjustment :deep(.adjustment-preview){padding:13px 14px 9px}.compact-adjustment :deep(.preview-heading){margin-bottom:10px}.compact-adjustment :deep(.preview-operations){gap:10px}.compact-adjustment :deep(.budget-impact){margin-top:12px;padding:10px}.compact-adjustment :deep(.preview-actions){margin-top:10px}
</style>
