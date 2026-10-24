package com.huxizhen.travel.dto.file;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huxizhen.travel.entity.TripFile;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 上传资料的对外视图。字段与 Pydantic {@code TripFileRead} 一致。 */
public record TripFileRead(
        Long id,
        Long tripId,
        String originalFilename,
        String storedFilename,
        String contentType,
        Long fileSize,
        String filePath,
        String parseStatus,
        String parsedText,
        Map<String, List<String>> parsedResult,
        String parseError,
        LocalDateTime parsedAt,
        String indexStatus,
        String indexError,
        Integer indexedChunks,
        LocalDateTime indexedAt,
        LocalDateTime createdAt) {

    private static final TypeReference<Map<String, List<String>>> RESULT_TYPE =
            new TypeReference<>() {
            };

    public static TripFileRead from(TripFile file, ObjectMapper objectMapper) {
        Map<String, List<String>> parsed = null;
        if (file.getParsedResult() != null && !file.getParsedResult().isBlank()) {
            try {
                parsed = objectMapper.readValue(file.getParsedResult(), RESULT_TYPE);
            } catch (Exception ignored) {
                // 一行被手工改坏的数据不该让整个列表接口 500；前端拿到 null 会显示"未解析"。
                parsed = null;
            }
        }
        return new TripFileRead(
                file.getId(),
                file.getTripId(),
                file.getOriginalFilename(),
                file.getStoredFilename(),
                file.getContentType(),
                file.getFileSize(),
                file.getFilePath(),
                file.getParseStatus(),
                file.getParsedText(),
                parsed,
                file.getParseError(),
                file.getParsedAt(),
                file.getIndexStatus(),
                file.getIndexError(),
                file.getIndexedChunks(),
                file.getIndexedAt(),
                file.getCreatedAt());
    }
}
