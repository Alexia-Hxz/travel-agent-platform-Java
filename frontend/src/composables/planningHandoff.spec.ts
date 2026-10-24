/**
 * The per-trip handoff store.
 *
 * These tests pin the guard that keeps the store from being a render-loop amplifier: writing
 * the SAME selection again must change nothing at all. It used to replace the stored object
 * and bump `updatedAt` every time, which re-rendered every panel reading the handoff -- and
 * the planning agent's parent is one of them, so a caller writing on its own re-render span
 * the page until the browser ran out of memory.
 */
import { beforeEach, describe, expect, it } from 'vitest'

import { usePlanningHandoff } from './planningHandoff'

const SELECTION = [{ name: '夫子庙', category: 'attraction', estimated_cost: 100, reason: '夜景' }]

describe('usePlanningHandoff', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    usePlanningHandoff(7).clear()
  })

  it('stores a selection', () => {
    usePlanningHandoff(7).setSelectedRecommendations(SELECTION)

    expect(usePlanningHandoff(7).handoff.value.selectedRecommendations).toEqual(SELECTION)
    expect(usePlanningHandoff(7).handoff.value.updatedAt).not.toBeNull()
  })

  it('ignores a selection that says the same thing', () => {
    const handoff = usePlanningHandoff(7)
    handoff.setSelectedRecommendations(SELECTION)
    const firstWrite = handoff.handoff.value
    const firstUpdatedAt = firstWrite.updatedAt

    // A brand-new array with identical content: an equal-but-new value must not be treated
    // as a change, or every re-render that rebuilds the list would write again.
    handoff.setSelectedRecommendations(SELECTION.map((item) => ({ ...item })))

    expect(handoff.handoff.value).toBe(firstWrite)
    expect(handoff.handoff.value.updatedAt).toBe(firstUpdatedAt)
  })

  it('stores a selection that really changed', () => {
    const handoff = usePlanningHandoff(7)
    handoff.setSelectedRecommendations(SELECTION)

    handoff.setSelectedRecommendations([...SELECTION, { name: '江南贡院', category: 'attraction', estimated_cost: 50, reason: null }])

    expect(handoff.handoff.value.selectedRecommendations).toHaveLength(2)
  })

  it('stores the plan recommendations and ignores an equal rewrite', () => {
    const handoff = usePlanningHandoff(7)
    const recommendation = {
      name: '南京大牌檔',
      category: 'restaurant',
      reason: '本地菜',
      estimated_cost: 240,
      unit_price: 120,
      quantity: 2,
      quantity_unit: '次',
    }

    handoff.setPlanRecommendations([recommendation])
    const firstWrite = handoff.handoff.value

    // Same guard as the selection: a rebuilt equal array must not re-render the panels.
    handoff.setPlanRecommendations([{ ...recommendation }])

    expect(handoff.handoff.value).toBe(firstWrite)
    expect(handoff.handoff.value.planRecommendations).toEqual([recommendation])
  })

  it('writes the plan recommendations again when a price changed', () => {
    const handoff = usePlanningHandoff(7)
    handoff.setPlanRecommendations([{ name: '金陵饭店', category: 'hotel', estimated_cost: 620, quantity: 1 }])
    const firstWrite = handoff.handoff.value

    // Raising the count changes the money, so it has to reach the editor.
    handoff.setPlanRecommendations([{ name: '金陵饭店', category: 'hotel', estimated_cost: 1860, quantity: 3 }])

    expect(handoff.handoff.value).not.toBe(firstWrite)
    expect(handoff.handoff.value.planRecommendations[0].estimated_cost).toBe(1860)
  })

  it('gives every trip its own recommendations', () => {
    usePlanningHandoff(7).setPlanRecommendations([{ name: '夫子庙', category: 'attraction' }])

    expect(usePlanningHandoff(99).handoff.value.planRecommendations).toEqual([])
  })
})
