package com.litlebro.agent.context;

/**
 * 会话上下文持有器，通过 ThreadLocal 在单个请求线程内传递当前会话 ID。
 *
 * <p>用于 LLM 工具回调：Spring AI 的 {@code @Tool} 方法参数由 LLM 生成，
 * 工具本身拿不到请求的 sessionId。因此在 {@code AgentService.chat} 入口
 * 将 sessionId 写入 ThreadLocal，工具内读取即可感知当前会话。
 *
 * <p>使用后必须在 finally 中调用 {@link #clear()}，避免线程池复用导致
 * 会话串号或内存泄漏。
 */
public final class SessionContextHolder {

    private SessionContextHolder() {
    }

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    public static void set(String sessionId) {
        SESSION_ID.set(sessionId);
    }

    public static String get() {
        return SESSION_ID.get();
    }

    public static void clear() {
        SESSION_ID.remove();
    }
}
