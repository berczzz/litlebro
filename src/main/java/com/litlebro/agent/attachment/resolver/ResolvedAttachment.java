package com.litlebro.agent.attachment.resolver;

/**
 * 附件解析结果，是各种来源（base64/url/multipart）解析后的统一字节形态。
 *
 * @param name     原始文件名（含扩展名）
 * @param mimeType 解析出的内容类型
 * @param bytes    附件字节内容
 */
public record ResolvedAttachment(
        String name,
        String mimeType,
        byte[] bytes
) {
}