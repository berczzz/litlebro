package com.litlebro.agent.skill.model;

/**
 * 技能脚本执行结果。
 *
 * @param exitCode  进程退出码；超时被强杀时为 -1
 * @param output    合并 stdout/stderr 后的文本（已截断）
 * @param truncated 输出是否达到截断上限
 * @param timedOut  是否超时被强杀
 * @param error     执行层错误描述（启动失败/超时等），无错误为 null
 */
public record SkillExecResult(
        int exitCode,
        String output,
        boolean truncated,
        boolean timedOut,
        String error) {
}