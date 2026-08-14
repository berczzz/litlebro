package com.litlebro.agent.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 附件清理任务：定时扫描附件注册表，删除过期附件的物理文件。
 *
 * <p>清理间隔由 {@code app.attachment.cleanup-interval-ms} 配置（默认 1 小时）。
 * 扫描基于注册表而非目录遍历，避免误删正在使用的文件。
 */
@Component
public class AttachmentCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(AttachmentCleanupTask.class);

    private final AttachmentStore attachmentStore;

    public AttachmentCleanupTask(AttachmentStore attachmentStore) {
        this.attachmentStore = attachmentStore;
    }

    @Scheduled(fixedDelayString = "${app.attachment.cleanup-interval-ms:3600000}")
    public void cleanupExpired() {
        try {
            attachmentStore.cleanupExpired();
        } catch (Exception e) {
            log.warn("附件清理任务执行失败 原因: {}", e.getMessage());
        }
    }
}