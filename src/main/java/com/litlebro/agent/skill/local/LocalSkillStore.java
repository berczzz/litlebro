package com.litlebro.agent.skill.local;

import com.litlebro.agent.skill.model.SkillDefinition;
import com.litlebro.agent.skill.store.SkillStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能存储本地内存实现：ConcurrentHashMap 存储，进程内共享，重启丢失。
 * 由配置 {@code app.skill.store.type=local}（默认）装配。
 *
 * <p>会话技能记录按请求 skillIds 自动累加（无过期时间），本地实现重启后随内存一起丢失。
 */
public class LocalSkillStore implements SkillStore {

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    /** sessionId → 已记录技能 ID 集合（Set 语义去重） */
    private final Map<String, Map<String, Boolean>> recorded = new ConcurrentHashMap<>();

    @Override
    public void save(SkillDefinition skill) {
        skills.put(skill.getSkillId(), skill);
    }

    @Override
    public Optional<SkillDefinition> findById(String skillId) {
        return Optional.ofNullable(skills.get(skillId));
    }

    @Override
    public List<SkillDefinition> findAll() {
        return new ArrayList<>(skills.values());
    }

    @Override
    public void delete(String skillId) {
        skills.remove(skillId);
        recorded.forEach((session, set) -> set.remove(skillId));
    }

    @Override
    public void record(String sessionId, String skillId) {
        recorded.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(skillId, Boolean.TRUE);
    }

    @Override
    public List<String> getRecordedSkillIds(String sessionId) {
        Map<String, Boolean> set = recorded.get(sessionId);
        if (set == null) {
            return List.of();
        }
        return new ArrayList<>(set.keySet());
    }

    @Override
    public void clearSessionSkills(String sessionId) {
        recorded.remove(sessionId);
    }
}