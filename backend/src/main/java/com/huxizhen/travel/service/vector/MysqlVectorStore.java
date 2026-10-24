package com.huxizhen.travel.service.vector;

import com.huxizhen.travel.config.VectorProperties;
import com.huxizhen.travel.service.DocumentSplitter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 把片段与向量存进 MySQL，检索时用**精确余弦**。
 *
 * <p>为什么这是 Chroma 最忠实的等价物：Chroma 的 cosine 空间里
 * {@code distance = 1 - cosine}，于是 {@code similarity = 1 - distance = cosine}。
 * 这里算的就是余弦，并把结果按 Python 的 {@code round(x, 4)}（银行家舍入）收敛。
 * Milvus 的 HNSW 是近似检索，反而会让 0.525 / 0.285 这类数值漂移。
 *
 * <p>数据量级：个人旅行资料通常是几十到几千个片段，1024 维 float32 全表扫描是毫秒级。
 */
public class MysqlVectorStore implements VectorStore {

    private static final int EMBEDDING_BYTES = 1024 * 4;

    private final JdbcTemplate jdbc;
    private final VectorProperties properties;

    public MysqlVectorStore(JdbcTemplate jdbc, VectorProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public String backend() {
        return "mysql";
    }

    @Override
    public String describePath() {
        return "mysql:trip_document_chunks";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String unavailableReason() {
        return null;
    }

    @Override
    public void ensureReady() {
        // 表由 Flyway 的 V2 建好，这里无事可做。
    }

    @Override
    public int upsert(
            long tripId,
            long fileId,
            String source,
            List<DocumentSplitter.Piece> pieces,
            List<float[]> vectors,
            String embeddingModel) {
        if (pieces.isEmpty()) {
            return 0;
        }
        List<Object[]> batch = new ArrayList<>(pieces.size());
        for (int index = 0; index < pieces.size(); index++) {
            DocumentSplitter.Piece piece = pieces.get(index);
            float[] vector = vectors.get(index);
            batch.add(new Object[] {
                chunkId(fileId, piece.index()),
                tripId,
                fileId,
                piece.index(),
                source,
                piece.text(),
                piece.charStart(),
                piece.charEnd(),
                embeddingModel,
                toBytes(vector)
            });
        }
        jdbc.batchUpdate(
                "INSERT INTO trip_document_chunks"
                        + " (chunk_id, trip_id, file_id, chunk_index, `source`, `text`, char_start, char_end,"
                        + " embedding_model, embedding)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE trip_id = VALUES(trip_id), file_id = VALUES(file_id),"
                        + " chunk_index = VALUES(chunk_index), `source` = VALUES(`source`),"
                        + " `text` = VALUES(`text`),"
                        + " char_start = VALUES(char_start), char_end = VALUES(char_end),"
                        + " embedding_model = VALUES(embedding_model), embedding = VALUES(embedding)",
                batch);
        return pieces.size();
    }

    @Override
    public int deleteFile(long fileId) {
        return jdbc.update("DELETE FROM trip_document_chunks WHERE file_id = ?", fileId);
    }

    @Override
    public int deleteTrip(long tripId) {
        return jdbc.update("DELETE FROM trip_document_chunks WHERE trip_id = ?", tripId);
    }

    @Override
    public Stats stats(long tripId) {
        Integer chunks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trip_document_chunks WHERE trip_id = ?", Integer.class, tripId);
        Integer files = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT file_id) FROM trip_document_chunks WHERE trip_id = ?",
                Integer.class,
                tripId);
        return new Stats(chunks == null ? 0 : chunks, files == null ? 0 : files);
    }

    @Override
    public List<Hit> search(long tripId, float[] query, int limit) {
        List<Hit> hits = jdbc.query(
                "SELECT chunk_id, file_id, chunk_index, `source`, `text`, embedding"
                        + " FROM trip_document_chunks WHERE trip_id = ?",
                (resultSet, rowNumber) -> new Hit(
                        resultSet.getString("chunk_id"),
                        resultSet.getLong("file_id"),
                        resultSet.getString("source"),
                        resultSet.getInt("chunk_index"),
                        resultSet.getString("text"),
                        round4(cosine(query, fromBytes(resultSet.getBytes("embedding"))))),
                tripId);
        return hits.stream()
                .sorted(Comparator.comparingDouble(Hit::similarity).reversed())
                .limit(limit)
                .toList();
    }

    private static String chunkId(long fileId, int index) {
        return "file-" + fileId + "-chunk-" + index;
    }

    /** float32 小端序，和 Milvus / numpy 的默认布局一致。 */
    static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    static float[] fromBytes(byte[] raw) {
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[raw.length / 4];
        for (int index = 0; index < vector.length; index++) {
            vector[index] = buffer.getFloat();
        }
        return vector;
    }

    static double cosine(float[] left, float[] right) {
        int length = Math.min(left.length, right.length);
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < length; index++) {
            dot += (double) left[index] * right[index];
            leftNorm += (double) left[index] * left[index];
            rightNorm += (double) right[index] * right[index];
        }
        if (leftNorm <= 0 || rightNorm <= 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    /** 与 Python {@code round(x, 4)} 一致：银行家舍入。 */
    static double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_EVEN).doubleValue();
    }

    public int expectedEmbeddingBytes() {
        return properties.dim() * 4;
    }
}
