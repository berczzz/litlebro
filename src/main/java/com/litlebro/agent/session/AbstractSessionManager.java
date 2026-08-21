package com.litlebro.agent.session;

import com.litlebro.agent.common.Constant;
import com.litlebro.agent.session.model.SessionMemory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话状态管理器抽象基类：封装与存储无关的公共业务逻辑
 * （token 更新、轮次自增、模型合并、消息序号分配），存储读写由子类实现。
 *
 * <p>会话状态的读-改-写（updateSession / resetCurTokens / nextMessageSeq）统一在
 * 每会话锁内串行执行，防止并发下元数据（如消息序号 lastMessageSeq）相互覆盖回退。
 */
public abstract class AbstractSessionManager implements SessionManager {

    /**
     * 每会话一把锁，串行化状态元数据变更（token/轮次/序号），避免并发覆盖回退。
     */
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    @Override
    public SessionMemory getOrCreate(String sessionId) {
        SessionMemory existing = get(sessionId);
        if (existing != null) {
            return existing;
        }
        SessionMemory created = new SessionMemory(sessionId, null, null, 0, 0, 0, 0, 0, 0, null);
        save(sessionId, created);
        return created;
    }

    @Override
    public void updateSession(String sessionId, String model, int promptTokens, int completionTokens) {
        synchronized (lock(sessionId)) {
            SessionMemory old = getOrCreate(sessionId);
            String mergedModel = mergeModel(old.model(), model);

            // 合并式更新元数据（turnCount 自增 + 保留 lastMessageSeq 等既有键）
            Map<String, Object> meta = new HashMap<>(old.metadata());
            meta.put(Constant.SESSION_META_TURN_COUNT, (int) old.metadata().getOrDefault(Constant.SESSION_META_TURN_COUNT, 0) + 1);

            // curPromptTokens 改为"替换"语义：取本次请求 LLM 实际返回的 prompt token 数，
            // 作为"当前上下文占用"的近似（0 表示未返回用量，沿用旧值避免误触发压缩）
            int curPrompt = promptTokens > 0 ? promptTokens : old.curPromptTokens();

            SessionMemory updated = new SessionMemory(
                    sessionId,
                    old.parentId(),
                    mergedModel,
                    old.totalUseTokens() + promptTokens + completionTokens,
                    old.totalCompletionTokens() + completionTokens,
                    old.totalPromptTokens() + promptTokens,
                    old.curUseTokens() + promptTokens + completionTokens,
                    old.curCompletionTokens() + completionTokens,
                    curPrompt,
                    meta
            );
            save(sessionId, updated);
        }
    }

    @Override
    public void resetCurTokens(String sessionId) {
        synchronized (lock(sessionId)) {
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
    }

    @Override
    public long nextMessageSeq(String sessionId, int count) {
        int alloc = Math.max(1, count);
        synchronized (lock(sessionId)) {
            SessionMemory old = getOrCreate(sessionId);
            long last = toLong(old.metadata().get(Constant.SESSION_META_LAST_SEQ));
            long start = last + 1;
            Map<String, Object> meta = new HashMap<>(old.metadata());
            meta.put(Constant.SESSION_META_LAST_SEQ, start + alloc - 1);
            SessionMemory updated = new SessionMemory(
                    sessionId,
                    old.parentId(),
                    old.model(),
                    old.totalUseTokens(),
                    old.totalCompletionTokens(),
                    old.totalPromptTokens(),
                    old.curUseTokens(),
                    old.curCompletionTokens(),
                    old.curPromptTokens(),
                    meta
            );
            save(sessionId, updated);
            return start;
        }
    }

    @Override
    public long getLastMessageSeq(String sessionId) {
        SessionMemory old = get(sessionId);
        return old == null ? 0 : toLong(old.metadata().get(Constant.SESSION_META_LAST_SEQ));
    }

    /**
     * 持久化会话状态（由子类写入对应存储）。
     */
    protected abstract void save(String sessionId, SessionMemory memory);

    private Object lock(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, k -> new Object());
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

    private static long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }
}