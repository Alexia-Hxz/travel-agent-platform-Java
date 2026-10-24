import type {
  AdjustmentApplyResult,
  AdjustmentDraft,
  AdjustmentDraftRequest,
  AdjustmentOperation,
} from '@/types/adjustment'
import { apiFetch } from './http'


async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await apiFetch(`${path}`, init)
  if (!response.ok) {
    throw new Error('行程调整请求失败。')
  }
  return (await response.json()) as T
}

export function createAdjustmentDraft(tripId: number, payload: AdjustmentDraftRequest): Promise<AdjustmentDraft> {
  return request<AdjustmentDraft>(`/trips/${tripId}/adjustments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export function listAdjustmentDrafts(tripId: number): Promise<AdjustmentDraft[]> {
  return request<AdjustmentDraft[]>(`/trips/${tripId}/adjustments`)
}

/**
 * Apply a draft. `operations` are the ones the user edited in the preview; sending them
 * means the price or count they changed is what gets written. Without them the draft's
 * own stored operations are used, optionally narrowed to `operationIndexes`.
 */
export function applyAdjustment(
  tripId: number,
  adjustmentId: number,
  operationIndexes?: number[],
  operations?: AdjustmentOperation[],
): Promise<AdjustmentApplyResult> {
  const body: Record<string, unknown> = {}
  if (operationIndexes && operationIndexes.length > 0) body.operation_indexes = operationIndexes
  if (operations && operations.length > 0) body.operations = operations
  return request<AdjustmentApplyResult>(`/trips/${tripId}/adjustments/${adjustmentId}/apply`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: Object.keys(body).length > 0 ? JSON.stringify(body) : undefined,
  })
}

export function rejectAdjustment(tripId: number, adjustmentId: number): Promise<AdjustmentDraft> {
  return request<AdjustmentDraft>(`/trips/${tripId}/adjustments/${adjustmentId}/reject`, { method: 'POST' })
}
