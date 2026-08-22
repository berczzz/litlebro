package com.litlebro.agent.embedding;

import java.util.List;

/**
 * Embedding 缓存抽象接口：以文本内容哈希为 key，缓存对应的 embedding 向量。
 *
 * <p>重复向量化相同文本时直接命中缓存、跳过 embedding HTTP 调用，
 * 尤其在文档入库（语义切块中重复段落）、记忆检索（相同查询文本）等场景下
 * 可大幅减少 embedding API 调用次数与 token 消耗。
 *
 * <p>存储后端由配置 {@code app.embedding.cache.type} 决定（见 {@code config.AppConfig}）：
 * {@code local} 加载 {@code embedding.local.LocalEmbeddingCache}（本地内存），
 * {@code redis} 加载 {@code embedding.external.RedisEmbeddingCache}（Redis + TTL）。
 * 新增存储实现只需实现本接口并在 {@code config.AppConfig} 中按配置装配。
 */
public interface EmbeddingCache {

    /**
     * 批量获取缓存的 embedding 向量。
     *
     * @param texts 待查询的文本列表
     * @return 与输入顺序一致的向量列表；未命中的位置为 null
     */
    List<float[]> getAll(List<String> texts);

    /**
     * 批量写入缓存。
     *
     * @param texts     文本列表
     * @param vectors   与文本顺序一致的向量列表
     */
    void putAll(List<String> texts, List<float[]> vectors);

    /**
     * 单条获取。
     *
     * @param text 待查询文本
     * @return 缓存的向量；未命中返回 null
     */
    float[] get(String text);

    /**
     * 单条写入。
     *
     * @param text   文本
     * @param vector embedding 向量
     */
    void put(String text, float[] vector);
}
