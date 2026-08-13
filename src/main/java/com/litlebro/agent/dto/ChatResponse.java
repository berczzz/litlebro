package com.litlebro.agent.dto;

import java.util.List;

/**
 * 聊天响应 DTO，封装一次对话交互的完整结果。
 *
 * <p>使用 record 确保响应数据的不可变性，适合序列化为 JSON 返回给前端。
 * toolCalls 字段记录本次对话中 LLM 实际调用了哪些工具，
 * 便于前端展示工具调用链，提升可解释性。
 *
 * @param question  原始用户问题
 * @param answer    LLM 生成的回答文本
 * @param sessionId 会话标识
 * @param toolCalls 本次对话中调用的工具名称列表，无工具调用时为 null
 */
public record ChatResponse(
        String question,
        String answer,
        String sessionId,
        List<String> toolCalls
) {
    /**
     * 静态工厂方法，创建标准响应对象。
     *
     * @param question  用户问题
     * @param answer    助手回答
     * @param sessionId 会话 ID
     * @param toolCalls 工具调用列表
     * @return 新的 ChatResponse 实例
     */
    public static ChatResponse of(String question, String answer, String sessionId, List<String> toolCalls) {
        return new ChatResponse(question, answer, sessionId, toolCalls);
    }
}