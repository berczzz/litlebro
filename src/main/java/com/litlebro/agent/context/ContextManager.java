package com.litlebro.agent.context;

import com.litlebro.agent.common.Constant;
import com.litlebro.agent.common.SystemPrompt;
import com.litlebro.agent.memory.LongTermMemoryService;
import com.litlebro.agent.session.SessionManager;
import com.litlebro.agent.session.model.SessionMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 上下文管理器，负责后置检查 LLM 调用后的上下文是否溢出，溢出时触发压缩。
 */
@Component
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    private static final double OVERFLOW_THRESHOLD = 0.75;
    // 最新消息保留条数
    private static final int RECENT_MESSAGES_KEEP = 6;

    private final ChatMemory chatMemory;
    private final CompressionService compressionService;
    private final LongTermMemoryService longTermMemoryService;
    private final SessionManager sessionManager;
    /** 模型上下文窗口大小（token），用于判断是否溢出 */
    private final int maxContextTokens;

    public ContextManager(ChatMemory chatMemory,
                          CompressionService compressionService,
                          LongTermMemoryService longTermMemoryService,
                          SessionManager sessionManager,
                          @Value("${app.memory.context.max-tokens:128000}") int maxContextTokens) {
        this.chatMemory = chatMemory;
        this.compressionService = compressionService;
        this.longTermMemoryService = longTermMemoryService;
        this.sessionManager = sessionManager;
        this.maxContextTokens = maxContextTokens;
    }

    public void compactIfNeeded(String sessionId) {
        SessionMemory session = sessionManager.get(sessionId);
        if (session == null) {
            return;
        }
        int curPromptTokens = session.curPromptTokens();
        if ((double) curPromptTokens / maxContextTokens <= OVERFLOW_THRESHOLD) {
            return;
        }
        doCompact(sessionId);
    }

    /**
     * 短期记忆为空（首次对话、30 分钟闲置过期被 Redis 清理）时，
     * 从长期记忆（向量库）恢复上下文回注 ChatMemory：
     * 摘要 SystemMessage（压缩点）+ 压缩点之后的增量消息，避免模型"失忆"。
     * 阻塞式 {@code AgentService} 与流式 {@code AgentStreamService} 共用。
     */
    public void restoreContextIfEmpty(String sessionId) {
        List<Message> existing = chatMemory.get(sessionId, Integer.MAX_VALUE);
        if (existing != null && !existing.isEmpty()) {
            return;
        }
        List<Message> rebuilt = longTermMemoryService.getStmMessage(sessionId);
        if (rebuilt.isEmpty()) {
            return;
        }
        chatMemory.add(sessionId, rebuilt);
        log.info("短期记忆为空，已从长期记忆恢复上下文：增量消息数={} sessionId={}", rebuilt.size(), sessionId);
    }

    private void doCompact(String sessionId) {
        List<Message> allMessages = chatMemory.get(sessionId, Integer.MAX_VALUE);
        if (allMessages == null || allMessages.isEmpty()) {
            return;
        }

        log.info("触发上下文压缩 sessionId={} 消息数={}", sessionId, allMessages.size());

        // 提取上一次压缩摘要（如果存在），压缩时传入做增量，不重复压缩
        int startIndex = 0;
        String previousSummary = extractSummaryFromMessages(allMessages);
        if (previousSummary != null) {
            startIndex = 1;
        }

        // 保留最近 RECENT_MESSAGES_KEEP 条消息原文，更早的旧消息参与压缩
        int splitIndex = Math.max(startIndex, allMessages.size() - RECENT_MESSAGES_KEEP);
        List<Message> oldMessages = allMessages.subList(startIndex, splitIndex);
        List<Message> recentMessages = allMessages.subList(splitIndex, allMessages.size());
        if (oldMessages.isEmpty()) {
            return;
        }

        Map<String, Object> result = compressionService.summarizeHistory(oldMessages, previousSummary);
        Object summaryObj = result.get("summary");
        if (summaryObj == null || summaryObj.toString().isBlank()) {
            return;
        }
        String summary = summaryObj.toString();
        Object costObj = result.getOrDefault("cost", 0);
        int cost = costObj instanceof Number n ? n.intValue() : 0;

        longTermMemoryService.saveSummary(sessionId, summary, cost);
        sessionManager.resetCurTokens(sessionId);

        // 重建上下文：摘要 + 最近 6 条原文
        List<Message> rebuilt = new ArrayList<>();
        rebuilt.add(new SystemMessage(SystemPrompt.SUMMARY_PREFIX + summary));
        rebuilt.addAll(recentMessages);

        chatMemory.clear(sessionId);
        chatMemory.add(sessionId, rebuilt);

        log.info("上下文压缩完成 sessionId={} 摘要长度={} 保留最近消息={}", sessionId, summary.length(), recentMessages.size());
    }

    private String extractSummaryFromMessages(List<Message> messages) {
        if (messages.isEmpty()) {
            return null;
        }
        Message first = messages.get(0);
        if (first.getMessageType() == MessageType.SYSTEM) {
            String text = first.getText();
            if (text != null && text.startsWith(SystemPrompt.SUMMARY_PREFIX)) {
                return text.substring(SystemPrompt.SUMMARY_PREFIX.length());
            }
        }
        return null;
    }
}