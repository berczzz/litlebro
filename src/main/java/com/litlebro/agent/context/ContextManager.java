package com.litlebro.agent.context;

import com.litlebro.agent.common.Constant;
import com.litlebro.agent.memory.LongTermMemoryService;
import com.litlebro.agent.session.SessionManager;
import com.litlebro.agent.session.model.SessionMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 上下文管理器，负责后置检查 LLM 调用后的上下文是否溢出，溢出时触发压缩。
 *
 * <p>压缩采用 A+ 时序：先向调用方交付答案（阻塞式 return / 流式 done 事件），
 * 再在后台异步执行压缩；同一会话的并发请求通过"每会话锁"串行化，
 * 下一轮请求在入口处等待上一轮压缩完成，保证上下文一致。
 */
@Component
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    /** 上下文占用/窗口比值超过该阈值即触发压缩（默认 0.7） */
    private final double compactThreshold;
    /** 压缩时保留最近 N 条消息原文（0 = 全量压缩） */
    private final int keepMessages;
    /** 压缩边界的额外安全余量（序号）：保证保留的最近消息永不被回收/过滤 */
    private final int boundaryMargin;
    /** 等待会话压缩完成的最长时间（毫秒），超时则携带旧上下文继续（降级） */
    private final long compactWaitTimeoutMs;
    /**
     * 压缩时是否回收长期记忆中的旧原文（默认 false）。
     * 回收会删除 seq <= 压缩边界的 CATEGORY_CHAT 记录——原始对话一旦删除，
     * search_memory 回溯旧对话时便检索不到原文。默认保留全部原文供语义检索。
     */
    private final boolean reclaimOnCompact;

    private final ChatMemory chatMemory;
    private final CompressionService compressionService;
    private final LongTermMemoryService longTermMemoryService;
    private final SessionManager sessionManager;
    /** 模型上下文窗口大小（token），用于判断是否溢出 */
    private final int maxContextTokens;
    /** 后台压缩专用线程池（与 LTM 持久化线程池隔离，避免相互排队） */
    private final TaskExecutor compactionExecutor;

    /** 每会话压缩锁：sessionId -> 进行中的压缩 Future（完成后移除） */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> compactionLocks = new ConcurrentHashMap<>();

    public ContextManager(ChatMemory chatMemory,
                          CompressionService compressionService,
                          LongTermMemoryService longTermMemoryService,
                          SessionManager sessionManager,
                          @Qualifier("compactionTaskExecutor") TaskExecutor compactionExecutor,
                          @Value("${app.memory.context.max-tokens:128000}") int maxContextTokens,
                          @Value("${app.memory.context.compact-threshold:0.7}") double compactThreshold,
                          @Value("${app.memory.context.compact-keep-messages:6}") int keepMessages,
                          @Value("${app.memory.context.compact-boundary-margin:1}") int boundaryMargin,
                          @Value("${app.memory.context.compact-wait-timeout-ms:60000}") long compactWaitTimeoutMs,
                          @Value("${app.memory.context.reclaim-on-compact:false}") boolean reclaimOnCompact) {
        this.chatMemory = chatMemory;
        this.compressionService = compressionService;
        this.longTermMemoryService = longTermMemoryService;
        this.sessionManager = sessionManager;
        this.compactionExecutor = compactionExecutor;
        this.maxContextTokens = maxContextTokens;
        this.compactThreshold = compactThreshold;
        this.keepMessages = Math.max(0, keepMessages);
        this.boundaryMargin = Math.max(0, boundaryMargin);
        this.compactWaitTimeoutMs = compactWaitTimeoutMs;
        this.reclaimOnCompact = reclaimOnCompact;
    }

    /**
     * 请求入口处等待该会话尚未完成的压缩任务结束，确保读取到一致上下文。
     * 超时则携带旧上下文继续（降级路径，不阻塞用户过久）。
     */
    public void awaitCompactionIfPending(String sessionId) {
        CompletableFuture<Void> pending = compactionLocks.get(sessionId);
        if (pending == null) {
            return;
        }
        try {
            pending.get(compactWaitTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("等待会话压缩完成超时/中断，按旧上下文继续 sessionId={} 原因: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 检查当前会话上下文是否溢出；溢出则异步触发压缩（A+：不阻塞当前请求返回）。
     * 同一会话同一时刻只允许一个压缩任务在途（每会话锁），其余触发调用直接返回。
     */
    public void triggerCompactionIfNeeded(String sessionId) {
        SessionMemory session = sessionManager.get(sessionId);
        if (session == null) {
            return;
        }
        int curPromptTokens = session.curPromptTokens();
        if ((double) curPromptTokens / maxContextTokens <= compactThreshold) {
            return;
        }
        CompletableFuture<Void> fut = new CompletableFuture<>();
        CompletableFuture<Void> existing = compactionLocks.putIfAbsent(sessionId, fut);
        if (existing != null) {
            // 已有压缩在途：由下一轮请求的 awaitCompactionIfPending 等待其完成
            return;
        }
        compactionExecutor.execute(() -> {
            try {
                doCompact(sessionId);
            } catch (Exception e) {
                log.warn("会话后台压缩异常，跳过本轮压缩 sessionId={} 原因: {}", sessionId, e.getMessage());
            } finally {
                compactionLocks.remove(sessionId, fut);
                fut.complete(null);
            }
        });
    }

    /**
     * 短期记忆为空（首次对话、30 分钟闲置过期被 Redis 清理）时，
     * 从长期记忆（向量库）恢复上下文回注 ChatMemory：
     * 摘要 AssistantMessage（压缩边界）+ 边界之后的增量消息，避免模型"失忆"。
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

        // 保留最近 keepMessages 条消息原文，更早的旧消息参与压缩
        int splitIndex = Math.max(startIndex, allMessages.size() - keepMessages);
        List<Message> oldMessages = allMessages.subList(startIndex, splitIndex);
        List<Message> recentMessages = allMessages.subList(splitIndex, allMessages.size());
        if (oldMessages.isEmpty()) {
            return;
        }

        CompressionService.CompactionResult result = compressionService.compactHistory(oldMessages, previousSummary);
        if (result.isEmpty()) {
            return;
        }
        String summary = result.summary();
        List<String> facts = result.facts();

        // 压缩边界（消息序号）= 最近分配序号 - 保留条数 - 安全余量。
        // 保证：序号 > 边界的最近消息永不参与回收/永不被恢复过滤（杜绝"最近6条夹缝"丢失）。
        long lastSeq = sessionManager.getLastMessageSeq(sessionId);
        long compactPoint = lastSeq - keepMessages - boundaryMargin;

        longTermMemoryService.saveSummary(sessionId, summary, result.cost(), compactPoint);
        // 持久事实 round 替换（先删旧事实再写新事实），失败不阻断压缩主流程
        if (facts != null && !facts.isEmpty()) {
            longTermMemoryService.saveFacts(sessionId, facts);
        }
        // 默认不回收旧原文（保留全部 CATEGORY_CHAT 供 search_memory 语义检索回溯）；
        // 仅当显式开启 reclaim-on-compact 时才回收压缩边界之前的旧原文。
        if (reclaimOnCompact) {
            longTermMemoryService.reclaimHistoryBefore(sessionId, compactPoint);
        }

        sessionManager.resetCurTokens(sessionId);

        // 重建上下文：摘要 + 最近 N 条原文
        List<Message> rebuilt = new ArrayList<>();
        Map<String, Object> summaryMeta = new HashMap<>();
        summaryMeta.put("category", Constant.CATEGORY_SUMMARY);
        summaryMeta.put("createdAt", System.currentTimeMillis());
        rebuilt.add(new AssistantMessage(summary, summaryMeta));
        rebuilt.addAll(recentMessages);

        chatMemory.clear(sessionId);
        chatMemory.add(sessionId, rebuilt);

        log.info("上下文压缩完成 sessionId={} 摘要长度={} 事实数={} 保留最近消息={} 压缩边界序号={}",
                sessionId, summary.length(), facts != null ? facts.size() : 0, recentMessages.size(), compactPoint);
    }

    private String extractSummaryFromMessages(List<Message> messages) {
        if (messages.isEmpty()) {
            return null;
        }
        Message first = messages.get(0);
        if (first instanceof AssistantMessage
                && first.getMetadata() != null
                && Constant.CATEGORY_SUMMARY.equals(first.getMetadata().get("category"))) {
            return first.getText();
        }
        return null;
    }
}