package com.litlebro.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litlebro.agent.common.Constant;
import com.litlebro.agent.memory.model.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.util.CollectionUtils;

import java.util.*;

/**
 * 向量记忆存储，封装 Spring AI VectorStore 实现语义记忆的写入和检索。
 *
 * <p>记忆按会话（sessionId）隔离，每条记忆记录所属会话，
 * 检索时通过 sessionId 过滤避免跨会话混淆。
 *
 * <p>存储对象与短期记忆共用统一的 {@link AgentMessage} 实体：
 * 文本（text）用于向量化语义检索，嵌套结构（media/toolCalls/toolResponses）
 * 以 JSON 字符串形式存入 Document 元数据，读取时反序列化还原。
 */
public class VectorMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(VectorMemoryStore.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final VectorStore vectorStore;

    public VectorMemoryStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void save(AgentMessage agentMessage) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("id", agentMessage.id());
            metadata.put("sessionId", agentMessage.sessionId());
            metadata.put("category", agentMessage.category());
            metadata.put("type", Constant.MEMORY_TYPE);
            metadata.put("messageType", agentMessage.messageType());
            metadata.put("role", agentMessage.role());
            if (agentMessage.metadata() != null) {
                metadata.putAll(agentMessage.metadata());
            }
            long now = agentMessage.createdAt() > 0 ? agentMessage.createdAt() : System.currentTimeMillis();
            metadata.putIfAbsent("createdAt", now);
            metadata.putIfAbsent("updatedAt", now);
            if (agentMessage.media() != null && !agentMessage.media().isEmpty()) {
                metadata.put("media", OBJECT_MAPPER.writeValueAsString(agentMessage.media()));
            }
            if (agentMessage.toolCalls() != null && !agentMessage.toolCalls().isEmpty()) {
                metadata.put("toolCalls", OBJECT_MAPPER.writeValueAsString(agentMessage.toolCalls()));
            }
            if (agentMessage.toolResponses() != null && !agentMessage.toolResponses().isEmpty()) {
                metadata.put("toolResponses", OBJECT_MAPPER.writeValueAsString(agentMessage.toolResponses()));
            }

            Document doc = Document.builder()
                    .text(agentMessage.text())
                    .metadata(metadata)
                    .build();
            vectorStore.add(List.of(doc));
            log.debug("向量记忆已存储 sessionId={} category={} id={}", agentMessage.sessionId(), agentMessage.category(), agentMessage.id());
        } catch (Exception e) {
            log.warn("向量记忆存储失败，已跳过写入 sessionId={} category={} 原因: {}",
                    agentMessage.sessionId(), agentMessage.category(), e.getMessage());
        }
    }

    @Override
    public List<AgentMessage> searchByCategory(String sessionId, String category, int limit) {
        List<Document> docs = searchMemories(sessionId, category, limit);
        List<AgentMessage> messages = new ArrayList<>();
        for (Document doc : docs) {
            AgentMessage am = toAgentMessage(doc);
            if (am != null) {
                messages.add(am);
            }
        }
        return messages;
    }

    @Override
    public AgentMessage getById(String memoryId) {
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(memoryId)
                            .topK(1)
                            .filterExpression("id == '" + memoryId + "'")
                            .similarityThreshold(0.0)
                            .build()
            );
            return CollectionUtils.isEmpty(docs) ? null : toAgentMessage(docs.get(0));
        } catch (Exception e) {
            log.warn("按 ID 查询长期记忆失败 id={} 原因: {}", memoryId, e.getMessage());
            return null;
        }
    }

    @Override
    public void delete(String memoryId) {
        try {
            vectorStore.delete("id == '" + memoryId + "'");
            log.debug("向量记忆已删除 id={}", memoryId);
        } catch (Exception e) {
            log.warn("按 ID 删除长期记忆失败 id={} 原因: {}", memoryId, e.getMessage());
        }
    }

    public List<Document> searchMemories(String sessionId, String query, int topK) {
        try {
            return vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .filterExpression("sessionId == '" + sessionId + "'")
                            .similarityThreshold(0.65)
                            .build()
            );
        } catch (Exception e) {
            log.warn("长期记忆检索失败，已降级为空结果 sessionId={} query={} 原因: {}",
                    sessionId, query, e.getMessage());
            return List.of();
        }
    }

    public List<Document> searchByCategoryNoThreshold(String sessionId, String category, int limit) {
        try {
            String filter = category != null
                    ? "sessionId == '" + sessionId + "' && category == '" + category + "'"
                    : "sessionId == '" + sessionId + "'";
            return vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("")
                            .topK(limit)
                            .filterExpression(filter)
                            .similarityThreshold(0.0)
                            .build()
            );
        } catch (Exception e) {
            log.warn("无阈值检索失败 sessionId={} category={} 原因: {}", sessionId, category, e.getMessage());
            return List.of();
        }
    }

    /**
     * 按时间范围检索：仅返回 createdAt 时间戳晚于 {@code afterTimestamp} 的记录。
     * 时间范围在向量库查询层过滤，避免拉取全量数据再内存筛选。
     *
     * @param sessionId      会话 ID
     * @param category       分类，可为 null 表示不过滤分类
     * @param afterTimestamp 起始时间戳（epoch millis），只返回 createdAt &gt; 该值的记录
     * @param limit          返回条数上限
     */
    public List<Document> searchByCategoryAfter(String sessionId, String category, long afterTimestamp, int limit) {
        try {
            StringBuilder filter = new StringBuilder("sessionId == '" + sessionId + "'");
            if (category != null) {
                filter.append(" && category == '").append(category).append("'");
            }
            filter.append(" && createdAt > ").append(afterTimestamp);
            return vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("")
                            .topK(limit)
                            .filterExpression(filter.toString())
                            .similarityThreshold(0.0)
                            .build()
            );
        } catch (Exception e) {
            log.warn("时间范围检索失败 sessionId={} category={} 原因: {}", sessionId, category, e.getMessage());
            return List.of();
        }
    }

    public void deleteSessionMemories(String sessionId) {
        vectorStore.delete("sessionId == '" + sessionId + "'");
        log.info("向量记忆已删除 sessionId={}", sessionId);
    }

    private AgentMessage toAgentMessage(Document doc) {
        if (Objects.isNull(doc)) {
            return null;
        }
        Map<String, Object> metadata = doc.getMetadata();
        String id = String.valueOf(metadata.getOrDefault("id", doc.getId()));
        String sessionId = String.valueOf(metadata.getOrDefault("sessionId", ""));
        String category = String.valueOf(metadata.getOrDefault("category", Constant.CATEGORY_OTHER));
        String messageType = String.valueOf(metadata.getOrDefault("messageType", ""));
        String role = String.valueOf(metadata.getOrDefault("role", ""));
        long createdAt = toLong(metadata.get("createdAt"));

        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String k = entry.getKey();
            if ("id".equals(k) || "sessionId".equals(k) || "category".equals(k) || "type".equals(k)
                    || "messageType".equals(k) || "role".equals(k) || "createdAt".equals(k)
                    || "updatedAt".equals(k) || "media".equals(k) || "toolCalls".equals(k)
                    || "toolResponses".equals(k)) {
                continue;
            }
            extra.put(k, entry.getValue());
        }

        return new AgentMessage(
                id,
                sessionId,
                category,
                messageType,
                role,
                doc.getText(),
                extra,
                fromJson(metadata.get("media"), new TypeReference<>() {
                }),
                fromJson(metadata.get("toolCalls"), new TypeReference<>() {
                }),
                fromJson(metadata.get("toolResponses"), new TypeReference<>() {
                }),
                createdAt
        );
    }

    private static <T> List<T> fromJson(Object value, TypeReference<List<T>> type) {
        if (value == null) {
            return List.of();
        }
        try {
            List<T> list = OBJECT_MAPPER.readValue(value.toString(), type);
            return list != null ? list : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static long toLong(Object value) {
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
