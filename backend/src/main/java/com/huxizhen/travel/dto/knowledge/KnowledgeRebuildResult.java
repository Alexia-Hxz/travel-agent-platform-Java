package com.huxizhen.travel.dto.knowledge;

/** 重建索引的结果。 */
public record KnowledgeRebuildResult(
        int indexedFiles, int skippedFiles, int failedFiles, int indexedChunks) {
}
