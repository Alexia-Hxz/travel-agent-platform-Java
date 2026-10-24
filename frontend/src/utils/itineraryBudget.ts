/**
 * The arithmetic behind "调整后的预算", in one place.
 *
 * The panel has to show a total that updates the moment the user ticks, deletes or
 * reprices something -- waiting for a round trip to the backend would make the number
 * jump around after every click. The backend owns the authoritative figure
 * (`app/agents/tools/budget.py::estimate_itinerary_budget`), and this file mirrors
 * exactly that model so the two cannot tell different stories:
 *
 *   total = 市内交通基础估算 (60 x 天数) + 每个行程项的价钱
 *
 * and a place's price is its quoted price when it has one (what the user was shown),
 * otherwise the same price-level formula the backend uses.
 */

/** Local transport, charged per trip day. Matches `_DAILY_TRANSPORT` in budget.py. */
export const DAILY_TRANSPORT = 60
const HOTEL_PER_NIGHT = 260
const MEAL_PER_PERSON = 80
const TRANSPORT_PLACE = 30
const OTHER_PLACE = 100
const DEFAULTS: Record<string, number> = {
  hotel: 3,
  restaurant: 2,
  airport: 1,
  station: 1,
  attraction: 1,
}

/** Something that costs money on the calendar. */
export interface BudgetSubject {
  name: string
  category: string
  /** The price the user agreed to, already covering `quantity` uses. */
  quoted_cost?: number | null
  price_level?: number | null
  quantity?: number | null
  quantity_unit?: string | null
}

export interface BudgetLine {
  name: string
  category: string
  unit_price: number
  quantity: number
  quantity_unit: string | null
  amount: number
  source: 'quoted' | 'level' | 'baseline'
}

export function tripDays(startDate: string | null | undefined, endDate: string | null | undefined): number {
  if (!startDate || !endDate) return 1
  const start = Date.parse(`${startDate}T00:00:00Z`)
  const end = Date.parse(`${endDate}T00:00:00Z`)
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return 1
  return Math.round((end - start) / 86_400_000) + 1
}

export function countUnit(category: string): string | null {
  if (category === 'hotel') return '晚'
  if (category === 'restaurant') return '次'
  return null
}

/** What one night / one meal / one ticket costs before the count is applied. */
export function levelUnitPrice(category: string, level: number | null | undefined, people: number): number {
  const resolved = level === null || level === undefined ? (DEFAULTS[category] ?? 2) : Math.max(0, Math.min(5, level))
  if (category === 'hotel') return resolved * HOTEL_PER_NIGHT
  if (category === 'restaurant') return resolved * MEAL_PER_PERSON * Math.max(1, people)
  if (category === 'airport' || category === 'station') return resolved * TRANSPORT_PLACE
  return resolved * OTHER_PLACE
}

export function quantityOf(subject: BudgetSubject): number {
  const unit = countUnit(subject.category)
  if (!unit) return 1
  const value = subject.quantity
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return 1
  return Math.max(1, Math.min(99, Math.round(Number(value))))
}

/** One line of the estimate, priced exactly as the backend prices it. */
export function budgetLine(subject: BudgetSubject, people: number): BudgetLine {
  const quantity = quantityOf(subject)
  const unit = countUnit(subject.category)
  const quoted = subject.quoted_cost
  if (quoted !== null && quoted !== undefined && Number.isFinite(Number(quoted))) {
    const amount = Math.max(0, Math.round(Number(quoted)))
    return {
      name: subject.name,
      category: subject.category,
      // The quoted number already covers the count, so the unit price is only shown so
      // the user can see what changing the count would do.
      unit_price: quantity > 1 ? Math.round(amount / quantity) : amount,
      quantity,
      quantity_unit: unit,
      amount,
      source: 'quoted',
    }
  }
  const unitPrice = levelUnitPrice(subject.category, subject.price_level, people)
  return {
    name: subject.name,
    category: subject.category,
    unit_price: unitPrice,
    quantity,
    quantity_unit: unit,
    amount: unitPrice * quantity,
    source: 'level',
  }
}

export interface ItineraryEstimate {
  transport: number
  lines: BudgetLine[]
  total: number
}

/**
 * Price a whole itinerary the way the backend does: a transport baseline plus one line
 * per scheduled item. Deleting an item removes its line, which is the whole point.
 */
export function estimateItinerary(subjects: BudgetSubject[], days: number, people: number): ItineraryEstimate {
  const transport = DAILY_TRANSPORT * Math.max(1, days)
  const lines = subjects.map((subject) => budgetLine(subject, people))
  return { transport, lines, total: lines.reduce((sum, line) => sum + line.amount, transport) }
}
