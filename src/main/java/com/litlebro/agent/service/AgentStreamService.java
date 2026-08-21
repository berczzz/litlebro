package com.litlebro.agent.service;

import com.litlebro.agent.attachment.AttachmentAssembler;
import com.litlebro.agent.attachment.resolver.AttachmentInput;
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
import com.litlebro.agent.tool.ToolResolver;
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
    private final ToolResolver toolResolver;

    public AgentStreamService(OpenAiSseClient sseClient,
                              StreamingToolExecutor toolExecutor,
                              OpenAiMessageConverter messageConverter,
                              AttachmentAssembler attachmentAssembler,
                              ContextManager contextManager,
                              ChatMemory chatMemory,
                              LongTermMemoryService longTermMemoryService,
                              SessionManager sessionManager,
                              ToolResolver toolResolver) {
        this.sseClient = sseClient;
        this.toolExecutor = toolExecutor;
        this.messageConverter = messageConverter;
        this.attachmentAssembler = attachmentAssembler;
        this.contextManager = contextManager;
        this.chatMemory = chatMemory;
        this.longTermMemoryService = longTermMemoryService;
        this.sessionManager = sessionManager;
        this.toolResolver = toolResolver;
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
    @Async("streamExecutor")
    public void streamChat(String userMessage, String sessionId, List<AttachmentInput> attachments, SseEmitter emitter) {
        streamChat(userMessage, sessionId, attachments, List.of(), List.of(), emitter);
    }

    /**
     * 同步校验技能/MCP 名单：未注册/未启用的 skillId 或 serverId 直接抛出，由全局异常处理器转 400。
     * 在控制器中于 {@code streamChat} 之前调用——流式处理在异步线程执行，异常无法再映射为 HTTP 400。
     *
     * @param sessionId     会话 ID
     * @param skillIds      请求声明要用的技能 ID 列表
     * @param mcpServerIds  请求声明要用的 MCP 服务器 ID 列表
     */
    public void validate(String sessionId, List<String> skillIds, List<String> mcpServerIds) {
        toolResolver.validate(sessionId, skillIds, mcpServerIds);
    }

    @Async("streamExecutor")
    public void streamChat(String userMessage, String sessionId, List<AttachmentInput> attachments,
                           List<String> skillIds, SseEmitter emitter) {
        streamChat(userMessage, sessionId, attachments, skillIds, List.of(), emitter);
    }

    @Async("streamExecutor")
    public void streamChat(String userMessage, String sessionId, List<AttachmentInput> attachments,
                           List<String> skillIds, List<String> mcpServerIds, SseEmitter emitter) {
        SessionContextHolder.set(sessionId);
        try {
            String model = sseClient.getModel();

            // 0. 等待上一轮后台压缩完成，保证读到一致上下文（超时则按旧上下文继续）
            contextManager.awaitCompactionIfPending(sessionId);

            // 1. 短期记忆为空时，从长期记忆回注最新摘要与增量消息，找回历史
            contextManager.restoreContextIfEmpty(sessionId);

            // 2. 处理附件：图片直传 Media，文档/文本落盘登记 fileId 供 LLM 工具读取
            // 必须先于 resolve：附件工具的暴露与检索路由的附件判定都依赖已登记的 fileId 注册表
            AttachmentAssembler.Result userContent = attachmentAssembler.build(userMessage, sessionId, attachments);

            // 统一解析本次工具集：技能可用名单写入线程上下文（工具内防御鉴权）+ MCP 按会话懒连接 +
            // 检索路由判定（按问题/历史/已登记附件 fileId 剔除不适用检索工具）+ 提示片段
            ToolResolver.ResolvedTools resolved = toolResolver.resolve(sessionId, skillIds, mcpServerIds, userMessage);

            // 3. 组装消息：system 提示词 + 可用能力片段 + 短期记忆历史 + 用户消息（含附件）
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", SystemPrompt.GENERAL));
            // 技能/MCP 模块有可用能力时，注入对应提示片段
            if (!resolved.skillFragment().isBlank()) {
                messages.add(Map.of("role", "system", "content", resolved.skillFragment()));
            }
            if (!resolved.mcpFragment().isBlank()) {
                messages.add(Map.of("role", "system", "content", resolved.mcpFragment()));
            }
            if (!resolved.routingFragment().isBlank()) {
                messages.add(Map.of("role", "system", "content", resolved.routingFragment()));
            }
            List<Message> history = chatMemory.get(sessionId, Integer.MAX_VALUE);
            if (history != null) {
                for (Message m : history) {
                    messages.add(messageConverter.toOpenAiMessage(m));
                }
            }
            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userContent.openAiContent());
            messages.add(userMsg);

            // 3. 调用前先写短期记忆用户消息（对齐 MessageChatMemoryAdvisor 的 before 行为）
            UserMessage stmUserMsg = userContent.media().isEmpty()
                    ? new UserMessage(userContent.promptText())
                    : new UserMessage(userContent.promptText(), userContent.media());
            chatMemory.add(sessionId, stmUserMsg);

            // 统一工具集：内置/技能经 ToolCallbacks 反射转换 + MCP 前缀化回调，已在 resolver 完成过滤
            toolExecutor.beginRequest(resolved.tools());

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

            // 5. 落记忆（对齐阻塞式）：短期记忆助手消息 + 会话状态 + 长期记忆
            String finalAnswer = finalContent.toString();
            if (!finalAnswer.isBlank()) {
                chatMemory.add(sessionId, new AssistantMessage(finalAnswer));
            }
            sessionManager.updateSession(sessionId, model, totalPrompt, totalCompletion);
            // 在请求线程内分配消息序号（与短期记忆追加顺序严格一致，压缩边界据此划界），
            // 长期记忆异步持久化，不阻塞 done 事件输出
            long firstSeq = sessionManager.nextMessageSeq(sessionId, 2);
            longTermMemoryService.saveChats(sessionId, userContent.promptText(), finalAnswer, totalPrompt, totalCompletion, firstSeq, firstSeq + 1);

            StreamEventSender.send(emitter, StreamEvent.TYPE_DONE,
                    Map.of("sessionId", sessionId, "model", model,
                            "usage", Map.of("promptTokens", totalPrompt,
                                    "completionTokens", totalCompletion,
                                    "totalTokens", totalPrompt + totalCompletion),
                            "toolCalls", invokedTools));
            emitter.complete();

            // 先交付 done 再触发压缩（A+）：压缩在后台异步执行，不阻塞事件流
            contextManager.triggerCompactionIfNeeded(sessionId);
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
            // 清除线程局部会话上下文与本次工具集，避免线程池复用导致串号
            toolExecutor.clearRequest();
            SessionContextHolder.clear();
        }
    }
}