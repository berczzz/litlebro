package com.litlebro.agent.attachment.local;

import com.litlebro.agent.attachment.AttachmentEntry;
import com.litlebro.agent.attachment.AttachmentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 附件注册表本地内存实现：ConcurrentHashMap 存储，进程内共享，重启丢失。
 * 由配置 {@code app.attachment.registry.type=local}（默认）装配。
 */
public class LocalAttachmentRegistry implements AttachmentRegistry {

    private static final Logger log = LoggerFactory.getLogger(LocalAttachmentRegistry.class);

    /** fileId → 附件条目 */
    private final Map<String, AttachmentEntry> entries = new ConcurrentHashMap<>();

    @Override
    public void register(AttachmentEntry entry) {
        entries.put(entry.fileId(), entry);
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
}
