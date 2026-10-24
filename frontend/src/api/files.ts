import type { TripFile } from '@/types/tripFile'
import type { VisionConfirmation, VisionConfirmationResult, VisionPreview } from '@/types/vision'
import { apiFetch } from './http'


async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await apiFetch(`${path}`, options)
  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as { detail?: string } | null
    throw new Error(payload?.detail ?? 'File request failed.')
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T)
}

export function listTripFiles(tripId: number): Promise<TripFile[]> {
  return request<TripFile[]>(`/trips/${tripId}/files`)
}

export function uploadTripFile(tripId: number, file: File): Promise<TripFile> {
  const formData = new FormData()
  formData.append('file', file)
  return request<TripFile>(`/trips/${tripId}/files`, { method: 'POST', body: formData })
}

export function deleteTripFile(tripId: number, fileId: number): Promise<void> {
  return request<void>(`/trips/${tripId}/files/${fileId}`, { method: 'DELETE' })
}

export function parseTripFile(tripId: number, fileId: number): Promise<TripFile> {
  return request<TripFile>(`/trips/${tripId}/files/${fileId}/parse`, { method: 'POST' })
}

/**
 * Download a file the signed-in account owns.
 *
 * This cannot be an <a href="..."> anymore: the download endpoint is behind the same
 * bearer token as everything else, and a plain link cannot carry an Authorization header.
 * The alternatives were a token in the query string (which puts it in history and server
 * logs) or this: fetch with the header, hand the bytes to the browser as a blob.
 */
export async function downloadTripFile(tripId: number, fileId: number, filename: string): Promise<void> {
  const response = await apiFetch(`/trips/${tripId}/files/${fileId}/download`)
  if (!response.ok) {
    throw new Error('下载文件失败，请稍后重试。')
  }
  const blob = await response.blob()
  const objectUrl = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = filename
    document.body.appendChild(link)
    link.click()
    link.remove()
  } finally {
    // Revoking immediately keeps the blob from living until the page is closed.
    URL.revokeObjectURL(objectUrl)
  }
}

export function previewVisionAnalysis(tripId: number, fileId: number): Promise<VisionPreview> {
  return request<VisionPreview>(`/trips/${tripId}/files/${fileId}/vision/preview`, { method: 'POST' })
}

export function confirmVisionAnalysis(
  tripId: number,
  fileId: number,
  payload: VisionConfirmation,
): Promise<VisionConfirmationResult> {
  return request<VisionConfirmationResult>(`/trips/${tripId}/files/${fileId}/vision/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}
