package com.huxizhen.travel.dto.knowledge;

/** 这趟旅行有多少资料真的能被检索到。 */
public record KnowledgeStatus(
        boolean available,
        int indexedChunks,
        int indexedFiles,
        String backend,
        String collection,
        String path,
        String embeddingModel,
        int embeddingDim,
        String error) {
}
