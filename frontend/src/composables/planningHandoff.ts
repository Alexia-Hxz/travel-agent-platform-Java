import { computed, reactive } from 'vue'

/**
 * Carries what the user picked in the planning agent and what the conversational
 * adjustment concluded over to the "生成行程介绍与总结" panel.
 *
 * The three panels are siblings inside `TripDetail.vue`, so instead of threading
 * props and events through the parent, they share this tiny per-trip store. It is
 * mirrored into sessionStorage so a page refresh inside the same tab keeps the
 * handoff instead of silently dropping it.
 */

export interface SelectedRecommendation {
  name: string
  category: string
  estimated_cost: number | null
  reason: string | null
}

/**
 * A recommendation from the latest plan, kept whole enough for the daily-itinerary
 * editor to offer it as an addition with the same price the planning card showed.
 */
export interface PlannedRecommendation {
  name: string
  category: string
  reason?: string | null
  estimated_cost?: number | null
  unit_price?: number | null
  quantity?: number | null
  quantity_unit?: string | null
  price_level?: number | null
  image_url?: string | null
  signature_dish?: string | null
  rating?: number | null
  /**
   * The planner's rough position for this place. Carried through the handoff so a place added
   * from a recommendation still reaches the workspace map instead of arriving as a blank pin.
   */
  latitude?: number | null
  longitude?: number | null
}

export interface PlanningHandoff {
  /** Recommendation cards the user ticked in the planning agent. */
  selectedRecommendations: SelectedRecommendation[]
  /**
   * Every place the latest plan proposed, with its price. This is what lets the
   * adjustment panel suggest "从规划建议里选一个" instead of inventing a new place, and
   * it is also sent to the backend as candidate material.
   */
  planRecommendations: PlannedRecommendation[]
  /** The latest conversational adjustment conclusion, when one was generated. */
  adjustmentSummary: string | null
  /** When the handoff was last written, so the summary panel can label it. */
  updatedAt: string | null
  /** Progress flags, so the step guide can show what has been done already. */
  planGenerated: boolean
  summaryGenerated: boolean
}

const STORAGE_KEY = 'travel-agent.planning-handoff'

function emptyHandoff(): PlanningHandoff {
  return {
    selectedRecommendations: [],
    planRecommendations: [],
    adjustmentSummary: null,
    updatedAt: null,
    planGenerated: false,
    summaryGenerated: false,
  }
}

function loadStored(): Record<number, PlanningHandoff> {
  const stored: Record<number, PlanningHandoff> = {}
  if (typeof window === 'undefined') return stored
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return stored
    const parsed = JSON.parse(raw) as Record<string, Partial<PlanningHandoff>>
    for (const [tripId, value] of Object.entries(parsed)) {
      const numericId = Number(tripId)
      if (!Number.isFinite(numericId)) continue
      stored[numericId] = {
        selectedRecommendations: Array.isArray(value?.selectedRecommendations)
          ? value!.selectedRecommendations!
          : [],
        planRecommendations: Array.isArray(value?.planRecommendations) ? value!.planRecommendations! : [],
        adjustmentSummary: value?.adjustmentSummary ?? null,
        updatedAt: value?.updatedAt ?? null,
        planGenerated: value?.planGenerated === true,
        summaryGenerated: value?.summaryGenerated === true,
      }
    }
  } catch {
    // A corrupt or unavailable store must never break the page.
  }
  return stored
}

const handoffs = reactive<Record<number, PlanningHandoff>>(loadStored())

/** Whether two selections say exactly the same thing. */
function sameSelections(a: SelectedRecommendation[], b: SelectedRecommendation[]): boolean {
  if (a.length !== b.length) return false
  return a.every((item, index) => {
    const other = b[index]
    return (
      item.name === other.name &&
      item.category === other.category &&
      (item.estimated_cost ?? null) === (other.estimated_cost ?? null) &&
      (item.reason ?? null) === (other.reason ?? null)
    )
  })
}

/**
 * The same no-op guard, for the plan's recommendation list.
 *
 * Writing an equal-but-new array here would re-render every panel that reads the
 * handoff, exactly like `setSelectedRecommendations` used to, so the comparison is by
 * content and only the fields the editor actually reads.
 */
function sameRecommendations(a: PlannedRecommendation[], b: PlannedRecommendation[]): boolean {
  if (a.length !== b.length) return false
  return a.every((item, index) => {
    const other = b[index]
    return (
      item.name === other.name &&
      item.category === other.category &&
      (item.estimated_cost ?? null) === (other.estimated_cost ?? null) &&
      (item.quantity ?? null) === (other.quantity ?? null) &&
      (item.unit_price ?? null) === (other.unit_price ?? null) &&
      (item.reason ?? null) === (other.reason ?? null)
    )
  })
}

function persist(): void {
  if (typeof window === 'undefined') return
  try {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(handoffs))
  } catch {
    // Ignore quota or privacy-mode failures; the in-memory copy still works.
  }
}

export function usePlanningHandoff(tripId: number) {
  const handoff = computed<PlanningHandoff>(() => handoffs[tripId] ?? emptyHandoff())

  function setSelectedRecommendations(items: SelectedRecommendation[]): void {
    const current = handoffs[tripId] ?? emptyHandoff()
    // Re-storing the same selection is NOT a harmless no-op. It would replace the object,
    // bump `updatedAt`, and re-render every panel that shows this handoff -- including the
    // parent that owns the planning agent. A caller that reacts to its own re-render then
    // writes again, and the page spins (see the regression test in TripPlanningAgent.spec.ts).
    if (sameSelections(current.selectedRecommendations, items)) return
    handoffs[tripId] = { ...current, selectedRecommendations: items, updatedAt: new Date().toISOString() }
    persist()
  }

  function setPlanRecommendations(items: PlannedRecommendation[]): void {
    const current = handoffs[tripId] ?? emptyHandoff()
    if (sameRecommendations(current.planRecommendations, items)) return
    handoffs[tripId] = { ...current, planRecommendations: items, updatedAt: new Date().toISOString() }
    persist()
  }

  function setAdjustmentSummary(summary: string): void {
    const text = summary.trim()
    if (!text) return
    const current = handoffs[tripId] ?? emptyHandoff()
    if (current.adjustmentSummary === text) return
    handoffs[tripId] = { ...current, adjustmentSummary: text, updatedAt: new Date().toISOString() }
    persist()
  }

  function markPlanGenerated(): void {
    const current = handoffs[tripId] ?? emptyHandoff()
    handoffs[tripId] = { ...current, planGenerated: true, updatedAt: new Date().toISOString() }
    persist()
  }

  function markSummaryGenerated(): void {
    const current = handoffs[tripId] ?? emptyHandoff()
    handoffs[tripId] = { ...current, summaryGenerated: true, updatedAt: new Date().toISOString() }
    persist()
  }

  function clear(): void {
    handoffs[tripId] = emptyHandoff()
    persist()
  }

  return {
    handoff,
    setSelectedRecommendations,
    setPlanRecommendations,
    setAdjustmentSummary,
    markPlanGenerated,
    markSummaryGenerated,
    clear,
  }
}
