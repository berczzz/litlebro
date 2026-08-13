package com.litlebro.agent.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 聊天请求 DTO。
 *
 * @param question  用户提问内容，必填
 * @param sessionId 会话标识，为空时默认使用 "default"
 */
public record ChatRequest(
        @NotBlank(message = "question 不能为空")
        String question,

        String sessionId
) {
    public ChatRequest {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "default";
        }
    }
}