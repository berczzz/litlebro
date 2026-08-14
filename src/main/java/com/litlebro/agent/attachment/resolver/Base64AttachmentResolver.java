package com.litlebro.agent.attachment.resolver;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Base64;
import java.util.Locale;

/**
 * base64 附件解析策略：将 JSON 请求体中携带的 base64 字符串解码为字节。
 *
 * <p>兼容两种输入形态：裸 base64 字符串，或 data URL（形如
 * {@code data:image/png;base64,xxxx}）。文件名缺省时自动生成
 * {@code attachment.<ext>}，扩展名从 data URL 的 MIME 推断。
 */
@Component
public class Base64AttachmentResolver implements AttachmentResolver {

    @Override
    public String type() {
        return "base64";
    }

    @Override
    public ResolvedAttachment resolve(AttachmentInput input) throws IOException {
        String data = input.data() != null ? input.data().trim() : "";
        if (data.isEmpty()) {
            throw new IOException("base64 附件内容为空");
        }

        String mimeType = input.mimeType();
        String payload = data;
        if (data.startsWith("data:")) {
            // 形如 data:image/png;base64,xxxx 的 data URL，解析出 MIME 与真实负载
            int comma = data.indexOf(',');
            if (comma >= 0) {
                String meta = data.substring(5, comma);
                int semi = meta.indexOf(';');
                String declaredMime = (semi >= 0 ? meta.substring(0, semi) : meta).trim();
                if (declaredMime.startsWith("image/") || declaredMime.startsWith("text/")
                        || declaredMime.startsWith("application/")) {
                    mimeType = declaredMime;
                }
                payload = data.substring(comma + 1);
            }
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new IOException("base64 解码失败: " + e.getMessage(), e);
        }

        String name = input.name();
        if (name == null || name.isBlank()) {
            name = "attachment" + extensionOf(mimeType);
        }
        return new ResolvedAttachment(name, mimeType, bytes);
    }

    private String extensionOf(String mimeType) {
        if (mimeType == null) {
            return ".bin";
        }
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/bmp" -> ".bmp";
            case "application/pdf" -> ".pdf";
            case "application/msword" -> ".doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "application/vnd.ms-excel" -> ".xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            case "text/plain" -> ".txt";
            case "text/markdown" -> ".md";
            case "application/json" -> ".json";
            default -> ".bin";
        };
    }
}