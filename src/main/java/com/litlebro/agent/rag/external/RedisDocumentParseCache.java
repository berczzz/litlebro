package com.litlebro.agent.rag.external;

import com.litlebro.agent.rag.DocumentParseCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 文档解析缓存 Redis 实现：以解析文本作为 value 直接存 Redis（TTL），重启不丢。
 * 由配置 {@code app.rag.cache.type=redis} 装配。
 */
public class RedisDocumentParseCache implements DocumentParseCache {

    private static final Logger log = LoggerFactory.getLogger(RedisDocumentParseCache.class);

    /** Redis key 前缀 */
    private static final String CACHE_KEY_PREFIX = "agent:rag:parse:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final long ttlHours;

    public RedisDocumentParseCache(RedisTemplate<String, Object> redisTemplate, long ttlHours) {
        this.redisTemplate = redisTemplate;
        this.ttlHours = ttlHours;
    }

    @Override
    public String get(String fileHash) {
        try {
            Object value = redisTemplate.opsForValue().get(CACHE_KEY_PREFIX + fileHash);
            return value == null ? null : value.toString();
        } catch (Exception e) {
            log.warn("文档解析缓存读取失败 fileHash={} 原因: {}", fileHash, e.getMessage());
            return null;
        }
    }

    @Override
    public void put(String fileHash, String content) {
        try {
            redisTemplate.opsForValue().set(CACHE_KEY_PREFIX + fileHash, content, ttlHours, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("文档解析缓存写入失败 fileHash={} 原因: {}", fileHash, e.getMessage());
        }
    }
}