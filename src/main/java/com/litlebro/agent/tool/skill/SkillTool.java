package com.litlebro.agent.tool.skill;

/**
 * 技能工具标记接口：{@code load_skill / exec_skill / read_skill_file} 三个工具实现本接口，
 * 供应用层按类型过滤（ToolRegistry 按中文展示名索引，无法按 @Tool 名过滤）。
 *
 * <p>当前请求无可用技能时，这些工具会从本次 LLM 工具列表中剔除。
 */
public interface SkillTool {
}