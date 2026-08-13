package com.litlebro.agent.memory;

import com.litlebro.agent.common.ChatContentRole;
import com.litlebro.agent.common.Constant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.*;

/**
 * 长期记忆服务，管理按会话（sessionId）隔离的向量库存储。
 *
 * <p>职责：
 * <ul>
 *   <li>压缩摘要 → 存入向量库（持久化，语义检索用）</li>
 *   <li>每条对话消息 → 存入向量库（长期记录）</li>
 *   <li>buildContextPrompt → 只做语义检索，不拼接摘要（摘要已在 ChatMemory 里）</li>
 * </ul>
 *
 * <p>注意：增量压缩时读摘要不走这里——摘要就在 ChatMemory 第一条 SystemMessage 里，
 * ContextManager 直接从 ChatMemory 取。
 */
public class LongTermMemoryService {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryService.class);

    private final VectorMemoryStore vectorMemoryStore;

    public LongTermMemoryService(VectorMemoryStore vectorMemoryStore) {
        this.vectorMemoryStore = vectorMemoryStore;
    }

    public void saveSummary(String sessionId, String summary, int costTokens) {
        if (summary == null || summary.isBlank()) {
            return;
        }
        HashMap<String, Object> map = new HashMap<>();
        map.put("cost", costTokens);
        vectorMemoryStore.storeMemory(sessionId, summary, Constant.CATEGORY_SUMMARY, ChatContentRole.ASSISTANT_ROLE, map);
        log.info("会话摘要已存入向量库 sessionId={}", sessionId);
    }

    public void saveChat(String sessionId, String chatContent, String role, int costTokens) {
        if (chatContent == null || chatContent.isBlank()) {
            return;
        }
        HashMap<String, Object> map = new HashMap<>();
        map.put("cost", costTokens);
        vectorMemoryStore.storeMemory(sessionId, chatContent, Constant.CATEGORY_CHAT, role, map);
        log.debug("会话记录已存入向量库 sessionId={} role={}", sessionId, role);
    }

    public String getLatestSummary(String sessionId) {
        List<Document> docs = vectorMemoryStore.searchByCategoryNoThreshold(sessionId, Constant.CATEGORY_SUMMARY, 1);
        return docs.isEmpty() ? null : docs.get(0).getText();
    }

    public Map<String, List<Map<String, String>>> getAllFacts(String sessionId) {
        List<Document> docs = vectorMemoryStore.searchByCategoryNoThreshold(sessionId, null, 50);
        Map<String, List<Map<String, String>>> allFacts = new LinkedHashMap<>();
        for (Document doc : docs) {
            String category = (String) doc.getMetadata().getOrDefault("category", Constant.CATEGORY_OTHER);
            allFacts.computeIfAbsent(category, k -> new ArrayList<>())
                    .add(Map.of(
                            "fact", doc.getText(),
                            "timestamp", (String) doc.getMetadata().getOrDefault("createdAt", "")
                    ));
        }
        return allFacts;
    }

    public String buildContextPrompt(String sessionId, String userMessage) {
        List<Document> memories = vectorMemoryStore.searchMemories(sessionId, userMessage, 5);
        if (memories.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("与当前问题相关的历史记忆:\n");
        for (Document doc : memories) {
            String category = (String) doc.getMetadata().getOrDefault("category", "记忆");
            sb.append("  [").append(category).append("] ").append(doc.getText()).append("\n");
        }
        return sb.toString();
    }

    public void deleteSessionFacts(String sessionId) {
        vectorMemoryStore.deleteSessionMemories(sessionId);
    }
}