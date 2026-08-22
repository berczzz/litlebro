package com.litlebro.agent.embedding.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litlebro.agent.embedding.EmbeddingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Embedding 缓存 Redis 实现：以文本哈希为 key、embedding 向量为 value 存入 Redis（TTL），重启不丢。
 * 由配置 {@code app.embedding.cache.type=redis} 装配。
 *
 * <p>embedding 向量（float[]）经 Jackson 序列化为 JSON 字符串存储，
 * 读取时反序列化回 float[]。Redis key 前缀 {@code agent:embed:cache:} 避免与现有 key 冲突。
 */
public class RedisEmbeddingCache implements EmbeddingCache {

    private static final Logger log = LoggerFactory.getLogger(RedisEmbeddingCache.class);

    /** Redis key 前缀 */
    private static final String CACHE_KEY_PREFIX = "agent:embed:cache:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final long ttlHours;

    public RedisEmbeddingCache(RedisTemplate<String, Object> redisTemplate,
                               ObjectMapper objectMapper, long ttlHours) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttlHours = ttlHours;
    }

    @Override
    public List<float[]> getAll(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(get(text));
        }
        return result;
    }

    @Override
    public void putAll(List<String> texts, List<float[]> vectors) {
        for (int i = 0; i < texts.size(); i++) {
            put(texts.get(i), vectors.get(i));
        }
    }

    @Override
    public float[] get(String text) {
        try {
            Object value = redisTemplate.opsForValue().get(CACHE_KEY_PREFIX + text);
            if (value == null) {
                return null;
            }
            String json = value instanceof String s ? s : value.toString();
            return objectMapper.readValue(json, float[].class);
        } catch (Exception e) {
            log.warn("Embedding 缓存读取失败 key={} 原因: {}", text, e.getMessage());
            return null;
        }
    }

    @Override
    public void put(String text, float[] vector) {
        try {
            String json = objectMapper.writeValueAsString(vector);
            redisTemplate.opsForValue().set(CACHE_KEY_PREFIX + text, json, ttlHours, TimeUnit.HOURS);
        } catch (JsonProcessingException e) {
            log.warn("Embedding 缓存序列化失败 key={} 原因: {}", text, e.getMessage());
        } catch (Exception e) {
            log.warn("Embedding 缓存写入失败 key={} 原因: {}", text, e.getMessage());
        }
    }
}
