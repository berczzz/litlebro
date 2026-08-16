package com.litlebro.agent.context;

import java.util.List;

/**
 * 会话上下文持有器，通过 ThreadLocal 在单个请求线程内传递当前请求的会话信息。
 *
 * <p>用于 LLM 工具回调：Spring AI 的 {@code @Tool} 方法参数由 LLM 生成，
 * 工具本身拿不到请求的 sessionId 与技能名单。因此在 {@code AgentService.chat} 入口
 * 将 sessionId 与本次请求可用的技能 ID 写入 ThreadLocal，工具内读取即可感知当前请求。
 *
 * <p>使用后必须在 finally 中调用 {@link #clear()}，避免线程池复用导致
 * 会话串号或内存泄漏。
 */
public final class SessionContextHolder {

    private SessionContextHolder() {
    }

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> SKILL_IDS = new ThreadLocal<>();

    public static void set(String sessionId) {
        SESSION_ID.set(sessionId);
    }

    public static String get() {
        return SESSION_ID.get();
    }

    /**
     * 写入本次请求可用的技能 ID 列表（已由 SkillService 解析为可用的名单）。
     */
    public static void setSkillIds(List<String> skillIds) {
        SKILL_IDS.set(skillIds == null ? List.of() : skillIds);
    }

    /**
     * 读取本次请求可用的技能 ID 列表；未设置时返回空列表。
     */
    public static List<String> getSkillIds() {
        List<String> ids = SKILL_IDS.get();
        return ids == null ? List.of() : ids;
    }

    public static void clear() {
        SESSION_ID.remove();
        SKILL_IDS.remove();
    }
}
