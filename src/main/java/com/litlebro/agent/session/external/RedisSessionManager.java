package com.litlebro.agent.session.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litlebro.agent.session.AbstractSessionManager;
import com.litlebro.agent.session.model.SessionMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 会话状态 Redis 实现：Jackson JSON 字符串存储（30min TTL），重启不丢。
 * 由配置 {@code app.memory.stm.type=redis} 装配。
 */
public class RedisSessionManager extends AbstractSessionManager {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionManager.class);

    private static final String SESSION_KEY_PREFIX = "agent:session:memory:";
    private static final long TTL_MINUTES = 30;

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisSessionManager(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public SessionMemory get(String sessionId) {
        try {
            Object value = redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + sessionId);
            if (value instanceof String json && !json.isBlank()) {
                return objectMapper.readValue(json, SessionMemory.class);
            }
        } catch (Exception e) {
            log.warn("会话状态反序列化失败，触发重建 sessionId={} 原因: {}", sessionId, e.getMessage());
            try {
                redisTemplate.delete(SESSION_KEY_PREFIX + sessionId);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    @Override
    public void delete(String sessionId) {
        redisTemplate.delete(SESSION_KEY_PREFIX + sessionId);
    }

    @Override
    protected void save(String sessionId, SessionMemory memory) {
        try {
            String json = objectMapper.writeValueAsString(memory);
            redisTemplate.opsForValue().set(SESSION_KEY_PREFIX + sessionId, json, TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("会话状态序列化失败 sessionId={} 原因: {}", sessionId, e.getMessage());
        }
    }
}