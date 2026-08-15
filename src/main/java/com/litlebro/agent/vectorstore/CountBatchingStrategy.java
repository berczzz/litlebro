package com.litlebro.agent.vectorstore;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * 按条数分批的 embedding 分批策略：每批最多 {@code batchSize} 条文档。
 *
 * <p>Spring AI 默认的 {@code TokenCountBatchingStrategy} 只按 token 数分批、
 * 不限制条数，可能超过 embedding 接口（如 dashscope text-embedding 系列）单请求
 * 的输入条数上限（10 条）；该策略按条数严格分批，供 Milvus 等已走
 * {@code EmbeddingModel.embed(List, options, batchingStrategy)} 批量路径的向量库使用。
 */
public class CountBatchingStrategy implements BatchingStrategy {

    private final int batchSize;

    public CountBatchingStrategy(int batchSize) {
        this.batchSize = Math.max(1, batchSize);
    }

    @Override
    public List<List<Document>> batch(List<Document> documents) {
        List<List<Document>> batches = new ArrayList<>();
        for (int i = 0; i < documents.size(); i += batchSize) {
            batches.add(documents.subList(i, Math.min(documents.size(), i + batchSize)));
        }
        return batches;
    }
}