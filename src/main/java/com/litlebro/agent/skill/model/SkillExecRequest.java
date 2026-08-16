package com.litlebro.agent.skill.model;

import com.litlebro.agent.skill.SkillExecutor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 技能脚本执行请求。
 *
 * @param scriptName     脚本文件名（仅展示用）
 * @param scriptPath     脚本绝对路径（已校验落在技能目录 scripts/ 内）
 * @param interpreter    解释器名称；{@link SkillExecutor#DIRECT_EXEC} 表示直接执行可执行文件
 * @param workdir        工作目录（技能目录）
 * @param args           位置参数
 * @param env            额外注入的环境变量
 * @param timeoutMs      执行超时（毫秒）
 * @param maxOutputChars 输出截断上限（字符）
 */
public record SkillExecRequest(
        String scriptName,
        Path scriptPath,
        String interpreter,
        Path workdir,
        List<String> args,
        Map<String, String> env,
        long timeoutMs,
        int maxOutputChars) {
}