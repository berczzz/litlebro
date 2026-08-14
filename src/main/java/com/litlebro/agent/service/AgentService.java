package com.litlebro.agent.service;

import com.litlebro.agent.attachment.AttachmentStore;
import com.litlebro.agent.attachment.resolver.AttachmentInput;
import com.litlebro.agent.attachment.resolver.AttachmentResolverFactory;
import com.litlebro.agent.attachment.resolver.ResolvedAttachment;
import com.litlebro.agent.common.ChatContentRole;
import com.litlebro.agent.common.Constant;
import com.litlebro.agent.context.ContextManager;
import com.litlebro.agent.context.SessionContextHolder;
import com.litlebro.agent.memory.LongTermMemoryService;
import com.litlebro.agent.session.SessionManager;
import com.litlebro.agent.session.model.SessionMemory;
import com.litlebro.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 核心业务服务，负责协调 LLM 调用、工具执行和记忆管理。
 *
 * <p>二层记忆架构（对齐 opencode）：
 * <ul>
 *   <li>短期记忆（STM）：ChatMemory + MessageChatMemoryAdvisor，自动管理最近对话历史</li>
 *   <li>长期记忆（LTM）：LongTermMemoryService 向量库，存储压缩摘要</li>
 * </ul>
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final ChatClient chatClient;
    private final ToolRegistry toolRegistry;
    private final ContextManager contextManager;
    private final LongTermMemoryService longTermMemoryService;
    private final SessionManager sessionManager;
    private final ChatMemory chatMemory;
    private final AttachmentStore attachmentStore;
    private final AttachmentResolverFactory attachmentResolverFactory;

    public AgentService(ChatClient chatClient, ToolRegistry toolRegistry,
                        ContextManager contextManager,
                        LongTermMemoryService longTermMemoryService,
                        SessionManager sessionManager,
                        ChatMemory chatMemory,
                        AttachmentStore attachmentStore,
                        AttachmentResolverFactory attachmentResolverFactory) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
        this.contextManager = contextManager;
        this.longTermMemoryService = longTermMemoryService;
        this.sessionManager = sessionManager;
        this.chatMemory = chatMemory;
        this.attachmentStore = attachmentStore;
        this.attachmentResolverFactory = attachmentResolverFactory;
    }

    public String chat(String userMessage) {
        return chat(userMessage, "default");
    }

    public String chat(String userMessage, String sessionId) {
        return chat(userMessage, sessionId, List.of());
    }

    public String chat(String userMessage, String sessionId, List<AttachmentInput> attachments) {
        log.info("会话 [{}] 收到问题: {}", sessionId, userMessage);
        SessionContextHolder.set(sessionId);
        try {
            // 短期记忆过期/为空时，从长期记忆回注最新摘要，找回历史记忆
            restoreContextIfEmpty(sessionId);

            // 处理附件：图片直传 Media，文档/文本落盘登记 fileId 供 LLM 工具读取
            List<Media> mediaList = new ArrayList<>();
            String promptText = buildAttachmentPrompt(userMessage, sessionId, attachments, mediaList);

            ChatClient.ChatClientRequestSpec prompt = chatClient.prompt();
            if (!mediaList.isEmpty()) {
                // 带媒体时走 messages 通道，确保 Media 随 UserMessage 一起入短记忆
                prompt.messages(List.of(new UserMessage(promptText, mediaList)));
            } else {
                prompt.user(promptText);
            }

            ChatResponse response = prompt
                    .tools(toolRegistry.toToolArray())
                    .advisors(a -> a.param(MessageChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, sessionId))
                    .call()
                    .chatResponse();

            AssistantMessage output = response != null && response.getResult() != null
                    ? response.getResult().getOutput() : null;
            String content = output != null ? output.getText() : null;
            if (content == null || content.isBlank()) {
                log.warn("会话 [{}] LLM 返回内容为空，可能有未完成的工具调用", sessionId);
                content = "";
            }

            if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                Usage usage = response.getMetadata().getUsage();
                String model = response.getMetadata().getModel();
                int promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
                int completionTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;

                sessionManager.updateSession(sessionId, model, promptTokens, completionTokens);

                longTermMemoryService.saveChat(sessionId, promptText, ChatContentRole.USER_ROLE, promptTokens);
                longTermMemoryService.saveChat(sessionId, content, ChatContentRole.ASSISTANT_ROLE, completionTokens);
            }

            // 当前会话信息超出上下文配置 则进行压缩
            contextManager.compactIfNeeded(sessionId);

            log.info("会话 [{}] 回答完成", sessionId);
            return content;
        } catch (Exception e) {
            log.error("会话 [{}] 处理失败", sessionId, e);
            return "抱歉，处理请求时出现错误: " + e.getMessage();
        } finally {
            // 清除线程局部会话上下文，避免线程池复用导致串号
            SessionContextHolder.clear();
        }
    }

    /**
     * 组装附件提示词：
     * <ul>
     *   <li>图片附件 → 直接转为 {@link Media} 加入 mediaList 直传模型（不落盘）</li>
     *   <li>文档/文本附件 → 落盘登记 fileId，并在提示词中列出 fileId，
     *       告知 LLM 用 read_file / grep_file 工具读取内容</li>
     * </ul>
     *
     * @return 拼接后的用户提示词（含附件说明）
     */
    private String buildAttachmentPrompt(String userMessage, String sessionId,
                                         List<AttachmentInput> attachments, List<Media> mediaList) {
        if (attachments == null || attachments.isEmpty()) {
            return userMessage;
        }
        StringBuilder fileNotice = new StringBuilder();
        int imageCount = 0;
        for (AttachmentInput input : attachments) {
            ResolvedAttachment resolved;
            try {
                resolved = attachmentResolverFactory.resolve(input);
            } catch (IOException e) {
                log.warn("附件解析失败 name={} 原因: {}", input.name(), e.getMessage());
                fileNotice.append("\n[附件解析失败: ").append(input.name()).append(" - ").append(e.getMessage()).append("]");
                continue;
            }
            String mime = resolved.mimeType() != null ? resolved.mimeType() : "application/octet-stream";
            if (isImageMime(mime)) {
                try {
                    MimeType mt = MimeType.valueOf(mime);
                    mediaList.add(new Media(mt, new ByteArrayResource(resolved.bytes())));
                    imageCount++;
                } catch (Exception e) {
                    log.warn("图片附件组装 Media 失败 name={} 原因: {}", resolved.name(), e.getMessage());
                }
            } else {
                try {
                    String fileId = attachmentStore.store(sessionId, resolved);
                    fileNotice.append("\n- ").append(resolved.name())
                            .append(" [fileId: ").append(fileId)
                            .append(", mime: ").append(mime).append("]");
                } catch (IOException e) {
                    log.warn("附件落盘失败 name={} 原因: {}", resolved.name(), e.getMessage());
                    fileNotice.append("\n[附件存储失败: ").append(resolved.name()).append(" - ").append(e.getMessage()).append("]");
                }
            }
        }
        if (imageCount == 0 && fileNotice.isEmpty()) {
            return userMessage;
        }
        StringBuilder sb = new StringBuilder(userMessage);
        if (imageCount > 0) {
            sb.append("\n\n[图片已随本条消息直接提供，请结合图片内容回答]");
        }
        if (!fileNotice.isEmpty()) {
            sb.append("\n\n[随消息附带以下文件，回答时可使用 read_file / grep_file 工具读取文件内容]")
                    .append(fileNotice);
        }
        return sb.toString();
    }

    private boolean isImageMime(String mime) {
        return mime != null && mime.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    public List<String> getToolList() {
        List<String> tools = new ArrayList<>();
        for (var tool : toolRegistry.getAll()) {
            tools.add(tool.name() + ": " + tool.description());
        }
        return tools;
    }

    public Map<String, Object> getSessionInfo(String sessionId) {
        SessionMemory session = sessionManager.get(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        if (session != null) {
            result.put("turnCount", session.metadata().getOrDefault("turnCount", 0));
            result.put("model", session.model());
            result.put("totalUseTokens", session.totalUseTokens());
            result.put("totalPromptTokens", session.totalPromptTokens());
            result.put("totalCompletionTokens", session.totalCompletionTokens());
            result.put("curUseTokens", session.curUseTokens());
            result.put("curPromptTokens", session.curPromptTokens());
            result.put("curCompletionTokens", session.curCompletionTokens());
        } else {
            result.put("turnCount", 0);
        }
        result.put("ttlSeconds", -1);
        return result;
    }

    public Map<String, Object> getSessionMemory(String sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("lastSummary", longTermMemoryService.getLatestSummary(sessionId));
        result.put("stmMessages", longTermMemoryService.getStmMessage(sessionId));
        // 调试接口：内存记忆较多时可截断，避免一次拉取全量
        result.put("ltmMessages", longTermMemoryService.getChatMessages(sessionId, 0, Constant.MAX_DEBUG_MEMORY_MESSAGES));
        return result;
    }

    /**
     * 短期记忆为空（首次对话、30 分钟闲置过期被 Redis 清理）时，
     * 从长期记忆（向量库）恢复上下文回注 ChatMemory：
     * 摘要 SystemMessage（压缩点）+ 压缩点之后的增量消息，避免模型"失忆"。
     */
    private void restoreContextIfEmpty(String sessionId) {
        List<Message> existing = chatMemory.get(sessionId, Integer.MAX_VALUE);
        if (existing != null && !existing.isEmpty()) {
            return;
        }

        // 在长期记忆中查询短期记忆
        List<Message> rebuilt = longTermMemoryService.getStmMessage(sessionId);
        if (rebuilt.isEmpty()) {
            return;
        }
        chatMemory.add(sessionId, rebuilt);
        log.info("短期记忆为空，已从长期记忆恢复上下文：增量消息数={} sessionId={}", rebuilt.size(), sessionId);
    }


}