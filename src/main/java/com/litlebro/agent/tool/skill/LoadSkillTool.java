package com.litlebro.agent.tool.skill;

import com.litlebro.agent.context.SessionContextHolder;
import com.litlebro.agent.skill.SkillService;
import com.litlebro.agent.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 加载技能说明工具：供 LLM 读取已启用技能的 SKILL.md，了解其用途与脚本用法。
 *
 * <p>采用渐进式披露：系统提示只注入可用技能 name+description，正文由本工具按需加载。
 */
@Component
@ConditionalOnProperty(name = "app.skill.enabled", havingValue = "true")
public class LoadSkillTool implements AgentTool, SkillTool {

    private static final Logger log = LoggerFactory.getLogger(LoadSkillTool.class);

    private final SkillService skillService;

    public LoadSkillTool(SkillService skillService) {
        this.skillService = skillService;
    }

    @Override
    public String name() {
        return "加载技能说明";
    }

    @Override
    public String description() {
        return "加载指定技能的说明文件（SKILL.md），返回该技能的用途、脚本清单与用法";
    }

    /**
     * 加载技能说明。
     *
     * @param skillId 技能 ID
     * @return SKILL.md 内容
     */
    @Tool(name = "load_skill", description = "加载指定技能（skillId）的说明文件（SKILL.md），返回该技能的用途、执行入口与脚本清单。仅对已在本请求启用的技能可用，未启用时会被拒绝")
    public String loadSkill(
            @ToolParam(description = "技能 ID（注册时的 skillId，即技能包目录名）") String skillId) {
        String sessionId = SessionContextHolder.get();
        log.info("load_skill执行开始 skillId={} sessionId={}", skillId, sessionId);
        return skillService.loadContent(skillId);
    }
}