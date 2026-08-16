package com.litlebro.agent.skill.store;

import com.litlebro.agent.skill.model.SkillDefinition;

import java.util.List;
import java.util.Optional;

/**
 * 技能存储抽象接口：技能定义 CRUD + 会话技能记录。
 *
 * <p>存储后端由配置 {@code app.skill.store.type} 决定（见 {@code config.AppConfig}）：
 * {@code local} 加载 {@code skill.local.LocalSkillStore}（本地内存，默认），
 * {@code redis} 加载 {@code skill.external.RedisSkillStore}（Redis，重启不丢）。
 * 新增存储实现只需实现本接口并在 {@code config.AppConfig} 中按配置装配。
 */
public interface SkillStore {

    /**
     * 保存技能定义（按 skillId 覆盖）。
     *
     * @param skill 技能定义
     */
    void save(SkillDefinition skill);

    /**
     * 按 skillId 查询技能。
     *
     * @param skillId 技能 ID
     * @return 技能定义；不存在返回空 Optional
     */
    Optional<SkillDefinition> findById(String skillId);

    /**
     * 枚举全部技能。
     *
     * @return 全部技能定义
     */
    List<SkillDefinition> findAll();

    /**
     * 删除技能（含其全部会话技能记录）。
     *
     * @param skillId 技能 ID
     */
    void delete(String skillId);

    /**
     * 记录会话使用过某技能（按请求 skillIds 自动累加，无过期时间）。
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 ID
     */
    void record(String sessionId, String skillId);

    /**
     * 查询会话已记录的技能 ID 列表（global 技能无需记录，不在此列表内）。
     *
     * @param sessionId 会话 ID
     * @return 已记录技能 ID 列表
     */
    List<String> getRecordedSkillIds(String sessionId);

    /**
     * 清空会话的全部技能记录。
     *
     * @param sessionId 会话 ID
     */
    void clearSessionSkills(String sessionId);
}