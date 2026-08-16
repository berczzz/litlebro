package com.litlebro.agent.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
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
 *   <li>工具禁用管理：按工具 ID 禁用/启用（状态存于 {@link ToolDisabledStore}，
 *       禁用后从给大模型的工具列表剔除，阻塞式与流式共用）</li>
 * </ul>
 *
 * <p>扩展预留：后续做权限控制（如 opencode 的 allow/deny/ask、按用户或会话
 * 限定可用工具）时，可在此处增加如 {@code resolveAllowedTools(userId)} 之类的方法，
 * 通过 name 过滤后再返回，调用方无需感知过滤逻辑。
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;
    /** 工具禁用状态存储（local 内存 / redis 持久，见 {@code app.tool.store.type}） */
    private final ToolDisabledStore disabledStore;

    /**
     * 构造器注入所有 AgentTool 实现，按名称建立索引。
     *
     * @param toolList      Spring 自动收集的全部工具 Bean
     * @param disabledStore 工具禁用状态存储
     */
    public ToolRegistry(List<AgentTool> toolList, ToolDisabledStore disabledStore) {
        this.tools = toolList.stream()
                .collect(Collectors.toUnmodifiableMap(AgentTool::name, Function.identity()));
        this.disabledStore = disabledStore;
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
     * 按工具 ID 查找工具（ID 默认取类名，见 {@link AgentTool#id()}）。
     *
     * @param id 工具 ID
     * @return 匹配的工具，不存在时返回空 Optional
     */
    public Optional<AgentTool> getById(String id) {
        return tools.values().stream().filter(t -> t.id().equals(id)).findFirst();
    }

    /**
     * 返回当前已注册的工具数量。
     *
     * @return 工具个数
     */
    public int size() {
        return tools.size();
    }

    // ==================== 禁用管理 ====================

    /**
     * 禁用指定工具：按 ID 查找后写入禁用状态存储。
     * 禁用后该工具不再进入给大模型的工具列表（阻塞式 ChatClient.tools 与流式 Schema 均剔除）。
     *
     * @param id 工具 ID
     * @throws IllegalArgumentException 工具 ID 不存在时抛出（应用层转 400）
     */
    public void disable(String id) {
        requireTool(id);
        disabledStore.disable(id);
    }

    /**
     * 恢复指定工具：按 ID 查找后从禁用状态存储移除。
     *
     * @param id 工具 ID
     * @throws IllegalArgumentException 工具 ID 不存在时抛出（应用层转 400）
     */
    public void enable(String id) {
        requireTool(id);
        disabledStore.enable(id);
    }

    /**
     * 指定工具 ID 是否处于禁用状态。
     *
     * @param id 工具 ID
     * @return true 表示已禁用
     */
    public boolean isDisabled(String id) {
        return disabledStore.isDisabled(id);
    }

    /**
     * 当前已禁用的工具 ID 列表。
     *
     * @return 禁用 ID 列表
     */
    public List<String> getDisabledIds() {
        return new ArrayList<>(disabledStore.getDisabledIds());
    }

    private void requireTool(String id) {
        if (getById(id).isEmpty()) {
            throw new IllegalArgumentException("工具不存在: " + id);
        }
    }

    /**
     * 组装工具信息列表（含 ID / 名称 / 描述 / 启用状态），供 {@code GET /api/agent/tools} 使用。
     * 已禁用的工具仍展示（enabled=false），便于界面查看与恢复。
     *
     * @return 工具信息列表
     */
    public List<Map<String, Object>> getToolInfoList() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentTool tool : tools.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", tool.id());
            entry.put("name", tool.name());
            entry.put("description", tool.description());
            entry.put("enabled", !disabledStore.isDisabled(tool.id()));
            result.add(entry);
        }
        return result;
    }

    /**
     * 将所有已注册的工具对象展开为数组，供 ChatClient.tools(Object...) 使用。
     * Spring AI 会根据数组中的 Bean 自动发现其 @Tool 方法。
     * 已禁用的工具不包含在内。
     *
     * @return 工具对象数组
     */
    public Object[] toToolArray() {
        return tools.values().stream()
                .filter(this::isEnabled)
                .toArray();
    }

    /**
     * 按过滤器展开工具对象数组，供按请求过滤工具集使用
     * （如：无可用技能时剔除 {@link com.litlebro.agent.tool.skill.SkillTool}）。
     * 已禁用的工具不包含在内。
     *
     * @param filter 工具过滤谓词，返回 true 的工具保留
     * @return 过滤后的工具对象数组
     */
    public Object[] toToolArray(Predicate<AgentTool> filter) {
        return tools.values().stream()
                .filter(filter)
                .filter(this::isEnabled)
                .toArray();
    }

    private boolean isEnabled(AgentTool tool) {
        return !disabledStore.isDisabled(tool.id());
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
