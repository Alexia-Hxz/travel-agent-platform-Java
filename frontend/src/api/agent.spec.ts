import { describe, expect, it, vi } from 'vitest'

import { generateTravelPlan } from './agent'
import type { PlanningJob, TravelPlan } from '@/types/agent'


const finalPlan: TravelPlan = {
  trip_id: 7,
  summary: 'ready',
  final_summary: 'done',
  recommendations: [],
  place_recommendations: [],
  transport_advice: null,
  daily_plan: [],
  budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
  conflicts: [],
  sources: [],
  events: [],
}

function job(overrides: Partial<PlanningJob>): PlanningJob {
  return {
    job_id: 'abc', trip_id: 7, status: 'running', phase: 'search', progress: 18,
    events: [], place_recommendations: [], result: null, error: null, ...overrides,
  }
}

describe('planning job polling', () => {
  it('reports incremental category cards before returning the completed plan', async () => {
    const updates: PlanningJob[] = []
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(job({ status: 'queued', phase: 'queued', progress: 0 })), { status: 202 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(job({
        phase: 'category:restaurant', progress: 46,
        place_recommendations: [{ name: '寿司大', category: 'restaurant', price_pending: true }],
      })), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(job({
        status: 'completed', phase: 'completed', progress: 100, result: finalPlan,
      })), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await generateTravelPlan(
      7,
      { query: '东京五日游', include_web: true },
      { pollIntervalMs: 0, onProgress: update => updates.push(update) },
    )

    expect(result).toEqual(finalPlan)
    expect(updates.map(update => update.progress)).toEqual([0, 46, 100])
    expect(updates[1].place_recommendations[0]).toMatchObject({ name: '寿司大', price_pending: true })
    expect(fetchMock.mock.calls[0][0]).toContain('/trips/7/plan-jobs')
    expect(fetchMock.mock.calls[1][0]).toContain('/trips/7/plan-jobs/abc')
  })
})
