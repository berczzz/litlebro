package com.litlebro.agent.skill;

import com.litlebro.agent.skill.model.SkillDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能（Skills）模块配置，前缀 {@code app.skill}。
 *
 * <p>技能采用"本地目录包"模型：技能包预置于服务器磁盘
 * {@code app.skill.dir}/{skillId}/（含 SKILL.md、scripts/、references/、assets/），
 * 运行时零下载，注册只声明并校验目录。由配置 {@code app.skill.enabled=true} 开启模块。
 */
@ConfigurationProperties(prefix = "app.skill")
public class SkillProperties {

    /**
     * 模块总开关：false 时不装配任何技能组件与工具
     */
    private boolean enabled;
    /**
     * 技能包根目录
     */
    private String dir = "./data/skills";
    /**
     * 存储配置
     */
    private Store store = new Store();
    /**
     * 脚本执行配置
     */
    private Exec exec = new Exec();
    /**
     * 静态预注册技能（同名跳过，需目录已存在）
     */
    private List<SkillDefinition> skills = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public Store getStore() {
        return store;
    }

    public void setStore(Store store) {
        this.store = store;
    }

    public Exec getExec() {
        return exec;
    }

    public void setExec(Exec exec) {
        this.exec = exec;
    }

    public List<SkillDefinition> getSkills() {
        return skills;
    }

    public void setSkills(List<SkillDefinition> skills) {
        this.skills = skills == null ? new ArrayList<>() : skills;
    }

    /**
     * 技能存储后端配置：{@code local}（默认，本地内存，重启丢失）/ {@code redis}（重启不丢）。
     */
    public static class Store {

        private String type = "local";

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }

    /**
     * 脚本执行配置：全局总开关 {@code enabled} 默认关闭，开启后 {@code exec_skill} 才允许执行。
     */
    public static class Exec {

        private boolean enabled;
        private long timeoutMs = 30000;
        private int outputMaxChars = 8192;
        private List<String> interpreterAllowList = List.of("python3", "node", "bash", "powershell");

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getOutputMaxChars() {
            return outputMaxChars;
        }

        public void setOutputMaxChars(int outputMaxChars) {
            this.outputMaxChars = outputMaxChars;
        }

        public List<String> getInterpreterAllowList() {
            return interpreterAllowList;
        }

        public void setInterpreterAllowList(List<String> interpreterAllowList) {
            this.interpreterAllowList = interpreterAllowList == null ? List.of() : interpreterAllowList;
        }
    }
}