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
 * 读取技能包文件工具：供 LLM 按需读取已绑定技能包内的参考文件（references/ assets/ 等）。
 *
 * <p>与 load_skill 互补：SKILL.md 引用到的参考文档、模板等由本工具按相对路径读取，
 * 支持渐进式披露，避免一次把技能包全部内容塞进上下文。
 */
@Component
@ConditionalOnProperty(name = "app.skill.enabled", havingValue = "true")
public class ReadSkillFileTool implements AgentTool, SkillTool {

    private static final Logger log = LoggerFactory.getLogger(ReadSkillFileTool.class);

    private final SkillService skillService;

    public ReadSkillFileTool(SkillService skillService) {
        this.skillService = skillService;
    }

    @Override
    public String name() {
        return "读取技能包文件";
    }

    @Override
    public String description() {
        return "读取指定技能包内的参考文件（references/ assets/ 等），返回文本内容";
    }

    /**
     * 读取技能包内相对路径文件。
     *
     * @param skillId      技能 ID
     * @param relativePath 技能包内相对路径，如 references/usage.md
     * @return 文件文本
     */
    @Tool(name = "read_skill_file", description = "读取指定技能（skillId）包内的参考文件（references/ 或 assets/ 目录），返回文本内容。仅对已在本请求启用的技能可用；相对路径不得越出技能包目录")
    public String readSkillFile(
            @ToolParam(description = "技能 ID（注册时的 skillId）") String skillId,
            @ToolParam(description = "技能包内相对路径，如 references/usage.md") String relativePath) {
        String sessionId = SessionContextHolder.get();
        log.info("read_skill_file执行开始 skillId={} path={} sessionId={}", skillId, relativePath, sessionId);
        return skillService.readFile(skillId, relativePath);
    }
}