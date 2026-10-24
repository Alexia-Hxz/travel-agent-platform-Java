package com.huxizhen.travel.dto.knowledge;

import java.util.List;

/**
 * 检索结果。
 *
 * <p>{@code status} 是必需的：{@code ok} 表示真的匹配到了；其它值说明"为什么没有"，
 * 这样空列表不会被误读成"这位旅客没有这种资料"。
 */
public record KnowledgeSearchResult(String status, String reason, List<KnowledgeChunk> chunks) {
}
