package com.litlebro.agent.session;

import com.litlebro.agent.session.model.SessionMemory;

/**
 * 会话状态管理器：维护会话的运行时状态（token 累积值、轮次、模型等）。
 *
 * <p>存储后端由配置 {@code app.memory.stm.type} 决定（见 {@code config.AppConfig}）：
 * {@code local} 加载 {@code session.local.LocalSessionManager}（本地内存），
 * {@code redis} 加载 {@code session.external.RedisSessionManager}（Redis，30min TTL）。
 * 新增存储实现只需实现本接口并在 {@code config.AppConfig} 中按配置装配。
 */
public interface SessionManager {

    SessionMemory getOrCreate(String sessionId);

    SessionMemory get(String sessionId);

    void updateSession(String sessionId, String model, int promptTokens, int completionTokens);

    void resetCurTokens(String sessionId);

    /**
     * 为会话分配一段连续的消息序号（用户+助手消息各占一个），返回起始序号。
     * 会话级单调递增，跨重启持久化（存于 SessionMemory 元数据），
     * 用作长期记忆压缩边界定位：压缩时以 {@code 最近分配序号 - 保留条数 - 安全余量} 划界。
     * 线程安全：同一会话的并发分配串行化，保证序号不重复。
     *
     * @param sessionId 会话 ID
     * @param count     需要分配的连续序号个数
     * @return 起始序号（含），分配区间为 [start, start + count)
     */
    long nextMessageSeq(String sessionId, int count);

    /**
     * 读取会话最近一次分配的消息序号（0 表示从未分配过）。用于压缩边界计算。
     */
    long getLastMessageSeq(String sessionId);

    void delete(String sessionId);
}