package com.litlebro.agent.router;

import com.litlebro.agent.attachment.AttachmentEntry;
import com.litlebro.agent.common.SystemPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * 检索前路由层：在进入主 LLM 前决定"本次请求需要检索哪个向量检索库"。
 *
 * <p>分层判定（成本从低到高）：
 * <ol>
 *   <li>附件指代优先：会话名下存在附件且问题引用附件（"这个文档/附件/上传的文件"）→ 目标 none，
 *       附件内容由 read_file/grep_file（按 fileId）读取，不拉全局文档库</li>
 *   <li>强文档知识库词 → document；强会话记忆词 → memory；两者都命中 → both</li>
 *   <li>弱/歧义词（文档/文件/数据/报表…）：不硬路由，启用 llm-fallback 时走一次
 *       轻量 LLM 分类（注入附件 fileId 清单与最近对话消解指代），否则默认 both 保召回</li>
 *   <li>无关闲聊 → none</li>
 *   <li>任何异常/LLM 调用失败 → both 兜底，不引入新误判</li>
 * </ol>
 *
 * <p>{@link #route} 返回 {@code null} 表示路由未启用（{@code app.router.enabled=false}），
 * 调用方（ToolResolver）应退回"全部检索工具常驻"的旧行为。
 *
 * <p>附件一律以注册表 {@link AttachmentEntry}（fileId 为主键）为输入，
 * 不接收裸文件名——主模型读取附件靠的是 fileId，路由层只据此判断问题是否指向附件。
 */
@Component
public class RetrievalRouter {

    private static final Logger log = LoggerFactory.getLogger(RetrievalRouter.class);

    private final RouterProperties props;
    /** 路由专用 ChatClient（{@code routerChatClient} Bean，仅在 {@code app.router.enabled=true} 时装配） */
    private final ObjectProvider<ChatClient> routerChatClientProvider;

    public RetrievalRouter(RouterProperties props,
                           @Qualifier("routerChatClient") ObjectProvider<ChatClient> routerChatClientProvider) {
        this.props = props;
        this.routerChatClientProvider = routerChatClientProvider;
    }

    /**
     * 判定本次请求的向量检索目标。
     *
     * <p>历史记忆采用懒加载：{@code historyProvider} 只在进 LLM 兜底分支（弱词 + llm-fallback）
     * 时才会被调用，其余分支（附件指代/强词/无信号）不会触碰短期记忆，避免每次请求都白查一次 STM。
     *
     * @param question        用户当前提问
     * @param historyProvider 最近对话历史的懒加载提供者（可为 null），用于消解"这个/那份"指代
     * @param attachments     会话名下已登记（落盘）的附件条目（fileId 主键），可为空
     * @return 检索目标；路由未启用或无有效提问时返回 null（表示不过滤）
     */
    public RetrievalTarget route(String question, Supplier<List<Message>> historyProvider,
                                 List<AttachmentEntry> attachments) {
        if (!props.isEnabled()) {
            return null;
        }
        if (question == null || question.isBlank()) {
            return null;
        }

        boolean hasAttachments = attachments != null && !attachments.isEmpty();

        // 1. 附件指代优先：会话有附件且问题引用附件 → none（附件由 read_file/grep_file 按 fileId 读取）
        boolean refAttachment = hasAttachments && containsAny(question, props.getAttachmentRefWords());
        boolean strongDoc = containsAny(question, props.getStrongDocumentKeywords());
        boolean strongMem = containsAny(question, props.getStrongMemoryKeywords());
        if (refAttachment) {
            // 同时问附件与知识库时降级为 both，避免丢失全局库检索
            return strongDoc ? RetrievalTarget.BOTH : RetrievalTarget.NONE;
        }

        // 2. 强规则硬路由
        if (strongDoc && strongMem) {
            return RetrievalTarget.BOTH;
        }
        if (strongDoc) {
            return RetrievalTarget.DOCUMENT;
        }
        if (strongMem) {
            return RetrievalTarget.MEMORY;
        }

        // 3. 弱/歧义词：不硬路由，LLM 兜底或默认 both
        if (containsAny(question, props.getWeakKeywords())) {
            if (props.isLlmFallback()) {
                // 此处才真正读取短期记忆（懒加载），供 LLM 消解指代
                return llmRoute(question,
                        historyProvider == null ? List.of() : historyProvider.get(),
                        attachments);
            }
            return RetrievalTarget.BOTH;
        }

        // 4. 无关闲聊：无需检索
        return RetrievalTarget.NONE;
    }

    /**
     * 轻量 LLM 兜底分类：结合问题、最近对话与附件 fileId 清单判断目标。
     *
     * <p>结构化输出（response-format=json_object/json_schema）下服务端保证返回合法 JSON，
     * 此处仍校验 target 语义值；解析失败或 target 非法时按 max-retries 有界重试
     * （把错误反馈给模型重出），重试耗尽统一返回 BOTH（保召回，不引入新误判）。
     */
    private RetrievalTarget llmRoute(String question, List<Message> recentHistory, List<AttachmentEntry> attachments) {
        ChatClient chatClient = routerChatClientProvider.getIfAvailable();
        if (chatClient == null) {
            log.warn("路由 LLM 不可用（routerChatClient 未装配），降级为 both 保召回");
            return RetrievalTarget.BOTH;
        }

        String lastError = null;
        int maxRetries = props.getLlm().getMaxRetries();
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String userText = buildUserPrompt(question, recentHistory, attachments);
                if (lastError != null) {
                    userText = userText + "\n\n【上一次输出不合规】" + lastError
                            + "\n请重新判定，严格只输出一个 JSON 对象：{\"target\": \"none|memory|document|both\", \"reason\": \"简短原因\"}";
                }
                RouterDecision decision = chatClient.prompt()
                        .system(SystemPrompt.ROUTER_CLASSIFY)
                        .user(userText)
                        .call()
                        .entity(RouterDecision.class);
                if (decision != null && decision.target() != null) {
                    RetrievalTarget mapped = switch (decision.target().trim().toLowerCase()) {
                        case "none" -> RetrievalTarget.NONE;
                        case "memory" -> RetrievalTarget.MEMORY;
                        case "document" -> RetrievalTarget.DOCUMENT;
                        case "both" -> RetrievalTarget.BOTH;
                        default -> null;
                    };
                    if (mapped != null) {
                        return mapped;
                    }
                    lastError = "target 值非法: " + decision.target() + "（只允许 none/memory/document/both）";
                } else {
                    lastError = "模型未返回可解析的结构化结果";
                }
            } catch (Exception e) {
                lastError = "解析失败: " + e.getMessage();
            }
            log.warn("路由 LLM 第 {} 次输出不合规，重试。原因: {}", attempt + 1, lastError);
        }
        log.warn("路由 LLM 重试 {} 次后仍失败，降级为 both 保召回", maxRetries);
        return RetrievalTarget.BOTH;
    }

    /** 组装路由 LLM 的用户提示：当前问题 + 最近对话（消解指代）+ 附件 fileId 清单 */
    private String buildUserPrompt(String question, List<Message> recentHistory, List<AttachmentEntry> attachments) {
        StringBuilder user = new StringBuilder("当前问题：").append(question).append("\n\n");
        if (recentHistory != null && !recentHistory.isEmpty()) {
            user.append("最近对话（供消解指代）：\n");
            for (Message m : recentHistory) {
                String role = switch (m.getMessageType()) {
                    case USER -> "用户";
                    case ASSISTANT -> "助手";
                    default -> String.valueOf(m.getMessageType());
                };
                user.append("- ").append(role).append(": ").append(truncate(m.getText(), 300)).append("\n");
            }
        }
        if (attachments != null && !attachments.isEmpty()) {
            user.append("\n会话名下已登记的附件（fileId 为唯一标识，文件名仅供识别）：\n");
            for (AttachmentEntry e : attachments) {
                user.append("- ").append(e.fileId())
                        .append(" : ").append(e.name() == null ? "(无文件名)" : e.name()).append("\n");
            }
            user.append("注意：附件内容只能通过 read_file / grep_file（按 fileId）读取；你只需判断问题是否引用附件，不要尝试按文件名访问附件。\n");
        }
        return user.toString();
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String kw : keywords) {
            if (kw != null && !kw.isBlank() && text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /**
     * 由路由目标生成中性的系统提示片段（注入主对话，强化路由边界）。
     * 返回空串表示无需注入。
     *
     * @param target 路由目标
     * @return 提示片段
     */
    public static String fragmentFor(RetrievalTarget target) {
        if (target == null) {
            return "";
        }
        return switch (target) {
            case MEMORY -> "本次请求的检索来源已确定为「当前会话记忆」，请只使用 search_memory 检索该来源，不要检索文档知识库。";
            case DOCUMENT -> "本次请求的检索来源已确定为「全局文档知识库」，请只使用 search_document 检索该来源，不要检索会话记忆。";
            case BOTH -> "本次请求可能同时涉及「会话记忆」与「文档知识库」，可按需分别使用 search_memory / search_document。";
            case NONE -> "";
        };
    }
}