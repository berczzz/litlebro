package com.litlebro.agent.memory;

import com.litlebro.agent.memory.model.Memory;

import java.util.List;

/**
 * 记忆存储抽象，定义长期记忆的通用读写接口，屏蔽底层存储实现。
 *
 * <p>记忆按会话（sessionId）隔离，一条记忆归属一个会话。
 */
public interface MemoryStore {

    void save(Memory memory);

    List<Memory> searchByCategory(String sessionId, String category, int limit);

    void delete(String memoryId);

    Memory getById(String memoryId);
}