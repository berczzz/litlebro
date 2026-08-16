package com.litlebro.agent.controller;

import com.litlebro.agent.mcp.McpServerService;
import com.litlebro.agent.mcp.model.McpServerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * MCP 服务器管理 API。
 *
 * <p>端点（模块由 {@code app.mcp.enabled=true} 开启）：
 * <ul>
 *   <li>POST /api/agent/mcp/servers — 注册服务器（已存在拒绝），body 为 {@link McpServerConfig} JSON</li>
 *   <li>GET /api/agent/mcp/servers — 服务器列表</li>
 *   <li>GET /api/agent/mcp/servers/{serverId}/tools — 连接（懒）并列出该服务器工具，用于验证接入</li>
 *   <li>POST /api/agent/mcp/servers/{serverId}/enable — 启用服务器</li>
 *   <li>POST /api/agent/mcp/servers/{serverId}/disable — 禁用服务器（立即关闭连接）</li>
 *   <li>DELETE /api/agent/mcp/servers/{serverId} — 删除服务器（含会话记录，关闭连接）</li>
 *   <li>DELETE /api/agent/mcp/records/{sessionId} — 清空会话已记录的服务器（无过期时间，需显式清空）</li>
 * </ul>
 *
 * <p>对话时通过请求 mcpServerIds 启用非 global 服务器并自动累加记录到会话（同技能模型）；
 * 服务器工具经前缀化（{@code {serverId}_}）后并入统一工具池，可在 {@code GET /api/agent/tools}
 * 查看与按前缀 ID 禁用。
 */
@RestController
@RequestMapping("/api/agent/mcp")
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpController {

    private final McpServerService mcpServerService;

    public McpController(McpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
    }

    @PostMapping("/servers")
    public McpServerConfig register(@RequestBody McpServerConfig server) {
        return mcpServerService.register(server);
    }

    @GetMapping("/servers")
    public Map<String, Object> list() {
        return Map.of("servers", mcpServerService.list());
    }

    @GetMapping("/servers/{serverId}/tools")
    public Map<String, Object> listTools(@PathVariable String serverId) {
        return Map.of(
                "serverId", serverId,
                "tools", mcpServerService.getServerTools(serverId)
        );
    }

    @PostMapping("/servers/{serverId}/enable")
    public Map<String, Object> enable(@PathVariable String serverId) {
        mcpServerService.enable(serverId);
        return Map.of("serverId", serverId, "enabled", true);
    }

    @PostMapping("/servers/{serverId}/disable")
    public Map<String, Object> disable(@PathVariable String serverId) {
        mcpServerService.disable(serverId);
        return Map.of("serverId", serverId, "disabled", true);
    }

    @DeleteMapping("/servers/{serverId}")
    public Map<String, Object> delete(@PathVariable String serverId) {
        mcpServerService.delete(serverId);
        return Map.of("deleted", serverId);
    }

    @DeleteMapping("/records/{sessionId}")
    public Map<String, Object> clearSessionRecords(@PathVariable String sessionId) {
        mcpServerService.clearSessionRecords(sessionId);
        return Map.of("sessionId", sessionId, "cleared", true);
    }
}