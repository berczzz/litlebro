package com.litlebro.agent.skill.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litlebro.agent.skill.model.SkillDefinition;
import com.litlebro.agent.skill.store.SkillStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 技能存储 Redis 实现：技能定义 JSON 存 Hash；会话技能记录存 Hash（无 TTL，重启不丢）。
 * 由配置 {@code app.skill.store.type=redis} 装配。
 *
 * <p>实现要点：
 * <ul>
 *   <li>技能定义存 Hash {@code agent:skill:skills}（field=skillId, value=JSON）</li>
 *   <li>会话技能记录存 Hash {@code agent:skill:recorded:{sessionId}}（field=skillId, value=1），
 *       按请求 skillIds 自动累加，不设过期时间；通过 {@code clearSessionSkills} 清空</li>
 * </ul>
 */
public class RedisSkillStore implements SkillStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSkillStore.class);

    private static final String SKILLS_KEY = "agent:skill:skills";
    private static final String RECORDED_PREFIX = "agent:skill:recorded:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisSkillStore(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(SkillDefinition skill) {
        try {
            redisTemplate.opsForHash().put(SKILLS_KEY, skill.getSkillId(), objectMapper.writeValueAsString(skill));
        } catch (Exception e) {
            log.warn("技能写入 Redis 失败 skillId={} 原因: {}", skill.getSkillId(), e.getMessage());
        }
    }

    @Override
    public Optional<SkillDefinition> findById(String skillId) {
        try {
            Object value = redisTemplate.opsForHash().get(SKILLS_KEY, skillId);
            if (value == null) {
                return Optional.empty();
            }
            String json = value instanceof String s ? s : value.toString();
            return Optional.ofNullable(objectMapper.readValue(json, SkillDefinition.class));
        } catch (Exception e) {
            log.warn("技能读取 Redis 失败 skillId={} 原因: {}", skillId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<SkillDefinition> findAll() {
        List<SkillDefinition> result = new ArrayList<>();
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(SKILLS_KEY);
            for (Object value : entries.values()) {
                if (value == null) {
                    continue;
                }
                try {
                    String json = value instanceof String s ? s : value.toString();
                    SkillDefinition skill = objectMapper.readValue(json, SkillDefinition.class);
                    if (skill != null) {
                        result.add(skill);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.warn("技能枚举 Redis 失败 原因: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public void delete(String skillId) {
        redisTemplate.opsForHash().delete(SKILLS_KEY, skillId);
    }

    @Override
    public void record(String sessionId, String skillId) {
        try {
            redisTemplate.opsForHash().put(RECORDED_PREFIX + sessionId, skillId, "1");
        } catch (Exception e) {
            log.warn("技能记录写入 Redis 失败 sessionId={} skillId={} 原因: {}", sessionId, skillId, e.getMessage());
        }
    }

    @Override
    public List<String> getRecordedSkillIds(String sessionId) {
        List<String> result = new ArrayList<>();
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(RECORDED_PREFIX + sessionId);
            for (Object key : entries.keySet()) {
                if (key != null) {
                    result.add(key.toString());
                }
            }
        } catch (Exception e) {
            log.warn("技能记录读取 Redis 失败 sessionId={} 原因: {}", sessionId, e.getMessage());
        }
        return result;
    }

    @Override
    public void clearSessionSkills(String sessionId) {
        try {
            redisTemplate.delete(RECORDED_PREFIX + sessionId);
        } catch (Exception e) {
            log.warn("技能记录清空 Redis 失败 sessionId={} 原因: {}", sessionId, e.getMessage());
        }
    }
}