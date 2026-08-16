package com.litlebro.agent.tool.external;

import com.litlebro.agent.tool.ToolDisabledStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 工具禁用状态 Redis 实现：禁用 ID 存 Hash {@code agent:tool:disabled}（field=toolId, value=1），
 * 无过期时间，重启不丢；通过 {@code enable} 显式恢复。由配置 {@code app.tool.store.type=redis} 装配。
 */
public class RedisToolDisabledStore implements ToolDisabledStore {

    private static final Logger log = LoggerFactory.getLogger(RedisToolDisabledStore.class);

    private static final String DISABLED_KEY = "agent:tool:disabled";

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisToolDisabledStore(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void disable(String toolId) {
        try {
            redisTemplate.opsForHash().put(DISABLED_KEY, toolId, "1");
        } catch (Exception e) {
            log.warn("工具禁用写入 Redis 失败 toolId={} 原因: {}", toolId, e.getMessage());
        }
    }

    @Override
    public void enable(String toolId) {
        try {
            redisTemplate.opsForHash().delete(DISABLED_KEY, toolId);
        } catch (Exception e) {
            log.warn("工具恢复写入 Redis 失败 toolId={} 原因: {}", toolId, e.getMessage());
        }
    }

    @Override
    public boolean isDisabled(String toolId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(DISABLED_KEY, toolId));
        } catch (Exception e) {
            log.warn("工具禁用状态读取 Redis 失败 toolId={} 原因: {}", toolId, e.getMessage());
            return false;
        }
    }

    @Override
    public Set<String> getDisabledIds() {
        Set<String> result = new HashSet<>();
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(DISABLED_KEY);
            for (Object key : entries.keySet()) {
                if (key != null) {
                    result.add(key.toString());
                }
            }
        } catch (Exception e) {
            log.warn("工具禁用列表读取 Redis 失败 原因: {}", e.getMessage());
        }
        return result;
    }
}