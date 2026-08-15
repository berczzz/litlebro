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
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.Future;

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

    /**
     * 语义检索相似度阈值，低于该值的记录视为不相关。
     *
     * <p>由配置项 {@code app.memory.vector.similarity-threshold} 注入，
     * 默认 0.3。text-embedding 模型的余弦相似度对相关文本通常在 0.4~0.6，
     * 阈值不宜设太高，否则会把相关片段全部过滤掉。
     */
    private final double similarityThreshold;

    private final VectorStore vectorStore;

    /**
     * 文档切块并发入库线程池（{@code ingestExecutor}）。
     */
    private final AsyncTaskExecutor ingestExecutor;

    /**
     * 文档切块并发入库的并行度，来自 {@code app.rag.ingest-parallelism}。
     */
    private final int ingestParallelism;

    public VectorMemoryStore(VectorStore vectorStore, double similarityThreshold,
                             AsyncTaskExecutor ingestExecutor, int ingestParallelism) {
        this.vectorStore = vectorStore;
        this.similarityThreshold = similarityThreshold;
        this.ingestExecutor = ingestExecutor;
        this.ingestParallelism = Math.max(1, ingestParallelism);
    }

    @Override
    public void save(AgentMessage agentMessage) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(Constant.MD_ID, agentMessage.id());
            metadata.put(Constant.MD_SESSION_ID, agentMessage.sessionId());
            metadata.put(Constant.MD_CATEGORY, agentMessage.category());
            metadata.put(Constant.MD_TYPE, Constant.MEMORY_TYPE);
            metadata.put(Constant.MD_MESSAGE_TYPE, agentMessage.messageType());
            metadata.put(Constant.MD_ROLE, agentMessage.role());
            if (agentMessage.metadata() != null) {
                metadata.putAll(agentMessage.metadata());
            }
            long now = agentMessage.createdAt() > 0 ? agentMessage.createdAt() : System.currentTimeMillis();
            metadata.putIfAbsent(Constant.MD_CREATED_AT, now);
            metadata.putIfAbsent(Constant.MD_UPDATED_AT, now);
            if (agentMessage.media() != null && !agentMessage.media().isEmpty()) {
                metadata.put(Constant.MD_MEDIA, OBJECT_MAPPER.writeValueAsString(agentMessage.media()));
            }
            if (agentMessage.toolCalls() != null && !agentMessage.toolCalls().isEmpty()) {
                metadata.put(Constant.MD_TOOL_CALLS, OBJECT_MAPPER.writeValueAsString(agentMessage.toolCalls()));
            }
            if (agentMessage.toolResponses() != null && !agentMessage.toolResponses().isEmpty()) {
                metadata.put(Constant.MD_TOOL_RESPONSES, OBJECT_MAPPER.writeValueAsString(agentMessage.toolResponses()));
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
        List<Document> docs = searchByCategoryNoThreshold(sessionId, category, limit);
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
                            .similarityThreshold(similarityThreshold)
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

    /**
     * 批量存储文档切块（RAG 知识库，全局共享，不绑定会话）。
     * 每个切块携带 docId 与 source 元数据，便于按文档聚合与删除。
     *
     * @param chunks 切块后的 Document 列表（含 docId/source/category 元数据）
     */
    public void saveDocumentChunks(List<Document> chunks) {
        if (CollectionUtils.isEmpty(chunks)) {
            return;
        }
        try {
            if (ingestParallelism <= 1 || chunks.size() == 1) {
                vectorStore.add(chunks);
            } else {
                parallelAdd(chunks);
            }
            log.info("文档切块已入库 count={}", chunks.size());
        } catch (Exception e) {
            log.warn("文档切块入库失败 原因: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * 将切块分为 {@code ingestParallelism} 组并发写入向量库，每组内部由向量库
     * 实现按 {@code embed-batch-size} 分批 embedding（本地 BatchingSimpleVectorStore /
     * Milvus batchingStrategy 均适用）。等待全部组完成，任一组失败则抛出异常。
     */
    private void parallelAdd(List<Document> chunks) {
        int groups = Math.min(ingestParallelism, chunks.size());
        int perGroup = (chunks.size() + groups - 1) / groups;
        List<Future<?>> futures = new ArrayList<>(groups);
        for (int i = 0; i < chunks.size(); i += perGroup) {
            List<Document> group = chunks.subList(i, Math.min(chunks.size(), i + perGroup));
            futures.add(ingestExecutor.submit(() -> vectorStore.add(group)));
        }
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                throw new RuntimeException("文档切块并行入库失败", e);
            }
        }
    }

    /**
     * 全局检索文档知识库（category == document，无 sessionId 过滤）。
     *
     * @param query 检索词
     * @param topK  返回条数上限
     * @return 命中的文档切块列表
     */
    public List<Document> searchDocuments(String query, int topK) {
        try {
            return vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .filterExpression("category == '" + Constant.CATEGORY_DOCUMENT + "'")
                            .similarityThreshold(similarityThreshold)
                            .build()
            );
        } catch (Exception e) {
            log.warn("文档知识库检索失败 query={} 原因: {}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 按文档 ID 删除该文档的全部切块。
     *
     * @param docId 文档唯一标识
     */
    public void deleteByDocId(String docId) {
        try {
            vectorStore.delete("docId == '" + docId + "'");
            log.info("文档切块已删除 docId={}", docId);
        } catch (Exception e) {
            log.warn("按文档 ID 删除切块失败 docId={} 原因: {}", docId, e.getMessage());
            throw e;
        }
    }

    /**
     * 将向量库 Document 还原为统一记忆实体 AgentMessage。
     * 供同包业务服务（LongTermMemoryService）复用，避免重复转换逻辑。
     */
    public AgentMessage toAgentMessage(Document doc) {
        if (Objects.isNull(doc)) {
            return null;
        }
        Map<String, Object> metadata = doc.getMetadata();
        String id = String.valueOf(metadata.getOrDefault(Constant.MD_ID, doc.getId()));
        String sessionId = String.valueOf(metadata.getOrDefault(Constant.MD_SESSION_ID, ""));
        String category = String.valueOf(metadata.getOrDefault(Constant.MD_CATEGORY, Constant.CATEGORY_OTHER));
        String messageType = String.valueOf(metadata.getOrDefault(Constant.MD_MESSAGE_TYPE, ""));
        String role = String.valueOf(metadata.getOrDefault(Constant.MD_ROLE, ""));
        long createdAt = toLong(metadata.get(Constant.MD_CREATED_AT));

        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String k = entry.getKey();
            if (Constant.MD_ID.equals(k) || Constant.MD_SESSION_ID.equals(k) || Constant.MD_CATEGORY.equals(k)
                    || Constant.MD_TYPE.equals(k) || Constant.MD_MESSAGE_TYPE.equals(k)
                    || Constant.MD_ROLE.equals(k) || Constant.MD_CREATED_AT.equals(k)
                    || Constant.MD_UPDATED_AT.equals(k) || Constant.MD_MEDIA.equals(k)
                    || Constant.MD_TOOL_CALLS.equals(k) || Constant.MD_TOOL_RESPONSES.equals(k)) {
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
                fromJson(metadata.get(Constant.MD_MEDIA), new TypeReference<>() {
                }),
                fromJson(metadata.get(Constant.MD_TOOL_CALLS), new TypeReference<>() {
                }),
                fromJson(metadata.get(Constant.MD_TOOL_RESPONSES), new TypeReference<>() {
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

