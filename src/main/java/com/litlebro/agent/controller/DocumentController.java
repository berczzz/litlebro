package com.litlebro.agent.controller;

import com.litlebro.agent.dto.DocumentIngestResult;
import com.litlebro.agent.service.DocumentService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 文档知识库 REST API 控制器。
 *
 * <p>API 端点：
 * <ul>
 *   <li>POST /api/rag/document — 上传文档并入库（multipart/form-data，字段名 file）</li>
 *   <li>DELETE /api/rag/document/{docId} — 按文档 ID 删除全部切块</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/rag")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(value = "/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentIngestResult upload(@RequestParam("file") MultipartFile file) {
        return documentService.ingest(file);
    }

    @DeleteMapping("/document/{docId}")
    public Map<String, Object> delete(@PathVariable("docId") String docId) {
        documentService.delete(docId);
        return Map.of("docId", docId, "message", "文档已删除");
    }
}
