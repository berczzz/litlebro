package com.litlebro.agent.vectorstore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStoreContent;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionConverter;
import org.springframework.ai.vectorstore.filter.converter.SimpleVectorStoreFilterExpressionConverter;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 分批向量化的本地向量库：覆写 {@link SimpleVectorStore#doAdd}，将逐条 embedding
 * 改为按固定条数分批一次请求，大幅减少 embedding HTTP 调用次数（原实现每个 chunk
 * 单独请求一次，大数据量文档入库极慢）。
 *
 * <p>embedding 输入使用纯文本 {@code Document.getText()}，与 Milvus 批量路径一致，
 * 保证两个后端存入同一批文档时向量空间相同（不混入 docId/source 等元数据前缀）。
 *
 * <p>该实现仅用于本地 {@code local} 长期记忆后端（{@link SimpleVectorStore}），
 * 外部后端（如 Milvus）不受影响。
 */
public class BatchingSimpleVectorStore extends SimpleVectorStore {

    private static final Logger log = LoggerFactory.getLogger(BatchingSimpleVectorStore.class);

    /** 单次 embedding 请求的输入条数上限（dashscope 兼容模式 text-embedding 系列单请求最多 10 条） */
    private final int batchSize;

    /**
     * 过滤表达式解析器：Spring AI M6 的 {@link SimpleVectorStore} 未实现按过滤条件删除
     * （{@code doDelete(Filter.Expression)} 默认抛 {@link UnsupportedOperationException}），
     * 此处自行按元数据求值补齐，使本地库与 Milvus 的删除行为一致。
     */
    private final ExpressionParser expressionParser = new SpelExpressionParser();

    private final FilterExpressionConverter filterExpressionConverter = new SimpleVectorStoreFilterExpressionConverter();

    public BatchingSimpleVectorStore(SimpleVectorStoreBuilder builder, int batchSize) {
        super(builder);
        this.batchSize = Math.max(1, batchSize);
    }

    @Override
    public void doAdd(List<Document> documents) {
        Objects.requireNonNull(documents, "Documents list cannot be null");
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("Documents list cannot be empty");
        }

        log.info("批量向量化文档入库 count={} 批次大小={}", documents.size(), batchSize);
        for (int i = 0; i < documents.size(); i += batchSize) {
            List<Document> batch = documents.subList(i, Math.min(documents.size(), i + batchSize));
            List<float[]> embeddings = embedBatch(batch);
            for (int j = 0; j < batch.size(); j++) {
                Document document = batch.get(j);
                this.store.put(document.getId(),
                        new SimpleVectorStoreContent(document.getId(), document.getText(),
                                document.getMetadata(), embeddings.get(j)));
            }
        }
    }

    /**
     * 分批向量化一组文档，返回与输入顺序一致的向量列表。
     *
     * <p>embedding 输入为纯文本 {@code getText()}，与 Milvus 批量路径一致。
     */
    private List<float[]> embedBatch(List<Document> batch) {
        List<String> texts = batch.stream()
                .map(Document::getText)
                .toList();
        return embeddingModel.embed(texts);
    }

    /**
     * 按过滤条件删除本地向量条目。
     *
     * <p>Spring AI M6 的 {@link SimpleVectorStore} 未实现该方法（基类默认抛
     * {@link UnsupportedOperationException}），此处补齐：将过滤器表达式转为 SpEL 后
     * 对每条记录的元数据（{@code #metadata} 变量）求值，命中则删除。
     * 求值方式与 {@link SimpleVectorStore} 搜索端的过滤谓词完全一致，保证
     * {@code docId/id/sessionId == '...'} 等现有过滤器写法兼容。
     */
    @Override
    protected void doDelete(Filter.Expression filterExpression) {
        Objects.requireNonNull(filterExpression, "Filter expression cannot be null");
        String spel = this.filterExpressionConverter.convertExpression(filterExpression);
        Expression expression = this.expressionParser.parseExpression(spel);
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, SimpleVectorStoreContent> entry : this.store.entrySet()) {
            StandardEvaluationContext context = new StandardEvaluationContext();
            context.setVariable("metadata", entry.getValue().getMetadata());
            if (Boolean.TRUE.equals(expression.getValue(context, Boolean.class))) {
                ids.add(entry.getKey());
            }
        }
        if (!ids.isEmpty()) {
            this.store.keySet().removeAll(ids);
            log.info("按过滤条件删除本地向量条目 count={}", ids.size());
        }
    }
}