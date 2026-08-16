package com.litlebro.agent.controller;

import com.litlebro.agent.service.AgentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 会话状态查询 API。
 *
 * <p>GET /api/agent/session/{sessionId} — 查询会话轮次计数与 token 累积情况
 */
@RestController
@RequestMapping("/api/agent/session")
public class SessionController {

    private final AgentService agentService;

    public SessionController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/{sessionId}")
    public Map<String, Object> info(@PathVariable("sessionId") String sessionId) {
        return agentService.getSessionInfo(sessionId);
    }
}