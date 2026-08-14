package com.litlebro.agent.service.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litlebro.agent.dto.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容流式接口的直接调用客户端。
 *
 * <p>Spring AI 1.0.0-M6 的 OpenAI 集成不解析 DashScope 兼容模式的
 * {@code reasoning_content} 字段，且流式工具调用参数不跨 chunk 聚合，
 * 因此本客户端直接以 WebClient 对接 {@code /chat/completions} 流式接口，
 * 自行解析原始 SSE 分块（含思考过程）并聚合工具调用声明。
 */
@Component
public class OpenAiSseClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiSseClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final boolean enableThinking;
    private final String completionsPath;

    public OpenAiSseClient(ObjectMapper objectMapper,
                           @Value("${spring.ai.openai.base-url:https://dashscope.aliyuncs.com/compatible-mode}") String baseUrl,
                           @Value("${spring.ai.openai.api-key:}") String apiKey,
                           @Value("${spring.ai.openai.chat.options.model:qwen3.8-max}") String model,
                           @Value("${spring.ai.openai.chat.options.temperature:0.7}") double temperature,
                           @Value("${spring.ai.openai.chat.options.max-tokens:131072}") int maxTokens,
                           @Value("${app.stream.enable-thinking:false}") boolean enableThinking,
                           @Value("${app.stream.completions-path:/v1/chat/completions}") String completionsPath) {
        this.objectMapper = objectMapper;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.enableThinking = enableThinking;
        this.completionsPath = completionsPath;
        WebClient.Builder clientBuilder = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (apiKey != null && !apiKey.isBlank()) {
            clientBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.webClient = clientBuilder.build();
    }

    public String getModel() {
        return model;
    }

    /**
     * 执行一轮流式模型调用：读取原始 SSE 分块，按类型推送思考/回答增量，
     * 累积工具调用声明，返回本轮的文本、思考与工具调用列表。
     *
     * @param messages    完整消息列表（含历史与工具回填）
     * @param toolSchemas 已组装好的 OpenAI 兼容 tools 数组
     * @param emitter     客户端 SSE 连接
     */
    public TurnResult streamTurn(List<Map<String, Object>> messages, List<Map<String, Object>> toolSchemas, SseEmitter emitter)
            throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("tools", toolSchemas);
        body.put("temperature", temperature);
        if (maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }
        body.put("stream", true);
        if (enableThinking) {
            // DashScope OpenAI 兼容模式：开启思考（reasoning_content）输出
            body.put("enable_thinking", true);
        }

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, AccumToolCall> toolAcc = new LinkedHashMap<>();
        Map<String, Object> usage = new LinkedHashMap<>();

        Flux<String> dataFlux = webClient.post()
                .uri(completionsPath)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .takeUntil(line -> line != null && line.trim().equals("[DONE]"));

        for (String data : dataFlux.toIterable()) {
            String line = data == null ? "" : data.trim();
            if (line.isEmpty() || line.equals("[DONE]")) {
                continue;
            }
            JsonNode root = objectMapper.readTree(line);
            if (root.has("usage") && !root.get("usage").isNull()) {
                usage = parseUsage(root.get("usage"));
            }
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                continue;
            }
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.path("delta");

            // 思考过程（DashScope/DeepSeek 兼容模式在 delta.reasoning_content 返回）
            if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
                String r = delta.get("reasoning_content").asText("");
                if (!r.isEmpty()) {
                    reasoning.append(r);
                    StreamEventSender.send(emitter, StreamEvent.TYPE_REASONING, Map.of("content", r));
                }
            }
            // 最终回答增量
            if (delta.has("content") && !delta.get("content").isNull()) {
                String c = delta.get("content").asText("");
                if (!c.isEmpty()) {
                    content.append(c);
                    StreamEventSender.send(emitter, StreamEvent.TYPE_CONTENT, Map.of("content", c));
                }
            }
            // 工具调用声明（arguments 按 index 分片累积）
            if (delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
                for (JsonNode tc : delta.get("tool_calls")) {
                    int index = tc.path("index").asInt(0);
                    AccumToolCall acc = toolAcc.computeIfAbsent(index, k -> new AccumToolCall());
                    if (tc.has("id") && !tc.get("id").isNull()) {
                        acc.id = tc.get("id").asText();
                    }
                    JsonNode fn = tc.path("function");
                    if (fn.has("name") && !fn.get("name").isNull()) {
                        acc.name = fn.get("name").asText();
                    }
                    if (fn.has("arguments") && !fn.get("arguments").isNull()) {
                        acc.arguments.append(fn.get("arguments").asText());
                    }
                }
            }
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        // 只要流式过程中累积到工具调用声明即视为本轮到工具阶段，
        // 不苛求 finish_reason 一定为 tool_calls（个别兼容网关以 stop 收尾）
        if (!toolAcc.isEmpty()) {
            for (AccumToolCall acc : toolAcc.values()) {
                if (acc.name == null || acc.name.isEmpty()) {
                    continue;
                }
                toolCalls.add(new ToolCall(acc.id, acc.name, acc.arguments.toString()));
            }
        }
        return new TurnResult(content.toString(), reasoning.toString(), toolCalls, usage);
    }

    private Map<String, Object> parseUsage(JsonNode usage) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("promptTokens", usage.path("prompt_tokens").asInt(0));
        m.put("completionTokens", usage.path("completion_tokens").asInt(0));
        m.put("totalTokens", usage.path("total_tokens").asInt(0));
        return m;
    }

    /** 一轮模型调用的解析结果 */
    public record TurnResult(
            String content,
            String reasoning,
            List<ToolCall> toolCalls,
            Map<String, Object> usage
    ) {
    }

    /** 累积的单个工具调用声明（arguments 按 index 分片追加） */
    private static class AccumToolCall {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}