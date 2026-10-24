<script setup lang="ts">
import { MagicStick, Refresh, Search } from '@element-plus/icons-vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'

import { generateTravelPlan, PlanRequestError } from '@/api/agent'
import { GENERIC_PLACEHOLDER, resolveAssetUrl } from '@/api/assets'
import { createPlace, createItineraryItem, listItineraryItems, listPlaces } from '@/api/itinerary'
import { usePlanningHandoff } from '@/composables/planningHandoff'
import { defaultOpenForCount } from '@/composables/panelState'
import type { AgentEvent, PlaceRecommendation, PlanningJob, TravelPlan } from '@/types/agent'
import type { PlaceCreate, PlaceType } from '@/types/itinerary'
import { approximateCoordinates } from '@/utils/workspace'
import CollapsiblePanel from '@/components/CollapsiblePanel.vue'

const props = defineProps<{ tripId: number; destination?: string; savedPlaceNames?: string[]; initialQuery?: string }>()
const emit = defineEmits<{ 'adjustment-requested': [instruction: string]; 'places-updated': [] }>()

/** How long a photo may stay unloaded before the placeholder replaces it. */
const IMAGE_LOAD_TIMEOUT_MS = 12000
/**
 * A bundled placeholder can fail while the backend restarts. Retrying with a
 * cache-busting suffix keeps that transient failure from disabling the picture
 * for every card that shares the same placeholder.
 */
const MAX_PLACEHOLDER_RETRIES = 2
/**
 * How many extra attempts a card gives its OWN photo before retiring it for good.
 *
 * A real plan asks the browser for ~34 pictures from photo hosts at once, so a timeout is
 * as likely to mean "slow right now" as "dead link". Retiring the photo on the first miss
 * turned a moment of congestion into a card that showed a grey placeholder until the page
 * was reloaded. One retry (cache-busted, so the browser cannot reuse the failed response)
 * recovers the slow case while still ending on the placeholder when the host really is
 * unreachable.
 */
const MAX_PRIMARY_RETRIES = 1
/**
 * How many times a still-below-the-fold picture may be re-checked instead of being
 * declared broken. Bounded so a card the user never scrolls to cannot keep a timer
 * alive indefinitely; 25 x 12s gives a lazy card five minutes to come into view.
 */
const MAX_LAZY_DEFERRALS = 25

const rootRef = ref<HTMLElement | null>(null)
/** Per-card count of "not fetched yet" re-checks, so the watch can stay bounded. */
const lazyDeferrals = new Map<string, number>()

interface ImageSource {
  image_url?: string | null
  fallback_image_url?: string | null
  name?: string | null
  place_name?: string | null
  date?: string | null
  start_time?: string | null
  signature_dish?: string | null
}

/** The caption for a full-size preview: for a restaurant, name the dish being shown. */
function imageLabel(item: ImageSource): string {
  const name = String(item.name ?? item.place_name ?? '')
  return item.signature_dish ? `${name} · 招牌菜 ${item.signature_dish}` : name
}

/**
 * A stable identity for one card, used to remember whether *its* picture failed.
 *
 * Failures used to be remembered per image URL, which made one transient failure retire
 * that photo on every card showing it. That is how a whole category could lose its
 * pictures at once: measured 38 recommendations sharing only 17 photos, one of them on 9
 * cards, so a single throttled request blanked 9 cards together and looked like "hotels
 * have no images". Keyed per card, a failure stays on the card it happened to.
 */
function cardKey(item: ImageSource): string {
  if (item.name) return `place:${item.name}`
  return `plan:${item.date ?? ''}:${item.place_name ?? ''}:${item.start_time ?? ''}`
}

const RECOMMENDATION_GROUPS = [
  { category: 'hotel', label: '酒店 / Hotels' },
  { category: 'restaurant', label: '吃喝 / Food & drink' },
  { category: 'attraction', label: '景点 / Attractions' },
]

const query = ref('使用已收藏地点和当前行程，生成一份平衡的旅行计划 / Create a balanced plan using my saved places and itinerary.')
watch(() => props.initialQuery, value => { if (value) query.value = value }, { immediate: true })
const includeWeb = ref(true)
const loading = ref(false)
const errorMessage = ref('')
const planningProgress = ref(0)
const planningPhase = ref('')
const progressEvents = ref<AgentEvent[]>([])
const partialRecommendations = ref<PlaceRecommendation[]>([])
let activePlanController: AbortController | null = null
const savingPlaces = ref(false)
const savePlacesMessage = ref('')
const plan = ref<TravelPlan | null>(null)
/**
 * Bumped every time a new plan arrives, to open the recommended places and the budget
 * once. The panels are folded by default (37 cards and 26 items made the page very long),
 * but a result the user just asked for should not be hidden behind a click.
 */
const planToken = ref(0)
const failedImages = ref(new Map<string, number>())
const loadedImages = ref(new Set<string>())
const imageTimers = new Map<string, ReturnType<typeof setTimeout>>()
const previewImage = ref<{ url: string; title: string } | null>(null)
const previewVisible = ref(false)

onBeforeUnmount(() => {
  activePlanController?.abort()
  imageTimers.forEach((timer) => clearTimeout(timer))
  imageTimers.clear()
})

/** How many recommended places the folded header has to account for. */
const recommendationCount = computed(
  () => (plan.value?.place_recommendations ?? []).length,
)

const recommendationGroups = computed(() => {
  const places: PlaceRecommendation[] = plan.value?.place_recommendations ?? []
  const groups = RECOMMENDATION_GROUPS.flatMap((group) => {
    const items = places.filter((place) => place.category === group.category)
    if (group.category !== 'restaurant') {
      return [{ ...group, items }]
    }
    // A day of eating is not one list: breakfast places are a different kind of shop
    // and the user asked to see them separately from lunch and dinner.
    const breakfast = items.filter((place) => place.meal_slot === 'breakfast')
    const mains = items.filter((place) => place.meal_slot !== 'breakfast')
    const split: { category: string; label: string; items: PlaceRecommendation[] }[] = []
    if (breakfast.length > 0) {
      split.push({ category: 'restaurant-breakfast', label: '早餐 / Breakfast', items: breakfast })
    }
    if (mains.length > 0) {
      split.push({ category: 'restaurant-main', label: '中晚餐 / Lunch & dinner', items: mains })
    }
    return split
  })
  const known = new Set(RECOMMENDATION_GROUPS.map((group) => group.category))
  const others = places.filter((place) => !known.has(place.category))
  if (others.length > 0) {
    groups.push({ category: 'other', label: '其他 / Other', items: others })
  }
  return groups.filter((group) => group.items.length > 0)
})

const partialRecommendationGroups = computed(() => RECOMMENDATION_GROUPS.flatMap((group) => {
  const items = partialRecommendations.value.filter((place) => place.category === group.category)
  if (!items.length) return []
  if (group.category !== 'restaurant') return [{ ...group, items }]
  const breakfast = items.filter((place) => place.meal_slot === 'breakfast')
  const main = items.filter((place) => place.meal_slot !== 'breakfast')
  return [
    ...(breakfast.length ? [{ category: 'restaurant-breakfast', label: '早餐 / Breakfast', items: breakfast }] : []),
    ...(main.length ? [{ category: 'restaurant-main', label: '中晚餐 / Lunch & dinner', items: main }] : []),
  ]
}))

const phaseLabel = computed(() => {
  if (planningPhase.value.startsWith('category:')) {
    const labels: Record<string, string> = { hotel: '酒店', restaurant: '餐厅', attraction: '景点' }
    return `已找到${labels[planningPhase.value.split(':')[1]] ?? '一批'}候选地点，正在继续规划`
  }
  return ({
    queued: '已提交规划任务', context: '正在读取旅行信息', search: '正在检索目的地资料',
    recommendations: '正在整理推荐地点', analysis: '正在计算预算与检查冲突', weather: '正在结合天气',
    summary: '正在生成最终总结', photos: '正在整理配图', completed: '规划已完成',
  } as Record<string, string>)[planningPhase.value] ?? '正在生成规划'
})

function receivePlanProgress(job: PlanningJob): void {
  planningProgress.value = job.progress
  planningPhase.value = job.phase
  progressEvents.value = job.events
  partialRecommendations.value = job.place_recommendations
  scheduleImageWatch()
}

/** Recommendation groups whose items exceed what the trip's budget can afford. */
const overBudgetCount = computed(
  () => (plan.value?.place_recommendations ?? []).filter((place) => place.over_budget).length,
)

/**
 * Whether any card uses a photo from the Pexels library.
 *
 * Using their API requires crediting Pexels itself wherever those photos appear, not only
 * the photographer, so this drives the "Photos provided by Pexels" link. The label matches
 * `pexels_images.ATTRIBUTION_LABEL` on the backend.
 */
const PEXELS_LABEL = 'Photos provided by Pexels'
const PEXELS_URL = 'https://www.pexels.com'
const usesPexelsPhotos = computed(() =>
  (plan.value?.place_recommendations ?? []).some((place) => Boolean(place.image_credit)),
)

const selectedNames = ref<string[]>([])

/**
 * Whether the baseline reports accommodation and meals as reference figures instead of
 * charging them. When it does, the baseline covers local transport only, so the panel must
 * not describe it as a full trip budget.
 */
const hasExcludedAllowances = computed(() => (plan.value?.budget.excluded_allowances?.length ?? 0) > 0)
const selectedItems = computed<PlaceRecommendation[]>(() =>
  (plan.value?.place_recommendations ?? []).filter((place) => selectedNames.value.includes(place.name)),
)
/**
 * The first day the plan suggests each place for. Ticking a recommendation used to
 * lose the day it belonged to, so 一键排入行程 put it on whichever day was free first.
 * The day group the item sits in is the only place that information lives, so it is
 * read straight off the daily plan.
 */
const suggestedDates = computed<Map<string, string>>(() => {
  const dates = new Map<string, string>()
  for (const day of plan.value?.daily_plan ?? []) {
    for (const item of day.items ?? []) {
      const name = (item.place_name ?? '').trim()
      if (name && day.date && !dates.has(name)) dates.set(name, day.date)
    }
  }
  return dates
})
/**
 * Names that already exist in 收藏地点. A ticked recommendation that has been saved
 * is part of the saved-place budget from then on, so charging it again as a
 * "selected" extra would count the same money twice in the running total.
 *
 * The parent owns the trip's places and passes their names down, so this stays in
 * sync with 收藏地点 without a second request.
 */
const justSavedNames = ref<string[]>([])

const savedPlaceNames = computed(() => {
  const names = new Set((props.savedPlaceNames ?? []).map((name) => name.trim()))
  for (const name of justSavedNames.value) names.add(name)
  return names
})

function isSaved(name: string): boolean {
  return savedPlaceNames.value.has(name)
}

const pendingSelectedItems = computed(() => selectedItems.value.filter((place) => !isSaved(place.name)))
const selectedCost = computed(() =>
  pendingSelectedItems.value.reduce((sum, place) => sum + itemCost(place), 0),
)
const savedSelectedCost = computed(() =>
  selectedItems.value
    .filter((place) => isSaved(place.name))
    .reduce((sum, place) => sum + itemCost(place), 0),
)
/** Saved-place budget plus everything still ticked and not yet saved. */
const combinedTotal = computed(() => (plan.value?.budget.total_estimate ?? 0) + selectedCost.value)
const combinedRemaining = computed(() => {
  const tripBudget = plan.value?.budget.trip_budget
  return tripBudget === null || tripBudget === undefined ? null : tripBudget - combinedTotal.value
})
/**
 * The category framework the plan was built with: the trip's own budget times 80%, split by the
 * model into 酒店/美食/景点门票/市内交通/其他. This is an estimate, and it is the number the
 * panel leads with because it is the only one that means anything before a place has been chosen.
 */
const budgetPlan = computed(() => plan.value?.budget_plan)
/** The panel header: the estimate when there is one, otherwise the exact saved/ticked sum. */
const budgetPlanHeadline = computed(() => {
  const allocation = budgetPlan.value
  if (allocation && allocation.status === 'estimated') {
    return `计划花掉约 ${allocation.planned_total} ${allocation.currency}`
  }
  return `合计约 ${combinedTotal.value} ${plan.value?.budget.currency ?? 'CNY'}`
})

function isSelected(name: string): boolean {
  return selectedNames.value.includes(name)
}

/**
 * How many times this place is used: nights at a hotel, meals at a restaurant.
 *
 * The first entry per item comes from the server (1 for both), and the traveller can raise
 * it on the card. Attractions have no unit and therefore no count.
 */
const quantities = ref<Record<string, number>>({})

function hasCount(place: PlaceRecommendation): boolean {
  return Boolean(place.quantity_unit)
}

function quantityOf(place: PlaceRecommendation): number {
  const override = quantities.value[place.name]
  if (typeof override === 'number' && Number.isFinite(override)) return override
  const fromServer = place.quantity
  return typeof fromServer === 'number' && fromServer >= 1 ? fromServer : 1
}

function quantityMax(place: PlaceRecommendation): number {
  const fromServer = place.quantity_max
  return typeof fromServer === 'number' && fromServer >= 1 ? fromServer : 99
}

function setQuantity(place: PlaceRecommendation, raw: string | number): void {
  const parsed = typeof raw === 'number' ? raw : Number.parseInt(raw, 10)
  const wanted = Number.isFinite(parsed) ? Math.trunc(parsed) : 1
  const clamped = Math.min(quantityMax(place), Math.max(1, wanted))
  quantities.value = { ...quantities.value, [place.name]: clamped }
}

/** How many people the meal prices are for; the backend prices per person per meal. */
const partySize = computed(() => {
  const people = Number(plan.value?.traveler_profile?.total_people ?? 0)
  return Number.isFinite(people) && people >= 1 ? Math.trunc(people) : 1
})

/**
 * What one place adds to the budget, including how many times it is used.
 *
 * A hotel is one night and a restaurant is one meal, so the number the user sets is what
 * actually gets charged. (`estimated_cost` from the server is the same arithmetic with the
 * default count of 1 -- this recomputes it as soon as the count changes.)
 */
function itemCost(place: PlaceRecommendation): number {
  const unitPrice = Number(place.unit_price ?? 0)
  const count = quantityOf(place)
  if (place.is_free) return 0
  if (place.category === 'hotel') {
    if (unitPrice > 0) return unitPrice * count
    return place.estimated_cost ?? 0
  }
  if (place.category === 'restaurant') {
    if (unitPrice > 0) return unitPrice * partySize.value * count
    return place.estimated_cost ?? 0
  }
  // Attractions and anything else keep the server's number untouched.
  return place.estimated_cost ?? 0
}

/** The arithmetic behind `itemCost`, so the card never shows a bare total. */
function costDetail(place: PlaceRecommendation): string | null {
  const unitPrice = Number(place.unit_price ?? 0)
  const count = quantityOf(place)
  const source = place.cost_source ? `（${place.cost_source}）` : ''
  if (!place.is_free && unitPrice > 0) {
    if (place.category === 'hotel') {
      return `约 ${unitPrice} 元/晚 × ${count} 晚 = ${itemCost(place)} 元${source}`
    }
    if (place.category === 'restaurant') {
      return `约 ${unitPrice} 元/人/餐 × ${partySize.value} 人 × ${count} 次 = ${itemCost(place)} 元${source}`
    }
  }
  return place.cost_explanation ?? null
}

function toggleSelected(name: string, checked: boolean): void {
  const selected = new Set(selectedNames.value)
  if (checked) selected.add(name)
  else selected.delete(name)
  selectedNames.value = [...selected]
}

function costLine(place: PlaceRecommendation): string {
  if (place.is_free) return '免费 / Free'
  const amount = itemCost(place)
  if (amount <= 0 && (place.estimated_cost === null || place.estimated_cost === undefined)) return '预算待估算'
  return `约 ${amount} CNY`
}

function handSelectedToAdjustment(): void {
  if (selectedItems.value.length === 0) return
  const lines = selectedItems.value.map(
    (place, index) => `${index + 1}. ${place.name}（${costLine(place)}）${place.reason ? `——${place.reason}` : ''}`,
  )
  const total = selectedCost.value
  emit(
    'adjustment-requested',
    `请把以下已选推荐项目加入我的行程，并结合现有每日行程安排合适的时间段与交通：\n${lines.join('\n')}\n这些项目合计约 ${total} CNY，请加入行程后重新计算预算。`,
  )
}

function timeLabel(value: string): string {
  return value.slice(0, 5)
}

/**
 * Send the weather-driven changes to the conversational adjustment agent, which
 * already knows how to propose a revised plan the user can review before applying.
 */
function handWeatherAdviceToAdjustment(): void {
  const request = plan.value?.weather_adjustment_request
  if (!request) return
  emit('adjustment-requested', request)
}

const PLACE_TYPES = new Set<string>(['attraction', 'hotel', 'restaurant', 'airport', 'station'])

/** Recommendation categories map onto the saved-place types the API accepts. */
function placeType(category: string): PlaceType {
  return (PLACE_TYPES.has(category) ? category : 'attraction') as PlaceType
}

/**
 * `PlaceCreate.image_url` is an HttpUrl. Preserve the planner's locally cached
 * provider photo by resolving its static path to the backend origin before saving.
 * Category placeholders are not stored as place photos.
 */
function absoluteImageUrl(value: string | null | undefined): string | null {
  if (!value || value.startsWith('/static/place_images/') && !value.startsWith('/static/place_images/cache/')) return null
  const resolved = resolveAssetUrl(value)
  return /^https?:\/\//i.test(resolved) ? resolved : null
}

function placeAddress(): string {
  return props.destination ? `${props.destination} · 地址待确认` : '地址待确认'
}

/**
 * The payload that turns a recommendation into a saved place.
 *
 * Shared by "加入收藏地点" and "一键加入每日行程": the second one has to create the place anyway
 * (an itinerary item needs a place_id), and it must create the *same* record or the two buttons
 * would disagree about the price and the note of one restaurant.
 */
function placePayloadFor(place: PlaceRecommendation, name: string): PlaceCreate {
  // Keep the planner's rough position, so the workspace map can place this stop and measure
  // the hop to the next one. It is an estimate, which is all the map's straight-line sketch
  // needs; when the model did not know, both halves stay null and the map says so.
  const position = approximateCoordinates(place)
  return {
    name,
    type: placeType(place.category),
    address: placeAddress(),
    latitude: position.latitude,
    longitude: position.longitude,
    image_url: absoluteImageUrl(place.image_url),
    rating: null,
    price_level: null,
    opening_hours: null,
    source_url: null,
    // Keep the reason and the quoted price: this is the whole point of saving a
    // recommendation, and both used to be lost at exactly this step.
    note: place.reason ?? null,
    estimated_cost: itemCost(place),
    // Remember how many nights/meals that price covers, so the saved place can still
    // explain itself and a later re-plan charges the same money.
    quantity: hasCount(place) ? quantityOf(place) : null,
    // Keep the day it was suggested for, so the later 一键排入行程 proposal can put
    // it on that day instead of guessing.
    preferred_date: suggestedDates.value.get(name) ?? null,
  }
}

/** Tick-to-save: the ticked recommendations become real saved places. */
async function saveSelectedAsPlaces(): Promise<void> {
  if (selectedItems.value.length === 0) return
  savingPlaces.value = true
  savePlacesMessage.value = ''
  errorMessage.value = ''
  try {
    const existing = await listPlaces(props.tripId)
    const known = new Set(existing.map((place) => place.name.trim()))
    const savedNow = new Set(justSavedNames.value)
    let added = 0
    let skipped = 0
    for (const place of selectedItems.value) {
      const name = place.name.trim()
      if (!name || known.has(name)) {
        skipped += 1
        savedNow.add(name)
        continue
      }
      const payload = placePayloadFor(place, name)
      await createPlace(props.tripId, payload)
      known.add(name)
      savedNow.add(name)
      added += 1
    }
    justSavedNames.value = [...savedNow].filter(Boolean)
    const duplicateNote = skipped > 0 ? `，${skipped} 个已存在已跳过` : ''
    savePlacesMessage.value = `已把 ${added} 个推荐项目加入收藏地点${duplicateNote}。`
    if (added > 0) emit('places-updated')
  } catch {
    errorMessage.value = '加入收藏地点失败，请稍后重试 / Could not save the selected places。'
  } finally {
    savingPlaces.value = false
  }
}

const { setSelectedRecommendations, setPlanRecommendations, markPlanGenerated } = usePlanningHandoff(props.tripId)

/**
 * One row of the suggested daily arrangement, flattened and addressable.
 *
 * The suggested days are a draft list of names and times. To turn one into a real itinerary item
 * the page needs three things the draft does not carry: which recommendation it came from (for the
 * price and the note), whether it can be written at all, and a stable key for the tick box.
 */
interface SuggestedRow {
  key: string
  date: string
  place_name: string
  start_time: string
  end_time: string
  note: string | null
  /** The recommendation this row came from; without one there is nothing to write. */
  recommendation: PlaceRecommendation | null
  /** Which slot of its day this is, so the written rows keep the suggested order. */
  order: number
}

const suggestedRows = computed<SuggestedRow[]>(() => {
  const rows: SuggestedRow[] = []
  for (const day of plan.value?.daily_plan ?? []) {
    ;(day.items ?? []).forEach((item, index) => {
      const name = (item.place_name ?? '').trim()
      if (!name) return
      const start = item.start_time ?? '09:00'
      const end = item.end_time ?? start
      rows.push({
        key: `${day.date}|${start}|${name}`,
        date: day.date,
        place_name: name,
        start_time: start,
        end_time: end,
        note: item.note ?? null,
        recommendation: (plan.value?.place_recommendations ?? []).find(
          (place) => place.name.trim() === name,
        ) ?? null,
        order: index,
      })
    })
  }
  return rows
})

/**
 * Keys the user ticked, or null while the plan is untouched.
 *
 * null and [] must stay different: null means "the user has not decided, so everything writeable
 * is offered", while [] means "the user unticked everything and nothing may be written". Reading
 * an empty array as "untouched" made the untick-all button silently re-select every row.
 */
const selectedSuggestedKeys = ref<string[] | null>(null)
const addingToItinerary = ref(false)
const itineraryMessage = ref('')

/** A row the user may write: a recommendation was matched, so a place can be created. */
function isWritable(row: SuggestedRow): boolean {
  return row.recommendation !== null
}

/** Tick state: a fresh plan starts with everything selected, since that is the common case. */
const effectiveSelectedKeys = computed(() => {
  if (selectedSuggestedKeys.value !== null) return selectedSuggestedKeys.value
  return suggestedRows.value.filter(isWritable).map((row) => row.key)
})

function isSuggestedSelected(row: SuggestedRow): boolean {
  return effectiveSelectedKeys.value.includes(row.key)
}

function toggleSuggested(row: SuggestedRow, value: boolean): void {
  const keys = new Set(effectiveSelectedKeys.value)
  if (value) keys.add(row.key)
  else keys.delete(row.key)
  selectedSuggestedKeys.value = [...keys]
}

const selectedSuggestedRows = computed(() => suggestedRows.value.filter(isSuggestedSelected))
const writableSelectedRows = computed(() => selectedSuggestedRows.value.filter(isWritable))
const allSuggestedSelected = computed(() =>
  suggestedRows.value.some(isWritable) &&
  suggestedRows.value.filter(isWritable).every(isSuggestedSelected),
)

function toggleAllSuggested(value: boolean): void {
  const writeable = suggestedRows.value.filter(isWritable).map((row) => row.key)
  selectedSuggestedKeys.value = value ? writeable : []
}

/**
 * Write the ticked suggestions straight into 每日行程, with the times the plan recommended.
 *
 * Deliberately not routed through the conversational adjustment agent: that path costs a model
 * round-trip and returns a proposal the user has to approve, when what was asked for is "these
 * rows, at these times". An itinerary item needs a `place_id`, so a suggestion that is not saved
 * yet is saved first -- with exactly the same payload the 加入收藏地点 button would have written.
 *
 * Repeat clicks are safe: an item already on that date for that place is skipped, not duplicated.
 */
async function addSuggestedToItinerary(): Promise<void> {
  const rows = writableSelectedRows.value
  if (rows.length === 0) return
  addingToItinerary.value = true
  itineraryMessage.value = ''
  errorMessage.value = ''
  try {
    const [existingPlaces, existingItems] = await Promise.all([
      listPlaces(props.tripId),
      listItineraryItems(props.tripId),
    ])
    const byName = new Map(existingPlaces.map((place) => [place.name.trim(), place.id]))
    // (date, place) is the identity of a written row: the same restaurant twice on one day is the
    // duplicate this guard exists for, and the plan can suggest a place on two days on purpose.
    const alreadyThere = new Set(existingItems.map((item) => `${item.date}|${item.place_id}`))
    let added = 0
    let duplicates = 0
    let failed = 0
    for (const row of rows) {
      const recommendation = row.recommendation
      if (!recommendation) continue
      try {
        let placeId = byName.get(row.place_name)
        if (placeId === undefined) {
          const created = await createPlace(props.tripId, placePayloadFor(recommendation, row.place_name))
          placeId = created.id
          byName.set(row.place_name, placeId)
        }
        const identity = `${row.date}|${placeId}`
        if (alreadyThere.has(identity)) {
          duplicates += 1
          continue
        }
        await createItineraryItem(props.tripId, {
          date: row.date,
          start_time: timeLabel(row.start_time),
          end_time: timeLabel(row.end_time),
          place_id: placeId,
          note: row.note,
          sort_order: row.order,
        })
        alreadyThere.add(identity)
        added += 1
      } catch {
        failed += 1
      }
    }
    const parts = [`已把 ${added} 项加入每日行程`]
    if (duplicates > 0) parts.push(`${duplicates} 项这一天已经排过，已跳过`)
    if (failed > 0) parts.push(`${failed} 项写入失败`)
    itineraryMessage.value = `${parts.join('，')}。`
    if (added > 0 || failed === 0) emit('places-updated')
  } catch {
    errorMessage.value = '加入每日行程失败，请稍后重试 / Could not add to the daily itinerary。'
  } finally {
    addingToItinerary.value = false
  }
}

/**
 * Publish the whole recommendation list so the daily-itinerary editor can offer these
 * places -- with these prices -- when the user wants to add something to a day. The
 * editor used to have no idea what the planning agent had proposed, which is why it
 * could only be driven by typing an instruction naming a saved place.
 */
function publishPlanRecommendations(): void {
  setPlanRecommendations(
    (plan.value?.place_recommendations ?? []).map((place) => ({
      name: place.name,
      category: place.category,
      reason: place.reason ?? null,
      estimated_cost: place.estimated_cost ?? null,
      unit_price: place.unit_price ?? null,
      quantity: place.quantity ?? null,
      quantity_unit: place.quantity_unit ?? null,
      image_url: place.image_url ?? null,
      signature_dish: place.signature_dish ?? null,
      rating: place.rating ?? null,
      // Like every other field here, the position has to be repeated or the editor's
      // "add from a recommendation" path would save the place without one and the map
      // would have nothing to draw.
      latitude: place.latitude ?? null,
      longitude: place.longitude ?? null,
    })),
  )
}

/**
 * The selection as a signature that changes only when the CONTENT does.
 *
 * Watching `pendingSelectedItems` itself was a bug, and it is what froze the page. Vue
 * compares watched values by identity, and a computed that ends in `.filter(...)` produces a
 * NEW array every time it re-evaluates -- including when nothing about the selection
 * changed. This component is re-rendered whenever the parent is, and the parent rebuilds the
 * `savedPlaceNames` prop it passes down on every one of its own renders. So the chain ran:
 *
 *   tick -> watcher writes the handoff store -> the parent re-renders (its step bar reads
 *   that store) -> a new `savedPlaceNames` array arrives -> the computed re-evaluates to an
 *   equal-but-new array -> the watcher fires again...
 *
 * round and round, with no way to converge, until the tab died. A string comparison can tell
 * "nothing changed" from "here is a new array", so the chain stops at the first link.
 */
function selectionSignature(items: PlaceRecommendation[]): string {
  // The cost is part of the signature, so raising a hotel from one night to three moves it.
  return JSON.stringify(items.map((place) => [place.name, place.category, itemCost(place)]))
}

// Hand the current selection to the summary panel, so "生成行程介绍与总结" can
// cover what the user actually picked instead of only the saved places. Items that
// are already saved places are left out: the summary already covers those, and
// sending them again would name them twice and count their cost twice.
watch(
  () => selectionSignature(pendingSelectedItems.value),
  () => {
    setSelectedRecommendations(
      pendingSelectedItems.value.map((place) => ({
        name: place.name,
        category: place.category,
        estimated_cost: itemCost(place),
        reason: place.reason ?? null,
      })),
    )
  },
  { immediate: true },
)

function failureCount(key: string): number {
  return failedImages.value.get(key) ?? 0
}

function recordImageFailure(key: string): void {
  const next = new Map(failedImages.value)
  next.set(key, (next.get(key) ?? 0) + 1)
  failedImages.value = next
}

/** Cache-bust a retry, otherwise the browser reuses the failed response. */
function withRetry(url: string, attempt: number): string {
  if (attempt <= 0) return url
  return `${url}${url.includes('?') ? '&' : '?'}_retry=${attempt}`
}

/**
 * Resolve the picture to show for one place. Provider photos can be dead links or
 * sit on hosts the browser cannot reach, so a failed photo falls back to the
 * bundled category placeholder.
 *
 * The decision is per card. It used to be per URL, which meant one failure retired a
 * shared photo for every card showing it -- and because the placeholder URL is shared by
 * every card in a category, one failed placeholder blanked the whole category forever.
 *
 * `failureKey` names the thing that would have failed if this picture does not appear: the
 * card while its own photo is showing, the placeholder once it has been swapped in. That
 * separation is what keeps a failed placeholder retrying itself instead of re-retiring the
 * photo the card started with.
 *
 * The result is never empty: a card without any picture is worse than a card
 * showing a placeholder.
 */
function resolveImage(item: ImageSource): { src: string; failureKey: string } {
  const key = cardKey(item)
  const primary = resolveAssetUrl(item.image_url)
  const fallback = resolveAssetUrl(item.fallback_image_url) || GENERIC_PLACEHOLDER
  const attempts = failureCount(key)
  const primaryUsable = Boolean(primary) && primary !== fallback && attempts <= MAX_PRIMARY_RETRIES
  // `attempts` is both the count of failures and the number of tries still to come, so the
  // first try is byte-for-byte the URL the backend handed over and only a retry is suffixed.
  if (primaryUsable) return { src: withRetry(primary as string, attempts), failureKey: key }
  const placeholderKey = `${key}#placeholder`
  const attempt = Math.min(failureCount(placeholderKey), MAX_PLACEHOLDER_RETRIES)
  return { src: withRetry(fallback, attempt), failureKey: placeholderKey }
}

function imageSrc(item: ImageSource): string {
  return resolveImage(item).src
}

function imageFailureKey(item: ImageSource): string {
  return resolveImage(item).failureKey
}

function handleImageError(event: Event): void {
  const key = (event.target as HTMLImageElement | null)?.getAttribute('data-failure')
  if (key) recordImageFailure(key)
}

/**
 * A photo host that never answers leaves the <img> blank and may not fire `error`
 * at all, so the placeholder would never swap in. Watch every rendered picture
 * with a timer and treat one that has not loaded in time as failed.
 *
 * The timer must NOT count a picture the browser has not even started fetching.
 * These images are `loading="lazy"`, and Chrome only begins the request once a card
 * scrolls near the viewport —so on a long plan the cards below the fold sat
 * unstarted, their timer expired, and their perfectly good photos were retired
 * forever and replaced by the placeholder. Measured in a real browser: 8 of 11
 * cards lost their photo without ever being fetched, and the same URLs loaded in
 * 186-681 ms once actually requested.
 */
function markLoaded(key: string): void {
  const timer = imageTimers.get(key)
  if (timer) {
    clearTimeout(timer)
    imageTimers.delete(key)
  }
  lazyDeferrals.delete(key)
  loadedImages.value = new Set(loadedImages.value).add(key)
}

/** The rendered <img> elements currently showing this card's picture, if any. */
function imageElementsFor(key: string): HTMLImageElement[] {
  const root = rootRef.value
  if (!root) return []
  return Array.from(root.querySelectorAll('img')).filter(
    (element) => element.getAttribute('data-card') === key,
  )
}

/** True once the browser has tried and failed —`complete` with no pixels. */
function hasFailedToLoad(element: HTMLImageElement): boolean {
  return element.complete && element.naturalWidth === 0
}

/** True when a lazy image at this position should already have started loading. */
function isInViewport(element: HTMLImageElement): boolean {
  const rect = element.getBoundingClientRect()
  // An element with no layout at all reports an all-zero rect (jsdom, or a hidden
  // panel). There is nothing to measure there, so assume it is visible and let the
  // timeout judge it the way it always did.
  if (rect.width === 0 && rect.height === 0) return true
  const viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0
  return rect.bottom > 0 && rect.top < viewportHeight
}

/**
 * True when every element for this src is both unfinished and off-screen: the
 * browser simply has not been asked to fetch it yet, so nothing has gone wrong.
 */
function isWaitingToBeFetched(elements: HTMLImageElement[]): boolean {
  return (
    elements.length > 0 &&
    elements.every((element) => !element.complete && !isInViewport(element))
  )
}

/**
 * The items that actually render a picture.
 *
 * Two kinds are deliberately absent. Daily-plan entries render no <img> since the
 * suggested-days list became text-only, and hotels carry no picture by design -- their
 * cards show a rating and a price instead. Watching either produced entries that could
 * never load: keyed per card as they now are, each would have armed a timer that expired,
 * recorded a failure and re-armed itself for the rest of the session.
 */
function allImageItems(): ImageSource[] {
  const items = plan.value?.place_recommendations ?? partialRecommendations.value
  return [...items].filter((place) => Boolean(place.image_url))
}

function scheduleImageWatch(): void {
  const pending = new Map<string, { src: string; failureKey: string }>()
  for (const item of allImageItems()) {
    const key = cardKey(item)
    if (!pending.has(key)) pending.set(key, resolveImage(item))
  }
  for (const [key, timer] of imageTimers) {
    if (pending.has(key)) continue
    clearTimeout(timer)
    imageTimers.delete(key)
  }
  for (const [key, target] of pending) {
    if (loadedImages.value.has(key) || imageTimers.has(key)) continue
    armImageTimer(key, target)
  }
}

function armImageTimer(key: string, target: { src: string; failureKey: string }): void {
  const timer = setTimeout(() => {
    imageTimers.delete(key)
    if (loadedImages.value.has(key)) return
    const elements = imageElementsFor(key)
    if (isWaitingToBeFetched(elements)) {
      // Still below the fold: keep waiting rather than discarding a good photo,
      // but stop after a bound so a card the user never scrolls to cannot re-arm
      // a timer for the rest of the session.
      const deferrals = (lazyDeferrals.get(key) ?? 0) + 1
      if (deferrals <= MAX_LAZY_DEFERRALS) {
        lazyDeferrals.set(key, deferrals)
        armImageTimer(key, target)
        return
      }
    }
    recordImageFailure(target.failureKey)
  }, IMAGE_LOAD_TIMEOUT_MS)
  imageTimers.set(key, timer)
}

function onImageLoad(item: ImageSource): void {
  markLoaded(cardKey(item))
}

/**
 * Forget every load result, because a new plan invalidates all of them.
 *
 * Failures are keyed per card (`place:<name>`), and they were never cleared when a plan was
 * replaced. So a card that failed once -- a photo host that was slow, a timeout while 34
 * pictures were fetched at once -- stayed failed for the rest of the session, and the next
 * plan showed its placeholder immediately WITHOUT trying the brand-new photo URL the new
 * response carried. From the user's side the plan had just been regenerated and the
 * pictures were missing again, which looked like a backend problem and was not one.
 *
 * Timers and lazy deferrals go too: they belong to the pictures of the plan being replaced.
 */
function resetImageState(): void {
  imageTimers.forEach((timer) => clearTimeout(timer))
  imageTimers.clear()
  lazyDeferrals.clear()
  failedImages.value = new Map()
  loadedImages.value = new Set()
}

function watchAllImages(): void {
  scheduleImageWatch()
}

watch([plan, failedImages], scheduleImageWatch)

function openImage(item: ImageSource, title: string): void {
  const source = imageSrc(item)
  if (source) {
    previewImage.value = { url: source, title }
    previewVisible.value = true
  }
}

async function submitPlan(): Promise<void> {
  if (!query.value.trim()) {
    errorMessage.value = '请输入规划请求 / Please enter a planning request。'
    return
  }
  loading.value = true
  errorMessage.value = ''
  planningProgress.value = 0
  planningPhase.value = 'queued'
  progressEvents.value = []
  partialRecommendations.value = []
  activePlanController?.abort()
  activePlanController = new AbortController()
  resetImageState()
  try {
    plan.value = await generateTravelPlan(props.tripId, {
      query: query.value.trim(),
      include_web: includeWeb.value,
    }, {
      signal: activePlanController.signal,
      onProgress: receivePlanProgress,
    })
    resetImageState()
    planToken.value += 1
    watchAllImages()
    // Records progress for the step guide on the trip page.
    markPlanGenerated()
    publishPlanRecommendations()
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return
    if (error instanceof PlanRequestError) {
      if (error.status === 504 || error.status === 408) {
        // The generation itself is slow; the request was cut off by a proxy.
        errorMessage.value =
          '生成规划用时过长，网关已超时中断。请稍等片刻后重新提交 / Plan generation timed out。'
      } else if (error.status === 0) {
        errorMessage.value = error.message
      } else {
        errorMessage.value = `${error.message} 请稍后重试。`
      }
    } else {
      errorMessage.value = '无法生成规划，请确认后端服务已启动后重试 / Unable to generate a plan。'
    }
  } finally {
    loading.value = false
    activePlanController = null
  }
}

function adjustmentInstruction(date: string, startTime: string, endTime: string, placeName: string, note: string | null): string {
  const noteText = note ? `，备注：${note}` : ''
  return `${date} ${timeLabel(startTime)}-${timeLabel(endTime)} 调整或确认“${placeName}”${noteText}`
}

function adjustItem(date: string, startTime: string, endTime: string, placeName: string, note: string | null): void {
  emit('adjustment-requested', adjustmentInstruction(date, startTime, endTime, placeName, note))
}

function adjustFullPlan(): void {
  if (!plan.value) return
  const items = plan.value.daily_plan.flatMap((day) =>
    day.items.map((item) => adjustmentInstruction(day.date, item.start_time, item.end_time, item.place_name, item.note)),
  )
  const request = items.length > 0
    ? `请根据以下规划逐项检查并给出可选择的调整建议：\n${items.map((item, index) => `${index + 1}. ${item}`).join('\n')}`
    : query.value.trim()
  emit('adjustment-requested', request)
}

</script>

<template>
  <section ref="rootRef" class="planning-section" aria-label="旅行规划 Agent / Travel planning agent">
    <div class="section-heading">
      <div>
        <h2>发现下一站</h2>
      </div>
      <el-icon class="agent-icon"><MagicStick /></el-icon>
    </div>

    <form class="planning-form" @submit.prevent="submitPlan">
      <el-input v-model="query" type="textarea" :rows="3" aria-label="Planning request" />
      <div class="planning-actions">
        <el-checkbox v-model="includeWeb">联网搜索最新资料</el-checkbox>
        <el-button
          v-if="plan"
          :icon="Refresh"
          :loading="loading"
          aria-label="重新生成规划建议"
          @click="submitPlan"
        >重新规划 / Re-plan</el-button>
        <el-button native-type="submit" type="primary" :icon="Search" :loading="loading">生成规划建议</el-button>
      </div>
    </form>

    <p v-if="errorMessage" class="error-message" role="alert">{{ errorMessage }}</p>

    <p class="replan-hint">
      想从头再来一次？先在「收藏地点」里点<strong>清空收藏</strong>，再点<strong>重新规划</strong>，
      这次生成的建议就不会再被上一轮留下的收藏地点影响。
    </p>

    <section v-if="loading" class="planning-progress" aria-live="polite" aria-busy="true">
      <div class="progress-heading">
        <div>
          <strong>{{ phaseLabel }}</strong>
          <span>{{ planningProgress }}%</span>
        </div>
        <progress :value="planningProgress" max="100" aria-label="规划生成进度" />
      </div>
      <p v-if="progressEvents.length" class="progress-event">
        {{ progressEvents[progressEvents.length - 1].message }}
      </p>
      <div v-if="partialRecommendationGroups.length" class="partial-results">
        <p class="partial-note">已找到的地点会先显示；全部类别完成后再统一计算最终价格和预算。</p>
        <div v-for="group in partialRecommendationGroups" :key="group.category" class="recommendation-group">
          <h4>{{ group.label }}</h4>
          <div class="recommendation-cards">
            <article v-for="place in group.items" :key="`${group.category}-${place.name}`" class="recommendation-card is-pending">
              <img
                v-if="place.image_url"
                class="recommendation-image"
                :src="imageSrc(place)"
                :data-card="cardKey(place)"
                :data-failure="imageFailureKey(place)"
                :alt="place.name"
                loading="lazy"
                @load="onImageLoad(place)"
                @error="handleImageError"
                @click="openImage(place, imageLabel(place))"
              >
              <div class="recommendation-body">
                <strong>{{ place.name }}</strong>
                <p v-if="place.reason" class="recommendation-reason">{{ place.reason }}</p>
                <p class="pending-price">地点已找到，价格计算中</p>
              </div>
            </article>
          </div>
        </div>
      </div>
    </section>

    <div v-if="plan" class="plan-results">
      <p class="plan-summary">{{ plan.summary }}</p>

      <CollapsiblePanel
        v-if="recommendationGroups.length > 0"
        class="plan-subsection recommendation-panel"
        panel-key="recommendations"
        :scope="tripId"
        title="推荐项目 / Recommended places"
        heading-tag="h3"
        :count="recommendationCount > 0 ? `${recommendationCount} 项` : null"
        :default-open="defaultOpenForCount(recommendationCount)"
        :auto-open-token="planToken"
      >
        <template #status>
          <p v-if="savePlacesMessage" class="save-places-message" role="status">{{ savePlacesMessage }}</p>
        </template>
        <p class="recommendation-hint">勾选你想要的项目，下方预算会立即计入；已加入收藏地点的项目会显示标记且不再重复计费，再点“把选中项目交给对话式调整”即可排进行程。</p>
        <p v-if="usesPexelsPhotos" class="recommendation-attribution">
          部分配图来自 Pexels 图库，是该地区的示意图，<strong>不一定是该地点的实景照片</strong>：          <a :href="PEXELS_URL" target="_blank" rel="noopener noreferrer">{{ PEXELS_LABEL }}</a>
        </p>
        <div v-for="group in recommendationGroups" :key="group.category" class="recommendation-group">
          <h4>{{ group.label }}</h4>
          <div class="recommendation-cards">
            <article
              v-for="place in group.items"
              :key="`${group.category}-${place.name}`"
              class="recommendation-card"
              :class="{ 'is-selected': isSelected(place.name) }"
            >
              <img
                v-if="place.image_url"
                class="recommendation-image"
                :src="imageSrc(place)"
                :data-card="cardKey(place)"
                :data-failure="imageFailureKey(place)"
                :alt="place.signature_dish ? `${place.name} 的招牌菜 ${place.signature_dish}` : place.name"
                loading="lazy"
                @load="onImageLoad(place)"
                @error="handleImageError"
                @click="openImage(place, imageLabel(place))"
              >
              <div class="recommendation-body">
                <label class="recommendation-pick">
                  <input
                    type="checkbox"
                    :checked="isSelected(place.name)"
                    :aria-label="`Select ${place.name}`"
                    @change="(event) => toggleSelected(place.name, (event.target as HTMLInputElement).checked)"
                  />
                  <strong>{{ place.name }}</strong>
                  <span v-if="isSaved(place.name)" class="saved-badge">已在收藏地点 / Saved</span>
                  <!-- Which price tier of the traveller's own budget this is. The list is
                       meant to be a mix: cheap places for every day, and the expensive one
                       that is worth it. Without the label the budget just looked wrong. -->
                  <span
                    v-if="place.price_tier_label"
                    class="price-tier-badge"
                    :class="`price-tier-${place.price_tier}`"
                  >{{ place.price_tier_label }}</span>
                  <span v-if="place.over_budget" class="over-budget-badge" :title="place.budget_note || ''">超预算 / Over budget</span>
                </label>
                <!-- Hotels deliberately carry no picture: a stock photo of a different hotel
                     says nothing, so the card leads with the two facts that do help. -->
                <p v-if="place.rating" class="recommendation-rating">
                  <span class="rating-value">{{ place.rating.toFixed(1) }}</span>
                  <span class="rating-scale">/ 5 参考评分</span>
                </p>
                <p v-if="place.signature_dish" class="recommendation-dish">
                  招牌菜 / Signature dish：<strong>{{ place.signature_dish }}</strong>
                </p>
                <p v-if="place.reason" class="recommendation-reason">{{ place.reason }}</p>
                <!-- How many times this place is used. Ticking one restaurant is one meal,
                     not every meal of the trip, and a trip may change hotels, so both start
                     at 1 and the budget follows whatever is set here. Attractions have no
                     count. -->
                <label v-if="hasCount(place)" class="recommendation-quantity">
                  <span class="quantity-label">{{ place.category === 'hotel' ? '住几晚' : '吃几次' }}</span>
                  <input
                    type="number"
                    min="1"
                    :max="quantityMax(place)"
                    :value="quantityOf(place)"
                    :aria-label="`${place.quantity_unit} ${place.name}`"
                    @input="(event) => setQuantity(place, (event.target as HTMLInputElement).value)"
                  />
                  <span class="quantity-unit">{{ place.quantity_unit }}</span>
                  <span class="quantity-hint">（最多 {{ quantityMax(place) }} {{ place.quantity_unit }}）</span>
                </label>
                <p class="recommendation-cost">{{ costLine(place) }}</p>
                <p v-if="suggestedDates.get(place.name)" class="recommendation-day">
                  建议安排在 {{ suggestedDates.get(place.name) }}，收藏后排入行程会用这一天                </p>
                <p v-if="costDetail(place)" class="recommendation-cost-detail">{{ costDetail(place) }}</p>
                <!-- An adjusted price says so on its own line: the card must never let a
                     corrected figure look like something the venue published. -->
                <p v-if="place.price_adjusted" class="recommendation-price-note">
                  资料上的价格低于当地同类店的常见人均，已按当地下限估算，供预算参考
                </p>
                <p v-if="(place.over_budget || place.price_tier === 'splurge') && place.budget_note" class="recommendation-budget-note">{{ place.budget_note }}</p>
                <p v-if="place.image_credit" class="recommendation-credit">
                  <a
                    v-if="place.image_credit_url"
                    :href="place.image_credit_url"
                    target="_blank"
                    rel="noopener noreferrer"
                  >{{ place.image_credit }}</a>
                  <span v-else>{{ place.image_credit }}</span>
                </p>
              </div>
            </article>
          </div>
        </div>
        <div class="recommendation-footer">
          <span>已选 {{ selectedItems.length }} 项，待加入 {{ selectedCost }} CNY<span v-if="savedSelectedCost > 0">（另有 {{ savedSelectedCost }} CNY 已收藏）</span><span v-if="overBudgetCount > 0" class="over-budget-total">，其中 {{ overBudgetCount }} 项超出预算参考</span></span>
          <div class="recommendation-footer-actions">
            <el-button
              :disabled="selectedItems.length === 0"
              :loading="savingPlaces"
              aria-label="Save selected places to my collection"
              @click="saveSelectedAsPlaces"
            >加入收藏地点 / Save to my places</el-button>
            <el-button
              type="primary"
              :disabled="selectedItems.length === 0"
              aria-label="Hand selected places to the conversational adjustment"
              @click="handSelectedToAdjustment"
            >把选中项目交给对话式调整</el-button>
          </div>
        </div>
      </CollapsiblePanel>

      <div class="plan-grid">
      <CollapsiblePanel
        panel-key="suggested-days"
        :scope="tripId"
        title="建议的每日安排 / Suggested days"
        heading-tag="h3"
        :count="plan.daily_plan.length > 0 ? `${plan.daily_plan.length} 天` : null"
        :default-open="defaultOpenForCount(plan.daily_plan.length)"
      >
        <p class="section-note">
          按天给出建议，覆盖整个行程。勾选想要的项目后点<strong>一键加入每日行程</strong>，
          就会按这里的推荐时间写进「编辑每日行程」的每日行程表格里（已经排过的那一天会自动跳过）；
          也可以仍然用“交给对话调整”让 AI 逐项给调整建议。
        </p>
        <div v-if="plan.daily_plan.length === 0" class="empty-state">暂时没有安排行程 / No itinerary items are scheduled yet。</div>
        <template v-if="plan.daily_plan.length > 0">
          <div class="suggested-actions">
            <!-- Native checkboxes, like the recommendation cards: the aria-label then lands on the
                 input itself instead of on a wrapper, which is what the tests and screen readers key on. -->
            <label class="suggested-all">
              <input
                type="checkbox"
                :checked="allSuggestedSelected"
                aria-label="Select all suggested items"
                @change="(event) => toggleAllSuggested((event.target as HTMLInputElement).checked)"
              />
              全选
            </label>
            <span class="suggested-count">已选 {{ selectedSuggestedRows.length }} 项，可写入 {{ writableSelectedRows.length }} 项</span>
            <el-button
              type="primary"
              :disabled="writableSelectedRows.length === 0"
              :loading="addingToItinerary"
              aria-label="Add selected suggestions to the daily itinerary"
              @click="addSuggestedToItinerary"
            >一键加入每日行程 / Add to daily itinerary</el-button>
          </div>
          <p v-if="itineraryMessage" class="suggested-message">{{ itineraryMessage }}</p>
          <p v-if="selectedSuggestedRows.length > writableSelectedRows.length" class="suggested-message suggested-warning">
            有 {{ selectedSuggestedRows.length - writableSelectedRows.length }} 项在推荐列表里找不到对应地点，无法自动写入，请用“交给对话调整”处理。
          </p>
        </template>
        <article v-for="day in plan.daily_plan" :key="day.date" class="plan-day">
            <h4>{{ day.date }}</h4>
            <p v-if="day.shortage" class="plan-shortage">{{ day.shortage }}</p>
            <p
              v-for="row in suggestedRows.filter((entry) => entry.date === day.date)"
              :key="`${row.date}-${row.place_name}-${row.start_time}`"
              class="plan-item"
            >
              <span class="plan-item-main">
                <input
                  type="checkbox"
                  :checked="isSuggestedSelected(row)"
                  :disabled="!isWritable(row)"
                  :aria-label="`Select suggested ${row.place_name} on ${row.date}`"
                  @change="(event) => toggleSuggested(row, (event.target as HTMLInputElement).checked)"
                />
                <span>
                  <strong>{{ timeLabel(row.start_time) }} - {{ timeLabel(row.end_time) }}</strong>
                  {{ row.place_name }}<span v-if="row.note">: {{ row.note }}</span>
                  <small v-if="!isWritable(row)" class="suggested-unwritable">（推荐列表里没有这条的地点信息）</small>
                </span>
              </span>
              <el-button
                text
                type="primary"
                size="small"
                :aria-label="`Adjust ${row.place_name}`"
                @click="adjustItem(row.date, row.start_time, row.end_time, row.place_name, row.note)"
              >交给对话调整</el-button>
            </p>
          </article>
          <el-button v-if="plan.daily_plan.length > 0" text type="primary" aria-label="Adjust full plan" @click="adjustFullPlan">交给对话式调整</el-button>
        </CollapsiblePanel>
        <CollapsiblePanel
          class="budget-panel"
          panel-key="plan-budget"
          :scope="tripId"
          title="预算分配建议 / Budget plan"
          heading-tag="h3"
          :count="budgetPlanHeadline"
          :auto-open-token="planToken"
        >
          <!-- What the traveller's own budget should roughly buy, by category. This is the figure
               the panel leads with: it is the only number that means anything before a single
               place has been chosen, and it is an ESTIMATE derived from the trip's budget. The
               exact sums (saved places, ticked recommendations, the daily editor) are kept below
               and are never mixed into it. -->
          <template v-if="budgetPlan && budgetPlan.status === 'estimated'">
            <p class="budget-value">计划花掉约 {{ budgetPlan.planned_total }} {{ budgetPlan.currency }}</p>
            <p class="budget-line">
              按你填写的总预算 {{ budgetPlan.trip_budget }} {{ budgetPlan.currency }} 估算，占
              {{ budgetPlan.planned_ratio_percent ?? 80 }}%；预留约 {{ budgetPlan.reserve_amount ?? 0 }}
              {{ budgetPlan.currency }}（{{ 100 - (budgetPlan.planned_ratio_percent ?? 80) }}%）给购物与临时支出。
            </p>
            <table class="allocation-table">
              <thead>
                <tr><th>类别</th><th>大概金额</th><th>占比</th><th>折算</th></tr>
              </thead>
              <tbody>
                <tr v-for="row in budgetPlan.categories" :key="row.category">
                  <td>{{ row.label }}</td>
                  <td class="allocation-amount">{{ row.amount }} {{ budgetPlan.currency }}</td>
                  <td>{{ row.share_percent ?? 0 }}%</td>
                  <td><small>{{ row.unit }}</small></td>
                </tr>
              </tbody>
              <tfoot>
                <tr>
                  <td>合计</td>
                  <td class="allocation-amount">{{ budgetPlan.planned_total }} {{ budgetPlan.currency }}</td>
                  <td>100%</td>
                  <td><small>{{ budgetPlan.source === 'model' ? '由 AI 按目的地行情估算' : '按通用比例估算' }}</small></td>
                </tr>
              </tfoot>
            </table>
            <p v-if="budgetPlan.note" class="budget-note">{{ budgetPlan.note }}</p>
          </template>
          <p v-else class="budget-note">
            {{ budgetPlan?.note || '填上总预算后，这里会按你的预算给出酒店/美食/景点等各类别的大概金额。' }}
          </p>

          <div class="budget-actual">
            <p class="budget-subheading">已选与已收藏项目的实际估算（真实单价相加，不是上面的分配建议）</p>
            <p class="budget-line">
              {{ hasExcludedAllowances ? '基础预算（住宿与餐饮按已选项目计入）' : '已收藏地点基础预算' }}：{{ plan.budget.total_estimate }} {{ plan.budget.currency }}
            </p>
            <p v-if="pendingSelectedItems.length > 0" class="budget-line">待加入的已选推荐：+{{ selectedCost }} {{ plan.budget.currency }}（{{ pendingSelectedItems.length }} 项）</p>
            <p v-if="savedSelectedCost > 0" class="budget-line">已加入收藏地点的已选推荐：{{ savedSelectedCost }} {{ plan.budget.currency }}（已计入上方基础预算，不再重复累加）</p>
            <p v-if="plan.budget.daily_estimate">约 {{ plan.budget.daily_estimate }} {{ plan.budget.currency }} / 天（按基础预算）</p>
            <p v-if="combinedRemaining !== null">剩余预算 / Remaining：{{ combinedRemaining }} {{ plan.budget.currency }}</p>
            <p>{{ plan.budget.note }}</p>
            <ul v-if="plan.budget.breakdown?.length" class="budget-breakdown">
              <li v-for="line in plan.budget.breakdown" :key="`${line.category}-${line.name}`">{{ line.name }}：{{ line.amount }} {{ plan.budget.currency }}</li>
            </ul>
            <ul v-if="pendingSelectedItems.length" class="budget-breakdown">
              <li v-for="place in pendingSelectedItems" :key="`sel-${place.name}`">{{ place.name }}：{{ itemCost(place) }} {{ plan.budget.currency }}<span v-if="hasCount(place)">（{{ quantityOf(place) }} {{ place.quantity_unit }}）</span></li>
            </ul>
            <p class="budget-note">
              把项目安排进日程之后的真实估算，请看「编辑每日行程」面板里的预算估算。
            </p>
          </div>
        </CollapsiblePanel>
      </div>

      <CollapsiblePanel
        v-if="plan.transport_advice"
        class="plan-subsection transport-panel"
        panel-key="transport-advice"
        :scope="tripId"
        title="交通建议 / Transport advice"
        heading-tag="h3"
      >
        <p><strong>出发地 / Origin：</strong>{{ plan.transport_advice.origin }}</p>
        <p><strong>目的地 / Destination：</strong>{{ plan.transport_advice.destination }}</p>
        <p><strong>建议 / Recommendation：</strong>{{ plan.transport_advice.recommendation }}</p>
      </CollapsiblePanel>

      <CollapsiblePanel
        class="plan-subsection final-summary-panel"
        panel-key="final-summary"
        :scope="tripId"
        title="最终旅行总结 / Final travel summary"
        heading-tag="h3"
        :auto-open-token="planToken"
      >
        <p>{{ plan.final_summary }}</p>
        <div v-if="plan.budget_summary" class="budget-brief">
          <strong>预算简表：</strong>{{ plan.budget_summary }}；全程约 {{ plan.budget.total_estimate }} {{ plan.budget.currency }}，平均每天约 {{ plan.budget.daily_estimate }} {{ plan.budget.currency }}。        </div>
        <div v-if="plan.budget.itemized_summary" class="budget-itemized">
          <strong>逐项预算：</strong>{{ plan.budget.itemized_summary }}
        </div>
      </CollapsiblePanel>

      <CollapsiblePanel
        v-if="plan.weather"
        class="plan-subsection weather-panel"
        panel-key="weather"
        :scope="tripId"
        title="出行日期天气 / Weather"
        heading-tag="h3"
        :count="plan.weather.days?.length ? `${plan.weather.days.length} 天` : null"
      >
        <p v-if="plan.weather.message" class="weather-note">{{ plan.weather.message }}</p>
        <p v-if="!plan.weather.days?.length" class="empty-state">{{ plan.weather.message || '当前日期暂时没有可用天气预报；请临近出行日期重新生成规划。' }}</p>
        <ul v-else class="weather-list">
          <li v-for="day in plan.weather.days" :key="day.date">
            <strong>{{ day.date }}</strong>：{{ day.condition || '天气已查询' }}<span v-if="day.precipitation_probability != null">，降水概率 {{ day.precipitation_probability }}%</span><span v-if="day.max_temperature != null && day.min_temperature != null">，{{ day.min_temperature }}–{{ day.max_temperature }}℃</span>
            <em v-if="day.indoor_priority">建议优先室内备选</em><em v-else>可安排户外项目</em>
          </li>
        </ul>
      </CollapsiblePanel>

      <CollapsiblePanel
        v-if="plan.weather_advice?.length"
        class="plan-subsection weather-advice-panel"
        panel-key="weather-advice"
        :scope="tripId"
        title="天气影响与建议 / Weather impact"
        heading-tag="h3"
        :count="`${plan.weather_advice.length} 天`"
      >
        <p class="weather-advice-hint">下面这些日子的天气可能影响你已安排的行程。</p>
        <ul class="weather-advice-list">
          <li v-for="advice in plan.weather_advice" :key="advice.date">
            <strong>{{ advice.date }}</strong>
            <p class="weather-advice-message">{{ advice.message }}</p>
            <p class="weather-advice-suggestion">建议：{{ advice.suggestion }}</p>
          </li>
        </ul>
        <el-button
          type="primary"
          aria-label="Hand the weather advice to the conversational adjustment"
          @click="handWeatherAdviceToAdjustment"
        >按天气建议调整行程</el-button>
      </CollapsiblePanel>

      <CollapsiblePanel
        class="plan-subsection"
        panel-key="conflicts"
        :scope="tripId"
        title="行程冲突 / Schedule conflicts"
        heading-tag="h3"
        :count="plan.conflicts.length > 0 ? `${plan.conflicts.length} 项` : '无冲突'"
      >
        <p v-if="plan.conflicts.length === 0" class="empty-state">未发现时间重叠 / No overlapping itinerary items were found。</p>
        <ul v-else class="plain-list">
          <li v-for="conflict in plan.conflicts" :key="`${conflict.date}-${conflict.first_place}-${conflict.second_place}`">{{ conflict.message }}</li>
        </ul>
      </CollapsiblePanel>
    </div>
    <el-dialog v-if="previewImage" v-model="previewVisible" title="查看大图" width="min(90vw, 900px)" destroy-on-close>
      <img class="preview-image" :src="previewImage.url" :alt="previewImage.title">
    </el-dialog>
  </section>
</template>

<style scoped>
.planning-section { max-width: 1120px; margin: 40px auto 0; padding-top: 32px; border-top: 1px solid #e7e9ee; }
.section-heading, .planning-actions, .plan-item { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.section-label { margin: 0 0 6px; color: #6b7890; font-size: 12px; font-weight: 700; letter-spacing: 0; }
h2, h3, h4, p { margin-top: 0; }
h2 { margin-bottom: 0; font-size: 22px; }
h3 { margin-bottom: 12px; font-size: 16px; }
.agent-icon { color: #246bfd; font-size: 26px; }
.planning-form { margin-top: 20px; }
.planning-actions { margin-top: 12px; }
.plan-results { margin-top: 24px; }
.planning-progress { margin-top: 22px; padding: 18px; border: 1px solid #dce4f2; border-radius: 12px; background: #f8faff; }
.progress-heading > div { display: flex; justify-content: space-between; gap: 16px; color: #263650; font-size: 14px; }
.progress-heading span { color: #60708c; font-variant-numeric: tabular-nums; }
.progress-heading progress { width: 100%; height: 8px; margin-top: 10px; accent-color: #246bfd; }
.progress-event, .partial-note { margin: 10px 0 0; color: #66748c; font-size: 12px; line-height: 1.6; }
.partial-results { margin-top: 18px; }
.partial-results .recommendation-group:first-of-type { margin-top: 14px; }
.recommendation-card.is-pending { border-color: #dde4ef; box-shadow: 0 8px 24px rgba(52, 76, 116, 0.08); }
.recommendation-card.is-pending .recommendation-body > strong { color: #172033; font-size: 14px; }
.pending-price { margin: 8px 0 0; color: #7b6320; font-size: 12px; }
.plan-summary { margin-bottom: 20px; color: #246bfd; font-weight: 600; }
.plan-grid { display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(220px, 0.7fr); gap: 24px; }
.plan-day { padding: 14px 0; border-top: 1px solid #e7e9ee; }
.plan-day h4 { margin-bottom: 8px; color: #246bfd; font-size: 14px; }
.plan-day p, .budget-panel p { margin-bottom: 6px; color: #6b7280; font-size: 14px; line-height: 1.5; }
.plan-day strong { display: inline-block; min-width: 98px; color: #111827; font-variant-numeric: tabular-nums; }
.plan-shortage { padding: 6px 10px; border-left: 3px solid #d98b2b; border-radius: 4px; background: #fdf5e8; color: #8a5a12; font-size: 13px; }
.recommendation-panel { padding: 18px; border: 1px solid #e5eaf4; border-radius: 6px; background: #f8faff; }
.recommendation-hint { margin-bottom: 14px; color: #6b7280; font-size: 13px; line-height: 1.5; }
.recommendation-group { margin-bottom: 16px; }
.recommendation-group:last-of-type { margin-bottom: 0; }
.recommendation-group h4 { margin-bottom: 10px; color: #246bfd; font-size: 14px; }
.recommendation-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(210px, 1fr)); gap: 14px; }
.recommendation-card { overflow: hidden; border: 1px solid #e7e9ee; border-radius: 8px; background: #ffffff; }
.recommendation-card.is-selected { border-color: #246bfd; box-shadow: 0 0 0 2px rgba(61, 95, 88, 0.18); }
.recommendation-image { display: block; width: 100%; height: 118px; object-fit: cover; background: #e9edf4; cursor: zoom-in; }
/* Pexels requires that its photos are credited where they are shown, so this line is
   part of using that source rather than decoration. */
.recommendation-credit { margin: 4px 0 0; font-size: 11px; color: #8a8f8b; }
.recommendation-credit a { color: #8a8f8b; text-decoration: underline; }
.recommendation-attribution { margin: 0 0 12px; font-size: 12px; color: #6b7a6f; }
.recommendation-attribution a { color: #2f7d4f; font-weight: 600; text-decoration: underline; }
/* Hotels show facts instead of a picture, so these two lines are the first thing read. */
.recommendation-rating { margin: 2px 0 0; display: flex; align-items: baseline; gap: 4px; }
.recommendation-rating .rating-value { font-size: 20px; font-weight: 700; color: #d98b1f; line-height: 1.1; }
.recommendation-rating .rating-scale { font-size: 11px; color: #8a8f8b; }
.recommendation-dish { margin: 4px 0 0; font-size: 12px; color: #2f7d4f; }
.recommendation-dish strong { font-weight: 700; }
.recommendation-quantity { display: flex; align-items: center; gap: 0.35rem; margin: 0.25rem 0 0; font-size: 0.88rem; color: #3d4a41; }
.recommendation-quantity .quantity-label { font-weight: 600; }
.recommendation-quantity input { width: 4rem; padding: 0.15rem 0.35rem; border: 1px solid #c8d2ca; border-radius: 6px; font: inherit; }
.recommendation-quantity .quantity-unit { font-weight: 600; }
.recommendation-quantity .quantity-hint { color: #8a8f8b; }
/* Reference amounts that are deliberately outside the total: visible, but visibly secondary
   so they cannot be mistaken for money the plan already spent. */
.budget-reference-note { color: #6b7a6f; }
.budget-reference li { color: #8a8f8b; }
.recommendation-body { padding: 10px 12px; }
.recommendation-pick { display: flex; align-items: flex-start; gap: 6px; cursor: pointer; flex-wrap: wrap; }
.recommendation-pick strong { color: #111827; font-size: 14px; line-height: 1.45; }
.saved-badge { padding: 1px 6px; border-radius: 9px; background: #e6f2ea; color: #16a34a; font-size: 11px; font-weight: 600; white-space: nowrap; }
.over-budget-badge { padding: 1px 6px; border-radius: 9px; background: #fdf0e6; color: #b8560f; font-size: 11px; font-weight: 600; white-space: nowrap; }
/* Price tiers: the food list is meant to hold cheap AND expensive-but-good options. */
.price-tier-badge { padding: 1px 6px; border-radius: 9px; background: #eef1f7; color: #4a5878; font-size: 11px; font-weight: 600; white-space: nowrap; }
.price-tier-cheap { background: #e8f4ec; color: #16a34a; }
.price-tier-splurge { background: #f3ecfa; color: #6a3fa0; }
.price-tier-over { background: #fdf0e6; color: #b8560f; }
.recommendation-price-note { margin: 2px 0 0; color: #6a5a2a; font-size: 12px; }
.recommendation-day { margin: 2px 0 0; color: #2f6b56; font-size: 12px; }
.over-budget-total { color: #b8560f; font-weight: 600; }
.recommendation-budget-note { margin: 2px 0 0; color: #b8560f; font-size: 12px; }
.recommendation-reason { margin: 6px 0 0; color: #6b7280; font-size: 13px; line-height: 1.5; }
.recommendation-cost { margin: 8px 0 0; color: #8b5d22; font-size: 14px; font-weight: 600; }
.recommendation-cost-detail { margin: 4px 0 0; color: #6b7784; font-size: 12px; line-height: 1.45; }
.recommendation-footer { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin-top: 16px; padding-top: 14px; border-top: 1px solid #e5eaf4; color: #44546f; font-size: 14px; font-weight: 600; }
.recommendation-footer-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.save-places-message { margin: 10px 0 0; color: #16a34a; font-size: 13px; }
.budget-line { color: #6b7280 !important; font-size: 13px !important; }
.weather-note { margin-bottom: 10px; padding: 10px 12px; border-left: 3px solid #c9a227; background: #fdf8e7; color: #6b5a1c; font-size: 13px; line-height: 1.5; }
.preview-image { display: block; width: 100%; max-height: 75vh; object-fit: contain; }
.budget-panel { padding: 18px; border: 1px solid #e7e9ee; border-radius: 6px; background: #f0f5f1; }
.budget-value { color: #111827 !important; font-size: 24px !important; font-weight: 700; }
/* The category framework the panel leads with, and the exact sums underneath it. The two are kept
   visually apart on purpose: one is an estimate built from the trip's budget, the other is real
   prices added up, and a reader must never have to guess which is which. */
.allocation-table { width: 100%; margin-top: 12px; border-collapse: collapse; font-size: 13px; }
.allocation-table th, .allocation-table td { padding: 8px 6px; border-bottom: 1px solid #dce7de; text-align: left; vertical-align: top; }
.allocation-table th { color: #6b7890; font-size: 12px; font-weight: 700; }
.allocation-table tfoot td { border-bottom: none; border-top: 2px solid #cfdcd2; color: #111827; font-weight: 700; }
.allocation-table td small { display: block; color: #7d8b84; font-size: 12px; }
.allocation-amount { color: #111827; font-weight: 700; white-space: nowrap; }
.budget-actual { margin-top: 18px; padding-top: 14px; border-top: 1px dashed #cfdcd2; }
.budget-subheading { margin: 0 0 8px; color: #44546f; font-size: 13px; font-weight: 700; }
/* Writing the suggested days into 每日行程 in one go. */
.suggested-actions { display: flex; align-items: center; gap: 14px; margin: 12px 0 4px; flex-wrap: wrap; }
.suggested-all { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: #44546f; }
.suggested-count { color: #6b7890; font-size: 13px; }
.suggested-message { margin: 8px 0 0; color: #16a34a; font-size: 13px; }
.suggested-warning { color: #9a6a12; }
.plan-item-main { display: flex; align-items: flex-start; gap: 8px; }
.suggested-unwritable { display: block; color: #9a6a12; font-size: 12px; }
.plan-subsection { margin-top: 28px; }
.transport-panel { padding: 18px; border: 1px solid #e7e9ee; border-radius: 6px; background: #f8faff; }
.transport-panel p { margin-bottom: 8px; color: #6b7280; line-height: 1.55; }
.final-summary-panel { padding: 18px; border: 1px solid #e5eaf4; border-radius: 6px; background: #f8faff; }
.final-summary-panel p { margin-bottom: 0; color: #3f514b; line-height: 1.75; white-space: pre-line; }
.budget-brief { margin-top: 12px; padding-top: 10px; border-top: 1px solid #e5eaf4; color: #6b7280; font-size: 14px; line-height: 1.6; }
.budget-itemized { margin-top: 10px; padding: 10px 12px; border-radius: 6px; background: #eef5ef; color: #44546f; font-size: 14px; line-height: 1.6; word-break: break-word; }
.plain-list { margin: 0; padding-left: 20px; color: #6b7280; }
.plain-list li { margin-bottom: 8px; }

.empty-state { color: #6b7280; font-size: 14px; }
.section-note { margin: 0 0 10px; color: #788398; font-size: 13px; }
.error-message { margin-top: 16px; padding: 12px 16px; border-left: 3px solid #c94747; background: #fff4f3; color: #9f2f2f; }
.budget-breakdown, .weather-list { margin: 8px 0 0; padding-left: 18px; color: #6b7280; font-size: 13px; line-height: 1.6; }
.weather-panel { padding: 18px; border: 1px solid #e5eaf4; border-radius: 6px; background: #f8faff; }
.weather-list em { margin-left: 8px; color: #8b5d22; font-style: normal; }
.weather-advice-panel { border-left: 3px solid #c9a227; background: #fffdf6; }
.weather-advice-hint { margin-bottom: 10px; color: #6b5a1c; font-size: 13px; }
.weather-advice-list { margin: 0 0 12px; padding-left: 18px; color: #6b7280; font-size: 13px; line-height: 1.6; }
.weather-advice-list li { margin-bottom: 10px; }
.weather-advice-message { margin: 4px 0 0; }
.weather-advice-suggestion { margin: 4px 0 0; color: #246bfd; font-weight: 600; }
@media (max-width: 760px) { .section-heading, .planning-actions, .plan-item { align-items: flex-start; flex-direction: column; } .plan-grid { grid-template-columns: 1fr; } .recommendation-cards { grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); } }
</style>
