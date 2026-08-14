package com.litlebro.agent.dto;

/**
 * 附件 DTO，描述一次对话中携带的单个附件。
 *
 * <p>兼容三种来源，由 {@code dataType} 区分：
 * <ul>
 *   <li>{@code base64} — data 为 base64 编码字符串（可带 data URL 前缀）</li>
 *   <li>{@code url} — data 为 http/https 远程地址，由服务端下载</li>
 *   <li>{@code multipart} — 走 multipart/form-data 上传，JSON 中不携带 data</li>
 * </ul>
 *
 * @param name     原始文件名（含扩展名），可为空
 * @param mimeType 声明的内容类型，可为空（按扩展名推断）
 * @param dataType 附件来源类型：base64 / url / multipart
 * @param data     附件数据：base64 字符串或 URL，multipart 时为空
 */
public record FileAttachment(
        String name,
        String mimeType,
        String dataType,
        String data
) {
}