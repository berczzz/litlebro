package com.litlebro.agent.session;

import com.litlebro.agent.session.model.SessionMemory;

/**
 * 会话状态管理器：维护会话的运行时状态（token 累积值、轮次、模型等）。
 *
 * <p>存储后端由配置 {@code app.memory.stm.type} 决定（见 {@code MemoryConfig}）：
 * {@code local} 加载 {@code session.local.LocalSessionManager}（本地内存），
 * {@code redis} 加载 {@code session.external.RedisSessionManager}（Redis，30min TTL）。
 * 新增存储实现只需实现本接口并在 {@code MemoryConfig} 中按配置装配。
 */
public interface SessionManager {

    SessionMemory getOrCreate(String sessionId);

    SessionMemory get(String sessionId);

    void updateSession(String sessionId, String model, int promptTokens, int completionTokens);

    void resetCurTokens(String sessionId);

    void delete(String sessionId);
}