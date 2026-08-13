package com.litlebro.agent.rag.local;

import com.litlebro.agent.rag.DocumentParseCache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档解析缓存本地内存实现：ConcurrentHashMap 存储，进程内共享，重启丢失。
 * 由配置 {@code app.rag.cache.type=local}（默认）装配。
 */
public class LocalDocumentParseCache implements DocumentParseCache {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @Override
    public String get(String fileHash) {
        return cache.get(fileHash);
    }

    @Override
    public void put(String fileHash, String content) {
        cache.put(fileHash, content);
    }
}