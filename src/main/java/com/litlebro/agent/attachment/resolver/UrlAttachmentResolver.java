package com.litlebro.agent.attachment.resolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * URL 附件解析策略：从远程 http/https 地址下载附件字节。
 *
 * <p>实现要点：
 * <ul>
 *   <li>超时控制：连接 10 秒、整体 60 秒，防止外部地址拖垮请求线程</li>
 *   <li>大小防护：流式读取边下边计数，超过 {@code app.attachment.max-size} 立即中止，
 *       避免整包缓冲进内存后再校验（超大响应 OOM）</li>
 *   <li>SSRF 防护：拒绝解析到回环/内网地址（含重定向后的最终地址）的 URL，
 *       防止利用服务端发起对内网探测</li>
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
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new IOException("URL 附件地址非法: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IOException("仅支持 http/https 协议下载附件");
        }
        // SSRF 防护：请求前校验目标主机（含 DNS 解析结果）
        assertNotInternal(uri, url);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            // 流式读取，边下边计数，超限立即中止连接
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                closeQuietly(response.body());
                throw new IOException("URL 下载失败 HTTP " + response.statusCode() + ": " + url);
            }
            // 重定向后可能跳到内网地址，最终地址同样需要校验
            assertNotInternal(response.uri(), url);

            byte[] bytes;
            try (InputStream in = response.body();
                 ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(chunk)) != -1) {
                    total += n;
                    if (total > maxSizeBytes) {
                        throw new IOException("URL 附件超过大小限制（> " + maxSizeBytes + "）");
                    }
                    buffer.write(chunk, 0, n);
                }
                bytes = buffer.toByteArray();
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

    /**
     * SSRF 防护：目标主机解析为回环/内网地址时拒绝。
     */
    private void assertNotInternal(URI uri, String originalUrl) throws IOException {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("URL 附件缺少主机名: " + originalUrl);
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (isInternal(addr)) {
                    log.warn("拒绝内网/回环地址的 URL 附件 host={} ip={} url={}", host, addr.getHostAddress(), originalUrl);
                    throw new IOException("不允许下载内网/回环地址的附件: " + host);
                }
            }
        } catch (UnknownHostException e) {
            throw new IOException("URL 附件域名解析失败: " + host, e);
        }
    }

    /**
     * 判定 IP 是否为回环/链路本地/私网/保留地址。
     */
    private boolean isInternal(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
            return true;
        }
        byte[] raw = addr.getAddress();
        if (raw.length != 4) {
            return false; // IPv6 已由 isLoopback/isLinkLocal/isSiteLocal 覆盖主要内网段
        }
        int first = raw[0] & 0xFF;
        return first == 0 || first == 10 || first == 127
                || (first == 169 && (raw[1] & 0xFF) == 254)
                || (first == 172 && (raw[1] & 0xF0) == 16)
                || (first == 192 && (raw[1] & 0xFF) == 168)
                || first == 100 && (raw[1] & 0xC0) == 64; // 100.64.0.0/10 CGNAT
    }

    private void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
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