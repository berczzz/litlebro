package com.litlebro.agent.mcp.local;

import com.litlebro.agent.mcp.model.McpServerConfig;
import com.litlebro.agent.mcp.store.McpServerStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 服务器存储本地内存实现：ConcurrentHashMap 存储，进程内共享，重启丢失。
 * 由配置 {@code app.mcp.store.type=local}（默认）装配。
 *
 * <p>会话服务器记录按请求 mcpServerIds 自动累加（无过期时间），本地实现重启后随内存一起丢失。
 */
public class LocalMcpServerStore implements McpServerStore {

    private final Map<String, McpServerConfig> servers = new ConcurrentHashMap<>();
    /** sessionId → 已记录服务器 ID 集合（Set 语义去重） */
    private final Map<String, Map<String, Boolean>> recorded = new ConcurrentHashMap<>();

    @Override
    public void save(McpServerConfig server) {
        servers.put(server.getServerId(), server);
    }

    @Override
    public Optional<McpServerConfig> findById(String serverId) {
        return Optional.ofNullable(servers.get(serverId));
    }

    @Override
    public List<McpServerConfig> findAll() {
        return new ArrayList<>(servers.values());
    }

    @Override
    public void delete(String serverId) {
        servers.remove(serverId);
        recorded.forEach((session, set) -> set.remove(serverId));
    }

    @Override
    public void record(String sessionId, String serverId) {
        recorded.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(serverId, Boolean.TRUE);
    }

    @Override
    public List<String> getRecordedServerIds(String sessionId) {
        Map<String, Boolean> set = recorded.get(sessionId);
        if (set == null) {
            return List.of();
        }
        return new ArrayList<>(set.keySet());
    }

    @Override
    public void clearSessionRecords(String sessionId) {
        recorded.remove(sessionId);
    }
}
