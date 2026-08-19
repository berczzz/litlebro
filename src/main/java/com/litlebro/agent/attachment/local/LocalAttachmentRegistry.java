package com.litlebro.agent.attachment.local;

import com.litlebro.agent.attachment.AttachmentEntry;
import com.litlebro.agent.attachment.AttachmentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 附件注册表本地内存实现：ConcurrentHashMap 存储，进程内共享，重启丢失。
 * 由配置 {@code app.attachment.registry.type=local}（默认）装配。
 *
 * <p>额外维护「sessionId → fileId 集合」二级索引，{@link #bySession} 直接命中
 * 该会话的附件而不必扫描全表。
 */
public class LocalAttachmentRegistry implements AttachmentRegistry {

    private static final Logger log = LoggerFactory.getLogger(LocalAttachmentRegistry.class);

    /** fileId → 附件条目 */
    private final Map<String, AttachmentEntry> entries = new ConcurrentHashMap<>();

    /** sessionId → 该会话名下 fileId 集合（register/remove 时同步维护） */
    private final Map<String, Set<String>> sessionIndex = new ConcurrentHashMap<>();

    @Override
    public void register(AttachmentEntry entry) {
        entries.put(entry.fileId(), entry);
        sessionIndex.computeIfAbsent(entry.sessionId(), k -> ConcurrentHashMap.newKeySet()).add(entry.fileId());
        log.debug("附件已注册 fileId={} name={} expiresAt={}", entry.fileId(), entry.name(), entry.expiresAt());
    }

    @Override
    public AttachmentEntry get(String fileId) {
        return entries.get(fileId);
    }

    @Override
    public AttachmentEntry remove(String fileId) {
        AttachmentEntry removed = entries.remove(fileId);
        if (removed != null) {
            Set<String> ids = sessionIndex.get(removed.sessionId());
            if (ids != null) {
                ids.remove(fileId);
            }
            log.debug("附件注册表项已移除 fileId={}", fileId);
        }
        return removed;
    }

    @Override
    public List<AttachmentEntry> all() {
        return new ArrayList<>(entries.values());
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public List<AttachmentEntry> bySession(String sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        Set<String> ids = sessionIndex.get(sessionId);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<AttachmentEntry> result = new ArrayList<>(ids.size());
        for (String fileId : ids) {
            AttachmentEntry entry = entries.get(fileId);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }
}
