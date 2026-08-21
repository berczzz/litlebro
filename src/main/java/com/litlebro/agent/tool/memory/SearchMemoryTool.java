package com.litlebro.agent.tool.memory;

import com.litlebro.agent.common.Constant;
import com.litlebro.agent.context.SessionContextHolder;
import com.litlebro.agent.memory.LongTermMemoryService;
import com.litlebro.agent.memory.VectorMemoryStore;
import com.litlebro.agent.memory.model.AgentMessage;
import com.litlebro.agent.router.RouterProperties;
import com.litlebro.agent.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话记忆检索工具，供 LLM 在对话中按需检索当前会话的历史记忆。
 *
 * <p>检索范围限定为当前会话（sessionId 从 {@link SessionContextHolder} 读取，
 * 由 AgentService 在每次请求入口写入），避免跨会话信息串扰。
 *
 * <p><b>自动降级链</b>（无需 conversation 参数，全程自动）：
 * <ol>
 *   <li>语义检索「摘要 + 持久事实」（压缩产物，命中率高、内容精炼）</li>
 *   <li>结果为空、会话从未压缩过、或用户使用了强回溯词（之前/刚才/上次…）时，
 *       补检索原始对话记录（CATEGORY_CHAT），保证回溯问题能取到逐字历史</li>
 * </ol>
 */
@Component
public class SearchMemoryTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SearchMemoryTool.class);

    private static final int TOP_K = 5;

    private final VectorMemoryStore vectorMemoryStore;
    private final LongTermMemoryService longTermMemoryService;
    private final RouterProperties routerProperties;
    /** 检索结果相似度二次过滤阈值：低于该分数的片段直接丢弃，过滤弱相关噪声 */
    private final double minScore;

    public SearchMemoryTool(VectorMemoryStore vectorMemoryStore,
                            LongTermMemoryService longTermMemoryService,
                            RouterProperties routerProperties,
                            @Value("${app.memory.min-score:0.35}") double minScore) {
        this.vectorMemoryStore = vectorMemoryStore;
        this.longTermMemoryService = longTermMemoryService;
        this.routerProperties = routerProperties;
        this.minScore = minScore;
    }

    @Override
    public String name() {
        return "会话记忆检索";
    }

    @Override
    public String description() {
        return "检索当前会话的对话记录，用于回忆本会话聊过什么、做过什么决定；不含上传文档与附件内容";
    }

    /**
     * 检索当前会话的历史记忆（自动降级：摘要+事实 → 原始对话记录）。
     *
     * @param query 检索描述，将用户问题改写为完整的查询描述（包含核心对象与要查找的信息）
     * @return 命中的记忆片段文本，无结果时返回提示语
     */
    @Tool(name = "search_memory", description = "检索当前会话的对话记录，用于回忆本会话中用户说过什么、助手答过什么、做过什么决定。适用场景：用户提及'之前/刚才/上次/我们说过的'等回溯对话。禁用场景（请勿调用）：涉及用户上传的文档知识库或随消息上传的附件内容，此类内容请分别用 search_document 或 read_file/grep_file；本工具仅包含对话记录，不含任何文档/文件/数据内容")
    public String searchMemory(
            @ToolParam(description = "检索描述：将用户问题改写为完整的查询描述（包含核心对象与要查找的信息），不要只截取几个关键词，描述越完整检索命中率越高") String query) {
        String sessionId = SessionContextHolder.get();
        log.info("searchMemory执行开始：{}，sessionId：{}", query, sessionId);
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("会话记忆检索缺少 sessionId，返回空结果");
            return "当前无法确定会话，请重试。";
        }
        List<Document> docs = searchWithDegradation(sessionId, query);
        List<AgentMessage> messages = new ArrayList<>();
        for (Document doc : docs) {
            if (doc.getScore() != null && doc.getScore() < minScore) {
                continue;
            }
            AgentMessage am = vectorMemoryStore.toAgentMessage(doc);
            if (am != null) {
                messages.add(am);
            }
        }
        if (messages.isEmpty()) {
            log.info("searchMemory未检索到相关历史记忆，sessionId：{}", sessionId);
            return "未检索到相关历史记忆。";
        }
        StringBuilder sb = new StringBuilder("检索到当前会话的历史记忆:\n");
        for (AgentMessage am : messages) {
            sb.append("- ").append(am.messageType() != null ? am.messageType().getValue() : "unknown")
                    .append(": ").append(am.text()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 自动降级检索链：
     * <ol>
     *   <li>先检「摘要 + 持久事实」分类（压缩产物，语义密度高）</li>
     *   <li>命中为空 / 从未压缩过 / 强回溯词命中时，再补检原始对话记录（CATEGORY_CHAT），
     *       合并去重后按相似度倒序取 TOP_K</li>
     * </ol>
     */
    private List<Document> searchWithDegradation(String sessionId, String query) {
        List<Document> condensed = vectorMemoryStore.searchMemories(
                sessionId, query, TOP_K, List.of(Constant.CATEGORY_SUMMARY, Constant.CATEGORY_FACT));

        boolean hasSummary = longTermMemoryService.getLatestSummaryDoc(sessionId) != null;
        boolean backtracking = containsAny(query, routerProperties.getStrongMemoryKeywords());
        boolean needChat = condensed.isEmpty() || backtracking || !hasSummary;
        if (!needChat) {
            return condensed;
        }

        List<Document> chatDocs = vectorMemoryStore.searchMemories(
                sessionId, query, TOP_K, List.of(Constant.CATEGORY_CHAT));
        if (chatDocs.isEmpty()) {
            return condensed;
        }
        List<Document> merged = new ArrayList<>(condensed);
        Set<String> seen = new HashSet<>();
        for (Document doc : condensed) {
            seen.add(doc.getId());
        }
        for (Document doc : chatDocs) {
            if (seen.add(doc.getId())) {
                merged.add(doc);
            }
        }
        merged.sort(Comparator.comparingDouble((Document d) ->
                d.getScore() != null ? d.getScore() : -1.0).reversed());
        return merged.size() > TOP_K ? merged.subList(0, TOP_K) : merged;
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null || keywords == null) {
            return false;
        }
        for (String kw : keywords) {
            if (kw != null && !kw.isBlank() && text.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}