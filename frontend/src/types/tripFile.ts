export interface TripFile {
  id: number
  trip_id: number
  original_filename: string
  stored_filename: string
  content_type: string
  file_size: number
  file_path: string
  parse_status: 'pending' | 'processing' | 'completed' | 'failed'
  parsed_text: string | null
  parsed_result: Record<string, string[]> | null
  parse_error: string | null
  parsed_at: string | null
  /** Whether this document can be found by search, and why not when it cannot. */
  index_status: 'pending' | 'indexed' | 'skipped' | 'failed'
  index_error: string | null
  indexed_chunks: number
  indexed_at: string | null
  created_at: string
}
