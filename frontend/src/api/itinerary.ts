import type { AutoSchedule, ItineraryItem, ItineraryItemCreate, Place, PlaceCreate, PlaceUpdate } from '@/types/itinerary'
import { apiFetch } from './http'


async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await apiFetch(`${path}`, {
    headers: { 'Content-Type': 'application/json', ...options?.headers },
    ...options,
  })

  if (!response.ok) {
    throw new Error('请求行程数据失败，请稍后重试。')
  }

  return response.status === 204 ? (undefined as T) : ((await response.json()) as T)
}

export function listPlaces(tripId: number): Promise<Place[]> {
  return request<Place[]>(`/trips/${tripId}/places`)
}

export function createPlace(tripId: number, payload: PlaceCreate): Promise<Place> {
  return request<Place>(`/trips/${tripId}/places`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/** Correct a saved place's price or count. Only the sent fields change. */
export function updatePlace(tripId: number, placeId: number, payload: PlaceUpdate): Promise<Place> {
  return request<Place>(`/trips/${tripId}/places/${placeId}`, {
    method: 'PATCH',
    body: JSON.stringify(payload),
  })
}

export function deletePlace(tripId: number, placeId: number): Promise<void> {
  return request<void>(`/trips/${tripId}/places/${placeId}`, { method: 'DELETE' })
}

/**
 * Delete every saved place of a trip at once and report how many were removed.
 *
 * One request instead of one per place: a half-finished loop would leave the trip in a
 * state the user did not ask for, and the itinerary items booked for those places go
 * with them, so the caller has to reload the calendar either way.
 */
export function clearPlaces(tripId: number): Promise<{ removed: number }> {
  return request<{ removed: number }>(`/trips/${tripId}/places`, { method: 'DELETE' })
}

export function listItineraryItems(tripId: number): Promise<ItineraryItem[]> {
  return request<ItineraryItem[]>(`/trips/${tripId}/itinerary-items`)
}

export function createItineraryItem(tripId: number, payload: ItineraryItemCreate): Promise<ItineraryItem> {
  return request<ItineraryItem>(`/trips/${tripId}/itinerary-items`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function deleteItineraryItem(tripId: number, itemId: number): Promise<void> {
  return request<void>(`/trips/${tripId}/itinerary-items/${itemId}`, { method: 'DELETE' })
}

/** Ask the backend where the unscheduled saved places should go. Writes nothing. */
export function previewAutoSchedule(tripId: number): Promise<AutoSchedule> {
  return request<AutoSchedule>(`/trips/${tripId}/itinerary-items/auto-schedule`, { method: 'POST' })
}
