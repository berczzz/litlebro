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

import java.util.List;

/**
 * 执行技能脚本工具：供 LLM 运行已绑定技能 scripts/ 目录下的脚本并取回结果。
 *
 * <p>安全边界：只能执行"该技能自己声明的脚本"，scriptName 仅允许单文件名，
 * 解释器经扩展名/覆盖/shebang 判定并过白名单；任意命令与任意路径均不可执行。
 */
@Component
@ConditionalOnProperty(name = "app.skill.enabled", havingValue = "true")
public class ExecSkillTool implements AgentTool, SkillTool {

    private static final Logger log = LoggerFactory.getLogger(ExecSkillTool.class);

    private final SkillService skillService;

    public ExecSkillTool(SkillService skillService) {
        this.skillService = skillService;
    }

    @Override
    public String name() {
        return "执行技能脚本";
    }

    @Override
    public String description() {
        return "执行指定技能捆绑的脚本，返回脚本输出；只能执行该技能 scripts/ 目录下的脚本";
    }

    /**
     * 执行技能脚本。
     *
     * @param skillId    技能 ID
     * @param scriptName 脚本文件名（技能 scripts/ 目录下）
     * @param args       传给脚本的位置参数列表，可空
     * @return 执行结果（含退出码与输出）
     */
    @Tool(name = "exec_skill", description = "执行指定技能（skillId）scripts/ 目录下捆绑的脚本（scriptName），返回 stdout/stderr 输出。仅对已在本请求启用的技能可用；只能执行该技能自己的脚本，不能执行任意命令")
    public String execSkill(
            @ToolParam(description = "技能 ID（注册时的 skillId）") String skillId,
            @ToolParam(description = "脚本文件名（技能 scripts/ 目录下，如 clean.py），不带路径") String scriptName,
            @ToolParam(description = "传给脚本的位置参数列表，可为空数组") List<String> args) {
        String sessionId = SessionContextHolder.get();
        log.info("exec_skill执行开始 skillId={} script={} sessionId={}", skillId, scriptName, sessionId);
        return skillService.exec(skillId, scriptName, args);
    }
}