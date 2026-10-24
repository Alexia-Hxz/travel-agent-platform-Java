package com.huxizhen.travel.dto.knowledge;

/** 一个命中片段，带来源文件名与相似度。 */
public record KnowledgeChunk(
        String chunkId,
        long fileId,
        String sourceLabel,
        int chunkIndex,
        String text,
        double similarity) {
}
