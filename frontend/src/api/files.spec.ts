import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { listTripFiles, previewVisionAnalysis, downloadTripFile } from './files'
import { setAuthToken } from './http'

/** The Authorization header the api layer attached, if any. */
function authHeaderOf(fetchMock: ReturnType<typeof vi.fn>, call = 0): string | null {
  const init = fetchMock.mock.calls[call]?.[1] as RequestInit | undefined
  return new Headers(init?.headers).get('Authorization')
}

function urlOf(fetchMock: ReturnType<typeof vi.fn>, call = 0): string {
  return String(fetchMock.mock.calls[call]?.[0])
}

describe('trip file API', () => {
  beforeEach(() => {
    setAuthToken(null)
  })

  afterEach(() => {
    setAuthToken(null)
    vi.unstubAllGlobals()
  })

  it('uses the phase three backend default when no API base URL is configured', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify([]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await listTripFiles(134)

    expect(urlOf(fetchMock)).toBe('http://127.0.0.1:8001/api/v1/trips/134/files')
  })

  it('sends the stored token on every request', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify([]), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    )
    vi.stubGlobal('fetch', fetchMock)
    setAuthToken('token-from-login')

    await listTripFiles(134)

    expect(authHeaderOf(fetchMock)).toBe('Bearer token-from-login')
  })

  it('preserves the parser failure detail returned by the API', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ detail: 'PaddleOCR is not installed.' }), { status: 422 }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(listTripFiles(134)).rejects.toThrow('PaddleOCR is not installed.')
  })

  it('requests a multimodal preview for an uploaded image', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ document_type: 'menu', summary: 'Restaurant menu', place: null, itinerary: null, reference_number: null, raw_text: null }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await previewVisionAnalysis(7, 31)

    expect(urlOf(fetchMock)).toBe('http://127.0.0.1:8001/api/v1/trips/7/files/31/vision/preview')
    expect((fetchMock.mock.calls[0][1] as RequestInit).method).toBe('POST')
  })

  it('downloads through an authenticated request instead of a bare link', async () => {
    // A plain <a href> cannot carry the bearer token, so the bytes are fetched and handed
    // to the browser as a blob; the token must never end up in a URL.
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(new Blob(['ticket']), { status: 200, headers: { 'Content-Type': 'application/pdf' } }),
    )
    vi.stubGlobal('fetch', fetchMock)
    setAuthToken('token-from-login')
    const clicked: string[] = []
    const createObjectURL = vi.fn(() => 'blob:mock-url')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })
    const originalClick = HTMLAnchorElement.prototype.click
    HTMLAnchorElement.prototype.click = function click(this: HTMLAnchorElement) {
      clicked.push(this.href)
    }

    try {
      await downloadTripFile(7, 31, 'ticket.pdf')
    } finally {
      HTMLAnchorElement.prototype.click = originalClick
    }

    expect(urlOf(fetchMock)).toBe('http://127.0.0.1:8001/api/v1/trips/7/files/31/download')
    expect(urlOf(fetchMock)).not.toContain('token')
    expect(authHeaderOf(fetchMock)).toBe('Bearer token-from-login')
    expect(clicked).toEqual(['blob:mock-url'])
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock-url')
  })
})
