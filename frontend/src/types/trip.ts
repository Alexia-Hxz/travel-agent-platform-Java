export interface Trip {
  id: number
  title: string
  destination: string
  start_date: string
  end_date: string
  budget: number | null
  adult_male_count: number
  adult_female_count: number
  child_male_count: number
  child_female_count: number
  created_at: string
}

export interface TripCreate {
  title: string
  destination: string
  start_date: string
  end_date: string
  budget: number | null
  adult_male_count: number
  adult_female_count: number
  child_male_count: number
  child_female_count: number
}
