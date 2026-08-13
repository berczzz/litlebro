package com.litlebro.agent.session.model;

import java.util.Map;

/**
 * 会话记忆实体
 */
public record SessionMemory(
        String id,
        String parentId, // 父级sessionId
        String model, // 多个模型按顺序逗号分割
        Integer totalUseTokens, // 当前会话汇总使用token数
        Integer totalCompletionTokens, // 当前会话汇总llm响应的使用token数
        Integer totalPromptTokens, // 当前会话提问汇总使用token数
        Integer curUseTokens, // 当前会话占用的token数
        Integer curCompletionTokens, // 当前会话占用llm响应的token数
        Integer curPromptTokens, // 当前会话提问占用的token数
        Map<String, Object> metadata // 其他额外字段 例如 turnCount 轮次（一问一答）的次数
) {

    public SessionMemory {
        if (metadata == null) {
            metadata = Map.of();
        }
    }

}