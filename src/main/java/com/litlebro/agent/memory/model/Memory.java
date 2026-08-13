package com.litlebro.agent.memory.model;

import com.litlebro.agent.common.ChatContentRole;
import com.litlebro.agent.common.Constant;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 记忆实体：一条可检索、可追溯的语义记忆记录。
 *
 * <p>字段语义：
 * <ul>
 *   <li>id — 记忆唯一标识</li>
 *   <li>sessionId — 记忆归属会话，检索时按此过滤实现会话隔离</li>
 *   <li>category — 记忆分类（如身份信息、偏好习惯、会话摘要）</li>
 *   <li>content — 记忆正文，也是向量化的文本</li>
 *   <li>metadata — 可扩展元数据（userId、source、confidence、updatedAt 等），
 *       为将来记忆整合（合并/关联）预留扩展位</li>
 * </ul>
 */
public record Memory(
        String id,
        String sessionId,
        String category,
        String content,
        String role, // ChatContentRole.user/ChatContentRole.assistant
        Map<String, Object> metadata
) {

    public Memory {
        if (metadata == null) {
            metadata = Map.of();
        }
    }

    public static Memory toMemory(Message message, String sessionId) {
        return new Memory(
                UUID.randomUUID().toString().replace("-", ""),
                sessionId,
                Constant.CATEGORY_CHAT,
                message.getText(),
                Objects.equals(message.getMessageType(), MessageType.USER) ?
                        ChatContentRole.USER_ROLE : ChatContentRole.ASSISTANT_ROLE,
                Map.of()
        );
    }
}