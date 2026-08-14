package com.litlebro.agent.rag;

/**
 * 文档解析结果缓存：以文件内容哈希为 key，解析出的文本为 value。
 *
 * <p>重复上传同一文件时直接命中缓存、跳过解析过程，尤其避免图片型文档
 * 重复调用视觉模型浪费 token（视觉描述只在首次上传时执行一次）。
 *
 * <p>存储后端由配置 {@code app.rag.cache.type} 决定（见 {@code config.AppConfig}）：
 * {@code local} 加载 {@code rag.local.LocalDocumentParseCache}（本地内存），
 * {@code redis} 加载 {@code rag.external.RedisDocumentParseCache}（Redis + TTL）。
 * 新增存储实现只需实现本接口并在 {@code config.AppConfig} 中按配置装配。
 */
public interface DocumentParseCache {

    /**
     * 按文件内容哈希取解析文本。
     *
     * @param fileHash 文件内容 SHA-256
     * @return 已解析文本；未命中或读取失败返回 null
     */
    String get(String fileHash);

    /**
     * 缓存解析结果。
     *
     * @param fileHash 文件内容 SHA-256
     * @param content  解析出的文本
     */
    void put(String fileHash, String content);
}