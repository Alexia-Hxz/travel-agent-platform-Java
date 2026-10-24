import type { ItineraryItem, Place, PlaceType } from '@/types/itinerary'
import type { Trip } from '@/types/trip'

export const placeTypeLabels: Record<PlaceType, string> = { attraction: '景点', hotel: '酒店', restaurant: '餐厅', airport: '机场', station: '车站' }
export function peopleCount(trip: Trip): number { return (trip.adult_male_count || 0) + (trip.adult_female_count || 0) + (trip.child_male_count || 0) + (trip.child_female_count || 0) }
export function tripDates(start: string, end: string): string[] {
  const first = Date.parse(`${start}T00:00:00Z`), last = Date.parse(`${end}T00:00:00Z`)
  if (!Number.isFinite(first) || !Number.isFinite(last) || last < first) return []
  return Array.from({ length: Math.min(3660, Math.round((last - first) / 86400000) + 1) }, (_, i) => new Date(first + i * 86400000).toISOString().slice(0, 10))
}
export function hasCoordinates(place: Pick<Place, 'latitude' | 'longitude'>): boolean {
  return typeof place.latitude === 'number' && Number.isFinite(place.latitude) && Math.abs(place.latitude) <= 85.0511 && typeof place.longitude === 'number' && Number.isFinite(place.longitude) && Math.abs(place.longitude) <= 180
}
/**
 * The rough position a planner recommendation reports, kept only when both halves are usable.
 *
 * The range check is deliberately the same one the map uses (`hasCoordinates`), so a place is
 * saved with a position exactly when the map can draw it -- saving a latitude of 91 or a
 * longitude of 181 would only produce a pin nobody can see.
 */
export function approximateCoordinates(source: { latitude?: number | null; longitude?: number | null }): { latitude: number | null; longitude: number | null } {
  const latitude = typeof source.latitude === 'number' ? source.latitude : null
  const longitude = typeof source.longitude === 'number' ? source.longitude : null
  if (!hasCoordinates({ latitude, longitude })) return { latitude: null, longitude: null }
  return { latitude, longitude }
}
export function distanceKm(a: Place, b: Place): number | null {
  if (!hasCoordinates(a) || !hasCoordinates(b)) return null
  const rad = Math.PI / 180, lat = (b.latitude! - a.latitude!) * rad, lng = (b.longitude! - a.longitude!) * rad
  const h = Math.sin(lat / 2) ** 2 + Math.cos(a.latitude! * rad) * Math.cos(b.latitude! * rad) * Math.sin(lng / 2) ** 2
  return 6371 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)))
}
export function itineraryText(trip: Trip, items: ItineraryItem[]): string {
  const lines = [`# ${trip.title}`, '', `${trip.destination} · ${trip.start_date} 至 ${trip.end_date}`, `预算：${trip.budget === null ? '未设置' : `¥${trip.budget}`} · ${peopleCount(trip) || '未设置'} 人同行`, '']
  let day = ''
  for (const item of [...items].sort((a, b) => `${a.date}${a.start_time}`.localeCompare(`${b.date}${b.start_time}`))) {
    if (item.date !== day) { day = item.date; lines.push('', `## ${day}`, '') }
    lines.push(`- ${item.start_time.slice(0, 5)}–${item.end_time.slice(0, 5)} ${item.place.name}${item.note ? `：${item.note}` : ''}`)
    if (item.place.address) lines.push(`  地址：${item.place.address}`)
  }
  if (!items.length) lines.push('尚未安排行程。')
  return lines.join('\n')
}
