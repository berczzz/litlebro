package com.litlebro.agent.service;

import com.litlebro.agent.attachment.AttachmentAssembler;
import com.litlebro.agent.attachment.resolver.AttachmentInput;
import com.litlebro.agent.common.Constant;
import com.litlebro.agent.context.ContextManager;
import com.litlebro.agent.context.SessionContextHolder;
import com.litlebro.agent.memory.LongTermMemoryService;
import com.litlebro.agent.session.SessionManager;
import com.litlebro.agent.session.model.SessionMemory;
import com.litlebro.agent.tool.ToolRegistry;
import com.litlebro.agent.tool.ToolResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.Media;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 核心业务服务，负责协调 LLM 调用、工具执行和记忆管理。
 *
 * <p>二层记忆架构
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
    private final AttachmentAssembler attachmentAssembler;
    private final ToolResolver toolResolver;

    public AgentService(ChatClient chatClient, ToolRegistry toolRegistry,
                        ContextManager contextManager,
                        LongTermMemoryService longTermMemoryService,
                        SessionManager sessionManager,
                        AttachmentAssembler attachmentAssembler,
                        ToolResolver toolResolver) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
        this.contextManager = contextManager;
        this.longTermMemoryService = longTermMemoryService;
        this.sessionManager = sessionManager;
        this.attachmentAssembler = attachmentAssembler;
        this.toolResolver = toolResolver;
    }

    public String chat(String userMessage) {
        return chat(userMessage, "default");
    }

    public String chat(String userMessage, String sessionId) {
        return chat(userMessage, sessionId, List.of());
    }

    public String chat(String userMessage, String sessionId, List<AttachmentInput> attachments) {
        return chat(userMessage, sessionId, attachments, List.of());
    }

    public String chat(String userMessage, String sessionId, List<AttachmentInput> attachments, List<String> skillIds) {
        return chat(userMessage, sessionId, attachments, skillIds, List.of());
    }

    public String chat(String userMessage, String sessionId, List<AttachmentInput> attachments,
                       List<String> skillIds, List<String> mcpServerIds) {
        log.info("会话 [{}] 收到问题: {}", sessionId, userMessage);
        SessionContextHolder.set(sessionId);
        try {
            // 统一解析本次工具集：技能可用名单写入线程上下文（工具内防御鉴权）+ MCP 按会话懒连接 + 提示片段
            ToolResolver.ResolvedTools resolved = toolResolver.resolve(sessionId, skillIds, mcpServerIds);

            // 短期记忆过期/为空时，从长期记忆回注最新摘要，找回历史记忆
            contextManager.restoreContextIfEmpty(sessionId);

            // 处理附件：图片直传 Media，文档/文本落盘登记 fileId 供 LLM 工具读取
            AttachmentAssembler.Result assembled = attachmentAssembler.build(userMessage, sessionId, attachments);
            List<Media> mediaList = assembled.media();
            String promptText = assembled.promptText();

            ChatClient.ChatClientRequestSpec prompt = chatClient.prompt();
            if (!mediaList.isEmpty()) {
                // 带媒体时走 messages 通道，确保 Media 随 UserMessage 一起入短记忆
                prompt.messages(List.of(new UserMessage(promptText, mediaList)));
            } else {
                prompt.user(promptText);
            }

            // 技能/MCP 模块有可用能力时，注入对应提示片段（已解析：技能 = global ∪ 会话记录 ∪ 请求名单；
            // MCP = global ∪ 会话记录，帮助 LLM 理解可用技能与 MCP 服务器）
            if (!resolved.skillFragment().isBlank()) {
                prompt.system(resolved.skillFragment());
            }
            if (!resolved.mcpFragment().isBlank()) {
                prompt.system(resolved.mcpFragment());
            }

            // 统一工具集：内置/技能经 ToolCallbacks 反射转换 + MCP 前缀化回调，已在 resolver 完成过滤
            ChatResponse response = prompt
                    .tools(resolved.tools())
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

                longTermMemoryService.saveChats(sessionId, promptText, content, promptTokens, completionTokens);
            }

            // 当前会话信息超出上下文配置 则进行压缩
            contextManager.compactIfNeeded(sessionId);

            log.info("会话 [{}] 回答完成", sessionId);
            return content;
        } catch (IllegalArgumentException e) {
            // 请求参数非法（如未注册/未启用的 skillId）直接抛出，由全局异常处理器转 400
            log.warn("会话 [{}] 请求参数非法: {}", sessionId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("会话 [{}] 处理失败", sessionId, e);
            return "抱歉，处理请求时出现错误: " + e.getMessage();
        } finally {
            // 清除线程局部会话上下文，避免线程池复用导致串号
            SessionContextHolder.clear();
        }
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

}