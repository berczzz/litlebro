package com.litlebro.agent.memory;

import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

/**
 * 向量库路由器：屏蔽底层向量存储的物理布局（单实例 / 多分片），
 * 向 {@link VectorMemoryStore} 提供"按会话路由 / 文档专用存储 / 全量广播"三类访问入口。
 *
 * <ul>
 *   <li>本地（local）实现为单实例：会话存储与文档存储是同一个 {@link VectorStore}</li>
 *   <li>Milvus 实现按 {@code sessionId 哈希取模} 路由到 N 个分片 collection，
 *       文档知识库走独立 collection，避免与会话记忆混装</li>
 * </ul>
 */
public interface VectorStoreRouter {

    /** 返回会话对应的存储实例（按 sessionId 取模路由）。 */
    VectorStore forSession(String sessionId);

    /** 返回文档知识库专用存储实例（全局共享，不绑定会话）。 */
    VectorStore forDocument();

    /** 返回全部存储实例（用于按 ID 广播查询/删除等无 sessionId 语义的操作）。 */
    List<VectorStore> all();

    /** 会话分片数量（本地为 1；Milvus 为配置的 shard 数，不含文档专用存储）。 */
    int shardCount();

    /** 全部物理 collection/存储名称（供 Milvus 索引初始化等运维任务使用）。 */
    List<String> names();
}