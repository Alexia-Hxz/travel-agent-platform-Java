import type { AgentEvent, BudgetEstimate, PlanConflict, PlanSource, PlaceRecommendation } from '@/types/agent'
import type { PlaceCreate } from '@/types/itinerary'

export type AdjustmentOperationKind =
  | 'remove_item'
  | 'add_item'
  | 'add_place_and_item'
  | 'set_budget'
  | 'update_item'
export type AdjustmentStatus = 'pending' | 'applied' | 'rejected'

export interface AdjustmentOperation {
  kind: AdjustmentOperationKind
  item_id: number | null
  place_id: number | null
  place: PlaceCreate | null
  date: string | null
  start_time: string | null
  end_time: string | null
  note: string | null
  sort_order: number
  budget_amount?: number | null
}

/** A plan recommendation the editor is allowed to add, with its agreed price. */
export interface AdjustmentCandidate {
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
}

export interface AdjustmentDraftRequest {
  instruction: string
  include_web: boolean
  /**
   * Operations built by the panel itself. When present the backend validates, previews and
   * prices exactly these changes; the model only runs for the parts they leave undecided.
   */
  operations?: AdjustmentOperation[]
  /** True when `instruction` merely echoes `operations` and the user typed nothing new. */
  structured_only?: boolean
  candidates?: AdjustmentCandidate[]
}

/**
 * One editable line of the adjusted budget: a scheduled item, its price, its count and
 * where that price came from. This is what makes "删掉的项目预算也要去掉" visible instead
 * of a single unexplained total.
 */
export interface AdjustmentBudgetRow {
  category: string
  name: string
  amount: number
  unit_price?: number
  quantity?: number
  quantity_unit?: string | null
  people?: number | null
  price_level?: number | null
  source: 'quoted' | 'level' | 'baseline'
  date?: string | null
  start_time?: string | null
  end_time?: string | null
  item_id?: number | null
  place_id?: number | null
  /** Which operation of the draft produced this row, when one did. */
  operation_index?: number | null
  note?: string | null
}

export type AdjustmentChanged = 'kept' | 'added' | 'moved'

export interface AdjustmentPlanItem {
  item_id: number | null
  place_id: number | null
  start_time: string
  end_time: string
  place_name: string
  note?: string | null
  image_url?: string | null
  type?: string | null
  changed?: AdjustmentChanged
}

export interface AdjustmentDayPlan {
  date: string
  items: AdjustmentPlanItem[]
}

export interface AdjustmentDraft {
  id: number
  trip_id: number
  instruction: string
  status: AdjustmentStatus
  operations: AdjustmentOperation[]
  summary: string
  sources: PlanSource[]
  events: AgentEvent[]
  budget: Partial<BudgetEstimate>
  conflicts: PlanConflict[]
  daily_plan: AdjustmentDayPlan[]
  final_summary: string
  traveler_profile?: Record<string, number>
}

export interface AdjustmentApplyResult {
  id: number
  status: 'applied'
  itinerary_item_ids: number[]
}

export type { PlaceRecommendation }
