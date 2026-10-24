/**
 * Calendar dates, in the user's own timezone.
 *
 * Element Plus hands date pickers a Date at LOCAL midnight. Converting that with
 * `toISOString()` renders it in UTC, which west-to-east of Greenwich shifts the day
 * backwards: in UTC+8, 2026-10-01 00:00 local becomes "2026-09-30". Comparing that
 * against a trip's ISO date string disabled the trip's own first day and allowed the
 * day after it ended, and made a one-day trip impossible to create.
 */

/** Format a Date as `YYYY-MM-DD` using its LOCAL calendar fields. */
export function toLocalIsoDate(value: Date): string {
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/** True when the picked calendar day falls before `startDate` or after `endDate`. */
export function isDateOutsideRange(value: Date, startDate: string, endDate: string): boolean {
  const date = toLocalIsoDate(value)
  return date < startDate || date > endDate
}

/** True when the picked calendar day falls before `startDate`. */
export function isDateBefore(value: Date, startDate: string): boolean {
  return toLocalIsoDate(value) < startDate
}
