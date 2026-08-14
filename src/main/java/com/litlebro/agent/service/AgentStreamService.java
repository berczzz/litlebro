package com.litlebro.agent.service;

import com.litlebro.agent.attachment.AttachmentAssembler;
import com.litlebro.agent.attachment.resolver.AttachmentInput;
import com.litlebro.agent.common.ChatContentRole;
import com.litlebro.agent.common.SystemPrompt;
import com.litlebro.agent.context.ContextManager;
import com.litlebro.agent.context.SessionContextHolder;
import com.litlebro.agent.dto.StreamEvent;
import com.litlebro.agent.memory.LongTermMemoryService;
import com.litlebro.agent.service.stream.OpenAiMessageConverter;
import com.litlebro.agent.service.stream.OpenAiSseClient;
import com.litlebro.agent.service.stream.StreamEventSender;
import com.litlebro.agent.service.stream.StreamingToolExecutor;
import com.litlebro.agent.service.stream.ToolCall;
import com.litlebro.agent.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式对话业务服务（编排层）。
 *
 * <p>与 {@link AgentService}（阻塞式）互补：通过 SSE 逐段推送事件
 * <ul>
 *   <li>思考过程（reasoning_content）实时输出</li>
 *   <li>工具调用（名称 + 参数）与执行结果实时展示</li>
 *   <li>最终回答增量输出</li>
 * </ul>
 *
 * <p>底层能力拆分为独立组件（复用而非重复实现）：
 * <ul>
 *   <li>{@link OpenAiSseClient} — 原始 SSE 流式调用与分块解析（含 reasoning_content）</li>
 *   <li>{@link StreamingToolExecutor} — 工具 Schema 组装与执行回填</li>
 *   <li>{@link OpenAiMessageConverter} — Spring AI Message 转 OpenAI 协议消息</li>
 *   <li>{@link AttachmentAssembler} — 附件组装（与阻塞式 {@link AgentService} 共用）</li>
 * </ul>
 * 本类只负责编排：恢复上下文 → 组装消息 → 模型-工具循环 → 落记忆。
 *
 * <p><b>记忆行为与阻塞式 {@link AgentService#chat} 完全一致</b>：
 * <ul>
 *   <li>短期记忆（STM）：调用前写入用户消息、调用后写入助手消息；并注入历史上下文，
 *       空时先从长期记忆回注（restoreContextIfEmpty）</li>
 *   <li>长期记忆（LTM）：按轮次将用户/助手消息与 token 用量异步写入向量库</li>
 *   <li>会话状态（SessionManager）：更新轮次、模型与累积 token</li>
 *   <li>上下文溢出时触发 compaction 压缩</li>
 * </ul>
 * 工具（search_memory / read_file / grep_file）通过 {@link SessionContextHolder}
 * 感知当前会话 ID，与阻塞式一致。
 */
@Service
public class AgentStreamService {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamService.class);

    /** 单轮对话中模型-工具循环的最大轮数，防止模型反复调用工具陷入死循环 */
    private static final int MAX_TOOL_ROUNDS = 8;

    private final OpenAiSseClient sseClient;
    private final StreamingToolExecutor toolExecutor;
    private final OpenAiMessageConverter messageConverter;
    private final AttachmentAssembler attachmentAssembler;
    private final ContextManager contextManager;
    private final ChatMemory chatMemory;
    private final LongTermMemoryService longTermMemoryService;
    private final SessionManager sessionManager;

    public AgentStreamService(OpenAiSseClient sseClient,
                              StreamingToolExecutor toolExecutor,
                              OpenAiMessageConverter messageConverter,
                              AttachmentAssembler attachmentAssembler,
                              ContextManager contextManager,
                              ChatMemory chatMemory,
                              LongTermMemoryService longTermMemoryService,
                              SessionManager sessionManager) {
        this.sseClient = sseClient;
        this.toolExecutor = toolExecutor;
        this.messageConverter = messageConverter;
        this.attachmentAssembler = attachmentAssembler;
        this.contextManager = contextManager;
        this.chatMemory = chatMemory;
        this.longTermMemoryService = longTermMemoryService;
        this.sessionManager = sessionManager;
    }

    /**
     * 发起一次流式对话，结果通过 SSE 逐段推送。
     *
     * <p>记忆链路与阻塞式 {@code AgentService.chat} 对齐：
     * <ol>
     *   <li>短期记忆为空时先从长期记忆回注上下文</li>
     *   <li>注入短期记忆历史 + system 提示词 + 用户消息</li>
     *   <li>调用前将用户消息写入短期记忆（对齐 MessageChatMemoryAdvisor）</li>
     *   <li>流式驱动模型-工具循环，累积最终回答与 token 用量</li>
     *   <li>结束后写短期记忆助手消息、更新会话状态、异步写长期记忆、按需压缩</li>
     * </ol>
     *
     * @param userMessage 用户提问
     * @param sessionId   会话标识
     * @param attachments 附件列表，可为空
     * @param emitter     客户端 SSE 连接
     */
    @Async
    public void streamChat(String userMessage, String sessionId, List<AttachmentInput> attachments, SseEmitter emitter) {
        SessionContextHolder.set(sessionId);
        try {
            String model = sseClient.getModel();

            // 1. 短期记忆为空时，从长期记忆回注最新摘要与增量消息，找回历史
            contextManager.restoreContextIfEmpty(sessionId);

            // 2. 组装消息：system 提示词 + 短期记忆历史 + 用户消息（含附件）
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", SystemPrompt.GENERAL));
            List<Message> history = chatMemory.get(sessionId, Integer.MAX_VALUE);
            if (history != null) {
                for (Message m : history) {
                    messages.add(messageConverter.toOpenAiMessage(m));
                }
            }
            AttachmentAssembler.Result userContent = attachmentAssembler.build(userMessage, sessionId, attachments);
            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userContent.openAiContent());
            messages.add(userMsg);

            // 3. 调用前先写短期记忆用户消息（对齐 MessageChatMemoryAdvisor 的 before 行为）
            UserMessage stmUserMsg = userContent.media().isEmpty()
                    ? new UserMessage(userContent.promptText())
                    : new UserMessage(userContent.promptText(), userContent.media());
            chatMemory.add(sessionId, stmUserMsg);

            StreamEventSender.send(emitter, StreamEvent.TYPE_START, Map.of("sessionId", sessionId, "model", model));

            // 4. 模型-工具循环，累积最终回答与各轮 token 用量
            StringBuilder finalContent = new StringBuilder();
            int totalPrompt = 0;
            int totalCompletion = 0;
            List<String> invokedTools = new ArrayList<>();
            int rounds = 0;
            while (rounds++ < MAX_TOOL_ROUNDS) {
                OpenAiSseClient.TurnResult turn = sseClient.streamTurn(messages, toolExecutor.getToolSchemas(), emitter);
                Map<String, Object> usage = turn.usage();
                totalPrompt += ((Number) usage.getOrDefault("promptTokens", 0)).intValue();
                totalCompletion += ((Number) usage.getOrDefault("completionTokens", 0)).intValue();
                finalContent.append(turn.content());
                if (turn.toolCalls().isEmpty()) {
                    break;
                }
                // 将模型的工具调用声明回填为 assistant 消息（OpenAI 协议要求）
                messages.add(toolExecutor.buildAssistantToolCallMessage(turn.toolCalls()));
                for (ToolCall tc : turn.toolCalls()) {
                    invokedTools.add(tc.name());
                    String result = toolExecutor.execute(tc, emitter);
                    messages.add(toolExecutor.buildToolResultMessage(tc, result));
                }
            }

            // 5. 落记忆（对齐阻塞式）：短期记忆助手消息 + 会话状态 + 长期记忆 + 压缩
            String finalAnswer = finalContent.toString();
            if (!finalAnswer.isBlank()) {
                chatMemory.add(sessionId, new AssistantMessage(finalAnswer));
            }
            sessionManager.updateSession(sessionId, model, totalPrompt, totalCompletion);
            // 长期记忆异步持久化，不阻塞 done 事件输出
            longTermMemoryService.saveChat(sessionId, userContent.promptText(), ChatContentRole.USER_ROLE, totalPrompt);
            if (!finalAnswer.isBlank()) {
                longTermMemoryService.saveChat(sessionId, finalAnswer, ChatContentRole.ASSISTANT_ROLE, totalCompletion);
            }
            contextManager.compactIfNeeded(sessionId);

            StreamEventSender.send(emitter, StreamEvent.TYPE_DONE,
                    Map.of("sessionId", sessionId, "model", model,
                            "usage", Map.of("promptTokens", totalPrompt,
                                    "completionTokens", totalCompletion,
                                    "totalTokens", totalPrompt + totalCompletion),
                            "toolCalls", invokedTools));
            emitter.complete();
        } catch (Exception e) {
            log.error("流式会话 [{}] 处理失败", sessionId, e);
            String message = (e.getMessage() == null || e.getMessage().isBlank())
                    ? e.getClass().getSimpleName() : e.getMessage();
            try {
                StreamEventSender.send(emitter, StreamEvent.TYPE_ERROR, Map.of("message", message));
                emitter.complete();
            } catch (Exception ex) {
                log.warn("流式会话 [{}] 推送错误事件失败: {}", sessionId, ex.getMessage());
                emitter.completeWithError(e);
            }
        } finally {
            // 清除线程局部会话上下文，避免线程池复用导致串号
            SessionContextHolder.clear();
        }
    }
}