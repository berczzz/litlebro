package com.litlebro.agent.router;

/**
 * 路由 LLM 的结构化输出模型。
 *
 * <p>{@code target} 取值仅允许 none/memory/document/both（大小写不敏感），
 * 由 {@link RetrievalRouter} 校验并映射到 {@link RetrievalTarget}。
 */
public record RouterDecision(String target, String reason) {
}