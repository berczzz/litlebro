package com.litlebro.agent.attachment.resolver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * multipart 附件解析策略：从 multipart/form-data 上传的 {@link MultipartFile} 读取字节。
 *
 * <p>文件名与内容类型优先取 MultipartFile 自身属性；大小限制以
 * {@code app.attachment.max-size} 兜底（Spring 全局 multipart 配置是前置防线）。
 */
@Component
public class MultipartAttachmentResolver implements AttachmentResolver {

    private final long maxSizeBytes;

    public MultipartAttachmentResolver(@Value("${app.attachment.max-size:20971520}") long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }

    @Override
    public String type() {
        return "multipart";
    }

    @Override
    public ResolvedAttachment resolve(AttachmentInput input) throws IOException {
        if (input.file() == null || input.file().isEmpty()) {
            throw new IOException("multipart 附件为空");
        }
        byte[] bytes = input.file().getBytes();
        if (bytes.length > maxSizeBytes) {
            throw new IOException("附件超过大小限制（" + bytes.length + " > " + maxSizeBytes + "）");
        }
        String name = input.file().getOriginalFilename();
        if (name == null || name.isBlank()) {
            name = input.name() != null && !input.name().isBlank() ? input.name() : "attachment.bin";
        }
        String mimeType = input.file().getContentType();
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = input.mimeType();
        }
        return new ResolvedAttachment(name, mimeType, bytes);
    }
}