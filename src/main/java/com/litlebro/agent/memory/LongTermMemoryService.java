package com.litlebro.agent.memory;

import com.litlebro.agent.common.ChatContentRole;
import com.litlebro.agent.common.Constant;
import com.litlebro.agent.common.SystemPrompt;
import com.litlebro.agent.memory.model.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.document.Document;
import org.springframework.scheduling.annotation.Async;
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

    /** 定位"最近一次"摘要的候选条数：向量检索按相似度排序，需取一批后按 createdAt 选最新 */
    private static final int MAX_SUMMARY_CANDIDATES = 20;

    private final VectorMemoryStore vectorMemoryStore;
    private final MessageCodec messageCodec;

    public LongTermMemoryService(VectorMemoryStore vectorMemoryStore, MessageCodec messageCodec) {
        this.vectorMemoryStore = vectorMemoryStore;
        this.messageCodec = messageCodec;
    }

    public void saveSummary(String sessionId, String summary, int costTokens) {
        if (summary == null || summary.isBlank()) {
            return;
        }
        HashMap<String, Object> map = new HashMap<>();
        map.put(Constant.SUMMARY_COST, costTokens);
        AgentMessage am = new AgentMessage(
                UUID.randomUUID().toString().replace("-", ""),
                sessionId,
                Constant.CATEGORY_SUMMARY,
                ChatContentRole.SYSTEM_ROLE,
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

    /**
     * 将一次对话的用户/助手消息合并持久化到长期记忆。
     *
     * <p>异步执行（{@code ltmTaskExecutor} 线程池），避免 embedding + 向量入库
     * 阻塞对话输出（流式场景下保证 done 事件不被拖慢）。
     *
     * <p>两条消息合并为一次向量化请求写入，减少 embedding HTTP 调用次数。
     * 助手消息为空（如中途失败）时仅保存用户消息。
     */
    @Async("ltmTaskExecutor")
    public void saveChats(String sessionId, String userContent, String assistantContent,
                          int userCostTokens, int assistantCostTokens) {
        long now = System.currentTimeMillis();
        List<AgentMessage> messages = new ArrayList<>(2);
        if (userContent != null && !userContent.isBlank()) {
            messages.add(buildChatMessage(sessionId, userContent, ChatContentRole.USER_ROLE, userCostTokens, now));
        }
        // 助手消息时间戳 +1ms，保证按 createdAt 恢复上下文时 user 恒在 assistant 之前
        if (assistantContent != null && !assistantContent.isBlank()) {
            messages.add(buildChatMessage(sessionId, assistantContent, ChatContentRole.ASSISTANT_ROLE, assistantCostTokens, now + 1));
        }
        if (messages.isEmpty()) {
            return;
        }
        vectorMemoryStore.saveAll(messages);
        log.debug("会话记录已批量存入向量库 sessionId={} count={}", sessionId, messages.size());
    }

    private AgentMessage buildChatMessage(String sessionId, String chatContent, String role, int costTokens, long createdAt) {
        HashMap<String, Object> map = new HashMap<>();
        map.put(Constant.SUMMARY_COST, costTokens);
        return new AgentMessage(
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
                createdAt
        );
    }

    public String getLatestSummary(String sessionId) {
        Document doc = getLatestSummaryDoc(sessionId);
        return doc == null ? null : doc.getText();
    }

    /**
     * 获取该会话最近一次压缩摘要的 Document（含 createdAt 元数据，可定位压缩点）。
     *
     * <p>向量检索按相似度排序返回（空 query 下最相似未必是时间最新），
     * 故取一批候选后按 createdAt 取最大，确保拿到的是"最近一次"摘要。
     */
    public Document getLatestSummaryDoc(String sessionId) {
        List<Document> docs = vectorMemoryStore.searchByCategoryNoThreshold(
                sessionId, Constant.CATEGORY_SUMMARY, MAX_SUMMARY_CANDIDATES);
        return docs.stream()
                .filter(d -> d.getMetadata() != null)
                .max(Comparator.comparingLong(d -> toLong(d.getMetadata().get(Constant.MD_CREATED_AT))))
                .orElse(null);
    }

    private long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
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
            AgentMessage am = vectorMemoryStore.toAgentMessage(doc);
            if (am != null) {
                result.add(am);
            }
        }
        return result;
    }

    public Map<String, List<Map<String, String>>> getAllFacts(String sessionId) {
        List<Document> docs = vectorMemoryStore.searchByCategoryNoThreshold(sessionId, null, Constant.MAX_DEBUG_FACTS);
        Map<String, List<Map<String, String>>> allFacts = new LinkedHashMap<>();
        for (Document doc : docs) {
            String category = String.valueOf(doc.getMetadata().getOrDefault(Constant.MD_CATEGORY, Constant.CATEGORY_OTHER));
            allFacts.computeIfAbsent(category, k -> new ArrayList<>())
                    .add(Map.of(
                            "fact", doc.getText(),
                            "timestamp", String.valueOf(doc.getMetadata().getOrDefault(Constant.MD_CREATED_AT, ""))
                    ));
        }
        return allFacts;
    }

    public void deleteSessionFacts(String sessionId) {
        vectorMemoryStore.deleteSessionMemories(sessionId);
    }

    public List<Message> getStmMessage(String sessionId) {
        List<Message> rebuilt = new ArrayList<>();

        // 最近一次压缩摘要 = 压缩点。若从未压缩过（无摘要），则压缩点视为最早
        long compactPoint = -1;
        Document summaryDoc = getLatestSummaryDoc(sessionId);
        if (summaryDoc != null) {
            AgentMessage summary = vectorMemoryStore.toAgentMessage(summaryDoc);
            String summaryText = summary != null ? summary.text() : summaryDoc.getText();
            if (summaryText != null && !summaryText.isBlank()) {
                rebuilt.add(new SystemMessage(SystemPrompt.SUMMARY_PREFIX + summaryText));
            }
            compactPoint = summary != null ? summary.createdAt() : 0;
        }

        // 只回注压缩点之后的消息（向量库查询层按 createdAt > compactPoint 过滤），
        List<AgentMessage> chatMessages = getChatMessages(sessionId, compactPoint, Constant.MAX_CONTEXT_RESTORE_MESSAGES);
        if (CollectionUtils.isEmpty(chatMessages)) {
            return Collections.emptyList();
        }
        chatMessages.sort(Comparator.comparingLong(AgentMessage::createdAt));
        return messageCodec.toMessages(chatMessages);
    }
}
