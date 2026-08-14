package com.litlebro.agent.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 聊天请求 DTO。
 *
 * @param question    用户提问内容，必填
 * @param sessionId   会话标识，为空时默认使用 "default"
 * @param attachments 附件列表（base64/url 来源），可为空；multipart 来源走单独端点
 */
public record ChatRequest(
        @NotBlank(message = "question 不能为空")
        String question,

        String sessionId,

        List<FileAttachment> attachments
) {
    public ChatRequest {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "default";
        }
        if (attachments == null) {
            attachments = List.of();
        }
    }
}