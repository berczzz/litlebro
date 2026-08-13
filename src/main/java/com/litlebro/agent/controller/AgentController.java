package com.litlebro.agent.controller;

import com.litlebro.agent.dto.ChatRequest;
import com.litlebro.agent.dto.ChatResponse;
import com.litlebro.agent.service.AgentService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Agent REST API 控制器。
 *
 * <p>API 端点：
 * <ul>
 *   <li>POST /api/agent/chat — 核心对话接口</li>
 *   <li>GET /api/agent/tools — 查询可用工具列表</li>
 *   <li>GET /api/agent/session/{sessionId} — 查询会话轮次计数</li>
 *   <li>GET /api/agent/memory/{sessionId} — 查询会话长期记忆</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        String answer = agentService.chat(request.question(), request.sessionId());
        return ChatResponse.of(request.question(), answer, request.sessionId(), null);
    }

    @GetMapping("/tools")
    public Map<String, Object> tools() {
        return Map.of(
                "tools", agentService.getToolList(),
                "description", "本Agent支持以下工具，LLM会根据用户问题自动选择合适的工具"
        );
    }

    @GetMapping("/session/{sessionId}")
    public Map<String, Object> sessionInfo(@PathVariable("sessionId") String sessionId) {
        return agentService.getSessionInfo(sessionId);
    }

    @GetMapping("/memory/{sessionId}")
    public Map<String, Object> sessionMemory(@PathVariable("sessionId") String sessionId) {
        return agentService.getSessionMemory(sessionId);
    }
}