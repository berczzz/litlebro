package com.litlebro.agent.session;

import com.litlebro.agent.session.model.SessionMemory;

import java.util.Map;

/**
 * 会话状态管理器抽象基类：封装与存储无关的公共业务逻辑
 * （token 累积、轮次自增、模型合并），存储读写由子类实现。
 */
public abstract class AbstractSessionManager implements SessionManager {

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
                old.curUseTokens() + promptTokens + completionTokens,
                old.curCompletionTokens() + completionTokens,
                old.curPromptTokens() + promptTokens,
                Map.of("turnCount", turnCount)
        );
        save(sessionId, updated);
    }

    @Override
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

    /**
     * 持久化会话状态（由子类写入对应存储）。
     */
    protected abstract void save(String sessionId, SessionMemory memory);

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