package com.litlebro.agent.mcp.store;

import com.litlebro.agent.mcp.model.McpServerConfig;

import java.util.List;
import java.util.Optional;

/**
 * MCP 服务器存储抽象接口：服务器定义 CRUD + 会话服务器记录。
 *
 * <p>存储后端由配置 {@code app.mcp.store.type} 决定（见 {@code config.AppConfig}）：
 * {@code local} 加载 {@code mcp.local.LocalMcpServerStore}（本地内存，默认），
 * {@code redis} 加载 {@code mcp.external.RedisMcpServerStore}（Redis，重启不丢）。
 * 新增存储实现只需实现本接口并在 {@code config.AppConfig} 中按配置装配。
 *
 * <p>仅持久化服务器配置与会话记录；运行时连接（McpSyncClient）是进程内内存态，不持久化。
 */
public interface McpServerStore {

    /**
     * 保存服务器配置（按 serverId 覆盖）。
     *
     * @param server 服务器配置
     */
    void save(McpServerConfig server);

    /**
     * 按 serverId 查询服务器。
     *
     * @param serverId 服务器 ID
     * @return 服务器配置；不存在返回空 Optional
     */
    Optional<McpServerConfig> findById(String serverId);

    /**
     * 枚举全部服务器。
     *
     * @return 全部服务器配置
     */
    List<McpServerConfig> findAll();

    /**
     * 删除服务器（含其全部会话记录）。
     *
     * @param serverId 服务器 ID
     */
    void delete(String serverId);

    /**
     * 记录会话使用过某服务器（按请求 mcpServerIds 自动累加，无过期时间）。
     *
     * @param sessionId 会话 ID
     * @param serverId  服务器 ID
     */
    void record(String sessionId, String serverId);

    /**
     * 查询会话已记录的服务器 ID 列表（global 服务器无需记录，不在此列表内）。
     *
     * @param sessionId 会话 ID
     * @return 已记录服务器 ID 列表
     */
    List<String> getRecordedServerIds(String sessionId);

    /**
     * 清空会话的全部服务器记录。
     *
     * @param sessionId 会话 ID
     */
    void clearSessionRecords(String sessionId);
}
