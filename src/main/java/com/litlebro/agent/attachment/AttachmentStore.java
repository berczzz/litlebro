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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    /** 工具单次读取/检索的最大行数（read_file/grep_file 共用硬上限） */
    private final int toolMaxLines;
    /** 工具单次返回的最大字符数（read_file/grep_file 共用硬上限） */
    private final int toolMaxChars;
    private final AttachmentRegistry registry;
    private final AttachmentResolverFactory resolverFactory;
    private final DocumentParserFactory parserFactory;
    /** 懒解析互斥锁（按 fileId），防止并发首读同一附件重复解析与写文件 */
    private final Map<String, Object> parseLocks = new ConcurrentHashMap<>();

    public AttachmentStore(
            AttachmentRegistry registry,
            AttachmentResolverFactory resolverFactory,
            DocumentParserFactory parserFactory,
            @Value("${app.attachment.dir:./data/attachments}") String dir,
            @Value("${app.attachment.ttl-days:7}") long ttlDays,
            @Value("${app.attachment.max-size:20971520}") long maxSizeBytes,
            @Value("${app.attachment.tool-max-lines:500}") int toolMaxLines,
            @Value("${app.attachment.tool-max-chars:12000}") int toolMaxChars) {
        this.registry = registry;
        this.resolverFactory = resolverFactory;
        this.parserFactory = parserFactory;
        this.baseDir = Path.of(dir).toAbsolutePath().normalize();
        this.ttlMillis = ttlDays * 24L * 60L * 60L * 1000L;
        this.maxSizeBytes = maxSizeBytes;
        this.toolMaxLines = Math.max(1, toolMaxLines);
        this.toolMaxChars = Math.max(1, toolMaxChars);
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
     * 单次读取受 {@link #toolMaxLines}（行数）与 {@link #toolMaxChars}（字符数）双重硬上限约束，
     * 超限即截断并在结尾追加续读提示，防止模型一次性读取整个大文件。
     *
     * @param fileId     附件唯一标识
     * @param startLine  起始行号（从 1 开始）
     * @param endLine    结束行号（含），小于 startLine 或超出文件范围时自动收拢；-1 表示读取到末尾
     * @return 切片文本（带原始行号标注），附件不存在返回 null
     * @throws IOException 解析或读取失败时抛出
     */
    public String readLines(String fileId, int startLine, int endLine) throws IOException {
        Path textPath = textPathOf(fileId);
        if (textPath == null) {
            return null;
        }
        int start = Math.max(1, startLine);
        // 行窗口硬上限：单次最多读取 toolMaxLines 行，防止 -1（读到末尾）全量读取大文件
        long endCap = (long) start + toolMaxLines - 1;
        boolean lineCapped = endLine <= 0 || endLine > endCap;
        int effectiveEnd = lineCapped ? (int) endCap : endLine;
        StringBuilder sb = new StringBuilder();
        long lineNo = 0;
        boolean inRange = false;
        boolean lineTruncated = false;
        boolean charTruncated = false;
        try (Stream<String> lines = Files.lines(textPath, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                lineNo++;
                if (lineNo < start) {
                    continue;
                }
                if (lineNo > effectiveEnd) {
                    // 仅当因行窗口上限（而非文件自然读完）停下才算截断
                    lineTruncated = lineCapped;
                    break;
                }
                String hit = lineNo + ": " + line + "\n";
                if (sb.length() + hit.length() > toolMaxChars) {
                    charTruncated = true;
                    break;
                }
                inRange = true;
                sb.append(hit);
            }
        }
        if (!inRange && !lineTruncated && !charTruncated) {
            return "[起始行 " + start + " 超出文件范围]";
        }
        if (lineTruncated || charTruncated) {
            // 截断提示指明续读位置，让模型知道如何继续（对齐业界"截断必须可见且指明恢复路径"）
            long resume = lineTruncated ? (long) effectiveEnd + 1 : lineNo;
            log.warn("read_file 结果超限已截断 fileId={} start={} end={} 行上限={} 字符上限={}", fileId, start, endLine, toolMaxLines, toolMaxChars);
            sb.append("\n...[结果已截断，请用 read_file 从第 ").append(resume).append(" 行继续读取]");
        }
        return sb.toString();
    }

    /**
     * 按正则检索附件文本（供 grep_file 工具使用），返回带行号的匹配行。
     *
     * <p>采用 {@link Files#lines} 流式读取，逐行匹配、只保留命中结果，避免大文件 OOM。
     * 单次检索受 {@link #toolMaxLines}（命中行数）与 {@link #toolMaxChars}（字符数）双重硬上限约束，
     * 超限即截断并追加提示，防止宽泛正则把整个大文件都匹配回来。
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
        int lineCap = Math.max(1, Math.min(maxLines, toolMaxLines));
        StringBuilder sb = new StringBuilder();
        long lineNo = 0;
        int count = 0;
        boolean truncated = false;
        try (Stream<String> lines = Files.lines(textPath, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                lineNo++;
                if (count >= lineCap) {
                    truncated = true;
                    break;
                }
                if (p.matcher(line).find()) {
                    String hit = lineNo + ": " + line + "\n";
                    if (sb.length() + hit.length() > toolMaxChars) {
                        truncated = true;
                        break;
                    }
                    sb.append(hit);
                    count++;
                }
            }
        }
        if (count == 0 && !truncated) {
            return "未找到匹配内容。";
        }
        if (truncated) {
            log.warn("grep_file 结果超限已截断 fileId={} pattern={} 行上限={} 字符上限={}", fileId, pattern, lineCap, toolMaxChars);
            sb.append("...[结果已截断，命中过多，请用更精确的正则缩小范围]");
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
     * <p>按 fileId 加互斥锁：并发首读同一附件时只有一个线程执行解析，
     * 其余线程在锁内重查注册表复用已生成的 txt 路径，避免重复解析与写文件竞态。
     *
     * @return 懒解析生成的 txt 文件路径
     */
    private Path lazyParse(AttachmentEntry entry) throws IOException {
        String fileId = entry.fileId();
        Object lock = parseLocks.computeIfAbsent(fileId, k -> new Object());
        synchronized (lock) {
            try {
                // 获得锁后重查：其他线程可能已完成解析并登记 txtPath
                AttachmentEntry fresh = registry.get(fileId);
                if (fresh != null && fresh.txtPath() != null && Files.exists(fresh.txtPath())) {
                    return fresh.txtPath();
                }
                DocumentParser parser;
                try {
                    parser = parserFactory.resolve(entry.name());
                } catch (IllegalArgumentException e) {
                    log.warn("附件格式不支持懒解析 name={} fileId={}", entry.name(), fileId);
                    throw new IOException("不支持的附件格式: " + entry.name(), e);
                }
                byte[] bytes = Files.readAllBytes(entry.rawPath());
                String text = parser.parse(bytes, entry.name());
                Path txtPath = baseDir.resolve(fileId + ".txt");
                Files.writeString(txtPath, text);
                registry.register(entry.withTxtPath(txtPath));
                log.info("附件懒解析完成 fileId={} name={} 文本长度={}", fileId, entry.name(), text.length());
                return txtPath;
            } finally {
                parseLocks.remove(fileId);
            }
        }
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