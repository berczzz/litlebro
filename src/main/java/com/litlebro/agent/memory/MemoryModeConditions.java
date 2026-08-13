package com.litlebro.agent.memory;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 记忆存储模式的条件判断，根据配置项 {@code app.memory.mode} 决定启用哪种存储实现。
 *
 * <p>三种模式：
 * <ul>
 *   <li>{@code memory}（默认）— 强制使用内存实现（InMemoryChatMemory + InMemorySessionMemoryService + SimpleVectorStore）</li>
 *   <li>{@code external} — 强制使用外部存储（Redis + Milvus）</li>
 *   <li>{@code auto} — 自动检测：当 Redis 和 Milvus 的开关均开启（{@code app.memory.redis.enabled} 与
 *   {@code app.memory.milvus.enabled} 为 true）时使用外部存储，否则回退到内存实现</li>
 * </ul>
 *
 * <p>auto 模式下通过显式开关判断，避免依赖 host 默认值产生误判：
 * 由于 Redis 自动配置要求 host 非空，yml 中默认提供 localhost，
 * 因此不能用 host 是否为空来判断是否启用外部存储。
 */
public class MemoryModeConditions {

    /**
     * 外部存储条件：模式为 external，或 auto 模式下 Redis 与 Milvus 开关均已开启。
     */
    public static class ExternalMemoryCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String mode = context.getEnvironment().getProperty("app.memory.mode", "auto");
            if ("external".equalsIgnoreCase(mode)) {
                return true;
            }
            if ("memory".equalsIgnoreCase(mode)) {
                return false;
            }
            // auto 模式：仅当 Redis 和 Milvus 的开关均为 true 时启用外部存储
            boolean redisEnabled = Boolean.parseBoolean(
                    context.getEnvironment().getProperty("app.memory.redis.enabled", "false"));
            boolean milvusEnabled = Boolean.parseBoolean(
                    context.getEnvironment().getProperty("app.memory.milvus.enabled", "false"));
            return redisEnabled && milvusEnabled;
        }
    }

    /**
     * 内存存储条件：与外部存储条件互斥，取反即可。
     */
    public static class InMemoryMemoryCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !new ExternalMemoryCondition().matches(context, metadata);
        }
    }
}
