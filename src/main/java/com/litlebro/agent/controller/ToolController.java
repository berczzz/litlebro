package com.litlebro.agent.controller;

import com.litlebro.agent.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 工具管理 API。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /api/agent/tools — 工具列表（含 id/name/description/enabled）</li>
 *   <li>POST /api/agent/tools/{toolId}/disable — 按工具 ID 禁用（禁用后不再下发给大模型）</li>
 *   <li>POST /api/agent/tools/{toolId}/enable — 按工具 ID 恢复</li>
 * </ul>
 *
 * <p>工具 ID 默认取类名（首字母小写），启动即确定且跨重启稳定（见 {@link com.litlebro.agent.tool.AgentTool#id()}）。
 * 禁用为内存态，重启后恢复全部工具。
 */
@RestController
@RequestMapping("/api/agent/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;

    public ToolController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of(
                "tools", toolRegistry.getToolInfoList(),
                "description", "本Agent支持以下工具，LLM会根据用户问题自动选择合适的工具；enabled=false 的工具已禁用，不会下发给大模型"
        );
    }

    @PostMapping("/{toolId}/disable")
    public Map<String, Object> disable(@PathVariable("toolId") String toolId) {
        toolRegistry.disable(toolId);
        return Map.of("toolId", toolId, "disabled", true);
    }

    @PostMapping("/{toolId}/enable")
    public Map<String, Object> enable(@PathVariable("toolId") String toolId) {
        toolRegistry.enable(toolId);
        return Map.of("toolId", toolId, "enabled", true);
    }
}