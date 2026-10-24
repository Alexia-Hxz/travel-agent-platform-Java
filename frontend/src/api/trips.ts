import type { Trip, TripCreate } from '@/types/trip'
import { apiFetch } from './http'


async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await apiFetch(`${path}`, {
    headers: { 'Content-Type': 'application/json', ...options?.headers },
    ...options,
  })

  if (!response.ok) {
    throw new Error('请求旅行数据失败，请稍后重试。')
  }

  return response.status === 204 ? (undefined as T) : ((await response.json()) as T)
}

export function listTrips(): Promise<Trip[]> {
  return request<Trip[]>('/trips')
}

export function createTrip(payload: TripCreate): Promise<Trip> {
  return request<Trip>('/trips', { method: 'POST', body: JSON.stringify(payload) })
}

export function deleteTrip(tripId: number): Promise<void> {
  return request<void>(`/trips/${tripId}`, { method: 'DELETE' })
}
