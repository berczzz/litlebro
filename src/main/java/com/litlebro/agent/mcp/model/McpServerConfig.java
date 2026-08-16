package com.litlebro.agent.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置模型（无 Lombok，手写 getter/setter，与项目约定一致）。
 *
 * <p>支持两种传输：
 * <ul>
 *   <li>{@code stdio}：本地进程通信，需要 {@code command} + {@code args}（可选）+ {@code env}（可选）</li>
 *   <li>{@code sse}：HTTP SSE 服务端，需要 {@code url}</li>
 * </ul>
 *
 * <p>可用性语义与技能一致：{@code global=true} 的服务器对所有会话直接可用；
 * 非 global 服务器需在对话请求中携带 {@code mcpServerIds} 按会话累加记录。
 */
public class McpServerConfig {

    /** 服务器唯一 ID（跨重启稳定，用于注册、记录与工具前缀命名） */
    private String serverId;
    /** 展示名称 */
    private String name;
    /** 功能描述（注入系统提示片段，帮助 LLM 理解该服务器能力） */
    private String description;
    /** 传输类型：stdio | sse */
    private String transport;
    /** stdio 启动命令（如 npx、python） */
    private String command;
    /** stdio 启动参数 */
    private List<String> args = new ArrayList<>();
    /** stdio 进程环境变量覆盖 */
    private Map<String, String> env = new LinkedHashMap<>();
    /** sse 服务端地址（SSE 端点） */
    private String url;
    /** 是否启用：false 时该服务器对任何会话都不可用 */
    private boolean enabled = true;
    /** 是否全局可用：true 时无需会话记录，所有会话直接可用 */
    private boolean global;

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args == null ? new ArrayList<>() : args;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env == null ? new LinkedHashMap<>() : env;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isGlobal() {
        return global;
    }

    public void setGlobal(boolean global) {
        this.global = global;
    }

    /**
     * 展示名：name 为空时回退 serverId。
     *
     * @return 展示名称
     */
    public String nameOrId() {
        return name == null || name.isBlank() ? serverId : name;
    }
}
