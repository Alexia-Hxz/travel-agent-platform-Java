export interface PlanItem {
  place_name: string
  start_time: string
  end_time: string
  note: string | null
  image_url?: string | null
  fallback_image_url?: string | null
}

export interface DailyPlan {
  date: string
  items: PlanItem[]
  /** Set when a slot was left empty because no unused candidate was left to fill it. */
  shortage?: string | null
}

export interface BudgetEstimate {
  total_estimate: number
  trip_budget: number | null
  remaining_budget: number | null
  currency: string
  note: string
  breakdown?: Array<{ category: string; name: string; amount: number }>
  daily_estimate?: number
  budget_summary?: string
  itemized_summary?: string
  /**
   * Typical accommodation/meal amounts that are deliberately NOT part of `total_estimate`.
   * Those two categories are charged only through an actual ticked or saved place, so the
   * baseline cannot claim them and then add the ticked price on top.
   */
  excluded_allowances?: Array<{ category: string; name: string; amount: number }>
}

export interface BudgetCategory {
  category: string
  label: string
  amount: number
  share?: number
  share_percent?: number
  /** A per-night / per-person reading of the row, e.g. "约 2200 CNY/晚 × 5 晚". */
  unit?: string
}

/**
 * What the traveller's own budget should roughly be spent on, by category.
 *
 * This is the *estimate* the planning panel leads with: the trip's budget times 80%, split by the
 * model (or by the default shares when the model cannot answer) and normalised so the rows add up
 * exactly. It is deliberately not the same number as `BudgetEstimate.total_estimate` (what the
 * saved and ticked places cost) nor as the daily editor's figure (what the scheduled rows cost).
 */
export interface BudgetPlan {
  status: 'estimated' | 'unavailable' | string
  currency: string
  trip_budget?: number | null
  planned_total: number
  planned_ratio?: number
  planned_ratio_percent?: number
  reserve_amount?: number
  /** 'model' when the split came from the model, 'default' when it fell back. */
  source?: string
  days?: number
  nights?: number
  people?: number
  categories: BudgetCategory[]
  note?: string
  summary?: string
}

export interface PlanConflict {
  date: string
  first_place: string
  second_place: string
  message: string
}

export interface PlanSource {
  source_type: string
  title: string
  url: string | null
  snippet: string
  score?: number | null
  image_url?: string | null
}

export interface PlaceRecommendation {
  name: string
  category: string
  reason?: string | null
  price_hint?: string | null
  unit_price?: number | null
  price_unit?: string | null
  estimated_cost?: number | null
  cost_explanation?: string | null
  /**
   * How many times this place is used: nights at this hotel, meals at this restaurant.
   * `estimated_cost` is the price for exactly this many uses. `quantity_unit` is "晚" /
   * "次", or null when a count makes no sense (an attraction).
   */
  quantity?: number | null
  quantity_unit?: string | null
  quantity_max?: number | null
  /** Why the price is what it is, so the card can rebuild the arithmetic live. */
  cost_source?: string | null
  image_url?: string | null
  fallback_image_url?: string | null
  /** Set only for a photo from the Pexels library, which must be credited where shown. */
  image_credit?: string | null
  image_credit_url?: string | null
  /**
   * Rough position of the place, used by the workspace map to sketch where each stop is and
   * how far the next one is. The planner reports an estimate rather than a geocoded address,
   * so it is only good to about a street or two -- enough for the straight-line sketch, never
   * for turn-by-turn navigation. Null when the model did not know.
   */
  latitude?: number | null
  longitude?: number | null
  /** Reference rating (0-5), only reported for hotels. */
  rating?: number | null
  /**
   * The dish a restaurant is known for. Shown on the card, and the subject its picture
   * was searched for.
   */
  signature_dish?: string | null
  /** `breakfast` / `lunch` / `dinner` / `any` for restaurants, else null. */
  meal_slot?: string | null
  /** True when the trip has a budget and this item is above what it can afford. */
  over_budget?: boolean
  budget_limit?: number | null
  budget_note?: string | null
  /**
   * Which price tier of the traveller's own budget this card is: `cheap` / `mid` /
   * `splurge` / `over`, plus its Chinese label. Recommendations are meant to be a mix --
   * cheap places you can eat at every day AND the expensive one that is genuinely good --
   * so the card says which is which instead of leaving one undifferentiated price list.
   */
  price_tier?: string | null
  price_tier_label?: string | null
  /**
   * True when the price on the card was raised to the local minimum for this kind of place,
   * because the material quoted less than a full meal costs there (a Tokyo sushi restaurant
   * shown at 53 CNY, which is a conveyor-belt lunch).
   */
  price_adjusted?: boolean
  /** The material said this place costs nothing to enter. */
  is_free?: boolean
  /** Polling snapshots set this until all categories are ready for final cost allocation. */
  price_pending?: boolean
}

export interface AgentEvent {
  tool: string
  status: string
  message: string
}

export interface TransportAdvice {
  origin: string | null
  destination: string | null
  recommendation: string | null
}

export interface SelectedRecommendation {
  name: string
  category: string
  estimated_cost?: number | null
  reason?: string | null
}

/** A day whose weather threatens what is planned, with a suggested fix. */
export interface WeatherAdvice {
  date: string
  condition: string
  precipitation_probability?: number | null
  indoor_priority?: boolean
  scheduled: string[]
  outdoor_items: string[]
  indoor_items: string[]
  message: string
  suggestion: string
}

export interface PlanHandoff {  selected_recommendations: SelectedRecommendation[]
  selected_total: number
  has_adjustment_summary: boolean
  saved_place_count: number
  saved_place_names: string[]
}

export interface TravelPlan {
  trip_id: number
  summary: string
  final_summary: string
  recommendations: PlanSource[]
  place_recommendations?: PlaceRecommendation[]
  transport_advice: TransportAdvice | null
  daily_plan: DailyPlan[]
  budget: BudgetEstimate
  /** The category-level estimate derived from the trip's own budget; see BudgetPlan. */
  budget_plan?: BudgetPlan
  conflicts: PlanConflict[]
  sources: PlanSource[]
  events: AgentEvent[]
  traveler_profile?: Record<string, number>
  weather?: { status?: string; message?: string; days?: Array<{ date: string; condition?: string; indoor_priority?: boolean; precipitation_probability?: number; max_temperature?: number; min_temperature?: number }> }
  weather_advice?: WeatherAdvice[]
  weather_adjustment_request?: string
  budget_summary?: string
  handoff?: PlanHandoff
}

export interface TravelPlanRequest {
  query: string
  include_web: boolean
  selected_recommendations?: SelectedRecommendation[]
  adjustment_summary?: string | null
}

export interface PlanningJob {
  job_id: string
  trip_id: number
  status: 'queued' | 'running' | 'completed' | 'failed' | string
  phase: string
  progress: number
  events: AgentEvent[]
  place_recommendations: PlaceRecommendation[]
  result: TravelPlan | null
  error: string | null
}
