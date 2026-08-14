package com.litlebro.agent.attachment;

import com.litlebro.agent.attachment.resolver.AttachmentInput;
import com.litlebro.agent.attachment.resolver.AttachmentResolverFactory;
import com.litlebro.agent.attachment.resolver.ResolvedAttachment;
import com.litlebro.agent.rag.parser.DocumentParser;
import com.litlebro.agent.rag.parser.DocumentParserFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 附件存储服务：负责附件落盘、注册、懒解析与删除。
 *
 * <p>职责：
 * <ul>
 *   <li>通过 {@link AttachmentResolverFactory} 解析各种来源的附件为统一字节</li>
 *   <li>以随机 fileId 落盘到 {@code app.attachment.dir} 目录，登记 {@link AttachmentRegistry}</li>
 *   <li>纯文本类附件（txt/md/json/csv）可直接读取；pdf/word/excel 首次被工具访问时
 *       复用 {@link DocumentParserFactory} 懒解析为文本并缓存 txtPath，避免上传即解析的开销</li>
 *   <li>删除/清理时同时删除原始文件与懒解析 txt 文件</li>
 * </ul>
 *
 * <p>安全约定：对外只暴露 fileId，不暴露物理路径；落盘文件名为随机 UUID，
 * 工具读取前必须经注册表校验归属会话，防止路径穿越与越权访问。
 */
@Component
public class AttachmentStore {

    private static final Logger log = LoggerFactory.getLogger(AttachmentStore.class);

    /** 可直接当作文本读取、无需懒解析的扩展名 */
    private static final Set<String> PLAIN_TEXT_EXT = Set.of("txt", "md", "json", "csv");

    private final Path baseDir;
    private final long ttlMillis;
    private final long maxSizeBytes;
    private final AttachmentRegistry registry;
    private final AttachmentResolverFactory resolverFactory;
    private final DocumentParserFactory parserFactory;

    public AttachmentStore(
            AttachmentRegistry registry,
            AttachmentResolverFactory resolverFactory,
            DocumentParserFactory parserFactory,
            @Value("${app.attachment.dir:./data/attachments}") String dir,
            @Value("${app.attachment.ttl-days:7}") long ttlDays,
            @Value("${app.attachment.max-size:20971520}") long maxSizeBytes) {
        this.registry = registry;
        this.resolverFactory = resolverFactory;
        this.parserFactory = parserFactory;
        this.baseDir = Path.of(dir).toAbsolutePath().normalize();
        this.ttlMillis = ttlDays * 24L * 60L * 60L * 1000L;
        this.maxSizeBytes = maxSizeBytes;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("附件目录创建失败: " + baseDir, e);
        }
    }

    /**
     * 解析附件来源并落盘登记，返回 fileId。
     *
     * @param sessionId 附件归属会话
     * @param input     附件来源输入
     * @return 附件唯一标识 fileId
     * @throws IOException 解析或写入失败时抛出
     */
    public String store(String sessionId, AttachmentInput input) throws IOException {
        ResolvedAttachment resolved = resolverFactory.resolve(input);
        return store(sessionId, resolved);
    }

    /**
     * 将已解析的附件落盘登记，返回 fileId。
     *
     * @param sessionId 附件归属会话
     * @param resolved  已解析的附件字节
     * @return 附件唯一标识 fileId
     * @throws IOException 写入失败时抛出
     */
    public String store(String sessionId, ResolvedAttachment resolved) throws IOException {
        if (resolved == null || resolved.bytes() == null || resolved.bytes().length == 0) {
            throw new IOException("附件内容为空");
        }
        if (resolved.bytes().length > maxSizeBytes) {
            throw new IOException("附件超过大小限制（" + resolved.bytes().length + " > " + maxSizeBytes + "）");
        }
        String fileId = UUID.randomUUID().toString().replace("-", "");
        String ext = extensionOf(resolved.name());
        Path rawPath = baseDir.resolve(fileId + (ext.isEmpty() ? "" : "." + ext));
        Files.write(rawPath, resolved.bytes(), StandardOpenOption.CREATE_NEW);
        long expiresAt = System.currentTimeMillis() + ttlMillis;
        AttachmentEntry entry = new AttachmentEntry(
                fileId, sessionId, resolved.name(), resolved.mimeType(),
                rawPath, null, resolved.bytes().length, expiresAt);
        registry.register(entry);
        log.info("附件已落盘 fileId={} name={} size={} expiresAt={}", fileId, resolved.name(), resolved.bytes().length, expiresAt);
        return fileId;
    }

    /**
     * 读取附件原始字节（供记忆回放重建 Media 使用）。
     *
     * @param fileId 附件唯一标识
     * @return 原始字节，附件不存在或文件缺失返回 null
     */
    public byte[] readBytes(String fileId) {
        AttachmentEntry entry = registry.get(fileId);
        if (entry == null) {
            return null;
        }
        try {
            return Files.readAllBytes(entry.rawPath());
        } catch (IOException e) {
            log.warn("附件字节读取失败 fileId={} 原因: {}", fileId, e.getMessage());
            return null;
        }
    }

    /**
     * 按行读取附件文本（供 read_file 工具使用），支持起始/结束行号切片。
     *
     * <p>采用 {@link Files#lines} 流式读取，只把目标行载入内存，避免大文件 OOM。
     *
     * @param fileId     附件唯一标识
     * @param startLine  起始行号（从 1 开始）
     * @param endLine    结束行号（含），小于 startLine 或超出文件范围时自动收拢
     * @return 切片文本（带原始行号标注），附件不存在返回 null
     * @throws IOException 解析或读取失败时抛出
     */
    public String readLines(String fileId, int startLine, int endLine) throws IOException {
        Path textPath = textPathOf(fileId);
        if (textPath == null) {
            return null;
        }
        int start = Math.max(1, startLine);
        StringBuilder sb = new StringBuilder();
        long lineNo = 0;
        boolean inRange = false;
        try (Stream<String> lines = Files.lines(textPath, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                lineNo++;
                if (lineNo < start) {
                    continue;
                }
                if (endLine > 0 && lineNo > endLine) {
                    break;
                }
                inRange = true;
                sb.append(lineNo).append(": ").append(line).append("\n");
            }
        }
        if (!inRange) {
            return "[起始行 " + start + " 超出文件范围]";
        }
        return sb.toString();
    }

    /**
     * 按正则检索附件文本（供 grep_file 工具使用），返回带行号的匹配行。
     *
     * <p>采用 {@link Files#lines} 流式读取，逐行匹配、只保留命中结果，避免大文件 OOM。
     *
     * @param fileId   附件唯一标识
     * @param pattern  正则表达式
     * @param maxLines 最多返回的匹配行数
     * @return 匹配结果，附件不存在返回 null
     * @throws IOException 解析或读取失败时抛出
     */
    public String grep(String fileId, String pattern, int maxLines) throws IOException {
        Path textPath = textPathOf(fileId);
        if (textPath == null) {
            return null;
        }
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        StringBuilder sb = new StringBuilder();
        long lineNo = 0;
        int count = 0;
        try (Stream<String> lines = Files.lines(textPath, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                lineNo++;
                if (count >= maxLines) {
                    break;
                }
                if (p.matcher(line).find()) {
                    sb.append(lineNo).append(": ").append(line).append("\n");
                    count++;
                }
            }
        }
        if (count == 0) {
            return "未找到匹配内容。";
        }
        return sb.toString();
    }

    /**
     * 解析附件文本路径：纯文本直接返回原文件；pdf/word/excel 首次访问时懒解析为 txt 并缓存。
     *
     * @param fileId 附件唯一标识
     * @return 可流式读取的文本文件路径，附件不存在返回 null
     * @throws IOException 懒解析失败时抛出
     */
    private Path textPathOf(String fileId) throws IOException {
        AttachmentEntry entry = registry.get(fileId);
        if (entry == null) {
            return null;
        }
        if (entry.txtPath() != null && Files.exists(entry.txtPath())) {
            return entry.txtPath();
        }
        String ext = extensionOf(entry.name());
        if (PLAIN_TEXT_EXT.contains(ext)) {
            return entry.rawPath();
        }
        // 懒解析：pdf/word/excel 等二进制格式，首次访问才解析为 txt 并缓存
        return lazyParse(entry);
    }

    /**
     * 校验附件是否属于指定会话（工具调用前防越权）。
     *
     * @param fileId    附件唯一标识
     * @param sessionId 待校验的会话
     * @return 归属校验结果
     */
    public boolean belongsTo(String fileId, String sessionId) {
        AttachmentEntry entry = registry.get(fileId);
        return entry != null && (sessionId == null || sessionId.equals(entry.sessionId()));
    }

    /**
     * 删除附件：物理删除原始文件与懒解析 txt 文件，并移除注册表项。
     *
     * @param fileId 附件唯一标识
     */
    public void delete(String fileId) {
        AttachmentEntry entry = registry.remove(fileId);
        if (entry == null) {
            return;
        }
        deleteQuietly(entry.rawPath());
        if (entry.txtPath() != null) {
            deleteQuietly(entry.txtPath());
        }
        log.info("附件已删除 fileId={} name={}", fileId, entry.name());
    }

    /**
     * 清理过期附件：扫描注册表，删除到期条目的物理文件并移除注册表项。
     * 由 {@code AttachmentCleanupTask} 定时调用。
     */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        List<AttachmentEntry> expired = registry.all().stream()
                .filter(e -> e.expiresAt() < now)
                .toList();
        for (AttachmentEntry entry : expired) {
            delete(entry.fileId());
            log.info("附件已过期清理 fileId={} name={}", entry.fileId(), entry.name());
        }
    }

    /**
     * 懒解析二进制附件为纯文本并缓存 txtPath。
     *
     * @return 懒解析生成的 txt 文件路径
     */
    private Path lazyParse(AttachmentEntry entry) throws IOException {
        DocumentParser parser;
        try {
            parser = parserFactory.resolve(entry.name());
        } catch (IllegalArgumentException e) {
            log.warn("附件格式不支持懒解析 name={} fileId={}", entry.name(), entry.fileId());
            throw new IOException("不支持的附件格式: " + entry.name(), e);
        }
        byte[] bytes = Files.readAllBytes(entry.rawPath());
        String text = parser.parse(bytes, entry.name());
        Path txtPath = baseDir.resolve(entry.fileId() + ".txt");
        Files.writeString(txtPath, text, StandardOpenOption.CREATE_NEW);
        registry.register(entry.withTxtPath(txtPath));
        log.info("附件懒解析完成 fileId={} name={} 文本长度={}", entry.fileId(), entry.name(), text.length());
        return txtPath;
    }

    private String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("附件物理文件删除失败 path={} 原因: {}", path, e.getMessage());
        }
    }
}