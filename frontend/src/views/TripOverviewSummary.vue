<script setup lang="ts">
import { MagicStick, Search } from '@element-plus/icons-vue'
import { computed, ref } from 'vue'

import { generateTravelPlan } from '@/api/agent'
import { resolvePlaceImage } from '@/api/assets'
import { usePlanningHandoff } from '@/composables/planningHandoff'
import type { TravelPlan } from '@/types/agent'
import type { ItineraryItem, Place } from '@/types/itinerary'
import type { Trip } from '@/types/trip'
import CollapsiblePanel from '@/components/CollapsiblePanel.vue'
import { defaultOpenForCount } from '@/composables/panelState'

const props = defineProps<{
  trip: Trip
  places: Place[]
  itineraryItems: ItineraryItem[]
}>()

const includeWeb = ref(false)
const loading = ref(false)
const errorMessage = ref('')
const plan = ref<TravelPlan | null>(null)
/** Bumped when a summary arrives, so the fresh result opens without hiding it. */
const summaryToken = ref(0)

// What the planning agent and the conversational adjustment produced. Both are
// folded into the summary request so the write-up covers them, not just the
// records saved on the trip.
const { handoff, markSummaryGenerated } = usePlanningHandoff(props.trip.id)

const selectedRecommendations = computed(() => handoff.value.selectedRecommendations)
const adjustmentSummary = computed(() => handoff.value.adjustmentSummary)

const PLACE_TYPE_LABELS: Record<string, string> = {
  hotel: '住宿',
  restaurant: '餐饮',
  attraction: '景点',
  airport: '机场',
  station: '车站',
}

// The summary is generated from the saved collection, so the collection is shown
// here in full: if a place is missing from this list, it is missing from the write-up.
const hasAnyMaterial = computed(
  () => props.places.length > 0 || selectedRecommendations.value.length > 0 || Boolean(adjustmentSummary.value),
)

function placeTypeLabel(type: string): string {
  return PLACE_TYPE_LABELS[type] ?? '其他'
}

/**
 * The picture to show for a saved place, which is what this page renders -- not a freshly
 * generated plan. Saved places can carry a dead URL (a seeded hotel pointed at
 * example.com/hotel_image.jpg, which is a 404) or no URL at all, and a card with a broken
 * icon or an empty box reads as a bug. So a photo that has already failed is retired per
 * place and the category placeholder is shown instead, never nothing.
 */
const failedPlaceImages = ref<Set<number>>(new Set())

function placeImage(place: Place): string {
  return resolvePlaceImage(
    { image_url: place.image_url, type: place.type },
    () => !failedPlaceImages.value.has(place.id),
  ).src
}

function markPlaceImageFailed(place: Place): void {
  failedPlaceImages.value = new Set(failedPlaceImages.value).add(place.id)
}

const requestText = computed(
  () => `请综合介绍${props.trip.destination}的本次旅行，并根据已收藏地点和每日行程生成最终安排。旅行日期为${props.trip.start_date}至${props.trip.end_date}，预算为${props.trip.budget ?? '未设置'}。请列出每天的时间安排、预算、注意事项和可能的冲突。`,
)

/**
 * A summary describes one particular set of inputs. Once the collection or the
 * itinerary changes, the summary is quietly out of date, so the inputs it was built
 * from are recorded and compared.
 */
interface SummarySnapshot {
  places: string[]
  items: string[]
  selected: string[]
  adjustment: string
}

function currentSnapshot(): SummarySnapshot {
  return {
    places: props.places.map((place) => `${place.id}|${place.name}|${place.opening_hours ?? ''}`).sort(),
    items: props.itineraryItems
      .map((item) => `${item.id}|${item.date}|${item.start_time}|${item.place_id}`)
      .sort(),
    selected: selectedRecommendations.value
      .map((item) => `${item.name}|${item.estimated_cost ?? ''}`)
      .sort(),
    adjustment: adjustmentSummary.value ?? '',
  }
}

const generatedFrom = ref<SummarySnapshot | null>(null)

/** What changed since the summary was generated, in the user's words. */
const staleChanges = computed<string[]>(() => {
  const before = generatedFrom.value
  if (!before) return []
  const now = currentSnapshot()
  const changes: string[] = []

  const addedPlaces = now.places.filter((entry) => !before.places.includes(entry)).length
  const removedPlaces = before.places.filter((entry) => !now.places.includes(entry)).length
  if (addedPlaces) changes.push(`新增 ${addedPlaces} 个收藏地点`)
  if (removedPlaces) changes.push(`减少了 ${removedPlaces} 个收藏地点`)

  const addedItems = now.items.filter((entry) => !before.items.includes(entry)).length
  const removedItems = before.items.filter((entry) => !now.items.includes(entry)).length
  if (addedItems) changes.push(`新增 ${addedItems} 个行程项`)
  if (removedItems) changes.push(`调整或删除了 ${removedItems} 个行程项`)

  if (now.selected.join('|') !== before.selected.join('|')) changes.push('规划建议的勾选有变化')
  if (now.adjustment !== before.adjustment) changes.push('对话式调整结论有更新')
  return changes
})

const isStale = computed(() => staleChanges.value.length > 0)

function timeLabel(value: string): string {
  return value.slice(0, 5)
}

async function generateSummary(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  // Captured before the request: the inputs the backend is about to use.
  const snapshot = currentSnapshot()
  try {
    plan.value = await generateTravelPlan(props.trip.id, {
      query: requestText.value,
      include_web: includeWeb.value,
      selected_recommendations: selectedRecommendations.value,
      adjustment_summary: adjustmentSummary.value,
    })
    generatedFrom.value = snapshot
    summaryToken.value += 1
    markSummaryGenerated()
  } catch {
    errorMessage.value = '无法生成行程介绍，请确认后端服务已启动后重试。'
  } finally {
    loading.value = false
  }
}

</script>
<template>
  <section class="overview-section" aria-label="行程介绍与总结">
    <div class="section-heading">
      <div>
        <p class="section-label">TRIP OVERVIEW · 行程总览</p>
        <h2>行程介绍与总结</h2>
      </div>
      <el-icon class="agent-icon"><MagicStick /></el-icon>
    </div>

    <div class="context-grid">
      <div class="context-item"><span>目的地</span><strong>{{ trip.destination }}</strong></div>
      <div class="context-item"><span>旅行日期</span><strong>{{ trip.start_date }} 至 {{ trip.end_date }}</strong></div>
      <div class="context-item"><span>预算</span><strong>{{ trip.budget ?? '未设置' }} {{ trip.budget === null ? '' : 'CNY' }}</strong></div>
      <div class="context-item"><span>已整理资料</span><strong>收藏地点 {{ places.length }} 个 · 每日行程 {{ itineraryItems.length }} 项</strong></div>
    </div>

    <div v-if="itineraryItems.length" class="context-details">
      <div>
        <h3>已排日期</h3>
        <p>{{ [...new Set(itineraryItems.map((item) => item.date))].join('、') }}</p>
      </div>
    </div>

    <CollapsiblePanel
      class="handoff-panel"
      aria-label="将纳入总结的资料"
      panel-key="summary-material"
      :scope="trip.id"
      title="将纳入总结的资料 / Material folded into the summary"
      heading-tag="h3"
      :count="`收藏 ${places.length} · 行程 ${itineraryItems.length}`"
      :default-open="defaultOpenForCount(places.length)"
    >
      <p v-if="!hasAnyMaterial" class="handoff-empty">
        还没有可纳入的资料。请先在下方“生成规划建议”里勾选项目并加入收藏地点，或在“对话式调整行程”里生成一次调整草案。
      </p>

      <div v-if="places.length" class="handoff-block">
        <strong>你收藏的 {{ places.length }} 个地点（全部纳入总结）</strong>
        <ul class="collection-list">
          <li v-for="place in places" :key="place.id">
            <img
              :src="placeImage(place)"
              :alt="place.name"
              class="collection-thumb"
              loading="lazy"
              @error="markPlaceImageFailed(place)"
            />
            <span class="collection-body">
              <span class="collection-name">{{ place.name }}</span>
              <span class="collection-meta">
                {{ placeTypeLabel(place.type) }}
                <template v-if="place.address"> · {{ place.address }}</template>
                <template v-if="place.opening_hours"> · {{ place.opening_hours }}</template>
                <template v-if="place.estimated_cost"> · 参考价约 {{ place.estimated_cost }} CNY</template>
              </span>
              <span v-if="place.note" class="collection-note">选择理由：{{ place.note }}</span>
            </span>
          </li>
        </ul>
      </div>

      <div v-if="selectedRecommendations.length" class="handoff-block">
        <strong>规划建议中已选的 {{ selectedRecommendations.length }} 个项目</strong>
        <ul class="plain-list">
          <li v-for="item in selectedRecommendations" :key="`${item.category}-${item.name}`">
            {{ item.name }}<span v-if="item.estimated_cost !== null && item.estimated_cost !== undefined">：约 {{ item.estimated_cost }} CNY</span>
          </li>
        </ul>
      </div>
      <div v-if="adjustmentSummary" class="handoff-block">
        <strong>对话式调整行程的结论</strong>
        <p class="handoff-text">{{ adjustmentSummary }}</p>
      </div>
    </CollapsiblePanel>

    <div class="overview-actions">
      <el-checkbox v-model="includeWeb">允许联网补充最新资料</el-checkbox>
      <el-button
        type="primary"
        :icon="Search"
        :loading="loading"
        aria-label="生成行程介绍与总结"
        @click="generateSummary"
      >生成行程介绍与总结</el-button>
    </div>

    <p v-if="errorMessage" class="error-message" role="alert">{{ errorMessage }}</p>

    <div v-if="plan" class="summary-results">
      <p v-if="isStale" class="stale-warning" role="status">
        <span>
          这份总结生成后，你的资料有了变化（{{ staleChanges.join('、') }}），当前结论可能已经不一致。
        </span>
        <el-button text type="primary" :loading="loading" aria-label="Regenerate the summary" @click="generateSummary">
          按最新资料重新生成
        </el-button>
      </p>
      <p class="plan-summary">{{ plan.summary }}</p>
      <CollapsiblePanel
        class="summary-block"
        aria-label="最终行程总结"
        panel-key="summary-final"
        :scope="trip.id"
        title="最终行程总结"
        heading-tag="h3"
        :auto-open-token="summaryToken"
      >
        <p class="final-summary">{{ plan.final_summary }}</p>
        <p v-if="plan.handoff && plan.handoff.saved_place_count" class="handoff-confirmation" role="status">
          已确认纳入：收藏地点 {{ plan.handoff.saved_place_count }} 个<span v-if="plan.handoff.selected_recommendations.length"> · 已选推荐 {{ plan.handoff.selected_recommendations.length }} 个</span><span v-if="plan.handoff.has_adjustment_summary"> · 对话式调整结论</span>
        </p>
      </CollapsiblePanel>

      <div class="result-grid">
        <CollapsiblePanel
          class="summary-block"
          panel-key="summary-days"
          :scope="trip.id"
          title="每日详细安排"
          heading-tag="h3"
          :count="plan.daily_plan.length > 0 ? `${plan.daily_plan.length} 天` : null"
          :default-open="defaultOpenForCount(plan.daily_plan.length)"
        >
          <p v-if="plan.daily_plan.length === 0" class="empty-state">暂无可展示的每日安排。</p>
          <div v-for="day in plan.daily_plan" :key="day.date" class="day-plan">
            <strong>{{ day.date }}</strong>
            <p v-for="item in day.items" :key="`${day.date}-${item.place_name}-${item.start_time}`">
              <span class="time-range">{{ timeLabel(item.start_time) }} - {{ timeLabel(item.end_time) }}</span>
              {{ item.place_name }}<span v-if="item.note">：{{ item.note }}</span>
            </p>
          </div>
        </CollapsiblePanel>
        <CollapsiblePanel
          class="summary-block budget-block"
          panel-key="summary-budget"
          :scope="trip.id"
          title="预算与注意事项"
          heading-tag="h3"
          :count="`${plan.budget.total_estimate} ${plan.budget.currency}`"
        >
          <p class="budget-value">{{ plan.budget.total_estimate }} {{ plan.budget.currency }}</p>
          <p v-if="plan.budget.trip_budget !== null">剩余预算：{{ plan.budget.remaining_budget }} {{ plan.budget.currency }}</p>
          <p>{{ plan.budget.note }}</p>
        </CollapsiblePanel>
      </div>

      <CollapsiblePanel
        class="summary-block"
        panel-key="summary-conflicts"
        :scope="trip.id"
        title="时间冲突"
        heading-tag="h3"
        :count="plan.conflicts.length > 0 ? `${plan.conflicts.length} 项` : '无冲突'"
      >
        <p v-if="plan.conflicts.length === 0" class="empty-state">未发现时间冲突。</p>
        <ul v-else class="plain-list"><li v-for="conflict in plan.conflicts" :key="`${conflict.date}-${conflict.message}`">{{ conflict.message }}</li></ul>
      </CollapsiblePanel>
    </div>
  </section>
</template>

<style scoped>
.overview-section { max-width: 1120px; margin: 32px auto 0; padding: 28px 0 4px; border-top: 1px solid #e7e9ee; }
.section-heading, .overview-actions, .block-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.section-label { margin: 0 0 6px; color: #6b7890; font-size: 12px; font-weight: 700; }
h2, h3, p { margin-top: 0; }
h2 { margin-bottom: 0; font-size: 22px; }
h3 { margin-bottom: 10px; font-size: 16px; }
.agent-icon { color: #246bfd; font-size: 26px; }
.context-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-top: 22px; }
.context-item { padding: 14px; border: 1px solid #e7e9ee; background: #fff; }
.context-item span { display: block; margin-bottom: 6px; color: #6b7784; font-size: 13px; }
.context-item strong { color: #111827; font-size: 15px; line-height: 1.4; }
.context-details { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-top: 18px; color: #6b7280; }
.context-details h3 { margin-bottom: 5px; font-size: 14px; color: #246bfd; }
.context-details p { margin-bottom: 0; font-size: 14px; line-height: 1.5; }
.overview-actions { margin-top: 22px; }
.handoff-panel { margin-top: 20px; padding: 16px 18px; border: 1px solid #e5eaf4; border-radius: 6px; background: #f8faff; }
.handoff-panel h3 { margin-bottom: 10px; font-size: 15px; color: #246bfd; }
.handoff-block { margin-top: 12px; color: #6b7280; font-size: 14px; }
.handoff-block strong { display: block; margin-bottom: 6px; color: #44546f; }
.collection-list { margin: 0; padding: 0; list-style: none; display: grid; gap: 8px; }
.collection-list li { display: flex; align-items: center; gap: 10px; }
.collection-thumb { width: 44px; height: 44px; flex: 0 0 44px; border-radius: 4px; object-fit: cover; background: #eef2ee; }
.collection-thumb-empty { display: inline-block; border: 1px dashed #cbd8cf; }
.collection-body { display: flex; flex-direction: column; min-width: 0; }
.collection-name { color: #44546f; font-weight: 600; }
.collection-meta { color: #788398; font-size: 13px; overflow-wrap: anywhere; }
.collection-note { color: #6b7280; font-size: 13px; overflow-wrap: anywhere; }
.handoff-confirmation { margin-top: 10px; color: #246bfd; font-size: 13px; font-weight: 600; }
.stale-warning { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 16px; padding: 10px 14px; border-left: 3px solid #c9a227; border-radius: 4px; background: #fdf8e7; color: #6b5a1c; font-size: 13px; }
.handoff-text { margin: 0; color: #3f514b; line-height: 1.65; white-space: pre-line; }
.handoff-empty { margin-bottom: 0; color: #6b7784; font-size: 13px; line-height: 1.6; }
.summary-results { margin-top: 24px; }
.plan-summary { margin-bottom: 18px; color: #246bfd; font-weight: 600; }
.summary-block { margin-top: 22px; padding-top: 18px; border-top: 1px solid #e7e9ee; }
.final-summary { margin: 12px 0 0; color: #3f514b; line-height: 1.75; white-space: pre-line; }
.result-grid { display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(220px, .7fr); gap: 24px; }
.day-plan { padding: 10px 0; border-top: 1px solid #edf0ed; color: #6b7280; }
.day-plan strong { display: block; margin-bottom: 7px; color: #246bfd; }
.day-plan p { margin-bottom: 6px; font-size: 14px; line-height: 1.5; }
.time-range { display: inline-block; min-width: 100px; color: #111827; font-variant-numeric: tabular-nums; }
.budget-block { padding: 18px; border: 1px solid #e7e9ee; background: #f0f5f1; }
.budget-value { margin-bottom: 8px; color: #111827; font-size: 24px; font-weight: 700; }
.budget-block p { color: #6b7280; font-size: 14px; line-height: 1.5; }
.plain-list { margin: 0; padding-left: 20px; color: #6b7280; }
.plain-list li { margin-bottom: 8px; }
.empty-state { color: #6b7280; font-size: 14px; }
.error-message { margin-top: 16px; padding: 12px 16px; border-left: 3px solid #c94747; background: #fff4f3; color: #9f2f2f; }
.save-message { margin: 10px 0 0; color: #16a34a; font-size: 14px; }
@media (max-width: 760px) {
  .overview-section { margin-top: 24px; }
  .context-grid, .result-grid, .context-details { grid-template-columns: 1fr; }
  .overview-actions, .block-heading { align-items: flex-start; flex-direction: column; }
}
</style>
