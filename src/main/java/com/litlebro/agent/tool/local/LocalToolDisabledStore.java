package com.litlebro.agent.tool.local;

import com.litlebro.agent.tool.ToolDisabledStore;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具禁用状态本地内存实现：进程内共享，重启丢失。
 * 由配置 {@code app.tool.store.type=local}（默认）装配。
 */
public class LocalToolDisabledStore implements ToolDisabledStore {

    private final Set<String> disabledIds = ConcurrentHashMap.newKeySet();

    @Override
    public void disable(String toolId) {
        disabledIds.add(toolId);
    }

    @Override
    public void enable(String toolId) {
        disabledIds.remove(toolId);
    }

    @Override
    public boolean isDisabled(String toolId) {
        return disabledIds.contains(toolId);
    }

    @Override
    public Set<String> getDisabledIds() {
        return Set.copyOf(disabledIds);
    }
}