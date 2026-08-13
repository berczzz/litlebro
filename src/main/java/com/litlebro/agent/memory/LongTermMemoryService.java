package com.litlebro.agent.memory;

import com.litlebro.agent.common.ChatContentRole;
import com.litlebro.agent.common.Constant;
import com.litlebro.agent.memory.model.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.util.CollectionUtils;

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
 *
 * <p>存储对象与短期记忆共用统一的 {@link AgentMessage} 实体。
 */
public class LongTermMemoryService {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryService.class);

    private static final String SUMMARY_PREFIX = "之前的对话摘要:\n";

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
        AgentMessage am = new AgentMessage(
                UUID.randomUUID().toString().replace("-", ""),
                sessionId,
                Constant.CATEGORY_SUMMARY,
                "system",
                ChatContentRole.SYSTEM_ROLE,
                summary,
                map,
                List.of(),
                List.of(),
                List.of(),
                System.currentTimeMillis()
        );
        vectorMemoryStore.save(am);
        log.info("会话摘要已存入向量库 sessionId={}", sessionId);
    }

    public void saveChat(String sessionId, String chatContent, String role, int costTokens) {
        if (chatContent == null || chatContent.isBlank()) {
            return;
        }
        HashMap<String, Object> map = new HashMap<>();
        map.put("cost", costTokens);
        AgentMessage am = new AgentMessage(
                UUID.randomUUID().toString().replace("-", ""),
                sessionId,
                Constant.CATEGORY_CHAT,
                role,
                role,
                chatContent,
                map,
                List.of(),
                List.of(),
                List.of(),
                System.currentTimeMillis()
        );
        vectorMemoryStore.save(am);
        log.debug("会话记录已存入向量库 sessionId={} role={}", sessionId, role);
    }

    public String getLatestSummary(String sessionId) {
        Document doc = getLatestSummaryDoc(sessionId);
        return doc == null ? null : doc.getText();
    }

    /**
     * 获取该会话最近一次压缩摘要的 Document（含 createdAt 元数据，可定位压缩点）。
     */
    public Document getLatestSummaryDoc(String sessionId) {
        List<Document> docs = vectorMemoryStore.searchByCategoryNoThreshold(sessionId, Constant.CATEGORY_SUMMARY, 1);
        return docs.isEmpty() ? null : docs.get(0);
    }

    /**
     * 读取该会话持久化的聊天消息（向量库 CATEGORY_CHAT 记录）。
     * 用于短期记忆过期后恢复上下文。
     *
     * <p>可按时间范围过滤：只返回 createdAt 晚于 {@code afterTimestamp} 的消息，
     * 过滤在向量库查询层完成。传入 {@code afterTimestamp <= 0} 表示不过滤（返回全部）。
     *
     * @param sessionId      会话 ID
     * @param afterTimestamp 起始时间戳（epoch millis），只返回该值之后的消息；&lt;=0 表示不过滤
     * @param limit          最多读取条数（防极端场景内存膨胀）
     * @return 统一记忆实体列表
     */
    public List<AgentMessage> getChatMessages(String sessionId, long afterTimestamp, int limit) {
        List<Document> docs = vectorMemoryStore.searchByCategoryAfter(sessionId, Constant.CATEGORY_CHAT, afterTimestamp, limit);
        List<AgentMessage> result = new ArrayList<>();
        for (Document doc : docs) {
            result.add(toAgentMessage(doc));
        }
        return result;
    }

    public Map<String, List<Map<String, String>>> getAllFacts(String sessionId) {
        List<Document> docs = vectorMemoryStore.searchByCategoryNoThreshold(sessionId, null, 50);
        Map<String, List<Map<String, String>>> allFacts = new LinkedHashMap<>();
        for (Document doc : docs) {
            String category = String.valueOf(doc.getMetadata().getOrDefault("category", Constant.CATEGORY_OTHER));
            allFacts.computeIfAbsent(category, k -> new ArrayList<>())
                    .add(Map.of(
                            "fact", doc.getText(),
                            "timestamp", String.valueOf(doc.getMetadata().getOrDefault("createdAt", ""))
                    ));
        }
        return allFacts;
    }

    public void deleteSessionFacts(String sessionId) {
        vectorMemoryStore.deleteSessionMemories(sessionId);
    }

    private AgentMessage toAgentMessage(Document doc) {
        if (doc == null) {
            return null;
        }
        Map<String, Object> metadata = doc.getMetadata();
        return new AgentMessage(
                String.valueOf(metadata.getOrDefault("id", doc.getId())),
                String.valueOf(metadata.getOrDefault("sessionId", "")),
                String.valueOf(metadata.getOrDefault("category", Constant.CATEGORY_OTHER)),
                String.valueOf(metadata.getOrDefault("messageType", "")),
                String.valueOf(metadata.getOrDefault("role", "")),
                doc.getText(),
                new LinkedHashMap<>(metadata),
                List.of(),
                List.of(),
                List.of(),
                toLong(metadata.get("createdAt"))
        );
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    public List<Message> getStmMessage(String sessionId) {
        List<Message> rebuilt = new ArrayList<>();

        // 最近一次压缩摘要 = 压缩点。若从未压缩过（无摘要），则压缩点视为最早
        long compactPoint = -1;
        Document summaryDoc = getLatestSummaryDoc(sessionId);
        if (summaryDoc != null) {
            String summaryText = summaryDoc.getText();
            if (summaryText != null && !summaryText.isBlank()) {
                rebuilt.add(new SystemMessage(SUMMARY_PREFIX + summaryText));
            }
            compactPoint = createdAtMillis(summaryDoc);
        }

        // 只回注压缩点之后的消息（向量库查询层按 createdAt > compactPoint 过滤），
        List<AgentMessage> chatMessages = getChatMessages(sessionId, compactPoint, 500);
        if (CollectionUtils.isEmpty(chatMessages)) {
            return Collections.emptyList();
        }
        chatMessages.sort(Comparator.comparingLong(AgentMessage::createdAt));
        for (AgentMessage am : chatMessages) {
            String text = am.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (ChatContentRole.USER_ROLE.equals(am.role())) {
                rebuilt.add(new UserMessage(text));
            } else if (ChatContentRole.ASSISTANT_ROLE.equals(am.role())) {
                rebuilt.add(new AssistantMessage(text));
            }
        }
        return rebuilt;
    }

    private long createdAtMillis(Document doc) {
        Object value = doc.getMetadata().get("createdAt");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (Exception ignored) {
            }
        }
        return 0;
    }
}
