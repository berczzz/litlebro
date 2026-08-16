package com.litlebro.agent.controller;

import com.litlebro.agent.skill.SkillService;
import com.litlebro.agent.skill.model.SkillDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 技能（Skills）管理 API。
 *
 * <p>端点（模块由 {@code app.skill.enabled=true} 开启）：
 * <ul>
 *   <li>POST /api/agent/skills — 注册技能（校验技能包目录与 SKILL.md，已存在返回 409）</li>
 *   <li>GET /api/agent/skills — 技能列表</li>
 *   <li>DELETE /api/agent/skills/{skillId} — 删除技能</li>
 *   <li>DELETE /api/agent/skills/records/{sessionId} — 清空会话已记录的技能（无过期时间，需显式清空）</li>
 * </ul>
 *
 * <p>技能与业务解耦：对话时通过请求 skillIds 启用技能并自动累加记录到会话，
 * 后续同会话无需再携带；global 技能无需记录。
 */
@RestController
@RequestMapping("/api/agent/skills")
@ConditionalOnProperty(name = "app.skill.enabled", havingValue = "true")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @PostMapping
    public SkillDefinition register(@RequestBody SkillDefinition skill) {
        return skillService.register(skill);
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("skills", skillService.listSkills());
    }

    @DeleteMapping("/{skillId}")
    public Map<String, Object> delete(@PathVariable String skillId) {
        skillService.delete(skillId);
        return Map.of("deleted", skillId);
    }

    @DeleteMapping("/records/{sessionId}")
    public Map<String, Object> clearSessionSkills(@PathVariable String sessionId) {
        skillService.clearSessionSkills(sessionId);
        return Map.of("sessionId", sessionId, "cleared", true);
    }
}