package com.litlebro.agent.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工具注册中心，统一收集和管理系统中所有可被 LLM 调用的工具。
 *
 * <p>通过构造器注入 Spring 容器中所有 {@link AgentTool} 的实现类，
 * 新增工具时无需修改本类，只要实现接口并声明为 Bean 即可自动注册。
 *
 * <p>职责：
 * <ul>
 *   <li>工具注册与索引：按工具名称建立快速查找索引</li>
 *   <li>工具列表查询：供系统提示词和 {@code GET /api/agent/tools} 接口使用</li>
 *   <li>工具批量注册：将全部工具对象展开，供 ChatClient.tools() 使用</li>
 * </ul>
 *
 * <p>扩展预留：后续做权限控制（如 opencode 的 allow/deny/ask、按用户或会话
 * 限定可用工具）时，可在此处增加如 {@code resolveAllowedTools(userId)} 之类的方法，
 * 通过 name 过滤后再返回，调用方无需感知过滤逻辑。
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;

    /**
     * 构造器注入所有 AgentTool 实现，按名称建立索引。
     *
     * @param toolList Spring 自动收集的全部工具 Bean
     */
    public ToolRegistry(List<AgentTool> toolList) {
        this.tools = toolList.stream()
                .collect(Collectors.toUnmodifiableMap(AgentTool::name, Function.identity()));
    }

    /**
     * 获取所有已注册的工具列表，顺序与注入顺序一致。
     *
     * @return 全部工具的可变列表，便于调用方自由使用
     */
    public List<AgentTool> getAll() {
        return new ArrayList<>(tools.values());
    }

    /**
     * 按名称查找工具。
     *
     * @param name 工具名称
     * @return 匹配的工具，不存在时返回空 Optional
     */
    public Optional<AgentTool> getByName(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 返回当前已注册的工具数量。
     *
     * @return 工具个数
     */
    public int size() {
        return tools.size();
    }

    /**
     * 将所有已注册的工具对象展开为数组，供 ChatClient.tools(Object...) 使用。
     * Spring AI 会根据数组中的 Bean 自动发现其 @Tool 方法。
     *
     * @return 工具对象数组
     */
    public Object[] toToolArray() {
        return tools.values().toArray();
    }

    /**
     * 获取所有工具的名称列表，仅保留不可修改的视图，防止外部误改。
     *
     * @return 工具名称的只读集合
     */
    public List<String> getNames() {
        return Collections.unmodifiableList(new ArrayList<>(tools.keySet()));
    }
}
