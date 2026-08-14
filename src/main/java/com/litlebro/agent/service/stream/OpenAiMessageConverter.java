package com.litlebro.agent.service.stream;

import com.litlebro.agent.memory.MessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.Media;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将短期记忆中的 Spring AI {@link Message} 转成 OpenAI 兼容的请求消息。
 *
 * <p>与 {@link MessageCodec}（AgentMessage ↔ Message 存储序列化）职责不同：
 * 本转换器面向 OpenAI 兼容 HTTP 协议，覆盖 system/user（含媒体）/assistant（含工具调用）/tool 四种类型。
 */
@Component
public class OpenAiMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(OpenAiMessageConverter.class);

    public Map<String, Object> toOpenAiMessage(Message message) {
        if (message instanceof SystemMessage) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", "system");
            m.put("content", textOf(message));
            return m;
        }
        if (message instanceof UserMessage um) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", "user");
            if (um.getMedia() == null || um.getMedia().isEmpty()) {
                m.put("content", textOf(um));
            } else {
                m.put("content", toOpenAiMultimodalContent(um));
            }
            return m;
        }
        if (message instanceof AssistantMessage am) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", "assistant");
            m.put("content", am.getText());
            if (am.hasToolCalls()) {
                List<Map<String, Object>> calls = new ArrayList<>();
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", tc.name());
                    fn.put("arguments", tc.arguments());
                    calls.add(Map.of("id", tc.id(), "type", "function", "function", fn));
                }
                m.put("tool_calls", calls);
            }
            return m;
        }
        if (message instanceof ToolResponseMessage trm) {
            if (!trm.getResponses().isEmpty()) {
                ToolResponseMessage.ToolResponse r = trm.getResponses().get(0);
                return Map.of("role", "tool", "tool_call_id", r.id(), "content", r.responseData());
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "user");
        m.put("content", textOf(message));
        return m;
    }

    /**
     * 将多模态用户消息转成 OpenAI content 数组：
     * 文本 + 图片（URL 媒体保持 URL，字节媒体转 data URI 直传），失败则降级为纯文本。
     */
    public List<Map<String, Object>> toOpenAiMultimodalContent(UserMessage um) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("type", "text", "text", textOf(um)));
        if (um.getMedia() != null) {
            for (Media media : um.getMedia()) {
                try {
                    String mime = media.getMimeType() == null
                            ? "application/octet-stream" : media.getMimeType().toString();
                    String url;
                    Object data = media.getData();
                    if (data instanceof String s && (s.startsWith("http://") || s.startsWith("https://"))) {
                        url = s;
                    } else {
                        byte[] bytes = media.getDataAsByteArray();
                        url = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
                    }
                    parts.add(Map.of("type", "image_url", "image_url", Map.of("url", url)));
                } catch (Exception e) {
                    log.warn("历史图片媒体转 image_url 失败: {}", e.getMessage());
                }
            }
        }
        return parts;
    }

    private String textOf(Message message) {
        String text = message.getText();
        return text == null ? "" : text;
    }
}