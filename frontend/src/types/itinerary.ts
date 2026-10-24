export type PlaceType = 'attraction' | 'hotel' | 'restaurant' | 'airport' | 'station'

export interface Place {
  id: number
  trip_id: number
  name: string
  type: PlaceType
  address: string
  latitude: number | null
  longitude: number | null
  image_url: string | null
  rating: number | null
  price_level: number | null
  opening_hours: string | null
  source_url: string | null
  /** Why this place was chosen, carried over from the recommendation. */
  note?: string | null
  /** The price the recommendation quoted when it was saved. */
  estimated_cost?: number | null
  /** How many nights/meals that price covers. */
  quantity?: number | null
  /** The day the recommendation panel suggested for it, honoured by auto-scheduling. */
  preferred_date?: string | null
}

export interface PlaceCreate {
  name: string
  type: PlaceType
  address: string
  latitude: number | null
  longitude: number | null
  image_url: string | null
  rating: number | null
  price_level: number | null
  opening_hours: string | null
  source_url: string | null
  note?: string | null
  estimated_cost?: number | null
  quantity?: number | null
  preferred_date?: string | null
}

/** Partial edit of a saved place. Only the fields you send are changed. */
export interface PlaceUpdate {
  name?: string
  type?: PlaceType
  address?: string
  image_url?: string | null
  rating?: number | null
  price_level?: number | null
  opening_hours?: string | null
  note?: string | null
  estimated_cost?: number | null
  quantity?: number | null
  preferred_date?: string | null
}

export interface ItineraryItem {
  id: number
  trip_id: number
  date: string
  start_time: string
  end_time: string
  place_id: number
  note: string | null
  sort_order: number
  place: Place
}

export interface ItineraryItemCreate {
  date: string
  start_time: string
  end_time: string
  place_id: number | null
  note: string | null
  sort_order: number
}

/** A slot the app suggests for a saved place that is not on the calendar yet. */
export interface ProposedItineraryItem {
  place_id: number
  place_name: string
  date: string
  start_time: string
  end_time: string
  note: string
}

export interface SkippedPlace {
  place_id: number
  place_name: string
  reason: string
}

export interface AutoSchedule {
  trip_id: number
  proposed: ProposedItineraryItem[]
  skipped: SkippedPlace[]
  already_scheduled: string[]
  message: string
}
