import type { ItineraryItem, Place, PlaceCreate, PlaceType } from './itinerary'

export type VisionDocumentType = 'attraction_photo' | 'hotel_booking' | 'transport_ticket' | 'menu' | 'travel_guide' | 'other'

export interface VisionPlaceSuggestion extends PlaceCreate {
  type: PlaceType
}

export interface VisionItinerarySuggestion {
  date: string
  start_time: string
  end_time: string
  note: string | null
}

export interface VisionPreview {
  document_type: VisionDocumentType
  summary: string
  place: VisionPlaceSuggestion | null
  itinerary: VisionItinerarySuggestion | null
  reference_number: string | null
  raw_text: string | null
}

export interface VisionConfirmation {
  save_place: boolean
  save_itinerary: boolean
  place: VisionPlaceSuggestion | null
  itinerary: VisionItinerarySuggestion | null
}

export interface VisionConfirmationResult {
  place: Place | null
  itinerary: ItineraryItem | null
}
