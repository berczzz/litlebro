package com.litlebro.agent.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP（Model Context Protocol）Client 模块配置，前缀 {@code app.mcp}。
 *
 * <p>模块由 {@code app.mcp.enabled=true} 开启（与技能模块 {@code app.skill.enabled} 同模式）：
 * 开启后装配 MCP 服务器存储、连接管理器与 REST 管理端点，支持通过 REST 注册
 * stdio / SSE 类型的 MCP 服务器，其工具经前缀化后并入统一工具池与对话工具集。
 */
@ConfigurationProperties(prefix = "app.mcp")
public class McpServerProperties {

    /**
     * 模块总开关：false 时不装配任何 MCP 组件
     */
    private boolean enabled;
    /**
     * 存储配置
     */
    private Store store = new Store();
    /**
     * 单次 MCP 请求超时（毫秒），如 initialize / listTools / callTool
     */
    private long requestTimeoutMs = 15000;
    /**
     * 工具列表缓存 TTL（秒）：过期后重新 listTools 刷新该服务器的工具集
     */
    private long toolCacheTtlSeconds = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Store getStore() {
        return store;
    }

    public void setStore(Store store) {
        this.store = store;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public long getToolCacheTtlSeconds() {
        return toolCacheTtlSeconds;
    }

    public void setToolCacheTtlSeconds(long toolCacheTtlSeconds) {
        this.toolCacheTtlSeconds = toolCacheTtlSeconds;
    }

    /**
     * MCP 服务器存储后端配置：{@code local}（默认，本地内存，重启丢失）/ {@code redis}（重启不丢）。
     */
    public static class Store {

        private String type = "local";

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }
}
