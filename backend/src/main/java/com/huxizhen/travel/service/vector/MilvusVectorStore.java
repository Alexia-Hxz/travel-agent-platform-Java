package com.huxizhen.travel.service.vector;

import com.huxizhen.travel.config.VectorProperties;
import com.huxizhen.travel.service.DocumentSplitter;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Milvus 2.4 实现（R5 选定方案）。
 *
 * <p>关键差异：**Milvus 的 COSINE 返回的 score 就是余弦相似度**（越大越像），
 * 不像 Chroma 返回 {@code 1 - cosine} 的距离。所以这里直接 {@code similarity = round(score, 4)}，
 * 千万不能再做一次 {@code 1 - score}。
 *
 * <p>{@code trip_id} 是标量字段，检索时用 {@code expr} 过滤；所有读写都带 tripId，
 * 与本接口的租户隔离约定一致。
 */
public class MilvusVectorStore implements VectorStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);
    private static final List<String> OUT_FIELDS =
            List.of("file_id", "chunk_index", "source", "text");

    private final VectorProperties properties;
    private volatile MilvusServiceClient client;
    private volatile boolean available;
    private volatile String unavailableReason;

    public MilvusVectorStore(VectorProperties properties) {
        this.properties = properties;
    }

    @Override
    public String backend() {
        return "milvus";
    }

    @Override
    public String describePath() {
        return properties.host() + ":" + properties.port();
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String unavailableReason() {
        return unavailableReason;
    }

    @Override
    public synchronized void ensureReady() {
        if (available) {
            return;
        }
        try {
            if (client == null) {
                client = new MilvusServiceClient(ConnectParam.newBuilder()
                        .withHost(properties.host())
                        .withPort(properties.port())
                        .withConnectTimeout(properties.connectTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS)
                        .build());
            }
            createCollectionIfMissing();
            load();
            available = true;
            unavailableReason = null;
            log.info("Milvus ready at {} collection={}", describePath(), properties.collection());
        } catch (RuntimeException error) {
            available = false;
            unavailableReason = error.getClass().getSimpleName() + ": " + error.getMessage();
            log.warn("Milvus unavailable at {}: {}", describePath(), unavailableReason);
        }
    }

    private void createCollectionIfMissing() {
        R<Boolean> exists = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(properties.collection())
                .build());
        if (Boolean.TRUE.equals(exists.getData())) {
            return;
        }
        CreateCollectionParam param = CreateCollectionParam.newBuilder()
                .withCollectionName(properties.collection())
                .withDescription("traveller uploaded documents")
                .withShardsNum(2)
                .addFieldType(FieldType.newBuilder()
                        .withName("chunk_id")
                        .withDataType(DataType.VarChar)
                        .withPrimaryKey(true)
                        .withAutoID(false)
                        .withMaxLength(128)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("trip_id")
                        .withDataType(DataType.Int64)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("file_id")
                        .withDataType(DataType.Int64)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("chunk_index")
                        .withDataType(DataType.Int64)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("source")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(512)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("char_start")
                        .withDataType(DataType.Int64)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("char_end")
                        .withDataType(DataType.Int64)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("embedding_model")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(128)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("text")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(65535)
                        .build())
                .addFieldType(FieldType.newBuilder()
                        .withName("embedding")
                        .withDataType(DataType.FloatVector)
                        .withDimension(properties.dim())
                        .build())
                .build();
        check(client.createCollection(param), "createCollection");
        check(client.createIndex(CreateIndexParam.newBuilder()
                        .withCollectionName(properties.collection())
                        .withFieldName("embedding")
                        .withIndexType(IndexType.HNSW)
                        .withMetricType(MetricType.COSINE)
                        .withExtraParam("{\"M\":16,\"efConstruction\":200}")
                        .build()),
                "createIndex");
    }

    private void load() {
        check(client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(properties.collection())
                .build()), "loadCollection");
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
        List<Long> tripIds = new ArrayList<>();
        List<Long> fileIds = new ArrayList<>();
        List<Long> chunkIndexes = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        List<Long> charStarts = new ArrayList<>();
        List<Long> charEnds = new ArrayList<>();
        List<String> models = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<String> chunkIds = new ArrayList<>();
        List<List<Float>> embeddings = new ArrayList<>();

        for (int index = 0; index < pieces.size(); index++) {
            DocumentSplitter.Piece piece = pieces.get(index);
            chunkIds.add("file-" + fileId + "-chunk-" + piece.index());
            tripIds.add(tripId);
            fileIds.add(fileId);
            chunkIndexes.add((long) piece.index());
            sources.add(source);
            charStarts.add((long) piece.charStart());
            charEnds.add((long) piece.charEnd());
            models.add(embeddingModel);
            texts.add(piece.text());
            embeddings.add(toList(vectors.get(index)));
        }

        List<InsertParam.Field> fields = List.of(
                new InsertParam.Field("chunk_id", chunkIds),
                new InsertParam.Field("trip_id", tripIds),
                new InsertParam.Field("file_id", fileIds),
                new InsertParam.Field("chunk_index", chunkIndexes),
                new InsertParam.Field("source", sources),
                new InsertParam.Field("char_start", charStarts),
                new InsertParam.Field("char_end", charEnds),
                new InsertParam.Field("embedding_model", models),
                new InsertParam.Field("text", texts),
                new InsertParam.Field("embedding", embeddings));
        check(client.insert(InsertParam.newBuilder()
                .withCollectionName(properties.collection())
                .withFields(fields)
                .build()), "insert");
        return pieces.size();
    }

    @Override
    public int deleteFile(long fileId) {
        R<?> response = client.delete(DeleteParam.newBuilder()
                .withCollectionName(properties.collection())
                .withExpr("file_id == " + fileId)
                .build());
        check(response, "deleteFile");
        return countByExpr("file_id == " + fileId);
    }

    @Override
    public int deleteTrip(long tripId) {
        R<?> response = client.delete(DeleteParam.newBuilder()
                .withCollectionName(properties.collection())
                .withExpr("trip_id == " + tripId)
                .build());
        check(response, "deleteTrip");
        return 0;
    }

    @Override
    public Stats stats(long tripId) {
        List<?> fileIds = queryField("trip_id == " + tripId, "file_id");
        Set<Long> distinct = new HashSet<>();
        for (Object value : fileIds) {
            distinct.add(((Number) value).longValue());
        }
        return new Stats(fileIds.size(), distinct.size());
    }

    @Override
    public List<Hit> search(long tripId, float[] query, int limit) {
        SearchParam param = SearchParam.newBuilder()
                .withCollectionName(properties.collection())
                .withMetricType(MetricType.COSINE)
                .withOutFields(OUT_FIELDS)
                .withTopK(limit)
                .withVectors(List.of(toList(query)))
                .withVectorFieldName("embedding")
                .withExpr("trip_id == " + tripId)
                .withParams("{\"ef\":64}")
                .build();
        R<SearchResults> response = client.search(param);
        check(response, "search");
        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
        List<?> fileIds = wrapper.getFieldData("file_id", 0);
        List<?> chunkIndexes = wrapper.getFieldData("chunk_index", 0);
        List<?> sources = wrapper.getFieldData("source", 0);
        List<?> texts = wrapper.getFieldData("text", 0);

        List<Hit> hits = new ArrayList<>(scores.size());
        for (int index = 0; index < scores.size(); index++) {
            SearchResultsWrapper.IDScore score = scores.get(index);
            hits.add(new Hit(
                    score.getStrID(),
                    ((Number) fileIds.get(index)).longValue(),
                    String.valueOf(sources.get(index)),
                    ((Number) chunkIndexes.get(index)).intValue(),
                    String.valueOf(texts.get(index)),
                    round4(score.getScore())));
        }
        return hits;
    }

    private int countByExpr(String expression) {
        return queryField(expression, "chunk_id").size();
    }

    private List<?> queryField(String expression, String field) {
        R<QueryResults> response = client.query(QueryParam.newBuilder()
                .withCollectionName(properties.collection())
                .withExpr(expression)
                .withOutFields(List.of(field))
                .withLimit(16384L)
                .build());
        check(response, "query");
        QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
        return wrapper.getFieldWrapper(field).getFieldData();
    }

    private static List<Float> toList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private static double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_EVEN).doubleValue();
    }

    private static void check(R<?> response, String operation) {
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException(operation + " 失败：" + response.getMessage());
        }
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
