package com.litlebro.agent.mcp;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * MCP 工具前缀适配器：把 {@code McpToolUtils} 产出的原始 ToolCallback 包一层，
 * 工具名加上 {@code {serverId}_} 前缀，避免不同服务器工具撞名，并统一纳入工具池。
 *
 * <p>这是"归一化"的关键一环：MCP 远端工具经本类包装后，与内置/技能工具一样，
 * 只是实现了 {@link ToolCallback} 契约的对象——下游只依赖 {@link #getToolDefinition()}
 * 与 {@link #call(String)}，不感知其来自 MCP。
 */
public final class McpToolCallback implements ToolCallback {

    /** 所属服务器 ID（工具前缀） */
    private final String serverId;
    /** 原始 MCP 工具回调（执行委托给它，内部走 JSON-RPC） */
    private final ToolCallback delegate;
    /** 前缀化后的工具定义（name 加前缀，description/inputSchema 原样） */
    private final ToolDefinition def;

    public McpToolCallback(String serverId, ToolCallback delegate) {
        this.serverId = serverId;
        this.delegate = delegate;
        ToolDefinition orig = delegate.getToolDefinition();
        String description = orig.description() == null ? "" : orig.description();
        String schema = orig.inputSchema() == null || orig.inputSchema().isBlank() ? "{}" : orig.inputSchema();
        this.def = ToolDefinition.builder()
                .name(serverId + "_" + orig.name())
                .description(description)
                .inputSchema(schema)
                .build();
    }

    /**
     * 所属服务器 ID（即工具前缀）。
     *
     * @return 服务器 ID
     */
    public String serverId() {
        return serverId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return def;
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }

    @Override
    public String getName() {
        return def.name();
    }

    @Override
    public String getDescription() {
        return def.description();
    }

    @Override
    public String getInputTypeSchema() {
        return def.inputSchema();
    }
}