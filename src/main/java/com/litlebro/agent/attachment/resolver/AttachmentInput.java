package com.litlebro.agent.attachment.resolver;

import org.springframework.web.multipart.MultipartFile;

/**
 * 附件来源输入，统一描述一次附件上传的原始数据。
 *
 * <p>兼容三种来源，由 {@link AttachmentResolverFactory} 按 {@code type} 选择对应解析策略：
 * <ul>
 *   <li>{@code base64} — JSON 请求体中携带的 base64 编码数据，data 为 base64 字符串</li>
 *   <li>{@code url} — 远程 URL 地址，data 为 http/https 链接，由服务端下载</li>
 *   <li>{@code multipart} — multipart/form-data 上传的文件，file 为 MultipartFile</li>
 * </ul>
 *
 * @param type     来源类型：base64 / url / multipart
 * @param name     原始文件名（含扩展名），可为空（由解析器推断）
 * @param mimeType 声明的内容类型，可为空（按扩展名推断）
 * @param data     base64 字符串或 URL，multipart 类型时为空
 * @param file     multipart 文件，其余类型为空
 */
public record AttachmentInput(
        String type,
        String name,
        String mimeType,
        String data,
        MultipartFile file
) {
}