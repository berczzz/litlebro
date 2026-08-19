package com.litlebro.agent.tool;

import com.litlebro.agent.attachment.AttachmentEntry;
import com.litlebro.agent.attachment.AttachmentRegistry;
import com.litlebro.agent.context.SessionContextHolder;
import com.litlebro.agent.memory.VectorMemoryStore;
import com.litlebro.agent.mcp.McpServerService;
import com.litlebro.agent.router.RetrievalRouter;
import com.litlebro.agent.router.RetrievalTarget;
import com.litlebro.agent.router.RouterProperties;
import com.litlebro.agent.skill.SkillService;
import com.litlebro.agent.skill.model.SkillDefinition;
import com.litlebro.agent.tool.skill.SkillTool;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话级工具解析器（统一下发入口）：一次调用决定"本次会话给模型哪些工具"。
 *
 * <p>三类工具在此归一化为统一的 {@link ToolCallback} 列表：
 * <ul>
 *   <li>内置/技能工具：{@link ToolRegistry#toToolArray} 的 AgentTool Bean，经
 *       {@link ToolCallbacks#from(Object...)} 反射 {@code @Tool} 方法转换；技能工具
 *       （{@link SkillTool}）在本次无可用技能时剔除（按会话解析）</li>
 *   <li>MCP 工具：{@link McpServerService#getSessionCallbacks} 按会话（global ∪ 记录）
 *       懒连接后返回的前缀化 ToolCallback，直接追加</li>
 * </ul>
 *
 * <p>注意：{@link ToolCallbacks#from(Object...)} 不识别 ToolCallback 实例（只反射 @Tool 方法），
 * 因此 MCP 回调不能混入同一数组，必须在转换后追加——这是本类"先转内置、再并 MCP"的原因。
 *
 * <p>检索路由过滤：内置工具转换后，按 {@link RetrievalRouter} 的路由结果
 * 剔除不适用的向量检索工具（search_memory / search_document），并在
 * 文档知识库为空时强制剔除 search_document；会话名下无任何附件时
 * 剔除 read_file / grep_file。路由未启用（{@code app.router.enabled=false}）时
 * 不做任何剔除，完全退回旧行为。
 *
 * <p>阻塞式与流式共用本类：阻塞式用 {@link #resolve} 的 tools 调 {@code ChatClient.tools(List)}，
 * 流式用同一结果调 {@code StreamingToolExecutor.beginRequest}，保证两链路工具集完全一致。
 */
@Component
public class ToolResolver {

    /** 会话记忆检索工具名 */
    private static final String TOOL_SEARCH_MEMORY = "search_memory";
    /** 文档知识库检索工具名 */
    private static final String TOOL_SEARCH_DOCUMENT = "search_document";
    /** 附件读取工具名 */
    private static final String TOOL_READ_FILE = "read_file";
    /** 附件内容检索工具名 */
    private static final String TOOL_GREP_FILE = "grep_file";

    private final ToolRegistry toolRegistry;
    private final ObjectProvider<SkillService> skillServiceProvider;
    private final ObjectProvider<McpServerService> mcpServiceProvider;
    private final RetrievalRouter retrievalRouter;
    private final VectorMemoryStore vectorMemoryStore;
    private final AttachmentRegistry attachmentRegistry;
    private final ChatMemory chatMemory;
    private final RouterProperties routerProperties;

    public ToolResolver(ToolRegistry toolRegistry,
                        ObjectProvider<SkillService> skillServiceProvider,
                        ObjectProvider<McpServerService> mcpServiceProvider,
                        RetrievalRouter retrievalRouter,
                        VectorMemoryStore vectorMemoryStore,
                        AttachmentRegistry attachmentRegistry,
                        ChatMemory chatMemory,
                        RouterProperties routerProperties) {
        this.toolRegistry = toolRegistry;
        this.skillServiceProvider = skillServiceProvider;
        this.mcpServiceProvider = mcpServiceProvider;
        this.retrievalRouter = retrievalRouter;
        this.vectorMemoryStore = vectorMemoryStore;
        this.attachmentRegistry = attachmentRegistry;
        this.chatMemory = chatMemory;
        this.routerProperties = routerProperties;
    }

    /**
     * 解析本次请求的完整工具集与提示片段。
     *
     * <p>副作用：技能/MCP 模块开启时，请求携带的 skillIds / mcpServerIds 会被累加记录到会话
     * （幂等）；技能可用名单写入 {@link SessionContextHolder} 供技能工具内防御性鉴权。
     *
     * <p>调用前提：附件必须先经 {@code AttachmentAssembler.build} 落盘登记
     * （fileId 写入附件注册表），本方法才能据此暴露 read_file/grep_file 并把
     * 附件 fileId 清单交给检索路由判定——不要在落盘前调用。
     *
     * @param sessionId     会话 ID
     * @param skillIds      请求声明的技能 ID 列表（可为空）
     * @param mcpServerIds  请求声明的 MCP 服务器 ID 列表（可为空）
     * @param question      用户当前提问（供检索路由判定，可为空串）
     * @return 统一工具回调列表 + 技能/MCP/路由系统提示片段
     */
    public ResolvedTools resolve(String sessionId, List<String> skillIds, List<String> mcpServerIds,
                                 String question) {
        // 技能：解析可用（校验+记录），写入线程上下文供技能工具内鉴权
        List<SkillDefinition> usable = List.of();
        String skillFragment = "";
        SkillService skillService = skillServiceProvider.getIfAvailable();
        if (skillService != null) {
            usable = skillService.resolveUsable(sessionId, skillIds);
            SessionContextHolder.setSkillIds(usable.stream().map(SkillDefinition::getSkillId).toList());
            skillFragment = skillService.getSystemPromptFragment(usable);
        }
        boolean includeSkillTools = !usable.isEmpty();

        // 内置/技能工具：反射 @Tool 转换（无可用技能时剔除技能工具）
        List<ToolCallback> callbacks = new ArrayList<>();
        callbacks.addAll(List.of(ToolCallbacks.from(
                toolRegistry.toToolArray(t -> includeSkillTools || !(t instanceof SkillTool)))));

        // 附件：会话名下已落盘登记的 fileId 条目（附件工具的暴露与检索路由的附件判定都基于注册表；
        // 经 bySession 会话索引直接命中，不扫描全库）
        List<AttachmentEntry> sessionAttachments = attachmentRegistry.bySession(sessionId);

        // 检索路由：历史记忆走懒加载（Supplier），仅 LLM 兜底分支才真正读短期记忆；
        // 路由未启用时 target 为 null，不做剔除
        RetrievalTarget target = retrievalRouter.route(question,
                () -> chatMemory.get(sessionId, routerProperties.getHistorySize()),
                sessionAttachments);
        if (target != null) {
            callbacks.removeIf(cb -> isRetrievalToolExcluded(cb, target));
        }
        // 空文档库：强制剔除 search_document（无论路由结论如何，无可检内容不下发工具）
        if (!vectorMemoryStore.hasDocuments()) {
            callbacks.removeIf(cb -> TOOL_SEARCH_DOCUMENT.equals(cb.getToolDefinition().name()));
        }
        // 空附件：会话名下无任何已登记附件时剔除附件工具
        if (sessionAttachments.isEmpty()) {
            callbacks.removeIf(cb -> TOOL_READ_FILE.equals(cb.getToolDefinition().name())
                    || TOOL_GREP_FILE.equals(cb.getToolDefinition().name()));
        }

        // MCP 工具：按会话解析并追加（已是 ToolCallback，直接并入）
        String mcpFragment = "";
        McpServerService mcpService = mcpServiceProvider.getIfAvailable();
        if (mcpService != null) {
            callbacks.addAll(mcpService.getSessionCallbacks(sessionId, mcpServerIds));
            mcpFragment = mcpService.getSystemPromptFragment(sessionId);
        }
        return new ResolvedTools(callbacks, skillFragment, mcpFragment,
                RetrievalRouter.fragmentFor(target));
    }

    /**
     * 按路由目标判断某个检索工具是否应剔除。
     */
    private boolean isRetrievalToolExcluded(ToolCallback cb, RetrievalTarget target) {
        String name = cb.getToolDefinition().name();
        return switch (target) {
            case NONE -> TOOL_SEARCH_MEMORY.equals(name) || TOOL_SEARCH_DOCUMENT.equals(name);
            case MEMORY -> TOOL_SEARCH_DOCUMENT.equals(name);
            case DOCUMENT -> TOOL_SEARCH_MEMORY.equals(name);
            case BOTH -> false;
        };
    }

    /**
     * 同步校验请求的技能/MCP 名单：未注册或未启用的 ID 抛 {@link IllegalArgumentException}（应用层转 400）。
     * 不建立连接、不记录，幂等，供流式端点在控制器线程同步校验（异步线程无法再映射 HTTP 状态码）。
     *
     * @param sessionId     会话 ID
     * @param skillIds      请求声明的技能 ID 列表（可为空）
     * @param mcpServerIds  请求声明的 MCP 服务器 ID 列表（可为空）
     */
    public void validate(String sessionId, List<String> skillIds, List<String> mcpServerIds) {
        SkillService skillService = skillServiceProvider.getIfAvailable();
        if (skillService != null) {
            skillService.resolveUsable(sessionId, skillIds);
        }
        McpServerService mcpService = mcpServiceProvider.getIfAvailable();
        if (mcpService != null) {
            mcpService.validate(sessionId, mcpServerIds);
        }
    }

    /**
     * 本次请求的解析结果：统一工具回调列表 + 技能/MCP/路由系统提示片段。
     *
     * @param tools          统一工具回调列表（内置/技能/MCP 合并，已过滤禁用与路由剔除）
     * @param skillFragment  技能系统提示片段（可为空串）
     * @param mcpFragment    MCP 系统提示片段（可为空串）
     * @param routingFragment 检索路由系统提示片段（可为空串，路由未启用时为空）
     */
    public record ResolvedTools(List<ToolCallback> tools, String skillFragment, String mcpFragment,
                                String routingFragment) {
    }
}