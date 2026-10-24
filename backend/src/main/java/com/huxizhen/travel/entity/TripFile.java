package com.huxizhen.travel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 上传资料。
 *
 * <p>解析状态与索引状态**分开记录**：一份扫描件可以解析成功却索引不出东西（skipped），
 * 一份文本完好的文档也可以因为嵌入服务不可达而索引失败（failed）。只留一个"已处理"标记，
 * 会让一份文档看起来可用、实际搜不到。
 */
@TableName("trip_files")
public class TripFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tripId;
    private String originalFilename;
    private String storedFilename;
    private String contentType;
    private Long fileSize;
    private String filePath;
    private String parseStatus;
    private String parsedText;
    /** MySQL JSON 列，按原文字符串存取，读写时再转成对象。 */
    private String parsedResult;
    private String parseError;
    private LocalDateTime parsedAt;
    private String indexStatus;
    private String indexError;
    private Integer indexedChunks;
    private LocalDateTime indexedAt;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getStoredFilename() {
        return storedFilename;
    }

    public void setStoredFilename(String storedFilename) {
        this.storedFilename = storedFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getParseStatus() {
        return parseStatus;
    }

    public void setParseStatus(String parseStatus) {
        this.parseStatus = parseStatus;
    }

    public String getParsedText() {
        return parsedText;
    }

    public void setParsedText(String parsedText) {
        this.parsedText = parsedText;
    }

    public String getParsedResult() {
        return parsedResult;
    }

    public void setParsedResult(String parsedResult) {
        this.parsedResult = parsedResult;
    }

    public String getParseError() {
        return parseError;
    }

    public void setParseError(String parseError) {
        this.parseError = parseError;
    }

    public LocalDateTime getParsedAt() {
        return parsedAt;
    }

    public void setParsedAt(LocalDateTime parsedAt) {
        this.parsedAt = parsedAt;
    }

    public String getIndexStatus() {
        return indexStatus;
    }

    public void setIndexStatus(String indexStatus) {
        this.indexStatus = indexStatus;
    }

    public String getIndexError() {
        return indexError;
    }

    public void setIndexError(String indexError) {
        this.indexError = indexError;
    }

    public Integer getIndexedChunks() {
        return indexedChunks;
    }

    public void setIndexedChunks(Integer indexedChunks) {
        this.indexedChunks = indexedChunks;
    }

    public LocalDateTime getIndexedAt() {
        return indexedAt;
    }

    public void setIndexedAt(LocalDateTime indexedAt) {
        this.indexedAt = indexedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
