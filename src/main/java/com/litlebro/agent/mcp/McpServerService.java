package com.litlebro.agent.mcp;

import com.litlebro.agent.mcp.model.McpServerConfig;
import com.litlebro.agent.mcp.store.McpServerStore;
import com.litlebro.agent.tool.ToolDisabledStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP 服务器核心业务服务：注册/删除/列表/校验/会话记录/工具回调解析/系统提示片段。
 *
 * <p>与技能模块同构：
 * <ul>
 *   <li>可用性模型：可用服务器 = {@code global} 服务器 ∪ 会话已记录服务器 ∪ 请求 mcpServerIds
 *       （请求携带时累加记录，无过期时间；未注册/未启用的 serverId 抛 {@link IllegalArgumentException} 转 400）</li>
 *   <li>下发面：{@link #getSessionCallbacks} 按会话解析可用服务器并懒连接，返回前缀化 ToolCallback</li>
 *   <li>管理面：{@link #getToolInfoList} / {@link #isServerTool} 供统一工具池（ToolRegistry）合并展示与禁用</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpServerService {

    private static final Logger log = LoggerFactory.getLogger(McpServerService.class);

    private final McpServerStore store;
    private final McpConnectionManager connectionManager;
    private final ToolDisabledStore disabledStore;

    public McpServerService(McpServerStore store, McpConnectionManager connectionManager,
                            ToolDisabledStore disabledStore) {
        this.store = store;
        this.connectionManager = connectionManager;
        this.disabledStore = disabledStore;
    }

    // ==================== 注册管理 ====================

    /**
     * 注册 MCP 服务器：校验定义，已存在则拒绝（应用层转 409）。
     * 注册只持久化配置，不建立连接（懒连接）。
     *
     * @param server 服务器配置
     * @return 注册后的服务器配置
     */
    public McpServerConfig register(McpServerConfig server) {
        validateDefinition(server);
        if (store.findById(server.getServerId()).isPresent()) {
            throw new IllegalArgumentException("MCP 服务器已存在: " + server.getServerId());
        }
        store.save(server);
        log.info("MCP 服务器注册成功 serverId={} transport={} global={}", server.getServerId(), server.getTransport(), server.isGlobal());
        return server;
    }

    /**
     * 删除服务器（含其全部会话记录），并关闭已建立的连接（stdio 结束子进程）。
     *
     * @param serverId 服务器 ID
     */
    public void delete(String serverId) {
        requireServer(serverId);
        store.delete(serverId);
        connectionManager.close(serverId);
        log.info("MCP 服务器已删除 serverId={}", serverId);
    }

    /**
     * 枚举全部服务器。
     *
     * @return 服务器配置列表
     */
    public List<McpServerConfig> list() {
        return store.findAll();
    }

    /**
     * 启用服务器：恢复为对会话可用，下次使用懒重连。
     *
     * @param serverId 服务器 ID
     */
    public void enable(String serverId) {
        McpServerConfig cfg = requireServer(serverId);
        cfg.setEnabled(true);
        store.save(cfg);
        log.info("MCP 服务器已启用 serverId={}", serverId);
    }

    /**
     * 禁用服务器：立即关闭连接并从可用集合剔除（会话记录保留，重新启用后可继续使用）。
     *
     * @param serverId 服务器 ID
     */
    public void disable(String serverId) {
        McpServerConfig cfg = requireServer(serverId);
        cfg.setEnabled(false);
        store.save(cfg);
        connectionManager.close(serverId);
        log.info("MCP 服务器已禁用 serverId={}", serverId);
    }

    /**
     * 清空会话的全部 MCP 服务器记录（后续请求将只使用 global 服务器，除非重新携带 mcpServerIds）。
     *
     * @param sessionId 会话 ID
     */
    public void clearSessionRecords(String sessionId) {
        store.clearSessionRecords(sessionId);
    }

    private void validateDefinition(McpServerConfig server) {
        if (server == null) {
            throw new IllegalArgumentException("MCP 服务器配置不能为空");
        }
        String serverId = server.getServerId();
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("serverId 不能为空");
        }
        if (serverId.contains("/") || serverId.contains("\\") || serverId.contains("..")) {
            throw new IllegalArgumentException("serverId 不能包含路径分隔符或 ..");
        }
        String transport = server.getTransport();
        if (transport == null || (!"stdio".equalsIgnoreCase(transport) && !"sse".equalsIgnoreCase(transport))) {
            throw new IllegalArgumentException("transport 必须是 stdio 或 sse: " + transport);
        }
    }

    private McpServerConfig requireServer(String serverId) {
        return store.findById(serverId)
                .orElseThrow(() -> new IllegalArgumentException("MCP 服务器不存在: " + serverId));
    }

    // ==================== 会话可用性与回调 ====================

    /**
     * 同步校验请求的 MCP 服务器名单：未注册或未启用的 serverId 抛 {@link IllegalArgumentException}（应用层转 400）。
     * 不建立连接、不记录会话，幂等，供流式端点在控制器线程同步校验（异步线程无法再映射 HTTP 状态码）。
     *
     * @param sessionId        会话 ID（可为 null）
     * @param requestedServerIds 请求声明的服务器 ID 列表（可为空）
     */
    public void validate(String sessionId, List<String> requestedServerIds) {
        List<String> requested = requestedServerIds == null ? List.of() : requestedServerIds;
        List<String> invalid = new ArrayList<>();
        for (String id : requested) {
            McpServerConfig cfg = store.findById(id).orElse(null);
            if (cfg == null || !cfg.isEnabled()) {
                invalid.add(id);
            }
        }
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException("MCP 服务器不存在或未启用: " + String.join(", ", invalid));
        }
    }

    /**
     * 解析本次请求的可用 MCP 服务器工具回调：校验 + 累加记录 + 连接 + 过滤禁用工具。
     *
     * <p>副作用：请求携带非空 mcpServerIds 时，将其累加记录到该会话（后续请求无需再携带）。
     * 某服务器连接失败仅跳过该服务器并记日志，不影响其他服务器与本请求。
     *
     * @param sessionId        会话 ID（可为 null）
     * @param requestedServerIds 请求声明的服务器 ID 列表（可为空）
     * @return 前缀化 ToolCallback 列表（已过滤 ToolDisabledStore 禁用的工具）
     */
    public List<ToolCallback> getSessionCallbacks(String sessionId, List<String> requestedServerIds) {
        List<String> requested = requestedServerIds == null ? List.of() : requestedServerIds;
        validate(sessionId, requested);
        if (sessionId != null && !sessionId.isBlank() && !requested.isEmpty()) {
            for (String id : requested) {
                store.record(sessionId, id);
            }
        }
        Set<String> recorded = new HashSet<>(sessionId == null ? List.of() : store.getRecordedServerIds(sessionId));
        List<ToolCallback> result = new ArrayList<>();
        for (McpServerConfig cfg : store.findAll()) {
            if (!cfg.isEnabled()) {
                continue;
            }
            if (!cfg.isGlobal() && !recorded.contains(cfg.getServerId())) {
                continue;
            }
            try {
                for (McpToolCallback cb : connectionManager.getTools(cfg.getServerId(), cfg)) {
                    if (!disabledStore.isDisabled(cb.getToolDefinition().name())) {
                        result.add(cb);
                    }
                }
            } catch (Exception e) {
                log.warn("MCP 服务器工具获取失败，本轮跳过 serverId={} 原因: {}", cfg.getServerId(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * 生成系统提示 MCP 片段：本次可用服务器（global ∪ 记录）的 name + description。
     * 空则返回空串，供对话服务按需注入，帮助 LLM 理解可用 MCP 服务器能力。
     *
     * @param sessionId 会话 ID
     * @return MCP 提示文本（可能为空串）
     */
    public String getSystemPromptFragment(String sessionId) {
        List<McpServerConfig> usable = new ArrayList<>();
        Set<String> recorded = new HashSet<>(sessionId == null ? List.of() : store.getRecordedServerIds(sessionId));
        for (McpServerConfig cfg : store.findAll()) {
            if (cfg.isEnabled() && (cfg.isGlobal() || recorded.contains(cfg.getServerId()))) {
                usable.add(cfg);
            }
        }
        if (usable.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (McpServerConfig cfg : usable) {
            String desc = cfg.getDescription() == null ? "" : cfg.getDescription();
            lines.add("- " + cfg.nameOrId() + (desc.isBlank() ? "" : ": " + desc));
        }
        return "可用 MCP 服务器（其工具以 " + "`{服务器ID}_` 前缀命名，可直接调用）:\n" + String.join("\n", lines);
    }

    // ==================== 统一工具池管理面 ====================

    /**
     * 查询单台服务器当前可用的工具列表（懒连接；用于 {@code GET /api/agent/mcp/servers/{serverId}/tools} 验证接入）。
     *
     * @param serverId 服务器 ID
     * @return 工具信息列表（id 为前缀化工具名）
     */
    public List<Map<String, Object>> getServerTools(String serverId) {
        McpServerConfig cfg = requireServer(serverId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (McpToolCallback cb : connectionManager.getTools(serverId, cfg)) {
            result.add(toolInfo(cb));
        }
        return result;
    }

    /**
     * 已连接服务器上全部 MCP 工具的信息列表，供统一工具池（{@code GET /api/agent/tools}）合并展示。
     * 未连接（尚未被任何会话使用）的服务器不展示其工具——连接是懒的，避免管理查询拉起子进程。
     *
     * @return 工具信息列表（id 为前缀化工具名，含 enabled 状态）
     */
    public List<Map<String, Object>> getToolInfoList() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (McpServerConfig cfg : store.findAll()) {
            if (!cfg.isEnabled()) {
                continue;
            }
            List<McpToolCallback> callbacks = connectionManager.getToolsIfConnected(cfg.getServerId());
            if (callbacks == null) {
                continue;
            }
            for (McpToolCallback cb : callbacks) {
                result.add(toolInfo(cb));
            }
        }
        return result;
    }

    /**
     * 指定工具 ID 是否是已连接 MCP 服务器上的工具（前缀化命名）。
     *
     * @param toolId 工具 ID（前缀化工具名）
     * @return true 表示是 MCP 工具
     */
    public boolean isServerTool(String toolId) {
        for (McpServerConfig cfg : store.findAll()) {
            if (!cfg.isEnabled()) {
                continue;
            }
            List<McpToolCallback> callbacks = connectionManager.getToolsIfConnected(cfg.getServerId());
            if (callbacks == null) {
                continue;
            }
            for (McpToolCallback cb : callbacks) {
                if (cb.getToolDefinition().name().equals(toolId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, Object> toolInfo(McpToolCallback cb) {
        ToolDefinition def = cb.getToolDefinition();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", def.name());
        entry.put("name", def.name());
        entry.put("description", def.description());
        entry.put("enabled", !disabledStore.isDisabled(def.name()));
        return entry;
    }
}