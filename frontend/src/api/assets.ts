const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? 'http://127.0.0.1:8001/api/v1'

function backendOrigin(): string {
  try {
    return new URL(apiBaseUrl, globalThis.location?.origin ?? 'http://localhost').origin
  } catch {
    return ''
  }
}

/**
 * Place images arrive either as an absolute provider URL or as an app-owned path
 * such as `/static/place_images/hotel.svg`. The relative form is served by the
 * backend, so it must be resolved against the API origin before the browser uses it.
 */
export function resolveAssetUrl(url: string | null | undefined): string {
  const value = (url ?? '').trim()
  if (!value) return ''
  if (/^(https?:)?\/\//i.test(value) || value.startsWith('data:') || value.startsWith('blob:')) return value
  return `${backendOrigin()}${value.startsWith('/') ? value : `/${value}`}`
}

/**
 * The app-owned picture to show when a place has none, or when the one it has is dead.
 *
 * Measured on the real database: a seeded hotel carried `https://example.com/hotel_image.jpg`
 * (HTTP 404) and a saved attraction had no URL at all, so the collection list on the trip
 * overview showed a broken-image icon and an empty dashed box. A card with no picture reads as
 * a bug even when the reason is benign, so every category has a picture of its own.
 */
const PLACE_PLACEHOLDERS: Record<string, string> = {
  hotel: '/static/place_images/hotel.svg',
  restaurant: '/static/place_images/restaurant.svg',
  attraction: '/static/place_images/attraction.svg',
}

export const GENERIC_PLACEHOLDER = '/static/place_images/generic.svg'

export function placePlaceholderUrl(type: string | null | undefined): string {
  const key = (type ?? '').trim().toLowerCase()
  return resolveAssetUrl(PLACE_PLACEHOLDERS[key] ?? GENERIC_PLACEHOLDER)
}

/**
 * The picture to put in a card: the place's own photo, or the placeholder for its category.
 *
 * `isUsable` lets a caller retire a photo the browser has already failed to load, so the same
 * helper covers both "never had a picture" and "the picture is dead".
 */
export function resolvePlaceImage(
  place: { image_url?: string | null; type?: string | null },
  isUsable: (url: string) => boolean = () => true,
): { src: string; fallback: string; usingPlaceholder: boolean } {
  const photo = resolveAssetUrl(place.image_url)
  const fallback = placePlaceholderUrl(place.type)
  if (photo && isUsable(photo)) return { src: photo, fallback, usingPlaceholder: false }
  return { src: fallback, fallback, usingPlaceholder: true }
}
