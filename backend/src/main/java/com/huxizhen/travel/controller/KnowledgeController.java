package com.huxizhen.travel.controller;

import com.huxizhen.travel.dto.knowledge.KnowledgeChunk;
import com.huxizhen.travel.dto.knowledge.KnowledgeRebuildResult;
import com.huxizhen.travel.dto.knowledge.KnowledgeSearchResult;
import com.huxizhen.travel.dto.knowledge.KnowledgeStatus;
import com.huxizhen.travel.entity.TripFile;
import com.huxizhen.travel.service.FileStorageService;
import com.huxizhen.travel.service.VectorIndexService;
import com.huxizhen.travel.service.vector.VectorStore;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 旅客自己上传资料的检索接口。
 *
 * <p>单独一个 router 而不是挂在 files 下：{@code /files/{file_id}} 会把 {@code /files/search}
 * 当成一个 file id 先匹配掉。
 */
@RestController
@RequestMapping("/api/v1/trips/{tripId}/knowledge")
public class KnowledgeController {

    private final VectorIndexService vectorIndexService;
    private final FileStorageService fileStorageService;

    public KnowledgeController(
            VectorIndexService vectorIndexService, FileStorageService fileStorageService) {
        this.vectorIndexService = vectorIndexService;
        this.fileStorageService = fileStorageService;
    }

    /** 注意：原实现的这个端点**不校验 trip 是否存在**，只靠归属拦截器。 */
    @GetMapping("/status")
    public KnowledgeStatus status(@PathVariable Long tripId) {
        Map<String, Object> status = vectorIndexService.knowledgeStatus(tripId);
        return new KnowledgeStatus(
                (Boolean) status.get("available"),
                (Integer) status.get("indexed_chunks"),
                (Integer) status.get("indexed_files"),
                (String) status.get("backend"),
                (String) status.get("collection"),
                (String) status.get("path"),
                (String) status.get("embedding_model"),
                (Integer) status.get("embedding_dim"),
                (String) status.get("error"));
    }

    @GetMapping("/search")
    public KnowledgeSearchResult search(
            @PathVariable Long tripId,
            @RequestParam("q") @Size(min = 1, max = 500) String q,
            @RequestParam(value = "limit", defaultValue = "5") @Min(1) @Max(20) int limit) {
        VectorIndexService.SearchOutcome outcome = vectorIndexService.search(tripId, q, limit);
        List<KnowledgeChunk> chunks = outcome.chunks().stream()
                .map(hit -> new KnowledgeChunk(
                        hit.chunkId(),
                        hit.fileId(),
                        hit.source(),
                        hit.chunkIndex(),
                        hit.text(),
                        hit.similarity()))
                .toList();
        return new KnowledgeSearchResult(outcome.status(), outcome.reason(), chunks);
    }

    /**
     * 重建全部已解析文件的索引。
     *
     * <p>索引落后是正常情况：文档在功能上线前就解析过、上传时嵌入服务不可用、或者换了模型。
     * 没有这个入口，修复索引的唯一办法就是删了重传。
     */
    @PostMapping("/rebuild")
    public KnowledgeRebuildResult rebuild(@PathVariable Long tripId) {
        int indexed = 0;
        int skipped = 0;
        int failed = 0;
        int chunks = 0;
        for (TripFile tripFile : fileStorageService.list(tripId)) {
            if (!"completed".equals(tripFile.getParseStatus())) {
                continue;
            }
            VectorIndexService.IndexOutcome outcome = vectorIndexService.indexTripFile(tripFile);
            switch (outcome.status()) {
                case "indexed" -> {
                    indexed++;
                    chunks += outcome.chunkCount();
                }
                case "skipped" -> skipped++;
                default -> failed++;
            }
        }
        return new KnowledgeRebuildResult(indexed, skipped, failed, chunks);
    }
}
