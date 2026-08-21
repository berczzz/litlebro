package com.litlebro.agent.memory;

import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

/**
 * 单实例向量库路由器（本地存储默认实现）。
 * 会话存储与文档存储共用同一个 {@link VectorStore}。
 */
public class SingleVectorStoreRouter implements VectorStoreRouter {

    private final VectorStore store;
    private final String name;

    public SingleVectorStoreRouter(VectorStore store, String name) {
        this.store = store;
        this.name = name;
    }

    @Override
    public VectorStore forSession(String sessionId) {
        return store;
    }

    @Override
    public VectorStore forDocument() {
        return store;
    }

    @Override
    public List<VectorStore> all() {
        return List.of(store);
    }

    @Override
    public int shardCount() {
        return 1;
    }

    @Override
    public List<String> names() {
        return List.of(name);
    }
}