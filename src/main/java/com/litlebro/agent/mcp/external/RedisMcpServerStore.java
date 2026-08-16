package com.litlebro.agent.mcp.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litlebro.agent.mcp.model.McpServerConfig;
import com.litlebro.agent.mcp.store.McpServerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP 服务器存储 Redis 实现：服务器配置 JSON 存 Hash；会话记录存 Hash（无 TTL，重启不丢）。
 * 由配置 {@code app.mcp.store.type=redis} 装配。
 *
 * <p>实现要点：
 * <ul>
 *   <li>服务器定义存 Hash {@code agent:mcp:servers}（field=serverId, value=JSON）</li>
 *   <li>会话服务器记录存 Hash {@code agent:mcp:recorded:{sessionId}}（field=serverId, value=1），
 *       按请求 mcpServerIds 自动累加，不设过期时间；通过 {@code clearSessionRecords} 清空</li>
 * </ul>
 */
public class RedisMcpServerStore implements McpServerStore {

    private static final Logger log = LoggerFactory.getLogger(RedisMcpServerStore.class);

    private static final String SERVERS_KEY = "agent:mcp:servers";
    private static final String RECORDED_PREFIX = "agent:mcp:recorded:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisMcpServerStore(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(McpServerConfig server) {
        try {
            redisTemplate.opsForHash().put(SERVERS_KEY, server.getServerId(), objectMapper.writeValueAsString(server));
        } catch (Exception e) {
            log.warn("MCP 服务器写入 Redis 失败 serverId={} 原因: {}", server.getServerId(), e.getMessage());
        }
    }

    @Override
    public Optional<McpServerConfig> findById(String serverId) {
        try {
            Object value = redisTemplate.opsForHash().get(SERVERS_KEY, serverId);
            if (value == null) {
                return Optional.empty();
            }
            String json = value instanceof String s ? s : value.toString();
            return Optional.ofNullable(objectMapper.readValue(json, McpServerConfig.class));
        } catch (Exception e) {
            log.warn("MCP 服务器读取 Redis 失败 serverId={} 原因: {}", serverId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<McpServerConfig> findAll() {
        List<McpServerConfig> result = new ArrayList<>();
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(SERVERS_KEY);
            for (Object value : entries.values()) {
                if (value == null) {
                    continue;
                }
                try {
                    String json = value instanceof String s ? s : value.toString();
                    McpServerConfig server = objectMapper.readValue(json, McpServerConfig.class);
                    if (server != null) {
                        result.add(server);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.warn("MCP 服务器枚举 Redis 失败 原因: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public void delete(String serverId) {
        redisTemplate.opsForHash().delete(SERVERS_KEY, serverId);
    }

    @Override
    public void record(String sessionId, String serverId) {
        try {
            redisTemplate.opsForHash().put(RECORDED_PREFIX + sessionId, serverId, "1");
        } catch (Exception e) {
            log.warn("MCP 记录写入 Redis 失败 sessionId={} serverId={} 原因: {}", sessionId, serverId, e.getMessage());
        }
    }

    @Override
    public List<String> getRecordedServerIds(String sessionId) {
        List<String> result = new ArrayList<>();
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(RECORDED_PREFIX + sessionId);
            for (Object key : entries.keySet()) {
                if (key != null) {
                    result.add(key.toString());
                }
            }
        } catch (Exception e) {
            log.warn("MCP 记录读取 Redis 失败 sessionId={} 原因: {}", sessionId, e.getMessage());
        }
        return result;
    }

    @Override
    public void clearSessionRecords(String sessionId) {
        try {
            redisTemplate.delete(RECORDED_PREFIX + sessionId);
        } catch (Exception e) {
            log.warn("MCP 记录清空 Redis 失败 sessionId={} 原因: {}", sessionId, e.getMessage());
        }
    }
}
