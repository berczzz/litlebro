package com.litlebro.agent.attachment.resolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * URL 附件解析策略：从远程 http/https 地址下载附件字节。
 *
 * <p>实现要点：
 * <ul>
 *   <li>超时控制：连接 10 秒、整体 60 秒，防止外部地址拖垮请求线程</li>
 *   <li>大小防护：超过 {@code app.attachment.max-size} 直接拒绝，防止内存溢出</li>
 *   <li>只允许 http/https，杜绝 file:// 等本地协议读取</li>
 * </ul>
 */
@Component
public class UrlAttachmentResolver implements AttachmentResolver {

    private static final Logger log = LoggerFactory.getLogger(UrlAttachmentResolver.class);

    private final long maxSizeBytes;

    public UrlAttachmentResolver(@Value("${app.attachment.max-size:20971520}") long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }

    @Override
    public String type() {
        return "url";
    }

    @Override
    public ResolvedAttachment resolve(AttachmentInput input) throws IOException {
        String url = input.data() != null ? input.data().trim() : "";
        if (url.isEmpty()) {
            throw new IOException("URL 附件地址为空");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IOException("仅支持 http/https 协议下载附件");
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("URL 下载失败 HTTP " + response.statusCode() + ": " + url);
            }
            byte[] bytes = response.body();
            if (bytes.length > maxSizeBytes) {
                throw new IOException("URL 附件超过大小限制（" + bytes.length + " > " + maxSizeBytes + "）");
            }

            String mimeType = response.headers().firstValue("Content-Type").orElse(input.mimeType());
            String name = input.name();
            if (name == null || name.isBlank()) {
                name = inferNameFromUrl(url, mimeType);
            }
            log.info("URL 附件下载成功 url={} size={} name={}", url, bytes.length, name);
            return new ResolvedAttachment(name, mimeType, bytes);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("URL 附件下载失败: " + e.getMessage(), e);
        }
    }

    private String inferNameFromUrl(String url, String mimeType) {
        String path = URI.create(url).getPath();
        if (path != null && !path.isEmpty()) {
            int lastSlash = path.lastIndexOf('/');
            String last = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            if (!last.isBlank()) {
                return last;
            }
        }
        return "download" + extensionOf(mimeType);
    }

    private String extensionOf(String mimeType) {
        if (mimeType == null) {
            return ".bin";
        }
        String m = mimeType.toLowerCase();
        if (m.contains("image/png")) return ".png";
        if (m.contains("image/jpeg") || m.contains("image/jpg")) return ".jpg";
        if (m.contains("image/gif")) return ".gif";
        if (m.contains("image/webp")) return ".webp";
        if (m.contains("application/pdf")) return ".pdf";
        if (m.contains("spreadsheetml")) return ".xlsx";
        if (m.contains("wordprocessingml")) return ".docx";
        if (m.contains("text/plain")) return ".txt";
        if (m.contains("text/markdown")) return ".md";
        if (m.contains("application/json")) return ".json";
        return ".bin";
    }
}