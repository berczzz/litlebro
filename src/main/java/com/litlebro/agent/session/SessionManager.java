package com.litlebro.agent.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litlebro.agent.session.model.SessionMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 会话管理器，维护会话的运行时状态（token 累积值、轮次、模型等）。
 *
 * <p>当 Redis 可用时，SessionMemory 存 Redis（30min TTL），重启不丢；
 * 否则回退 ConcurrentHashMap。Redis 值以 Jackson JSON 字符串存储。
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private static final String SESSION_KEY_PREFIX = "agent:session:memory:";
    private static final long TTL_MINUTES = 30;

    private final Map<String, SessionMemory> fallback = new ConcurrentHashMap<>();
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public SessionManager(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public SessionMemory getOrCreate(String sessionId) {
        SessionMemory existing = get(sessionId);
        if (existing != null) {
            return existing;
        }
        SessionMemory created = new SessionMemory(sessionId, null, null, 0, 0, 0, 0, 0, 0, null);
        save(sessionId, created);
        return created;
    }

    public SessionMemory get(String sessionId) {
        if (redisTemplate != null) {
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
        return fallback.get(sessionId);
    }

    public void updateSession(String sessionId, String model, int promptTokens, int completionTokens) {
        SessionMemory old = getOrCreate(sessionId);
        String mergedModel = mergeModel(old.model(), model);
        int turnCount = (int) old.metadata().getOrDefault("turnCount", 0) + 1;
        SessionMemory updated = new SessionMemory(
                sessionId,
                old.parentId(),
                mergedModel,
                old.totalUseTokens() + promptTokens,
                old.totalCompletionTokens() + completionTokens,
                old.totalPromptTokens() + promptTokens,
                old.curUseTokens() + promptTokens,
                old.curCompletionTokens() + completionTokens,
                old.curPromptTokens() + promptTokens,
                Map.of("turnCount", turnCount)
        );
        save(sessionId, updated);
    }

    public void resetCurTokens(String sessionId) {
        SessionMemory old = getOrCreate(sessionId);
        SessionMemory updated = new SessionMemory(
                sessionId,
                old.parentId(),
                old.model(),
                old.totalUseTokens(),
                old.totalCompletionTokens(),
                old.totalPromptTokens(),
                0, 0, 0,
                old.metadata()
        );
        save(sessionId, updated);
    }

    public void delete(String sessionId) {
        if (redisTemplate != null) {
            redisTemplate.delete(SESSION_KEY_PREFIX + sessionId);
        } else {
            fallback.remove(sessionId);
        }
    }

    private void save(String sessionId, SessionMemory memory) {
        if (redisTemplate != null) {
            try {
                String json = objectMapper.writeValueAsString(memory);
                redisTemplate.opsForValue().set(SESSION_KEY_PREFIX + sessionId, json, TTL_MINUTES, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("会话状态序列化失败 sessionId={} 原因: {}", sessionId, e.getMessage());
            }
        } else {
            fallback.put(sessionId, memory);
        }
    }

    private String mergeModel(String existing, String newModel) {
        if (newModel == null || newModel.isBlank()) {
            return existing;
        }
        if (existing == null || existing.isBlank()) {
            return newModel;
        }
        if (existing.contains(newModel)) {
            return existing;
        }
        return existing + "," + newModel;
    }
}