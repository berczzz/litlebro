package com.litlebro.agent.dto;

import java.util.Map;

/**
 * 流式对话 SSE 事件 DTO。
 *
 * <p>每个事件通过 {@code data: <json>} 推送给前端，客户端按 type 区分处理：
 * <ul>
 *   <li>{@code start} — 会话开始，含 sessionId 与模型名</li>
 *   <li>{@code reasoning} — 思考过程增量（模型思考中逐段输出）</li>
 *   <li>{@code tool_call} — 工具调用开始，含工具名与参数</li>
 *   <li>{@code tool_result} — 工具执行结果（内容过长时截断展示）</li>
 *   <li>{@code content} — 最终回答增量</li>
 *   <li>{@code error} — 处理异常</li>
 *   <li>{@code done} — 一轮对话结束，含 usage 与本次调用的工具列表</li>
 * </ul>
 *
 * @param type 事件类型，见本类的 TYPE_* 常量
 * @param data 事件负载，字段随类型不同
 */
public record StreamEvent(
        String type,
        Map<String, Object> data
) {

    public static final String TYPE_START = "start";
    public static final String TYPE_REASONING = "reasoning";
    public static final String TYPE_TOOL_CALL = "tool_call";
    public static final String TYPE_TOOL_RESULT = "tool_result";
    public static final String TYPE_CONTENT = "content";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_DONE = "done";
}
