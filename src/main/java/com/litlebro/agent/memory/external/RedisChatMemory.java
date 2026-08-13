package com.litlebro.agent.memory.external;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的短期聊天记忆实现，实现 Spring AI 的 {@link ChatMemory} 接口。
 *
 * <p>这是三层记忆架构中的短期记忆层（STM — Short-Term Memory），
 * 对应传统的多轮对话历史，供 Spring AI 框架自动管理对话上下文。
 *
 * <p>设计要点：
 * <ul>
 *   <li>使用 Redis 作为存储后端，支持分布式部署和会话共享</li>
 *   <li>TTL 设为 30 分钟，超时自动清理，避免内存泄漏</li>
 *   <li>MSG_AT_MEMORY_ADVISOR 直接使用此实现获取对话历史注入 LLM 上下文</li>
 *   <li>get 方法返回最近 lastN 条消息，而非全量，避免上下文过长</li>
 * </ul>
 *
 * <p>与中期记忆（SessionMemoryService）的区别：
 * 短期记忆面向 Spring AI 框架，存储 Message 对象；中期记忆面向
 * 会话管理，存储结构化 JSON 数据，TTL 更长（7 天）。
 */
public class RedisChatMemory implements ChatMemory {

    /** Redis 键前缀，stm 代表 Short-Term Memory */
    private static final String KEY_PREFIX = "agent:stm:";
    /** 短期记忆过期时间：30 分钟，超时自动删除 */
    private static final long TTL_MINUTES = 30;

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisChatMemory(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 向指定会话追加一批消息。
     * 先取出已有消息，追加新消息后再整体写回，并刷新 TTL。
     *
     * @param conversationId 会话 ID
     * @param messages       待追加的消息列表
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        String key = KEY_PREFIX + conversationId;
        List<Message> existing = getConversationMessages(conversationId);
        existing.addAll(messages);
        // 每次写入都刷新 TTL，保持活跃会话不过期
        redisTemplate.opsForValue().set(key, existing, TTL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * 获取指定会话的最近 lastN 条历史消息。
     * 如果消息总数不足 lastN，返回全部消息。
     *
     * @param conversationId 会话 ID
     * @param lastN          需要获取的最近消息数量
     * @return 最近 lastN 条消息的列表（新副本，不影响缓存）
     */
    @Override
    public List<Message> get(String conversationId, int lastN) {
        List<Message> messages = getConversationMessages(conversationId);
        int size = messages.size();
        if (size <= lastN) {
            return new ArrayList<>(messages);
        }
        // 截取最后 lastN 条消息，控制上下文长度
        return new ArrayList<>(messages.subList(size - lastN, size));
    }

    /**
     * 清空指定会话的所有消息记录。
     *
     * @param conversationId 会话 ID
     */
    @Override
    public void clear(String conversationId) {
        redisTemplate.delete(KEY_PREFIX + conversationId);
    }

    /**
     * 从 Redis 读取指定会话的完整消息列表。
     * 使用 @SuppressWarnings 抑制 unchecked 转换警告（Redis 存储的泛型信息在运行时擦除）。
     *
     * @param conversationId 会话 ID
     * @return 消息列表，不存在时返回空列表
     */
    @SuppressWarnings("unchecked")
    private List<Message> getConversationMessages(String conversationId) {
        String key = KEY_PREFIX + conversationId;
        Object value = redisTemplate.opsForValue().get(key);
        if (value instanceof List<?> list) {
            return (List<Message>) list;
        }
        return new ArrayList<>();
    }
}