package com.litlebro.agent.tool;

import com.litlebro.agent.common.Constant;
import com.litlebro.agent.memory.VectorMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档知识库检索工具，供 LLM 检索已上传的文档内容。
 *
 * <p>检索范围为全局文档知识库（category == document），不绑定会话，
 * 任意会话均可检索同一批文档。适用于用户询问文档内容的问题
 * （如项目文档、使用说明、手册等）。
 */
@Component
public class SearchDocumentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SearchDocumentTool.class);

    private static final int TOP_K = 5;

    private final VectorMemoryStore vectorMemoryStore;
    /** 检索结果相似度二次过滤阈值：低于该分数的片段直接丢弃，过滤弱相关噪声 */
    private final double minScore;

    public SearchDocumentTool(VectorMemoryStore vectorMemoryStore,
                              @Value("${app.rag.min-score:0.35}") double minScore) {
        this.vectorMemoryStore = vectorMemoryStore;
        this.minScore = minScore;
    }

    @Override
    public String name() {
        return ToolDescriptions.SEARCH_DOCUMENT_NAME;
    }

    @Override
    public String description() {
        return ToolDescriptions.SEARCH_DOCUMENT_DESC;
    }

    /**
     * 检索文档知识库。
     *
     * @param query 检索词，描述想查找的文档内容
     * @return 命中的文档片段文本（含来源文件名），无结果时返回提示语
     */
    @Tool(name = "search_document", description = ToolDescriptions.SEARCH_DOCUMENT_TOOL)
    public String searchDocument(
            @ToolParam(description = ToolDescriptions.SEARCH_DOCUMENT_PARAM) String query) {
        log.info("searchDocument执行开始：{}",query);
        List<Document> docs = vectorMemoryStore.searchDocuments(query, TOP_K);
        if (docs == null || docs.isEmpty()) {
            log.info("searchDocument未检索到相关文档内容返回");
            return "未检索到相关文档内容。";
        }
        List<Document> filtered = docs.stream()
                .filter(doc -> doc.getScore() == null || doc.getScore() >= minScore)
                .sorted((a, b) -> Double.compare(
                        b.getScore() == null ? 0.0 : b.getScore(),
                        a.getScore() == null ? 0.0 : a.getScore()))
                .toList();
        if (filtered.isEmpty()) {
            log.info("searchDocument二次过滤后无结果，minScore={}，原始命中={}", minScore, docs.size());
            return "未检索到相关文档内容。";
        }
        StringBuilder sb = new StringBuilder("检索到相关文档内容:\n");
        for (Document doc : filtered) {
            String source = String.valueOf(doc.getMetadata().getOrDefault(Constant.MD_SOURCE, "未知来源"));
            sb.append("[来源: ").append(source).append("] ").append(doc.getText()).append("\n");
        }
        return sb.toString();
    }
}
