package com.litlebro.agent.memory;

import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Milvus 分片向量库路由器：按 {@code sessionId} 哈希取模路由到 N 个分片 collection，
 * 文档知识库走独立 collection（固定专用，不参与会话取模）。
 *
 * <p>取模使用 {@code Math.floorMod} 保证非负索引；{@code String.hashCode} 由规范固定，
 * 跨 JVM/重启稳定，同一会话恒路由到同一分片。
 */
public class ShardedMilvusVectorStoreRouter implements VectorStoreRouter {

    private final List<VectorStore> memoryShards;
    private final VectorStore documentStore;
    private final List<String> names;
    private final int shardCount;

    public ShardedMilvusVectorStoreRouter(List<VectorStore> memoryShards,
                                          VectorStore documentStore,
                                          List<String> names) {
        this.memoryShards = List.copyOf(memoryShards);
        this.documentStore = documentStore;
        this.shardCount = memoryShards.size();
        List<String> allNames = new ArrayList<>(names);
        this.names = List.copyOf(allNames);
    }

    @Override
    public VectorStore forSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            // 空 sessionId 视为文档等全局数据，走文档存储
            return documentStore;
        }
        return memoryShards.get(Math.floorMod(sessionId.hashCode(), shardCount));
    }

    @Override
    public VectorStore forDocument() {
        return documentStore;
    }

    @Override
    public List<VectorStore> all() {
        List<VectorStore> all = new ArrayList<>(memoryShards);
        all.add(documentStore);
        return all;
    }

    @Override
    public int shardCount() {
        return shardCount;
    }

    @Override
    public List<String> names() {
        return names;
    }
}