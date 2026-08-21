package com.litlebro.agent.memory;

import com.litlebro.agent.memory.model.AgentMessage;

import java.util.List;

/**
 * 记忆存储抽象，定义长期记忆的通用读写接口，屏蔽底层存储实现。
 *
 * <p>记忆按会话（sessionId）隔离，一条记忆归属一个会话。
 * 存储对象为统一的 {@link AgentMessage} 实体。
 */
public interface MemoryStore {

    void save(AgentMessage agentMessage);

    List<AgentMessage> searchByCategory(String sessionId, String category, int limit);

    void delete(String sessionId, String memoryId);

    AgentMessage getById(String sessionId, String memoryId);
}
