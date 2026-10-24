import type { KnowledgeRebuildResult, KnowledgeSearchResult, KnowledgeStatus } from '@/types/knowledge'
import { apiFetch } from './http'


async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await apiFetch(path, options)
  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as { detail?: string } | null
    throw new Error(payload?.detail ?? 'Knowledge request failed.')
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T)
}

export function getKnowledgeStatus(tripId: number): Promise<KnowledgeStatus> {
  return request<KnowledgeStatus>(`/trips/${tripId}/knowledge/status`)
}

/**
 * Ask the document index a question directly.
 *
 * Separate from the Copilot on purpose: it makes retrieval observable. A question asked
 * here shows the matching excerpts and their similarity, so "the assistant cited my
 * booking" can be told apart from "the assistant happened to guess right".
 */
export function searchKnowledge(tripId: number, query: string, limit = 5): Promise<KnowledgeSearchResult> {
  return request<KnowledgeSearchResult>(
    `/trips/${tripId}/knowledge/search?q=${encodeURIComponent(query)}&limit=${limit}`,
  )
}

export function rebuildKnowledgeIndex(tripId: number): Promise<KnowledgeRebuildResult> {
  return request<KnowledgeRebuildResult>(`/trips/${tripId}/knowledge/rebuild`, { method: 'POST' })
}
