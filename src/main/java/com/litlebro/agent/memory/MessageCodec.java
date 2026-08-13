package com.litlebro.agent.memory;

import com.litlebro.agent.common.Constant;
import com.litlebro.agent.memory.model.AgentMessage;
import com.litlebro.agent.memory.model.AgentMessage.MediaData;
import com.litlebro.agent.memory.model.AgentMessage.ToolCallData;
import com.litlebro.agent.memory.model.AgentMessage.ToolResponseData;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;

import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 消息转换器：在 Spring AI 的多态 {@link Message} 与统一记忆实体 {@link AgentMessage} 之间双向转换。
 *
 * <p>转换覆盖全部四种消息类型：
 * <ul>
 *   <li>UserMessage → media + text + metadata</li>
 *   <li>AssistantMessage → text + toolCalls + media + metadata</li>
 *   <li>SystemMessage → text</li>
 *   <li>ToolResponseMessage → responses + metadata</li>
 * </ul>
 *
 * <p>media 的 data 有两种形态：URL 存 {@code url}，字节/Resource 转 base64 存 {@code base64}，
 * 还原时通过 {@link Media} 对应的构造器重建，与 Spring AI 内部形态兼容。
 */
public final class MessageCodec {

    private MessageCodec() {
    }

    public static List<AgentMessage> toAgentMessages(List<Message> messages, String sessionId) {
        List<AgentMessage> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }
        for (Message message : messages) {
            AgentMessage am = toAgentMessage(message, sessionId);
            if (am != null) {
                result.add(am);
            }
        }
        return result;
    }

    public static AgentMessage toAgentMessage(Message message, String sessionId) {
        if (message == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString().replace("-", "");
        String messageType = message.getMessageType() == null ? null : message.getMessageType().getValue();
        String text = message.getText();
        Map<String, Object> metadata = message.getMetadata();

        if (message instanceof UserMessage um) {
            return new AgentMessage(
                    id, sessionId, Constant.CATEGORY_CHAT, messageType, messageType,
                    text, metadata, toMediaData(um.getMedia()), List.of(), List.of(), now);
        }
        if (message instanceof AssistantMessage am) {
            return new AgentMessage(
                    id, sessionId, Constant.CATEGORY_CHAT, messageType, messageType,
                    text, metadata, toMediaData(am.getMedia()), toToolCallData(am.getToolCalls()), List.of(), now);
        }
        if (message instanceof ToolResponseMessage trm) {
            List<ToolResponseData> responses = new ArrayList<>();
            if (trm.getResponses() != null) {
                for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                    responses.add(new ToolResponseData(tr.id(), tr.name(), tr.responseData()));
                }
            }
            return new AgentMessage(
                    id, sessionId, Constant.CATEGORY_CHAT, messageType, messageType,
                    text, metadata, List.of(), List.of(), responses, now);
        }
        // SystemMessage 及其他
        return new AgentMessage(
                id, sessionId, Constant.CATEGORY_CHAT, messageType, messageType,
                text, metadata, List.of(), List.of(), List.of(), now);
    }

    public static List<Message> toMessages(List<AgentMessage> agentMessages) {
        List<Message> result = new ArrayList<>();
        if (agentMessages == null) {
            return result;
        }
        for (AgentMessage am : agentMessages) {
            Message message = toMessage(am);
            if (message != null) {
                result.add(message);
            }
        }
        return result;
    }

    public static Message toMessage(AgentMessage am) {
        if (am == null) {
            return null;
        }
        String messageType = am.messageType() != null ? am.messageType().toUpperCase() : null;
        if (MessageType.USER.getValue().equalsIgnoreCase(messageType)) {
            return new UserMessage(MessageType.USER, am.text(), toMedia(am.media()), am.metadata());
        }
        if (MessageType.ASSISTANT.getValue().equalsIgnoreCase(messageType)) {
            return new AssistantMessage(am.text(), am.metadata(), toToolCall(am.toolCalls()), toMedia(am.media()));
        }
        if (MessageType.TOOL.getValue().equalsIgnoreCase(messageType)) {
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            if (am.toolResponses() != null) {
                for (ToolResponseData tr : am.toolResponses()) {
                    responses.add(new ToolResponseMessage.ToolResponse(tr.id(), tr.name(), tr.responseData()));
                }
            }
            return new ToolResponseMessage(responses, am.metadata());
        }
        // 默认按 SystemMessage 处理（摘要等）
        return new SystemMessage(am.text());
    }

    private static List<MediaData> toMediaData(List<Media> media) {
        if (media == null) {
            return List.of();
        }
        List<MediaData> result = new ArrayList<>();
        for (Media m : media) {
            String mimeType = m.getMimeType() == null ? null : m.getMimeType().toString();
            Object data = m.getData();
            if (data instanceof String url && looksLikeUrl(url)) {
                result.add(new MediaData(mimeType, url, "url"));
            } else {
                try {
                    byte[] bytes = m.getDataAsByteArray();
                    result.add(new MediaData(mimeType, Base64.getEncoder().encodeToString(bytes), "base64"));
                } catch (Exception e) {
                    // 字节读取失败则跳过该媒体
                }
            }
        }
        return result;
    }

    private static List<Media> toMedia(List<MediaData> mediaData) {
        if (mediaData == null) {
            return List.of();
        }
        List<Media> result = new ArrayList<>();
        for (MediaData md : mediaData) {
            try {
                MimeType mimeType = md.mimeType() != null ? MimeType.valueOf(md.mimeType()) : MimeType.valueOf("application/octet-stream");
                if ("url".equals(md.dataType()) && md.data() != null) {
                    result.add(new Media(mimeType, new URL(md.data())));
                } else if (md.data() != null) {
                    byte[] bytes = Base64.getDecoder().decode(md.data());
                    result.add(new Media(mimeType, new ByteArrayResource(bytes)));
                }
            } catch (Exception e) {
                // 单个媒体还原失败不影响整体
            }
        }
        return result;
    }

    private static List<ToolCallData> toToolCallData(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null) {
            return List.of();
        }
        List<ToolCallData> result = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : toolCalls) {
            result.add(new ToolCallData(tc.id(), tc.type(), tc.name(), tc.arguments()));
        }
        return result;
    }

    private static List<AssistantMessage.ToolCall> toToolCall(List<ToolCallData> toolCalls) {
        if (toolCalls == null) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> result = new ArrayList<>();
        for (ToolCallData tc : toolCalls) {
            result.add(new AssistantMessage.ToolCall(tc.id(), tc.type(), tc.name(), tc.arguments()));
        }
        return result;
    }

    private static boolean looksLikeUrl(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("data:"));
    }
}
