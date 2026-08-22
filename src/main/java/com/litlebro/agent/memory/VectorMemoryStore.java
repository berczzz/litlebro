package com.litlebro.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litlebro.agent.common.Constant;
import com.litlebro.agent.memory.model.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * 向量记忆存储，封装 Spring AI VectorStore 实现语义记忆的写入和检索。
 *
 * <p>记忆按会话（sessionId）隔离，每条记忆记录所属会话，
 * 检索时通过 sessionId 过滤避免跨会话混淆。
 *
 * <p>物理存储布局经 {@link VectorStoreRouter} 屏蔽：本地单实例 / Milvus 多分片。
 * 文档知识库（category == document）走路由器专用存储，与会话记忆物理隔离。
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

    private final VectorStoreRouter router;

    /**
     * 文档切块并发入库线程池（{@code ingestExecutor}）。
     */
    private final AsyncTaskExecutor ingestExecutor;

    /**
     * 文档切块并发入库的并行度，来自 {@code app.rag.ingest-parallelism}。
     */
    private final int ingestParallelism;

    /**
     * 各 docId 已入库的切块数（进程内计数，用于 deleteByDocId 时回减）。
     */
    private final Map<String, Integer> docChunkCounts = new ConcurrentHashMap<>();

    /**
     * 文档切块总数。0 表示确定为空；-1 表示"无法精确计数但已知非空"哨兵
     * （进程重启后外部向量库仍有数据时使用，避免误判为空而隐藏 search_document）。
     */
    private volatile long documentCount = 0;

    /**
     * 是否已对底层向量库做过一次存在性探测（只探测一次，避免每次请求多一次 embedding）。
     */
    private volatile boolean documentStoreChecked = false;

    private static final long DOCUMENT_COUNT_UNKNOWN = -1;

    public VectorMemoryStore(VectorStoreRouter router, double similarityThreshold,
                             AsyncTaskExecutor ingestExecutor, int ingestParallelism) {
        this.router = router;
        this.similarityThreshold = similarityThreshold;
        this.ingestExecutor = ingestExecutor;
        this.ingestParallelism = Math.max(1, ingestParallelism);
    }

    @Override
    public void save(AgentMessage agentMessage) {
        saveAll(List.of(agentMessage));
    }

    /**
     * 批量存储多条记忆，合并为单次向量化请求写入向量库，
     * 减少 embedding HTTP 调用次数（一次对话的用户/助手消息合并入库）。
     *
     * <p>按消息所属会话路由到对应分片存储，同会话消息仍合并为单次写入。
     */
    public void saveAll(List<AgentMessage> agentMessages) {
        if (CollectionUtils.isEmpty(agentMessages)) {
            return;
        }
        try {
            Map<VectorStore, List<Document>> byStore = new LinkedHashMap<>();
            for (AgentMessage am : agentMessages) {
                Document doc = toDocument(am);
                if (doc == null) {
                    continue;
                }
                byStore.computeIfAbsent(router.forSession(am.sessionId()), k -> new ArrayList<>()).add(doc);
            }
            int total = 0;
            for (Map.Entry<VectorStore, List<Document>> entry : byStore.entrySet()) {
                entry.getKey().add(entry.getValue());
                total += entry.getValue().size();
            }
            log.debug("向量记忆已批量存储 count={}", total);
        } catch (Exception e) {
            log.warn("向量记忆存储失败，已跳过写入 原因: {}", e.getMessage());
        }
    }

    /** 将统一记忆实体转为向量库 Document；构建失败返回 null 由调用方跳过。 */
    private Document toDocument(AgentMessage agentMessage) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(Constant.MD_ID, agentMessage.id());
            metadata.put(Constant.MD_SESSION_ID, agentMessage.sessionId());
            metadata.put(Constant.MD_CATEGORY, agentMessage.category());
            metadata.put(Constant.MD_TYPE, Constant.MEMORY_TYPE);
            metadata.put(Constant.MD_MESSAGE_TYPE, agentMessage.messageType());
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

            return Document.builder()
                    .text(agentMessage.text())
                    .metadata(metadata)
                    .build();
        } catch (Exception e) {
            log.warn("向量记忆消息构建失败，已跳过 原因: {}", e.getMessage());
            return null;
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
    public AgentMessage getById(String sessionId, String memoryId) {
        try {
            List<Document> docs = router.forSession(sessionId).similaritySearch(
                    SearchRequest.builder()
                            .query(memoryId)
                            .topK(1)
                            .filterExpression("id == '" + memoryId + "'")
                            .similarityThreshold(0.0)
                            .build()
            );
            if (!CollectionUtils.isEmpty(docs)) {
                return toAgentMessage(docs.get(0));
            }
        } catch (Exception e) {
            log.warn("按 ID 查询长期记忆失败 sessionId={} id={} 原因: {}", sessionId, memoryId, e.getMessage());
        }
        return null;
    }

    @Override
    public void delete(String sessionId, String memoryId) {
        try {
            router.forSession(sessionId).delete("id == '" + memoryId + "'");
        } catch (Exception e) {
            log.warn("按 ID 删除长期记忆失败 sessionId={} id={} 原因: {}", sessionId, memoryId, e.getMessage());
        }
        log.debug("向量记忆已删除 sessionId={} id={}", sessionId, memoryId);
    }

    /**
     * 会话记忆语义检索：支持按分类列表检索。
     *
     * <p>为规避向量库 filter 表达式中 {@code IN + &&} 组合在不同实现下的兼容性坑
     * （部分实现会拼出非法表达式并静默返回空），这里按分类逐个执行 EQ 过滤检索，
     * 在内存中合并去重，再按相似度倒序截取 topK。检索跨全部相关分类。
     *
     * @param sessionId  会话 ID（路由到对应分片）
     * @param query      检索词
     * @param topK       返回条数上限
     * @param categories 参与检索的分类列表；null/空表示全部分类
     * @return 合并去重后的命中结果（按相似度倒序）
     */
    public List<Document> searchMemories(String sessionId, String query, int topK, List<String> categories) {
        try {
            VectorStore store = router.forSession(sessionId);
            List<Document> merged = new ArrayList<>();
            Set<String> seenIds = new HashSet<>();
            List<String> cats = (categories == null || categories.isEmpty())
                    ? List.of((String) null)
                    : categories;
            for (String category : cats) {
                String filter = category != null
                        ? "sessionId == '" + sessionId + "' && category == '" + category + "'"
                        : "sessionId == '" + sessionId + "'";
                List<Document> docs = store.similaritySearch(
                        SearchRequest.builder()
                                .query(query)
                                .topK(Math.max(topK, 10))
                                .filterExpression(filter)
                                .similarityThreshold(similarityThreshold)
                                .build()
                );
                for (Document doc : docs) {
                    String id = doc.getId();
                    if (id != null && seenIds.add(id)) {
                        merged.add(doc);
                    }
                }
            }
            merged.sort(Comparator.comparingDouble((Document d) ->
                    d.getScore() != null ? d.getScore() : -1.0).reversed());
            if (merged.size() > topK) {
                return new ArrayList<>(merged.subList(0, topK));
            }
            return merged;
        } catch (Exception e) {
            log.warn("长期记忆检索失败，已降级为空结果 sessionId={} query={} 原因: {}",
                    sessionId, query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 兼容旧签名：按全部分类检索（摘要 + 事实 + 原文统一候选池）。
     */
    public List<Document> searchMemories(String sessionId, String query, int topK) {
        return searchMemories(sessionId, query, topK, null);
    }

    public List<Document> searchByCategoryNoThreshold(String sessionId, String category, int limit) {
        try {
            String filter = category != null
                    ? "sessionId == '" + sessionId + "' && category == '" + category + "'"
                    : "sessionId == '" + sessionId + "'";
            return router.forSession(sessionId).similaritySearch(
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
            return router.forSession(sessionId).similaritySearch(
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

    /**
     * 删除某会话压缩边界之前的历史原文（category == chat_message 且 seq &lt;= maxSeq）。
     * 用于压缩完成后回收旧原文，只保留边界之后的增量消息与新摘要。
     * 序号为会话级单调递增（见 {@code LongTermMemoryService.saveChats}），
     * 与压缩时的边界计算（{@code ContextManager.doCompact}）严格对齐。
     *
     * <p>实现：先按序号枚举全部原文（阈值 -1 全量召回，不按相似度漏检），
     * 过滤出序号不高于边界的记录后按 ID 批量删除——避免依赖向量库表达式删除
     * 对不同实现（SimpleVectorStore 空查询按余弦排位会漏掉负相关记录）的一致性。
     *
     * @param sessionId 会话 ID
     * @param maxSeq    压缩边界序号，仅删除序号不大于该值的原文
     */
    public void deleteChatMessagesUpToSeq(String sessionId, long maxSeq) {
        if (maxSeq <= 0) {
            return;
        }
        try {
            String filter = "sessionId == '" + sessionId
                    + "' && category == '" + Constant.CATEGORY_CHAT
                    + "' && seq <= " + maxSeq;
            router.forSession(sessionId).delete(filter);
            log.debug("历史原文已回收 sessionId={} 边界序号={}", sessionId, maxSeq);
        } catch (Exception e) {
            log.warn("历史原文回收失败 sessionId={} 边界序号={} 原因: {}", sessionId, maxSeq, e.getMessage());
        }
    }

    /**
     * 按会话级消息序号范围检索原文：只返回 seq 大于 {@code afterSeq} 的聊天记录。
     * 用于短期记忆过期后按压缩边界恢复增量原文（与压缩时的划界逻辑一致，避免时间戳近似导致的"最近6条夹缝"）。
     * 阈值设为 -1 全量召回——按序号/时间过滤的查询语义是"取全部"，不应被相似度排位漏掉负相关记录。
     *
     * @param sessionId 会话 ID
     * @param afterSeq  起始序号（只返回序号大于该值的记录；-1 表示不过滤）
     * @param limit     返回条数上限
     */
    public List<Document> searchChatBySeqAfter(String sessionId, long afterSeq, int limit) {
        try {
            StringBuilder filter = new StringBuilder("sessionId == '" + sessionId + "'");
            filter.append(" && category == '").append(Constant.CATEGORY_CHAT).append("'");
            filter.append(" && seq > ").append(afterSeq);
            return router.forSession(sessionId).similaritySearch(
                    SearchRequest.builder()
                            .query("")
                            .topK(limit)
                            .filterExpression(filter.toString())
                            .similarityThreshold(0.0)
                            .build()
            );
        } catch (Exception e) {
            log.warn("序号范围检索失败 sessionId={} afterSeq={} 原因: {}", sessionId, afterSeq, e.getMessage());
            return List.of();
        }
    }

    /**
     * 删除会话的全部持久事实（round 替换：先删旧事实再写新事实，实现整轮去重）。
     */
    public void deleteSessionFacts(String sessionId) {
        try {
            router.forSession(sessionId).delete(
                    "sessionId == '" + sessionId + "' && category == '" + Constant.CATEGORY_FACT + "'");
            log.debug("会话持久事实已清空 sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("会话持久事实清空失败 sessionId={} 原因: {}", sessionId, e.getMessage());
        }
    }

    public void deleteSessionMemories(String sessionId) {
        router.forSession(sessionId).delete("sessionId == '" + sessionId + "'");
        log.info("向量记忆已删除 sessionId={}", sessionId);
    }

    /**
     * 批量存储文档切块（RAG 知识库，全局共享，不绑定会话，路由到文档专用存储）。
     * 每个切块携带 docId 与 source 元数据，便于按文档聚合与删除。
     *
     * @param chunks 切块后的 Document 列表（含 docId/source/category 元数据）
     */
    public void saveDocumentChunks(List<Document> chunks) {
        if (CollectionUtils.isEmpty(chunks)) {
            return;
        }
        VectorStore store = router.forDocument();
        try {
            if (ingestParallelism <= 1 || chunks.size() == 1) {
                store.add(chunks);
            } else {
                parallelAdd(store, chunks);
            }
            for (Document chunk : chunks) {
                Object docId = chunk.getMetadata().get(Constant.MD_DOC_ID);
                if (docId != null) {
                    docChunkCounts.merge(String.valueOf(docId), 1, Integer::sum);
                }
            }
            documentCount += chunks.size();
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
    private void parallelAdd(VectorStore store, List<Document> chunks) {
        int groups = Math.min(ingestParallelism, chunks.size());
        int perGroup = (chunks.size() + groups - 1) / groups;
        List<Future<?>> futures = new ArrayList<>(groups);
        for (int i = 0; i < chunks.size(); i += perGroup) {
            List<Document> group = chunks.subList(i, Math.min(chunks.size(), i + perGroup));
            futures.add(ingestExecutor.submit(() -> store.add(group)));
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
     * 全局检索文档知识库（category == document，无 sessionId 过滤，路由到文档专用存储）。
     *
     * @param query 检索词
     * @param topK  返回条数上限
     * @return 命中的文档切块列表
     */
    public List<Document> searchDocuments(String query, int topK) {
        try {
            return router.forDocument().similaritySearch(
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
            router.forDocument().delete("docId == '" + docId + "'");
            Integer removed = docChunkCounts.remove(docId);
            if (removed != null && documentCount > 0) {
                documentCount = Math.max(0, documentCount - removed);
            }
            log.info("文档切块已删除 docId={}", docId);
        } catch (Exception e) {
            log.warn("按文档 ID 删除切块失败 docId={} 原因: {}", docId, e.getMessage());
            throw e;
        }
    }

    /**
     * 判断文档知识库是否已有内容（是否应该暴露 search_document 工具）。
     *
     * <p>进程内计数优先；计数为 0 时会对底层向量库做一次存在性探测
     * （query 空串 + topK 1 + 阈值 0 + category==document 过滤），
     * 以覆盖"进程重启后外部向量库（Milvus）仍有历史数据"的场景。
     * 探测只执行一次并缓存结果，不随每次请求触发。
     *
     * @return true 表示知识库非空（应暴露 search_document）
     */
    public boolean hasDocuments() {
        if (documentCount > 0) {
            return true;
        }
        if (!documentStoreChecked) {
            documentStoreChecked = true;
            boolean found = !probeEmptyStore();
            if (found) {
                documentCount = DOCUMENT_COUNT_UNKNOWN;
            }
            return found;
        }
        return documentCount != 0;
    }

    /**
     * 探测文档知识库是否非空：返回是否存在至少 1 条 category==document 记录。
     * 空串查询被 embedding 服务拒绝时退回非空占位查询（topK+阈值 0 保证只要库非空必然命中）。
     */
    private boolean probeEmptyStore() {
        for (String query : new String[]{"", "查询"}) {
            try {
                List<Document> docs = router.forDocument().similaritySearch(
                        SearchRequest.builder()
                                .query(query)
                                .topK(1)
                                .filterExpression("category == '" + Constant.CATEGORY_DOCUMENT + "'")
                                .similarityThreshold(0.0)
                                .build()
                );
                return CollectionUtils.isEmpty(docs);
            } catch (Exception e) {
                log.warn("文档库存在性探测失败（query='{}'），尝试下一占位查询 原因: {}", query, e.getMessage());
            }
        }
        // 全部探测失败时假定非空，避免误隐藏工具
        return false;
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
        String messageTypeStr = String.valueOf(metadata.getOrDefault(Constant.MD_MESSAGE_TYPE, ""));
        MessageType messageType;
        try {
            messageType = MessageType.valueOf(messageTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            messageType = MessageType.USER;
        }
        long createdAt = toLong(metadata.get(Constant.MD_CREATED_AT));

        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String k = entry.getKey();
            if (Constant.MD_ID.equals(k) || Constant.MD_SESSION_ID.equals(k) || Constant.MD_CATEGORY.equals(k)
                    || Constant.MD_TYPE.equals(k) || Constant.MD_MESSAGE_TYPE.equals(k)
                    || Constant.MD_CREATED_AT.equals(k)
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