package com.litlebro.agent.memory;

import com.litlebro.agent.common.ChatContentRole;
import com.litlebro.agent.common.Constant;
import com.litlebro.agent.memory.model.Memory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 向量记忆存储，封装 Spring AI VectorStore 实现语义记忆的写入和检索。
 *
 * <p>记忆按会话（sessionId）隔离，每条记忆记录所属会话，
 * 检索时通过 sessionId 过滤避免跨会话混淆。
 */
public class VectorMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(VectorMemoryStore.class);

    private final VectorStore vectorStore;

    public VectorMemoryStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void save(Memory memory) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("id", memory.id());
            metadata.put("sessionId", memory.sessionId());
            metadata.put("category", memory.category());
            metadata.put("type", Constant.MEMORY_TYPE);
            if (memory.metadata() != null) {
                metadata.putAll(memory.metadata());
            }
            metadata.putIfAbsent("createdAt", LocalDateTime.now().toString());
            metadata.putIfAbsent("updatedAt", LocalDateTime.now().toString());

            Document doc = Document.builder()
                    .text(memory.content())
                    .metadata(metadata)
                    .build();
            vectorStore.add(List.of(doc));
            log.debug("向量记忆已存储 sessionId={} category={} id={}", memory.sessionId(), memory.category(), memory.id());
        } catch (Exception e) {
            log.warn("向量记忆存储失败，已跳过写入 sessionId={} category={} 原因: {}",
                    memory.sessionId(), memory.category(), e.getMessage());
        }
    }

    public void storeMemory(String sessionId, String content, String category, String role, Map<String, Object> metadata) {
        this.save(new Memory(
                UUID.randomUUID().toString().replace("-", ""),
                sessionId,
                category,
                content,
                role,
                metadata
        ));
    }

    @Override
    public List<Memory> searchByCategory(String sessionId, String category, int limit) {
        List<Document> docs = searchMemories(sessionId, category, limit);
        List<Memory> memories = new ArrayList<>();
        for (Document doc : docs) {
            memories.add(toMemory(doc));
        }
        return memories;
    }

    @Override
    public Memory getById(String memoryId) {
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(memoryId)
                            .topK(1)
                            .filterExpression("id == '" + memoryId + "'")
                            .similarityThreshold(0.0)
                            .build()
            );
            return CollectionUtils.isEmpty(docs) ? null : toMemory(docs.get(0));
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

    public void deleteSessionMemories(String sessionId) {
        vectorStore.delete("sessionId == '" + sessionId + "'");
        log.info("向量记忆已删除 sessionId={}", sessionId);
    }

    private Memory toMemory(Document doc) {
        if (Objects.isNull(doc)) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
            Object value = entry.getValue();
            if (value != null) {
                metadata.put(entry.getKey(), value.toString());
            }
        }
        return new Memory(
                metadata.getOrDefault("id", doc.getId()).toString(),
                metadata.getOrDefault("sessionId", "").toString(),
                metadata.getOrDefault("category", Constant.CATEGORY_OTHER).toString(),
                doc.getText(),
                metadata.getOrDefault("role", "").toString(),
                metadata
        );
    }
}