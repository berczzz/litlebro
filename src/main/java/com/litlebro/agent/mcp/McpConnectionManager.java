package com.litlebro.agent.mcp;

import com.litlebro.agent.mcp.model.McpServerConfig;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.ClientMcpTransport;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MCP 连接管理器：懒连接 + 每服务器缓存 + 工具列表 TTL 刷新。
 *
 * <p>设计要点：
 * <ul>
 *   <li>懒连接：首次有会话需要某服务器工具时才建立连接（stdio 启动子进程 / sse 建连）</li>
 *   <li>每服务器一把锁：避免并发首用导致重复启动进程</li>
 *   <li>工具缓存：连接建立后把 listTools 结果缓存为前缀化 {@link McpToolCallback}，
 *       超过 {@code app.mcp.tool-cache-ttl-seconds} 后重新 listTools 刷新</li>
 *   <li>连接失败：抛出 {@link IllegalStateException}，由上层按服务器跳过，不影响其他服务器</li>
 *   <li>关闭：删除/禁用服务器或应用退出时 {@link McpSyncClient#close()}，stdio 同时结束子进程</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionManager.class);

    private final McpServerProperties props;
    /** serverId → 连接快照（客户端 + 前缀化工具回调 + 建立/刷新时间） */
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    /** serverId → 每服务器连接锁 */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public McpConnectionManager(McpServerProperties props) {
        this.props = props;
    }

    /**
     * 获取某服务器的工具回调；未连接时建立连接，缓存过期时刷新。
     *
     * @param serverId 服务器 ID
     * @param cfg      服务器配置（连接参数）
     * @return 前缀化工具回调列表
     */
    public List<McpToolCallback> getTools(String serverId, McpServerConfig cfg) {
        Connection conn = connections.get(serverId);
        if (conn == null) {
            return connect(serverId, cfg).callbacks();
        }
        if (isStale(conn)) {
            return refresh(serverId, cfg, conn).callbacks();
        }
        return conn.callbacks();
    }

    /**
     * 获取某服务器已连接（含缓存未过期）的工具回调；未连接时返回 null，供管理面轻量查询。
     *
     * @param serverId 服务器 ID
     * @return 前缀化工具回调列表；未连接返回 null
     */
    public List<McpToolCallback> getToolsIfConnected(String serverId) {
        Connection conn = connections.get(serverId);
        if (conn == null || isStale(conn)) {
            return null;
        }
        return conn.callbacks();
    }

    /**
     * 关闭某服务器的连接（stdio 结束子进程）并移除缓存。
     *
     * @param serverId 服务器 ID
     */
    public void close(String serverId) {
        Connection conn = connections.remove(serverId);
        if (conn != null) {
            closeClient(serverId, conn);
        }
        locks.remove(serverId);
    }

    /** 应用退出时关闭全部连接，避免遗留 stdio 子进程 */
    @PreDestroy
    public void closeAll() {
        for (Map.Entry<String, Connection> entry : connections.entrySet()) {
            closeClient(entry.getKey(), entry.getValue());
        }
        connections.clear();
        locks.clear();
    }

    private Connection connect(String serverId, McpServerConfig cfg) {
        Object lock = locks.computeIfAbsent(serverId, k -> new Object());
        synchronized (lock) {
            Connection existing = connections.get(serverId);
            if (existing != null && !isStale(existing)) {
                return existing;
            }
            if (existing != null) {
                closeClient(serverId, existing);
            }
            McpSyncClient client = null;
            try {
                ClientMcpTransport transport = buildTransport(cfg);
                client = McpClient.sync(transport)
                        .requestTimeout(Duration.ofMillis(props.getRequestTimeoutMs()))
                        .build();
                client.initialize();
                List<McpToolCallback> callbacks = toPrefixedCallbacks(serverId, client);
                Connection conn = new Connection(client, callbacks, System.currentTimeMillis());
                connections.put(serverId, conn);
                log.info("MCP 服务器连接成功 serverId={} transport={} tools={}", serverId, cfg.getTransport(), callbacks.size());
                return conn;
            } catch (Exception e) {
                if (client != null) {
                    closeClient(serverId, new Connection(client, List.of(), 0));
                }
                throw new IllegalStateException("MCP 服务器连接失败: " + serverId + " - " + e.getMessage(), e);
            }
        }
    }

    private Connection refresh(String serverId, McpServerConfig cfg, Connection conn) {
        Object lock = locks.computeIfAbsent(serverId, k -> new Object());
        synchronized (lock) {
            Connection current = connections.get(serverId);
            if (current != null && !isStale(current)) {
                return current;
            }
            try {
                List<McpToolCallback> callbacks = toPrefixedCallbacks(serverId, conn.client());
                Connection fresh = new Connection(conn.client(), callbacks, System.currentTimeMillis());
                connections.put(serverId, fresh);
                log.info("MCP 服务器工具已刷新 serverId={} tools={}", serverId, callbacks.size());
                return fresh;
            } catch (Exception e) {
                log.warn("MCP 服务器工具刷新失败，将重连 serverId={} 原因: {}", serverId, e.getMessage());
                return connect(serverId, cfg);
            }
        }
    }

    private List<McpToolCallback> toPrefixedCallbacks(String serverId, McpSyncClient client) {
        List<ToolCallback> raw = McpToolUtils.getToolCallbacksFromSyncClients(List.of(client));
        return raw.stream()
                .map(cb -> new McpToolCallback(serverId, cb))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private ClientMcpTransport buildTransport(McpServerConfig cfg) {
        if ("sse".equalsIgnoreCase(cfg.getTransport())) {
            if (cfg.getUrl() == null || cfg.getUrl().isBlank()) {
                throw new IllegalArgumentException("sse 传输必须配置 url: " + cfg.getServerId());
            }
            return new HttpClientSseClientTransport(cfg.getUrl());
        }
        // 默认 stdio
        if (cfg.getCommand() == null || cfg.getCommand().isBlank()) {
            throw new IllegalArgumentException("stdio 传输必须配置 command: " + cfg.getServerId());
        }
        ServerParameters.Builder builder = ServerParameters.builder(cfg.getCommand());
        if (cfg.getArgs() != null && !cfg.getArgs().isEmpty()) {
            builder.args(cfg.getArgs());
        }
        if (cfg.getEnv() != null && !cfg.getEnv().isEmpty()) {
            builder.env(cfg.getEnv());
        }
        return new StdioClientTransport(builder.build());
    }

    private boolean isStale(Connection conn) {
        long ttl = props.getToolCacheTtlSeconds() * 1000;
        return ttl > 0 && System.currentTimeMillis() - conn.refreshedAt() > ttl;
    }

    private void closeClient(String serverId, Connection conn) {
        try {
            conn.client().close();
            log.info("MCP 服务器连接已关闭 serverId={}", serverId);
        } catch (Exception e) {
            log.warn("MCP 服务器连接关闭失败 serverId={} 原因: {}", serverId, e.getMessage());
        }
    }

    /**
     * 连接快照：客户端 + 前缀化工具回调 + 建立/刷新时间。
     */
    private record Connection(McpSyncClient client, List<McpToolCallback> callbacks, long refreshedAt) {
    }
}