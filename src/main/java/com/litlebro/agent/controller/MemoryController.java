package com.litlebro.agent.controller;

import com.litlebro.agent.service.AgentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 会话记忆查询 API。
 *
 * <p>GET /api/agent/memory/{sessionId} — 查询会话长期记忆（最近摘要 + 短期消息 + 长期消息）
 */
@RestController
@RequestMapping("/api/agent/memory")
public class MemoryController {

    private final AgentService agentService;

    public MemoryController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/{sessionId}")
    public Map<String, Object> memory(@PathVariable("sessionId") String sessionId) {
        return agentService.getSessionMemory(sessionId);
    }
}