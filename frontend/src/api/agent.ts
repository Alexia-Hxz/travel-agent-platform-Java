import type { PlanningJob, TravelPlan, TravelPlanRequest } from '@/types/agent'
import { apiFetch } from './http'


/**
 * Raised when the backend answered but rejected the request. The plan view can
 * then tell a slow generation apart from an unreachable backend, instead of always
 * blaming a stopped service.
 */
export class PlanRequestError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
    this.name = 'PlanRequestError'
  }
}

export interface PlanProgressCallbacks {
  onProgress?: (job: PlanningJob) => void
  signal?: AbortSignal
  pollIntervalMs?: number
}

function isPlanningJob(value: TravelPlan | PlanningJob): value is PlanningJob {
  return typeof (value as PlanningJob).job_id === 'string'
}

async function waitForPoll(milliseconds: number, signal?: AbortSignal): Promise<void> {
  if (signal?.aborted) throw new DOMException('Planning cancelled', 'AbortError')
  await new Promise<void>((resolve, reject) => {
    const timer = window.setTimeout(resolve, milliseconds)
    signal?.addEventListener('abort', () => {
      window.clearTimeout(timer)
      reject(new DOMException('Planning cancelled', 'AbortError'))
    }, { once: true })
  })
}

export async function generateTravelPlan(
  tripId: number,
  payload: TravelPlanRequest,
  callbacks: PlanProgressCallbacks = {},
): Promise<TravelPlan> {
  let response: Response
  try {
    response = await apiFetch(`/trips/${tripId}/plan-jobs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal: callbacks.signal,
    })
  } catch (error) {
    // A rejected fetch means the request never reached the backend.
    throw new PlanRequestError(0, '无法连接后端服务，请确认服务已启动后重试 / Cannot reach the backend。')
  }
  if (!response.ok) {
    throw new PlanRequestError(
      response.status,
      `生成规划失败（HTTP ${response.status}） / Plan generation failed (HTTP ${response.status})。`,
    )
  }
  let current = (await response.json()) as TravelPlan | PlanningJob
  // Compatibility with an older backend (and with saved fixtures): a final plan can still
  // be returned directly. New backends answer with a job id and short polling snapshots.
  if (!isPlanningJob(current)) return current
  callbacks.onProgress?.(current)
  while (current.status === 'queued' || current.status === 'running') {
    await waitForPoll(callbacks.pollIntervalMs ?? 800, callbacks.signal)
    let poll: Response
    try {
      poll = await apiFetch(`/trips/${tripId}/plan-jobs/${current.job_id}`, { signal: callbacks.signal })
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') throw error
      throw new PlanRequestError(0, '无法连接后端服务，请确认服务已启动后重试 / Cannot reach the backend。')
    }
    if (!poll.ok) {
      throw new PlanRequestError(poll.status, `读取规划进度失败（HTTP ${poll.status}）。`)
    }
    current = (await poll.json()) as PlanningJob
    callbacks.onProgress?.(current)
  }
  if (current.status === 'completed' && current.result) return current.result
  throw new PlanRequestError(500, current.error || '规划生成失败，请稍后重试。')
}
