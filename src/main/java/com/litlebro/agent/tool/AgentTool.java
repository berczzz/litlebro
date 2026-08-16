package com.litlebro.agent.tool;

/**
 * Agent 工具的通用抽象，所有可被 LLM 调用的工具都应实现本接口。
 *
 * <p>设计目的：将"工具"从具体的 Bean 中抽象出来，使工具具备统一的元数据
 * （名称、描述），从而支持：
 * <ul>
 *   <li>动态注册与发现：通过 {@link ToolRegistry} 统一管理所有工具</li>
 *   <li>动态提示词：系统提示词不再硬编码工具清单，避免新增工具时漏改</li>
 *   <li>权限控制：后续可按工具名称做粒度化的授权（如 opencode 的 allow/deny/ask）</li>
 *   <li>工具列表查询：{@code GET /api/agent/tools} 从注册中心动态生成</li>
 * </ul>
 *
 * <p>实现约定：每个工具类使用 {@code @Tool} 注解标注具体方法，
 * Spring AI 会自动将其注册为 LLM 可调用的函数；本接口只负责提供工具的
 * 业务元数据，二者互补，互不冲突。
 *
 * <p>新增工具时只需：实现本接口 + 提供 {@code @Tool} 方法 + 声明为 Spring Bean，
 * 即可被 ToolRegistry 自动收集，无需改动 AgentService 等调用方。
 */
public interface AgentTool {

    /**
     * 工具唯一名称，用于注册中心的索引、权限控制和界面展示。
     * 应保持稳定且全局唯一，例如"日期时间工具"、"天气查询工具"。
     *
     * @return 工具名称
     */
    String name();

    /**
     * 工具功能描述，用于界面展示和生成系统提示词。
     * 描述应概括该工具能做什么，帮助用户和 LLM 快速理解。
     *
     * @return 工具描述
     */
    String description();

    /**
     * 工具唯一 ID，用于禁用/启用等管理操作（禁用后从给大模型的工具列表剔除）。
     * 默认取类名（首字母小写），启动即确定且跨重启稳定，避免重启后禁用关系失效；
     * 实现类可按需覆盖。
     *
     * @return 工具 ID
     */
    default String id() {
        String simple = getClass().getSimpleName();
        return simple.isEmpty() ? name() : Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }
}
