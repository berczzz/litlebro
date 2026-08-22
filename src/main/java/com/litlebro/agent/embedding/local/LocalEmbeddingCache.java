package com.litlebro.agent.embedding.local;

import com.litlebro.agent.embedding.EmbeddingCache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Embedding 缓存本地内存实现：有上限 LRU，进程内共享，重启丢失。
 * 由配置 {@code app.embedding.cache.type=local}（默认）装配。
 *
 * <p>缓存条目为文本哈希到 embedding 向量的映射，embedding 向量通常为
 * 1536 维 float 数组（约 6KB/条），{@code app.embedding.cache.max-entries}
 * 封顶并按访问序淘汰最久未用的条目（LRU），防止无界缓存耗尽内存。
 */
public class LocalEmbeddingCache implements EmbeddingCache {

    private final Map<String, float[]> cache;

    public LocalEmbeddingCache(int maxEntries) {
        int cap = Math.max(1, maxEntries);
        // 访问序 LRU；LinkedHashMap 非线程安全，读写统一用 synchronized 方法
        this.cache = new LinkedHashMap<>(cap, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                return size() > cap;
            }
        };
    }

    @Override
    public synchronized List<float[]> getAll(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(cache.get(text));
        }
        return result;
    }

    @Override
    public synchronized void putAll(List<String> texts, List<float[]> vectors) {
        for (int i = 0; i < texts.size(); i++) {
            cache.put(texts.get(i), vectors.get(i));
        }
    }

    @Override
    public synchronized float[] get(String text) {
        return cache.get(text);
    }

    @Override
    public synchronized void put(String text, float[] vector) {
        cache.put(text, vector);
    }
}
