package com.litlebro.agent.service.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litlebro.agent.dto.StreamEvent;
import com.litlebro.agent.tool.ToolRegistry;
import com.litlebro.agent.tool.skill.SkillTool;
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
 *
 * <p>工具集按请求动态决定：对话入口通过 {@link #beginRequest(boolean)} 传入本次是否包含技能工具，
 * 组装当前请求的工具回调与 Schema 到 ThreadLocal；工具调用按该请求的工具集解析，
 * 保证与阻塞式对话同一请求同一工具集（技能模块无可用技能时剔除技能工具）。
 */
@Component
public class StreamingToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(StreamingToolExecutor.class);

    /** 工具结果推送给前端的最大字符数，过长时截断，避免事件体过大 */
    private static final int MAX_TOOL_RESULT_CHARS = 2000;

    private final ToolRegistry toolRegistry;
    /** 全部已注册工具的执行回调（兜底），按 LLM 返回的工具名（@Tool name）索引 */
    private final List<ToolCallback> defaultToolCallbacks;
    /** 全部工具组装一次即缓存的工具 JSON Schema 列表（兜底） */
    private final List<Map<String, Object>> defaultToolSchemas;
    /** 当前请求的工具回调与 Schema（beginRequest 设置，clearRequest 清除） */
    private final ThreadLocal<RequestTools> requestTools = new ThreadLocal<>();
    private final ObjectMapper objectMapper;

    public StreamingToolExecutor(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.defaultToolCallbacks = List.of(ToolCallbacks.from(toolRegistry.toToolArray()));
        this.defaultToolSchemas = buildToolSchemas(defaultToolCallbacks);
    }

    /**
     * 开启一次请求的工具上下文：按本次是否包含技能工具过滤工具集并组装回调与 Schema。
     *
     * @param includeSkillTools 本次请求是否有可用技能（决定 load_skill/exec_skill/read_skill_file 是否进入工具列表）
     */
    public void beginRequest(boolean includeSkillTools) {
        Object[] tools = toolRegistry.toToolArray(t -> includeSkillTools || !(t instanceof SkillTool));
        List<ToolCallback> callbacks = List.of(ToolCallbacks.from(tools));
        requestTools.set(new RequestTools(callbacks, buildToolSchemas(callbacks)));
    }

    /** 结束请求的工具上下文，防止线程池复用导致工具集串号 */
    public void clearRequest() {
        requestTools.remove();
    }

    public List<Map<String, Object>> getToolSchemas() {
        RequestTools current = requestTools.get();
        return current != null ? current.schemas() : defaultToolSchemas;
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
                String args = toolCall.arguments().isBlank() ? "{}" : toolCall.arguments();
                result = callback.call(args);
            }
        } catch (Exception e) {
            log.warn("工具执行失败 name={} 原因: {}", name, e.getMessage());
            result = "工具执行失败: " + e.getMessage();
        }

        String display = result.isBlank() ? "(空结果)"
                : result.length() > MAX_TOOL_RESULT_CHARS
                        ? result.substring(0, MAX_TOOL_RESULT_CHARS) + "\n...(已截断)"
                        : result;
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
        RequestTools current = requestTools.get();
        List<ToolCallback> callbacks = current != null ? current.callbacks() : defaultToolCallbacks;
        for (ToolCallback cb : callbacks) {
            if (cb.getToolDefinition().name().equals(name)) {
                return cb;
            }
        }
        return null;
    }

    /**
     * 将工具的 ToolDefinition 转成 OpenAI 兼容的 tools 数组。
     * 工具参数 JSON Schema 从 {@link ToolDefinition#inputSchema()} 解析后原样透传。
     */
    private List<Map<String, Object>> buildToolSchemas(List<ToolCallback> callbacks) {
        List<Map<String, Object>> schemas = new ArrayList<>();
        for (ToolCallback cb : callbacks) {
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

    /** 当前请求的工具回调与 Schema */
    private record RequestTools(List<ToolCallback> callbacks, List<Map<String, Object>> schemas) {
    }
}