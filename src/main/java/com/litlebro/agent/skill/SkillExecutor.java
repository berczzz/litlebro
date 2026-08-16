package com.litlebro.agent.skill;

import com.litlebro.agent.skill.model.SkillExecRequest;
import com.litlebro.agent.skill.model.SkillExecResult;

/**
 * 技能脚本执行器抽象。
 *
 * <p>v1 仅提供 {@link LocalSkillExecutor}（本地进程同步执行）；
 * 后续如需 Docker 沙箱 / 远程执行服务，只需实现本接口并按配置装配，
 * 调用方（SkillService）无需感知执行方式差异。
 */
public interface SkillExecutor {

    /** 标记值：直接执行可执行文件（不经解释器） */
    String DIRECT_EXEC = "__DIRECT__";

    /**
     * 执行技能脚本。
     *
     * @param request 执行请求（脚本路径已由上层校验）
     * @return 执行结果（含输出与超时/截断标记）
     */
    SkillExecResult exec(SkillExecRequest request);
}