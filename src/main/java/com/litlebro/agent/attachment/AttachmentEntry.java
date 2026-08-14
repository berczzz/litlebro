package com.litlebro.agent.attachment;

import java.nio.file.Path;

/**
 * 附件注册表条目，记录一个已上传附件在服务端的全部信息。
 *
 * <p>附件以 {@code fileId} 唯一标识，LLM 工具只接收 fileId、不接收文件路径，
 * 防止路径穿越与越权访问。条目包含：
 * <ul>
 *   <li>sessionId — 附件归属会话，工具调用时校验隔离</li>
 *   <li>rawPath — 原始文件落盘路径</li>
 *   <li>txtPath — 懒解析后生成的纯文本路径（pdf/word/excel 首次被工具访问时才解析），可为 null</li>
 *   <li>expiresAt — 过期时间戳（epoch millis），到期由清理任务删除原文件 + txt 文件</li>
 * </ul>
 *
 * @param fileId     附件唯一标识
 * @param sessionId  附件归属会话 ID
 * @param name       原始文件名（含扩展名）
 * @param mimeType   MIME 类型
 * @param rawPath    原始文件落盘路径
 * @param txtPath    懒解析文本文件路径，未解析为 null
 * @param size       文件字节数
 * @param expiresAt  过期时间戳（epoch millis）
 */
public record AttachmentEntry(
        String fileId,
        String sessionId,
        String name,
        String mimeType,
        Path rawPath,
        Path txtPath,
        long size,
        long expiresAt
) {

    /**
     * 返回携带指定 txtPath 的副本（懒解析完成后更新注册表条目）。
     *
     * @param newTxtPath 懒解析生成的文本文件路径
     * @return 更新后的条目副本
     */
    public AttachmentEntry withTxtPath(Path newTxtPath) {
        return new AttachmentEntry(fileId, sessionId, name, mimeType, rawPath, newTxtPath, size, expiresAt);
    }
}
