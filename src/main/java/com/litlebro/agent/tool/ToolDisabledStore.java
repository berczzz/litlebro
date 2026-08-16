package com.litlebro.agent.tool;

import java.util.Set;

/**
 * 工具禁用状态存储抽象接口。
 *
 * <p>存储后端由配置 {@code app.tool.store.type} 决定（见 {@code config.AppConfig}）：
 * {@code local} 加载 {@code tool.local.LocalToolDisabledStore}（本地内存，默认，重启丢失），
 * {@code redis} 加载 {@code tool.external.RedisToolDisabledStore}（Redis，重启不丢）。
 * 新增存储实现只需实现本接口并在 {@code config.AppConfig} 中按配置装配。
 *
 * <p>禁用状态无过期时间，需显式调用 {@link #enable(String)} 恢复；未启用时默认全量可用。
 */
public interface ToolDisabledStore {

    /**
     * 禁用指定工具。
     *
     * @param toolId 工具 ID
     */
    void disable(String toolId);

    /**
     * 恢复指定工具。
     *
     * @param toolId 工具 ID
     */
    void enable(String toolId);

    /**
     * 指定工具当前是否处于禁用状态。
     *
     * @param toolId 工具 ID
     * @return true 表示已禁用
     */
    boolean isDisabled(String toolId);

    /**
     * 当前全部已禁用的工具 ID 集合。
     *
     * @return 已禁用工具 ID 集合
     */
    Set<String> getDisabledIds();
}