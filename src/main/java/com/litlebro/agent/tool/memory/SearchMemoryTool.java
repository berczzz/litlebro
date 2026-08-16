package com.litlebro.agent.tool.memory;

import com.litlebro.agent.context.SessionContextHolder;
import com.litlebro.agent.memory.VectorMemoryStore;
import com.litlebro.agent.memory.model.AgentMessage;
import com.litlebro.agent.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆检索工具，供 LLM 在对话中按需检索当前会话的历史记忆。
 *
 * <p>检索范围限定为当前会话（sessionId 从 {@link SessionContextHolder} 读取，
 * 由 AgentService 在每次请求入口写入），避免跨会话信息串扰。
 *
 * <p>适用于用户询问"我之前说过什么/我们之前聊过什么"等需要回溯对话历史的场景。
 */
@Component
public class SearchMemoryTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(SearchMemoryTool.class);

    private static final int TOP_K = 5;

    private final VectorMemoryStore vectorMemoryStore;
    /** 检索结果相似度二次过滤阈值：低于该分数的片段直接丢弃，过滤弱相关噪声 */
    private final double minScore;

    public SearchMemoryTool(VectorMemoryStore vectorMemoryStore,
                            @Value("${app.memory.min-score:0.35}") double minScore) {
        this.vectorMemoryStore = vectorMemoryStore;
        this.minScore = minScore;
    }

    @Override
    public String name() {
        return "会话记忆检索";
    }

    @Override
    public String description() {
        return "检索当前会话的对话记录，用于回忆本会话聊过什么、做过什么决定；不含上传文档内容";
    }

    /**
     * 检索当前会话的历史记忆。
     *
     * @param query 检索词，描述想查找的记忆内容
     * @return 命中的记忆片段文本，无结果时返回提示语
     */
    @Tool(name = "search_memory", description = "检索当前会话的对话记录，用于回忆本会话中用户说过什么、助手答过什么、做过什么决定。注意：本工具仅包含对话记录，不包含用户上传的文档/文件/数据内容；查询涉及上传文件中的数据、记录、报表、文档内容时，不要使用本工具")
    public String searchMemory(
            @ToolParam(description = "检索描述：将用户问题改写为完整的查询描述（包含核心对象与要查找的信息），不要只截取几个关键词，描述越完整检索命中率越高") String query) {
        String sessionId = SessionContextHolder.get();
        log.info("searchMemory执行开始：{}，sessionId：{}", query, sessionId);
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("会话记忆检索缺少 sessionId，返回空结果");
            return "当前无法确定会话，请重试。";
        }
        List<Document> docs = vectorMemoryStore.searchMemories(sessionId, query, TOP_K);
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
            sb.append("- ").append(am.role()).append(": ").append(am.text()).append("\n");
        }
        return sb.toString();
    }
}
