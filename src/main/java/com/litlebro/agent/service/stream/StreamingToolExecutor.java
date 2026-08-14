package com.litlebro.agent.service.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litlebro.agent.dto.StreamEvent;
import com.litlebro.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式场景下的工具执行器：组装工具 JSON Schema、按 LLM 返回的工具调用执行并推送事件。
 */
@Component
public class StreamingToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(StreamingToolExecutor.class);

    /** 工具结果推送给前端的最大字符数，过长时截断，避免事件体过大 */
    private static final int MAX_TOOL_RESULT_CHARS = 2000;

    /** 全部已注册工具的执行回调，按 LLM 返回的工具名（@Tool name）索引 */
    private final List<ToolCallback> toolCallbacks;
    /** 组装一次即缓存的工具 JSON Schema 列表 */
    private final List<Map<String, Object>> toolSchemas;
    private final ObjectMapper objectMapper;

    public StreamingToolExecutor(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.toolCallbacks = List.of(ToolCallbacks.from(toolRegistry.toToolArray()));
        this.toolSchemas = buildToolSchemas();
    }

    public List<Map<String, Object>> getToolSchemas() {
        return toolSchemas;
    }

    /**
     * 执行单个工具调用，并向前端推送调用与结果事件。
     *
     * @return 工具执行结果原文（完整回填给模型）
     */
    public String execute(ToolCall toolCall, SseEmitter emitter) {
        String name = toolCall.name();
        log.info("流式会话执行工具: {} args={}", name, toolCall.arguments());
        StreamEventSender.send(emitter, StreamEvent.TYPE_TOOL_CALL, Map.of("name", name, "arguments", toolCall.arguments()));

        String result;
        try {
            ToolCallback callback = findTool(name);
            if (callback == null) {
                result = "错误: 未找到工具 " + name;
            } else {
                // 无参工具模型可能返回空 arguments，需补成合法的空对象 JSON
                String args = (toolCall.arguments() == null || toolCall.arguments().isBlank()) ? "{}" : toolCall.arguments();
                result = callback.call(args);
            }
        } catch (Exception e) {
            log.warn("工具执行失败 name={} 原因: {}", name, e.getMessage());
            result = "工具执行失败: " + e.getMessage();
        }

        String display = (result == null || result.isBlank()) ? "(空结果)"
                : (result.length() > MAX_TOOL_RESULT_CHARS
                        ? result.substring(0, MAX_TOOL_RESULT_CHARS) + "\n...(已截断)"
                        : result);
        StreamEventSender.send(emitter, StreamEvent.TYPE_TOOL_RESULT, Map.of("name", name, "content", display));
        return result;
    }

    /**
     * 组装 assistant 的工具调用消息（OpenAI 协议回填格式）。
     * arguments 必须保持 JSON 字符串形态，不能先反序列化为对象。
     */
    public Map<String, Object> buildAssistantToolCallMessage(List<ToolCall> toolCalls) {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (ToolCall tc : toolCalls) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tc.name());
            function.put("arguments", tc.arguments());
            calls.add(Map.of("id", tc.id(), "type", "function", "function", function));
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", null);
        msg.put("tool_calls", calls);
        return msg;
    }

    /** 组装 tool 角色的执行结果消息 */
    public Map<String, Object> buildToolResultMessage(ToolCall toolCall, String result) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "tool");
        msg.put("tool_call_id", toolCall.id());
        msg.put("content", result == null ? "" : result);
        return msg;
    }

    private ToolCallback findTool(String name) {
        for (ToolCallback cb : toolCallbacks) {
            if (cb.getToolDefinition().name().equals(name)) {
                return cb;
            }
        }
        return null;
    }

    /**
     * 将全部工具的 ToolDefinition 转成 OpenAI 兼容的 tools 数组。
     * 工具参数 JSON Schema 从 {@link ToolDefinition#inputSchema()} 解析后原样透传。
     */
    private List<Map<String, Object>> buildToolSchemas() {
        List<Map<String, Object>> schemas = new ArrayList<>();
        for (ToolCallback cb : toolCallbacks) {
            ToolDefinition def = cb.getToolDefinition();
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", def.name());
            fn.put("description", def.description());
            try {
                fn.put("parameters", objectMapper.readTree(def.inputSchema()));
            } catch (JsonProcessingException e) {
                log.warn("工具参数 Schema 解析失败 name={} 原因: {}", def.name(), e.getMessage());
                fn.put("parameters", Map.of("type", "object", "properties", Map.of()));
            }
            schemas.add(Map.of("type", "function", "function", fn));
        }
        return schemas;
    }
}