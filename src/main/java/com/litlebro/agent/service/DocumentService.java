package com.litlebro.agent.service;

import com.litlebro.agent.common.Constant;
import com.litlebro.agent.memory.VectorMemoryStore;
import com.litlebro.agent.rag.DocumentSplitterFactory;
import com.litlebro.agent.dto.DocumentIngestResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 文档知识库服务：接收上传文档，解析文本、切块并写入向量库（全局共享，不绑定会话）。
 *
 * <p>支持格式：
 * <ul>
 *   <li>{@code .txt} / {@code .md} — UTF-8 文本直接读取</li>
 *   <li>{@code .json} — 按 UTF-8 文本读取（文本本身参与向量化检索）</li>
 *   <li>{@code .pdf} — 通过 PDFBox 提取文本</li>
 * </ul>
 *
 * <p>切块策略由 {@link DocumentSplitterFactory} 按配置返回（semantic / fixed）。
 * 每个切块携带 docId（整份文档共享）与 source（文件名）元数据，
 * 便于按文档聚合查看与删除。
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final List<String> SUPPORTED_EXTENSIONS = List.of("txt", "md", "json", "pdf");

    private final VectorMemoryStore vectorMemoryStore;
    private final DocumentSplitterFactory splitterFactory;

    public DocumentService(VectorMemoryStore vectorMemoryStore, DocumentSplitterFactory splitterFactory) {
        this.vectorMemoryStore = vectorMemoryStore;
        this.splitterFactory = splitterFactory;
    }

    /**
     * 入库一份上传文档：解析文本 → 切块 → 写入向量库。
     *
     * @param file 上传文件
     * @return 入库结果（docId / source / chunkCount）
     * @throws IllegalArgumentException 不支持的格式或文件为空时抛出（全局异常转 400）
     */
    public DocumentIngestResult ingest(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String filename = StringUtils.cleanPath(file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
        String extension = extensionOf(filename);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("不支持的文件格式: " + extension
                    + "，支持: " + String.join("/", SUPPORTED_EXTENSIONS));
        }

        String text = parseText(file, extension);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文件内容为空或无法解析文本: " + filename);
        }
        log.info("上传文件的知识库文件内容如下：{}",text);
        String docId = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();

        Document sourceDoc = new Document(text, baseMetadata(docId, filename, now));
        TextSplitter splitter = splitterFactory.getSplitter();
        List<Document> chunks = splitter.split(List.of(sourceDoc));

        List<Document> chunkDocs = new ArrayList<>(chunks.size());
        for (Document chunk : chunks) {
            Map<String, Object> metadata = baseMetadata(docId, filename, now);
            metadata.put(Constant.MD_ID, UUID.randomUUID().toString().replace("-", ""));
            chunkDocs.add(new Document(chunk.getText(), metadata));
        }

        vectorMemoryStore.saveDocumentChunks(chunkDocs);
        log.info("文档入库完成 docId={} source={} chunks={}", docId, filename, chunkDocs.size());
        return new DocumentIngestResult(docId, filename, chunkDocs.size());
    }

    /**
     * 删除一份文档的全部切块。
     *
     * @param docId 文档唯一标识
     */
    public void delete(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId 不能为空");
        }
        vectorMemoryStore.deleteByDocId(docId);
    }

    /**
     * 解析上传文件为纯文本。
     */
    private String parseText(MultipartFile file, String extension) {
        try {
            if ("pdf".equals(extension)) {
                try (PDDocument pdf = PDDocument.load(file.getBytes())) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    return stripper.getText(pdf);
                }
            }
            // txt / md / json 统一按 UTF-8 文本读取
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("文件解析失败 原因: {}", e.getMessage());
            return null;
        }
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> baseMetadata(String docId, String source, long now) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Constant.MD_CATEGORY, Constant.CATEGORY_DOCUMENT);
        metadata.put(Constant.MD_TYPE, Constant.RAG_DOCUMENT_TYPE);
        metadata.put(Constant.MD_SOURCE, source);
        metadata.put(Constant.MD_DOC_ID, docId);
        metadata.put(Constant.MD_CREATED_AT, now);
        metadata.put(Constant.MD_UPDATED_AT, now);
        return metadata;
    }
}
