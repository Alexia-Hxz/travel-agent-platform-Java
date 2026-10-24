package com.huxizhen.travel.service.vector;

import com.huxizhen.travel.service.DocumentSplitter;
import java.util.List;

/**
 * 向量存储抽象。
 *
 * <p>两个实现：{@code MilvusVectorStore}（R5 选定方案）与 {@code MysqlVectorStore}
 * （精确余弦，用于没有 Milvus 的环境）。业务层只认这个接口，切换不改调用点。
 *
 * <p>**租户隔离是接口的硬约束**：所有读写方法都带 {@code tripId} 参数，实现内部必须把它
 * 变成 {@code trip_id == ?} 的过滤条件；调用方没有"不加过滤"的入口。
 */
public interface VectorStore {

    /** 释放连接（MySQL 实现无需动作）。 */
    default void close() {
    }

    /** {@code milvus} / {@code mysql}。 */
    String backend();

    /** 给状态接口展示的位置描述（Milvus 是 host:port，MySQL 是表名）。 */
    String describePath();

    boolean available();

    String unavailableReason();

    /** 建立连接 / 建 collection。可重复调用。 */
    void ensureReady();

    /** 写入一个文件的全部片段。调用方应先用 {@link #deleteFile} 清掉旧片段。 */
    int upsert(
            long tripId,
            long fileId,
            String source,
            List<DocumentSplitter.Piece> pieces,
            List<float[]> vectors,
            String embeddingModel);

    int deleteFile(long fileId);

    int deleteTrip(long tripId);

    Stats stats(long tripId);

    List<Hit> search(long tripId, float[] query, int limit);

    /** 一个检索命中。{@code similarity} 是余弦相似度，已四舍五入到 4 位。 */
    record Hit(
            String chunkId,
            long fileId,
            String source,
            int chunkIndex,
            String text,
            double similarity) {
    }

    record Stats(int chunks, int files) {
    }
}
