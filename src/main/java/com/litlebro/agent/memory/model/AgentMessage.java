package com.litlebro.agent.memory.model;

import org.springframework.ai.chat.messages.MessageType;

import java.util.List;
import java.util.Map;

/**
 * 统一记忆实体：短期记忆（Redis）与长期记忆（向量库）共用同一对象。
 *
 * <p>字段语义：
 * <ul>
 *   <li>id — 记忆唯一标识</li>
 *   <li>sessionId — 记忆归属会话，检索时按此过滤实现会话隔离</li>
 *   <li>category — 记忆分类（chat_message / session_summary / session_fact）</li>
 *   <li>messageType — Spring AI {@link MessageType} 枚举（USER/ASSISTANT/SYSTEM/TOOL），用于精确还原</li>
 *   <li>text — 消息正文</li>
 *   <li>metadata — 可扩展元数据</li>
 *   <li>media — 多媒体内容（图片/音频等），URL 或 base64 字节</li>
 *   <li>toolCalls — Assistant 消息中的工具调用</li>
 *   <li>toolResponses — 工具执行结果</li>
 *   <li>createdAt — 创建时间（epoch millis），用于定位压缩点、时间范围过滤</li>
 * </ul>
 *
 * <p>所有字段均为简单可序列化类型，Jackson 可直接存取，
 * 不依赖 Spring AI 不可变的 Message 实现类。
 */
public record AgentMessage(
        String id,
        String sessionId,
        String category,
        MessageType messageType,
        String text,
        Map<String, Object> metadata,
        List<MediaData> media,
        List<ToolCallData> toolCalls,
        List<ToolResponseData> toolResponses,
        long createdAt
) {

    public AgentMessage {
        if (metadata == null) {
            metadata = Map.of();
        }
        if (media == null) {
            media = List.of();
        }
        if (toolCalls == null) {
            toolCalls = List.of();
        }
        if (toolResponses == null) {
            toolResponses = List.of();
        }
    }

    /**
     * 多媒体数据：data 为 URL 字符串或 base64 编码的字节。
     */
    public record MediaData(
            String mimeType,
            String data, // URL 或 base64
            String dataType // "url" 或 "base64"
    ) {
    }

    /**
     * 工具调用（对应 AssistantMessage.ToolCall）。
     */
    public record ToolCallData(
            String id,
            String type,
            String name,
            String arguments
    ) {
    }

    /**
     * 工具响应（对应 ToolResponseMessage.ToolResponse）。
     */
    public record ToolResponseData(
            String id,
            String name,
            String responseData
    ) {
    }
}
