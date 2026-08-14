package com.litlebro.agent.memory.external;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litlebro.agent.memory.MessageCodec;
import com.litlebro.agent.memory.model.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的短期聊天记忆实现，实现 Spring AI 的 {@link ChatMemory} 接口。
 *
 * <p>这是二层记忆架构中的短期记忆层（STM — Short-Term Memory），
 * 对应传统的多轮对话历史，供 Spring AI 框架自动管理对话上下文。
 *
 * <p>设计要点：
 * <ul>
 *   <li>存储统一记忆实体 {@link AgentMessage} 的 JSON 列表（与长期记忆共用同一对象），
 *       Redis 值序列化采用 StringRedisSerializer + Jackson，不再依赖 fastjson</li>
 *   <li>接口边界通过 {@link MessageCodec} 在 Spring AI 的 {@link Message} 与 {@link AgentMessage} 之间双向转换</li>
 *   <li>TTL 设为 30 分钟，超时自动清理，避免内存泄漏</li>
 *   <li>get 方法返回最近 lastN 条消息，而非全量，避免上下文过长</li>
 * </ul>
 */
public class RedisChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemory.class);

    /** Redis 键前缀，stm 代表 Short-Term Memory */
    private static final String KEY_PREFIX = "agent:stm:";
    /** 短期记忆过期时间：30 分钟，超时自动删除 */
    private static final long TTL_MINUTES = 30;

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final MessageCodec messageCodec;

    public RedisChatMemory(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper,
                           MessageCodec messageCodec) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.messageCodec = messageCodec;
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
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String key = KEY_PREFIX + conversationId;
        List<AgentMessage> existing = getAgentMessages(conversationId);
        existing.addAll(messageCodec.toAgentMessages(messages, conversationId));
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(existing), TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("短期记忆写入失败 conversationId={} 原因: {}", conversationId, e.getMessage());
        }
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
        List<AgentMessage> all = getAgentMessages(conversationId);
        int size = all.size();
        List<AgentMessage> sub;
        if (size <= lastN) {
            sub = new ArrayList<>(all);
        } else {
            // 截取最后 lastN 条消息，控制上下文长度
            sub = new ArrayList<>(all.subList(size - lastN, size));
        }
        return messageCodec.toMessages(sub);
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
     * 从 Redis 读取指定会话的完整消息列表（统一记忆实体形态）。
     * 旧格式脏数据（fastjson/JDK 序列化）反序列化失败时删除该键并返回空列表，自动容错。
     *
     * @param conversationId 会话 ID
     * @return 统一记忆实体列表，不存在时返回空列表
     */
    private List<AgentMessage> getAgentMessages(String conversationId) {
        String key = KEY_PREFIX + conversationId;
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                return new ArrayList<>();
            }
            String json = value instanceof String s ? s : value.toString();
            List<AgentMessage> list = objectMapper.readValue(json, new TypeReference<>() {
            });
            return list != null ? new ArrayList<>(list) : new ArrayList<>();
        } catch (Exception e) {
            log.warn("短期记忆反序列化失败，已清除该会话记录 conversationId={} 原因: {}", conversationId, e.getMessage());
            try {
                redisTemplate.delete(key);
            } catch (Exception ignored) {
            }
            return new ArrayList<>();
        }
    }
}
