import { describe, expect, it } from 'vitest'

import { GENERIC_PLACEHOLDER, placePlaceholderUrl, resolveAssetUrl, resolvePlaceImage } from './assets'

describe('resolveAssetUrl', () => {
  it('keeps an absolute provider URL as it is', () => {
    expect(resolveAssetUrl('https://images.pexels.com/photos/1/pexels-photo-1.jpeg')).toBe(
      'https://images.pexels.com/photos/1/pexels-photo-1.jpeg',
    )
  })

  it('resolves an app-owned path against the API origin', () => {
    expect(resolveAssetUrl('/static/place_images/hotel.svg')).toBe(
      'http://127.0.0.1:8001/static/place_images/hotel.svg',
    )
  })

  it('is empty for nothing at all', () => {
    expect(resolveAssetUrl(null)).toBe('')
    expect(resolveAssetUrl('   ')).toBe('')
  })
})

describe('placePlaceholderUrl', () => {
  it('has a picture per category', () => {
    expect(placePlaceholderUrl('hotel')).toContain('/static/place_images/hotel.svg')
    expect(placePlaceholderUrl('restaurant')).toContain('/static/place_images/restaurant.svg')
    expect(placePlaceholderUrl('attraction')).toContain('/static/place_images/attraction.svg')
  })

  it('falls back to the generic picture for anything else', () => {
    // The saved-place collection has types the planning agent never produces.
    expect(placePlaceholderUrl('airport')).toContain(GENERIC_PLACEHOLDER)
    expect(placePlaceholderUrl(null)).toContain(GENERIC_PLACEHOLDER)
    expect(placePlaceholderUrl(undefined)).toContain(GENERIC_PLACEHOLDER)
  })

  it('is case and space insensitive, because place types are free text', () => {
    expect(placePlaceholderUrl(' Hotel ')).toContain('/static/place_images/hotel.svg')
  })
})

describe('resolvePlaceImage', () => {
  it("uses the place's own photo when there is one", () => {
    const resolved = resolvePlaceImage({ image_url: 'https://example.com/a.jpg', type: 'attraction' })
    expect(resolved.src).toBe('https://example.com/a.jpg')
    expect(resolved.fallback).toContain('/static/place_images/attraction.svg')
    expect(resolved.usingPlaceholder).toBe(false)
  })

  it('shows the category placeholder when the place has no photo', () => {
    const resolved = resolvePlaceImage({ image_url: null, type: 'hotel' })
    expect(resolved.src).toContain('/static/place_images/hotel.svg')
    expect(resolved.usingPlaceholder).toBe(true)
  })

  it('falls back once the caller retires a photo the browser could not load', () => {
    // This is the seeded hotel: example.com/hotel_image.jpg, which answers HTTP 404.
    const place = { image_url: 'https://example.com/hotel_image.jpg', type: 'hotel' }
    const resolved = resolvePlaceImage(place, () => false)
    expect(resolved.src).toContain('/static/place_images/hotel.svg')
    expect(resolved.usingPlaceholder).toBe(true)
  })
})
