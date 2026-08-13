package com.litlebro.agent.session.local;

import com.litlebro.agent.session.AbstractSessionManager;
import com.litlebro.agent.session.model.SessionMemory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话状态本地内存实现：ConcurrentHashMap 存储，进程内共享，重启丢失。
 * 由配置 {@code app.memory.stm.type=local}（默认）装配。
 */
public class LocalSessionManager extends AbstractSessionManager {

    private final Map<String, SessionMemory> store = new ConcurrentHashMap<>();

    @Override
    public SessionMemory get(String sessionId) {
        return store.get(sessionId);
    }

    @Override
    public void delete(String sessionId) {
        store.remove(sessionId);
    }

    @Override
    protected void save(String sessionId, SessionMemory memory) {
        store.put(sessionId, memory);
    }
}