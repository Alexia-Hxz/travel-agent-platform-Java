import { onMounted, reactive, ref } from 'vue'
import { clearPlaces, createItineraryItem, createPlace, deleteItineraryItem, deletePlace, listItineraryItems, listPlaces, previewAutoSchedule } from '@/api/itinerary'
import { confirmDestructive } from '@/utils/confirm'
import { isDateOutsideRange } from '@/utils/date'
import type { AutoSchedule, ItineraryItem, ItineraryItemCreate, Place, PlaceCreate, PlaceType } from '@/types/itinerary'
import type { Trip } from '@/types/trip'

/** Existing trip mutations, separated from the workspace presentation. */
export function useTripWorkspace(trip: Trip) {
const props = { trip }
const places = ref<Place[]>([])
const itineraryItems = ref<ItineraryItem[]>([])
const loading = ref(true)
const submittingPlace = ref(false)
const submittingItem = ref(false)
const errorMessage = ref('')
const budgetOverride = ref<number | null | undefined>(undefined)
const placeDialogVisible = ref(false)
const itemDialogVisible = ref(false)
const autoScheduleDialogVisible = ref(false)
const autoScheduleLoading = ref(false)
const applyingAutoSchedule = ref(false)
const autoSchedule = ref<AutoSchedule | null>(null)
const autoScheduleMessage = ref('')
/** Status line under the saved-places heading, e.g. the result of clearing the list. */
const placesMessage = ref('')
/** Validation and save errors shown INSIDE a dialog, not behind its modal mask. */
const placeDialogError = ref('')
const itemDialogError = ref('')

const placeTypeLabels: Record<PlaceType, string> = {
  attraction: '景点',
  hotel: '酒店',
  restaurant: '餐厅',
  airport: '机场',
  station: '车站',
}

const placeForm = reactive<PlaceCreate>({
  name: '',
  type: 'attraction',
  address: '',
  latitude: null,
  longitude: null,
  image_url: null,
  rating: null,
  price_level: null,
  opening_hours: null,
  source_url: null,
  note: null,
  estimated_cost: null,
})

const itemForm = reactive<ItineraryItemCreate>({
  date: props.trip.start_date,
  start_time: '09:00:00',
  end_time: '10:00:00',
  place_id: null,
  note: null,
  sort_order: 0,
})

function cleanOptionalText(value: string | null): string | null {
  return value?.trim() || null
}

function isDateOutsideTrip(value: Date): boolean {
  // Local calendar fields, NOT toISOString(): see src/utils/date.ts.
  return isDateOutsideRange(value, props.trip.start_date, props.trip.end_date)
}

function timeLabel(value: string): string {
  return value.slice(0, 5)
}

/**
 * Reload places and itinerary items.
 *
 * `silent` must be used for every refresh that follows a write. Every panel is
 * mounted behind `v-if="!loading"`, so flipping `loading` UNMOUNTS them: the
 * generated plan, the adjustment draft and the final summary are component-local
 * state and would be destroyed, forcing the user to pay for the LLM call again.
 * Only the initial page load shows the full-page state.
 */
async function loadTripDetails({ silent = false }: { silent?: boolean } = {}): Promise<void> {
  if (!silent) {
    loading.value = true
  }
  errorMessage.value = ''
  try {
    const [loadedPlaces, loadedItems] = await Promise.all([
      listPlaces(props.trip.id),
      listItineraryItems(props.trip.id),
    ])
    places.value = loadedPlaces
    itineraryItems.value = loadedItems
  } catch {
    errorMessage.value = '暂时无法加载地点和行程，请确认后端服务已启动。'
  } finally {
    if (!silent) {
      loading.value = false
    }
  }
}

/** Refresh after a write without tearing down the panels. */
let refreshTask: Promise<void> | null = null
function refreshAfterWrite(): Promise<void> {
  if (!refreshTask) refreshTask = loadTripDetails({ silent: true }).finally(() => { refreshTask = null })
  return refreshTask
}

function openPlaceDialog(): void {
  Object.assign(placeForm, {
    name: '',
    type: 'attraction',
    address: '',
    latitude: null,
    longitude: null,
    image_url: null,
    rating: null,
    price_level: null,
    opening_hours: null,
    source_url: null,
    note: null,
    estimated_cost: null,
  })
  placeDialogError.value = ''
  placeDialogVisible.value = true
}

function openItemDialog(day = props.trip.start_date): void {
  Object.assign(itemForm, {
    date: day,
    start_time: '09:00:00',
    end_time: '10:00:00',
    place_id: places.value[0]?.id ?? null,
    note: null,
    sort_order: itineraryItems.value.length,
  })
  itemDialogError.value = ''
  itemDialogVisible.value = true
}

async function submitPlace(): Promise<void> {
  placeDialogError.value = ''
  if (!placeForm.name.trim() || !placeForm.address.trim()) {
    placeDialogError.value = '请填写地点名称和地址。'
    return
  }

  submittingPlace.value = true
  try {
    const place = await createPlace(props.trip.id, {
      ...placeForm,
      name: placeForm.name.trim(),
      address: placeForm.address.trim(),
      image_url: cleanOptionalText(placeForm.image_url),
      opening_hours: cleanOptionalText(placeForm.opening_hours),
      source_url: cleanOptionalText(placeForm.source_url),
    })
    places.value = [...places.value, place]
    placeDialogVisible.value = false
  } catch {
    placeDialogError.value = '添加地点失败，请检查填写内容（名称和地址必填）。'
  } finally {
    submittingPlace.value = false
  }
}

async function submitItineraryItem(): Promise<void> {
  itemDialogError.value = ''
  if (!itemForm.date || itemForm.place_id === null) {
    itemDialogError.value = '请选择行程日期和地点。'
    return
  }
  // Checked here so the reason is specific and shown next to the form, instead of a
  // generic 422 from the backend that the user cannot see behind the modal mask.
  if (isDateOutsideRange(new Date(`${itemForm.date}T00:00:00`), props.trip.start_date, props.trip.end_date)) {
    itemDialogError.value = `日期需要在旅行期间内（${props.trip.start_date} 至 ${props.trip.end_date}）。`
    return
  }
  if (itemForm.end_time < itemForm.start_time) {
    itemDialogError.value = '结束时间不能早于开始时间。'
    return
  }

  submittingItem.value = true
  try {
    const item = await createItineraryItem(props.trip.id, {
      ...itemForm,
      note: cleanOptionalText(itemForm.note),
    })
    itineraryItems.value = [...itineraryItems.value, item].sort(
      (left, right) =>
        left.date.localeCompare(right.date) ||
        left.sort_order - right.sort_order ||
        left.start_time.localeCompare(right.start_time),
    )
    itemDialogVisible.value = false
  } catch {
    itemDialogError.value = '添加行程项失败，请检查填写内容后重试。'
  } finally {
    submittingItem.value = false
  }
}

async function removePlace(place: Place): Promise<void> {
  // Deleting a place also deletes the itinerary items booked for it (the model
  // relationship is `cascade="all, delete-orphan"`), which heals the old error text
  // that claimed the items had to be removed first.
  const scheduled = itineraryItems.value.filter((item) => item.place_id === place.id).length
  const consequence = scheduled
    ? `它已排入 ${scheduled} 个行程项，这些行程项会一并删除，且无法恢复。`
    : '删除后无法恢复。'
  if (!(await confirmDestructive(`删除「${place.name}」？`, consequence))) return

  errorMessage.value = ''
  try {
    await deletePlace(props.trip.id, place.id)
    places.value = places.value.filter((item) => item.id !== place.id)
    itineraryItems.value = itineraryItems.value.filter((item) => item.place_id !== place.id)
  } catch {
    errorMessage.value = '删除地点失败，请稍后重试。'
  }
}

/**
 * Throw the whole saved-places list away in one go, so planning can start from nothing.
 *
 * The point is to be able to re-plan from scratch: keeping a handful of places from the
 * previous round is what makes a fresh plan come back looking like the old one. The
 * itinerary items booked for those places go with them, which is spelled out in the
 * confirmation because it cannot be undone.
 */
async function clearAllPlaces(): Promise<void> {
  if (places.value.length === 0) return
  const scheduled = itineraryItems.value.length
  const consequence = scheduled
    ? `已收藏的 ${places.value.length} 个地点会全部删除，其中 ${scheduled} 个行程项也会一并删除，且无法恢复。`
    : `已收藏的 ${places.value.length} 个地点会全部删除，且无法恢复。`
  if (!(await confirmDestructive('清空全部收藏地点？', `${consequence}规划建议里已经勾选的内容不会保留。`))) {
    return
  }

  errorMessage.value = ''
  try {
    const result = await clearPlaces(props.trip.id)
    places.value = []
    itineraryItems.value = []
    placesMessage.value = `已清空 ${result.removed} 个收藏地点，现在可以重新生成规划建议了。`
  } catch {
    errorMessage.value = '清空收藏地点失败，请稍后重试。'
  }
}

async function removeItineraryItem(item: ItineraryItem): Promise<void> {
  if (
    !(await confirmDestructive(
      `删除「${item.place.name}」的这段行程？`,
      `${item.date} ${item.start_time.slice(0, 5)}-${item.end_time.slice(0, 5)} 的安排会被移除，且无法恢复。收藏地点会保留。`,
    ))
  ) {
    return
  }

  errorMessage.value = ''
  try {
    await deleteItineraryItem(props.trip.id, item.id)
    itineraryItems.value = itineraryItems.value.filter((entry) => entry.id !== item.id)
  } catch {
    errorMessage.value = '删除行程项失败，请稍后重试。'
  }
}

/**
 * Ask the backend where the unscheduled saved places should go, show the proposal,
 * and only create the items once the user confirms it.
 */
async function openAutoSchedule(): Promise<void> {
  autoScheduleLoading.value = true
  errorMessage.value = ''
  autoScheduleMessage.value = ''
  try {
    autoSchedule.value = await previewAutoSchedule(props.trip.id)
    autoScheduleDialogVisible.value = true
  } catch {
    errorMessage.value = '无法生成行程建议，请稍后重试。'
  } finally {
    autoScheduleLoading.value = false
  }
}

async function applyAutoSchedule(): Promise<void> {
  if (applyingAutoSchedule.value || !autoSchedule.value || autoSchedule.value.proposed.length === 0) return
  applyingAutoSchedule.value = true
  errorMessage.value = ''
  let created = 0
  try {
    for (const proposed of autoSchedule.value.proposed) {
      const item = await createItineraryItem(props.trip.id, {
        date: proposed.date,
        start_time: proposed.start_time,
        end_time: proposed.end_time,
        place_id: proposed.place_id,
        note: proposed.note,
        sort_order: itineraryItems.value.length + created,
      })
      itineraryItems.value = [...itineraryItems.value, item]
      created += 1
    }
    itineraryItems.value = [...itineraryItems.value].sort(
      (left, right) =>
        left.date.localeCompare(right.date) ||
        left.sort_order - right.sort_order ||
        left.start_time.localeCompare(right.start_time),
    )
    autoScheduleDialogVisible.value = false
    autoScheduleMessage.value = `已把 ${created} 个收藏地点排进行程。`
    autoSchedule.value = null
  } catch {
    // Some items may already exist; reload so the page matches the server.
    autoScheduleDialogVisible.value = false
    errorMessage.value = `已排入 ${created} 个，其余失败，请刷新后重试。`
    await loadTripDetails({ silent: true })
  } finally {
    applyingAutoSchedule.value = false
  }
}

function updateBudget(value: number | null): void {
  budgetOverride.value = value
}

onMounted(() => {
  void loadTripDetails()
})

return { places, itineraryItems, loading, errorMessage, budgetOverride, placeDialogVisible, itemDialogVisible,
  submittingPlace, submittingItem, placeForm, itemForm, placeDialogError, itemDialogError,
  autoScheduleDialogVisible, autoScheduleLoading, applyingAutoSchedule, autoSchedule, autoScheduleMessage,
  placesMessage, placeTypeLabels, isDateOutsideTrip, loadTripDetails, refreshAfterWrite, openPlaceDialog,
  openItemDialog, submitPlace, submitItineraryItem, removePlace, clearAllPlaces, removeItineraryItem,
  openAutoSchedule, applyAutoSchedule, updateBudget }
}
