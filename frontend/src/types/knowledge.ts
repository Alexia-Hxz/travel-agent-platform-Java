/** The document index behind semantic search over uploaded travel documents. */

export interface KnowledgeStatus {
  available: boolean
  indexed_chunks: number
  indexed_files: number
  backend: string
  collection: string
  path: string
  embedding_model: string
  embedding_dim: number
  error: string | null
}

export interface KnowledgeChunk {
  chunk_id: string
  file_id: number
  source_label: string
  chunk_index: number
  text: string
  /** Cosine similarity in [0, 1]. Surfaced because "how close was it" is the one number
   *  that tells a reader whether a weak match is worth trusting. */
  similarity: number
}

export interface KnowledgeSearchResult {
  /** "ok" carries matches; every other value says why there are none. */
  status: string
  reason: string | null
  chunks: KnowledgeChunk[]
}

export interface KnowledgeRebuildResult {
  indexed_files: number
  skipped_files: number
  failed_files: number
  indexed_chunks: number
}
