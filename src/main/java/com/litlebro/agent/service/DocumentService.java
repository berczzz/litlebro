package com.litlebro.agent.service;

import com.litlebro.agent.common.Constant;
import com.litlebro.agent.memory.VectorMemoryStore;
import com.litlebro.agent.rag.DocumentParseCache;
import com.litlebro.agent.rag.DocumentSplitterFactory;
import com.litlebro.agent.dto.DocumentIngestResult;
import com.litlebro.agent.rag.parser.DocumentParser;
import com.litlebro.agent.rag.parser.DocumentParserFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文档知识库服务：接收上传文档，解析文本、切块并写入向量库（全局共享，不绑定会话）。
 *
 * <p>文件解析采用策略模式（{@link DocumentParserFactory}），按扩展名选择解析器：
 * <ul>
 *   <li>txt / md / json — UTF-8 文本直接读取</li>
 *   <li>pdf — PDFBox 提取文本层；图片型页面走 dashscope 多模态模型描述内容</li>
 *   <li>docx — Apache POI 读取段落与表格</li>
 *   <li>xlsx / xls — Apache POI 流式读取（xlsx 走 SAX），防大文件内存溢出</li>
 *   <li>png / jpg / jpeg / gif / webp / bmp — 视觉模型描述图片内容后入库</li>
 * </ul>
 *
 * <p>解析文本超过 {@code app.rag.max-text-length} 时截断，防止超大文档耗尽内存。
 *
 * <p>切块策略由 {@link DocumentSplitterFactory} 按配置返回（semantic / fixed）。
 * 每个切块携带 docId（整份文档共享）与 source（文件名）元数据，
 * 便于按文档聚合查看与删除。
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final VectorMemoryStore vectorMemoryStore;
    private final DocumentSplitterFactory splitterFactory;
    private final DocumentParserFactory parserFactory;
    private final DocumentParseCache parseCache;
    private final int maxTextLength;

    public DocumentService(VectorMemoryStore vectorMemoryStore,
                           DocumentSplitterFactory splitterFactory,
                           DocumentParserFactory parserFactory,
                           DocumentParseCache parseCache,
                           @org.springframework.beans.factory.annotation.Value(
                                   "${app.rag.max-text-length:3000000}") int maxTextLength) {
        this.vectorMemoryStore = vectorMemoryStore;
        this.splitterFactory = splitterFactory;
        this.parserFactory = parserFactory;
        this.parseCache = parseCache;
        this.maxTextLength = maxTextLength;
    }

    /**
     * 入库一份上传文档：解析文本 → 切块 → 写入向量库。
     *
     * <p>以文件内容哈希为 key 查询 {@link DocumentParseCache}，命中则跳过解析
     * （图片型文档可避免重复调用视觉模型浪费 token）；未命中则解析并写缓存。
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

        DocumentParser parser = parserFactory.resolve(filename);
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("读取文件失败: " + filename + "，原因: " + e.getMessage());
        }
        String fileHash = sha256(fileBytes);

        String text = parseCache.get(fileHash);
        if (text == null) {
            try {
                text = parser.parse(fileBytes, filename);
            } catch (IOException e) {
                throw new IllegalArgumentException("文件解析失败: " + filename + "，原因: " + e.getMessage());
            }
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("文件内容为空或无法解析文本: " + filename);
            }
            // 大文件防护：解析文本超长时截断，避免超长文本耗尽内存
            if (text.length() > maxTextLength) {
                log.warn("解析文本超过上限（{}），已截断 filename={}", maxTextLength, filename);
                text = text.substring(0, maxTextLength);
            }
            parseCache.put(fileHash, text);
        } else {
            log.info("文档解析命中缓存，跳过解析 filename={} hash={}", filename, fileHash);
        }
        log.info("上传文件的知识库文件内容如下：{}", text);
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

    /**
     * 计算文件内容的 SHA-256 哈希（hex 小写），用作解析缓存 key。
     */
    private String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
