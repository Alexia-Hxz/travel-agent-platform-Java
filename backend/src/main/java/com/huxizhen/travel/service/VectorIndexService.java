package com.huxizhen.travel.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.huxizhen.travel.config.DashScopeProperties;
import com.huxizhen.travel.config.VectorProperties;
import com.huxizhen.travel.entity.TripFile;
import com.huxizhen.travel.mapper.TripFileMapper;
import com.huxizhen.travel.service.vector.VectorStore;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 资料切分、索引与语义检索（对应 Python 的 {@code vector_service}）。
 *
 * <p>三条硬约束：
 * <ul>
 *   <li>按行切分，目标 420 字、重叠 60 字、低于 40 字拒绝索引；</li>
 *   <li>索引状态四态 {@code pending/indexed/skipped/failed} 写回文件行，让"解析成功但搜不到"
 *       可以显示出来；</li>
 *   <li>检索失败与"没有资料"必须区分（{@code unavailable} / {@code empty} / {@code failed}）。</li>
 * </ul>
 *
 * <p>租户隔离由 {@link VectorStore} 的签名保证：每个方法都必须传 tripId。
 */
@Service
public class VectorIndexService {

    public static final int MIN_INDEXABLE_CHARS = 40;
    public static final int DEFAULT_RESULT_LIMIT = 5;
    public static final int MAX_RESULT_LIMIT = 20;

    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final TripFileMapper tripFileMapper;
    private final DashScopeProperties properties;
    private final VectorProperties vectorProperties;

    public VectorIndexService(
            VectorStore vectorStore,
            EmbeddingService embeddingService,
            TripFileMapper tripFileMapper,
            DashScopeProperties properties,
            VectorProperties vectorProperties) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        this.tripFileMapper = tripFileMapper;
        this.properties = properties;
        this.vectorProperties = vectorProperties;
    }

    public record IndexOutcome(String status, int chunkCount, String reason) {
    }

    public record SearchOutcome(String status, List<VectorStore.Hit> chunks, String reason) {
    }

    /** (重)建一个文件的全部片段与向量。可重复调用：先删旧片段。 */
    public IndexOutcome indexTripFile(TripFile tripFile) {
        String parsed = tripFile.getParsedText() == null ? "" : tripFile.getParsedText().strip();
        List<DocumentSplitter.Piece> pieces =
                parsed.length() >= MIN_INDEXABLE_CHARS ? DocumentSplitter.split(parsed) : List.of();

        if (!vectorStore.available()) {
            return recordOutcome(
                    tripFile, "failed", 0, "向量库不可用：" + vectorStore.unavailableReason());
        }
        vectorStore.deleteFile(tripFile.getId());

        if (pieces.isEmpty()) {
            String reason = "解析出的文本只有 " + parsed.length() + " 字，"
                    + (parsed.isEmpty() ? "文件没有解析出任何文本。" : "不足以建立索引（可能是扫描件或截图质量太差）。");
            return recordOutcome(tripFile, "skipped", 0, reason);
        }

        List<float[]> vectors;
        try {
            vectors = embeddingService.embedTexts(pieces.stream().map(DocumentSplitter.Piece::text).toList());
        } catch (EmbeddingService.EmbeddingException error) {
            return recordOutcome(tripFile, "failed", 0, error.getMessage());
        }

        try {
            vectorStore.upsert(
                    tripFile.getTripId(),
                    tripFile.getId(),
                    tripFile.getOriginalFilename(),
                    pieces,
                    vectors,
                    properties.embeddingModel());
        } catch (RuntimeException error) {
            return recordOutcome(
                    tripFile, "failed", 0, "写入向量库失败：" + error.getClass().getSimpleName() + ": "
                            + error.getMessage());
        }
        return recordOutcome(tripFile, "indexed", pieces.size(), null);
    }

    private IndexOutcome recordOutcome(TripFile tripFile, String status, int chunks, String reason) {
        tripFile.setIndexStatus(status);
        tripFile.setIndexError(reason);
        tripFile.setIndexedChunks(chunks);
        tripFile.setIndexedAt("indexed".equals(status) ? LocalDateTime.now(ZoneOffset.UTC) : null);
        // indexed_at 要能被显式清成 NULL：updateById 会跳过 null，所以整行用 UpdateWrapper 写。
        tripFileMapper.update(null, Wrappers.<TripFile>lambdaUpdate()
                .eq(TripFile::getId, tripFile.getId())
                .set(TripFile::getIndexStatus, status)
                .set(TripFile::getIndexError, reason)
                .set(TripFile::getIndexedChunks, chunks)
                .set(TripFile::getIndexedAt, tripFile.getIndexedAt()));
        return new IndexOutcome(status, chunks, reason);
    }

    /** 语义检索。**不抛异常**：调用方都是请求路径。 */
    public SearchOutcome search(Long tripId, String query, int limit) {
        String term = query == null ? "" : query.strip();
        if (term.isEmpty()) {
            return new SearchOutcome("skipped", List.of(), "查询内容为空。");
        }
        if (!vectorStore.available()) {
            return new SearchOutcome("unavailable", List.of(), "向量库不可用：" + vectorStore.unavailableReason());
        }
        VectorStore.Stats stats = vectorStore.stats(tripId);
        if (stats.chunks() == 0) {
            return new SearchOutcome("empty", List.of(), "这趟旅行还没有已建立索引的资料。");
        }

        List<float[]> vectors;
        try {
            vectors = embeddingService.embedTexts(List.of(term));
        } catch (EmbeddingService.EmbeddingException error) {
            return new SearchOutcome("failed", List.of(), error.getMessage());
        }
        int results = Math.max(1, Math.min(Math.min(limit, MAX_RESULT_LIMIT), stats.chunks()));
        List<VectorStore.Hit> hits;
        try {
            hits = vectorStore.search(tripId, vectors.get(0), results);
        } catch (RuntimeException error) {
            return new SearchOutcome(
                    "unavailable", List.of(), "向量库不可用：" + error.getClass().getSimpleName() + ": "
                            + error.getMessage());
        }
        if (hits.isEmpty()) {
            return new SearchOutcome("empty", List.of(), "没有找到相关资料片段。");
        }
        return new SearchOutcome("ok", hits, null);
    }

    /** 检索到的片段渲染成提示词材料，来源保持可见。 */
    public List<Map<String, Object>> documentContext(List<VectorStore.Hit> chunks) {
        List<Map<String, Object>> context = new ArrayList<>(chunks.size());
        for (VectorStore.Hit chunk : chunks) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("source", chunk.source());
            entry.put("section", chunk.chunkIndex() + 1);
            entry.put("relevance", chunk.similarity());
            entry.put("excerpt", chunk.text());
            context.add(entry);
        }
        return context;
    }

    public Map<String, Object> knowledgeStatus(Long tripId) {
        Map<String, Object> status = new LinkedHashMap<>();
        boolean available = vectorStore.available();
        int chunks = 0;
        int files = 0;
        if (available) {
            try {
                VectorStore.Stats stats = vectorStore.stats(tripId);
                chunks = stats.chunks();
                files = stats.files();
            } catch (RuntimeException error) {
                available = false;
            }
        }
        status.put("available", available);
        status.put("indexed_chunks", chunks);
        status.put("indexed_files", files);
        status.put("backend", vectorStore.backend());
        status.put("collection", vectorProperties.collection());
        status.put("path", vectorStore.describePath());
        status.put("embedding_model", properties.embeddingModel());
        status.put("embedding_dim", properties.embeddingDim());
        status.put("error", available ? null : vectorStore.unavailableReason());
        return status;
    }

    public int deleteFileChunks(Long fileId) {
        if (!vectorStore.available()) {
            throw new IllegalStateException("向量库不可用：" + vectorStore.unavailableReason());
        }
        return vectorStore.deleteFile(fileId);
    }

    public int deleteTripChunks(Long tripId) {
        if (!vectorStore.available()) {
            return 0;
        }
        return vectorStore.deleteTrip(tripId);
    }

    public VectorStore store() {
        return vectorStore;
    }
}
