package com.litlebro.agent.service.stream;

/**
 * 一轮中模型发起的单个工具调用。
 *
 * @param id        工具调用唯一标识（tool_call_id，回填结果时使用）
 * @param name      工具名（@Tool name，与 ToolRegistry 索引一致）
 * @param arguments 参数 JSON 字符串（保持原始文本，不做反序列化）
 */
public record ToolCall(String id, String name, String arguments) {
}