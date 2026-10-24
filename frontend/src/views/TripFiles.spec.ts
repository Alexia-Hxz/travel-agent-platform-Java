import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import TripFiles from './TripFiles.vue'


function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const KNOWLEDGE_STATUS = {
  available: true,
  indexed_chunks: 4,
  indexed_files: 1,
  backend: 'chromadb',
  collection: 'trip_documents',
  path: 'data/chroma',
  embedding_model: 'text-embedding-v4',
  embedding_dim: 1024,
  error: null,
}

const IMAGE_FILE = {
  id: 32, trip_id: 7, original_filename: 'museum.jpg', stored_filename: 'museum.jpg', content_type: 'image/jpeg',
  file_size: 1024, file_path: 'data/uploads/7/museum.jpg', parse_status: 'pending', parsed_text: null,
  parsed_result: null, parse_error: null, parsed_at: null, index_status: 'pending', index_error: null,
  indexed_chunks: 0, indexed_at: null, created_at: '2026-09-05T10:00:00',
}

const PDF_FILE = { ...IMAGE_FILE, id: 31, original_filename: 'transport-ticket.pdf', content_type: 'application/pdf' }

/**
 * TripFiles asks for the file list AND the index summary on mount, so one blanket
 * response is no longer enough: the stub has to answer by URL. Routes are checked in
 * order, so a specific fragment always wins over the defaults below it.
 */
function stubApi(files: unknown[], routes: Array<[string, () => Response]> = []): void {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input)
      for (const [fragment, respond] of routes) {
        if (url.includes(fragment)) return Promise.resolve(respond())
      }
      if (url.includes('/knowledge/status')) return Promise.resolve(json(KNOWLEDGE_STATUS))
      return Promise.resolve(json(files))
    }),
  )
}

describe('TripFiles', () => {
  it('lists a trip file returned by the API', async () => {
    stubApi([PDF_FILE])

    const wrapper = mount(TripFiles, {
      props: { tripId: 7 },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('transport-ticket.pdf')
    expect(wrapper.text()).toContain('Parse file')
  })

  it('reports how much of the trip is searchable', async () => {
    stubApi([PDF_FILE])

    const wrapper = mount(TripFiles, {
      props: { tripId: 7 },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('4 段可检索')
    expect(wrapper.text()).toContain('text-embedding-v4')
  })

  it('offers multimodal analysis for an uploaded image', async () => {
    stubApi([IMAGE_FILE])

    const wrapper = mount(TripFiles, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('Analyze with agent')
  })

  it('notifies the parent after saving an analyzed place', async () => {
    stubApi([IMAGE_FILE], [
      ['/vision/preview', () => json({
        summary: 'Detected place', document_type: 'ticket', reference_number: null,
        raw_text: 'Museum', place: { name: 'Museum', type: 'attraction', address: 'Road', latitude: null, longitude: null, image_url: null, rating: null, price_level: null, opening_hours: null, source_url: null },
        itinerary: null,
      })],
      ['/vision/confirm', () => json({ place: { id: 99 }, itinerary: null }, 201)],
    ])
    const wrapper = mount(TripFiles, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await flushPromises()
    const analyzeButton = wrapper.findAll('button').find((button) => button.text().includes('Analyze with agent'))
    expect(analyzeButton).toBeDefined()
    await analyzeButton!.trigger('click')
    await flushPromises()
    const confirmButton = wrapper.findAll('button').find((button) => button.text().includes('Confirm save'))
    expect(confirmButton).toBeDefined()
    await confirmButton!.trigger('click')
    await flushPromises()

    expect(wrapper.emitted('places-updated')).toHaveLength(1)
  })

  it('shows the excerpt and similarity a document search returned', async () => {
    stubApi([PDF_FILE], [
      ['/knowledge/search', () => json({
        status: 'ok',
        reason: null,
        chunks: [{
          chunk_id: 'file-31-chunk-0', file_id: 31, source_label: 'hotel-voucher.pdf',
          chunk_index: 0, text: '入住日期: 2026-10-02', similarity: 0.8316,
        }],
      })],
    ])
    const wrapper = mount(TripFiles, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.find('.knowledge-search input').setValue('我订的酒店什么时候入住')
    const searchButton = wrapper.findAll('button').find((button) => button.text().includes('检索 / Search'))
    expect(searchButton).toBeDefined()
    await searchButton!.trigger('click')
    await flushPromises()

    // The source and the score are shown on purpose: they are what separate a cited
    // booking from a plausible-sounding guess.
    expect(wrapper.text()).toContain('hotel-voucher.pdf')
    expect(wrapper.text()).toContain('入住日期: 2026-10-02')
    expect(wrapper.text()).toContain('0.832')
  })

  it('reports why a search found nothing instead of showing an empty list', async () => {
    stubApi([PDF_FILE], [
      ['/knowledge/search', () => json({
        status: 'empty', reason: '这趟旅行还没有已建立索引的资料。', chunks: [],
      })],
    ])
    const wrapper = mount(TripFiles, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await flushPromises()

    await wrapper.find('.knowledge-search input').setValue('有资料吗')
    await wrapper.findAll('button').find((button) => button.text().includes('检索 / Search'))!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('这趟旅行还没有已建立索引的资料。')
  })
})
