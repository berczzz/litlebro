package com.litlebro.agent.rag.local;

import com.litlebro.agent.rag.DocumentParseCache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文档解析缓存本地内存实现：有上限 LRU，进程内共享，重启丢失。
 * 由配置 {@code app.rag.cache.type=local}（默认）装配。
 *
 * <p>缓存条目为整份解析文本（单条可达 {@code app.rag.max-text-length}），
 * 无界缓存会被持续上传的新文件耗尽内存，故以 {@code app.rag.cache.max-entries}
 * 封顶并按访问序淘汰最久未用的条目（LRU）。
 */
public class LocalDocumentParseCache implements DocumentParseCache {

    private final Map<String, String> cache;

    public LocalDocumentParseCache(int maxEntries) {
        int cap = Math.max(1, maxEntries);
        // 访问序 LRU；LinkedHashMap 非线程安全，读写统一走 synchronized 方法
        this.cache = new LinkedHashMap<>(cap, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > cap;
            }
        };
    }

    @Override
    public synchronized String get(String fileHash) {
        return cache.get(fileHash);
    }

    @Override
    public synchronized void put(String fileHash, String content) {
        cache.put(fileHash, content);
    }
}
