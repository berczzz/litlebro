package com.litlebro.agent.session;

import com.litlebro.agent.session.model.SessionMemory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 会话管理器，维护会话的运行时状态（token 累积值、轮次、模型等）。
 *
 * <p>当 Redis 可用时，SessionMemory 存 Redis（30min TTL），重启不丢；
 * 否则回退 ConcurrentHashMap。
 */
public class SessionManager {

    private static final String SESSION_KEY_PREFIX = "agent:session:memory:";
    private static final long TTL_MINUTES = 30;

    private final Map<String, SessionMemory> fallback = new ConcurrentHashMap<>();
    private final RedisTemplate<String, Object> redisTemplate;

    public SessionManager(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
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
            Object value = redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + sessionId);
            return value instanceof SessionMemory ? (SessionMemory) value : null;
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
                old.totalUseTokens() + promptTokens + completionTokens,
                old.totalCompletionTokens() + completionTokens,
                old.totalPromptTokens() + promptTokens,
                promptTokens + completionTokens,
                completionTokens,
                promptTokens,
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
            redisTemplate.opsForValue().set(SESSION_KEY_PREFIX + sessionId, memory, TTL_MINUTES, TimeUnit.MINUTES);
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