package com.litlebro.agent.skill.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 技能定义：描述一个外置技能包的元数据与执行信息。
 *
 * <p>技能包采用本地目录模型，目录结构（预置于 {@code app.skill.dir} 下）：
 * <pre>
 * {dir}/{skillId}/
 *   ├── SKILL.md        （必填，load_skill 读取）
 *   ├── scripts/        （exec_skill 执行）
 *   ├── references/     （read_skill_file 读取）
 *   └── assets/         （read_skill_file 读取）
 * </pre>
 *
 * <p>本对象仅存元数据（名称/描述/路径/执行覆盖），脚本与文件本身不随注册上传下载。
 */
public class SkillDefinition {

    /** 技能唯一 ID，同时作为技能包目录名 */
    private String skillId;
    /** 展示名，为空时默认使用 skillId */
    private String name;
    /** 触发描述：注入系统提示供 LLM 判断何时使用 */
    private String description;
    /** 是否启用 */
    private boolean enabled = true;
    /** 全局可用：为 true 时所有会话可直接使用，无需记录 */
    private boolean global;
    /** 技能包目录路径，为空时默认 {app.skill.dir}/{skillId}，必须落在技能根目录内 */
    private String path;
    /** 脚本名 → 解释器显式覆盖（可选），优先级高于扩展名映射与 shebang */
    private Map<String, String> interpreterMap = new LinkedHashMap<>();
    /** 执行环境变量注入（按技能声明，不打印日志） */
    private Map<String, String> env = new LinkedHashMap<>();
    /** 注册来源：STATIC（配置预注册）| DYNAMIC（接口注册） */
    private String origin;
    /** 注册时间戳（毫秒） */
    private long createdAt;

    public String getSkillId() {
        return skillId;
    }

    public void setSkillId(String skillId) {
        this.skillId = skillId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isGlobal() {
        return global;
    }

    public void setGlobal(boolean global) {
        this.global = global;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Map<String, String> getInterpreterMap() {
        return interpreterMap;
    }

    public void setInterpreterMap(Map<String, String> interpreterMap) {
        this.interpreterMap = interpreterMap == null ? new LinkedHashMap<>() : interpreterMap;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env == null ? new LinkedHashMap<>() : env;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    /** 展示名：name 为空时回退到 skillId */
    public String nameOrId() {
        return name == null || name.isBlank() ? skillId : name;
    }
}