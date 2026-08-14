package com.litlebro.agent.attachment.external;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litlebro.agent.attachment.AttachmentEntry;
import com.litlebro.agent.attachment.AttachmentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 附件注册表 Redis 实现：以 JSON 字符串存储附件条目（带 TTL），重启不丢。
 * 由配置 {@code app.attachment.registry.type=redis} 装配。
 *
 * <p>实现要点：
 * <ul>
 *   <li>单条条目以 {@code agent:attachment:<fileId>} 为 key 存储 JSON，TTL 与附件过期时间对齐</li>
 *   <li>索引集合 {@code agent:attachment:index} 记录全部 fileId，供 {@link #all()} 枚举</li>
 *   <li>Path 字段以字符串存储（Jackson 默认不支持 Path 序列化）</li>
 * </ul>
 */
public class RedisAttachmentRegistry implements AttachmentRegistry {

    private static final Logger log = LoggerFactory.getLogger(RedisAttachmentRegistry.class);

    /** 单条附件条目 key 前缀 */
    private static final String KEY_PREFIX = "agent:attachment:";
    /** 附件 fileId 索引集合 key */
    private static final String INDEX_KEY = "agent:attachment:index";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisAttachmentRegistry(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void register(AttachmentEntry entry) {
        try {
            String key = KEY_PREFIX + entry.fileId();
            String json = objectMapper.writeValueAsString(toMap(entry));
            long ttlMillis = Math.max(1, entry.expiresAt() - System.currentTimeMillis());
            redisTemplate.opsForValue().set(key, json, ttlMillis, TimeUnit.MILLISECONDS);
            redisTemplate.opsForSet().add(INDEX_KEY, entry.fileId());
            log.debug("附件已写入 Redis fileId={} name={} expiresAt={}", entry.fileId(), entry.name(), entry.expiresAt());
        } catch (Exception e) {
            log.warn("附件写入 Redis 失败 fileId={} 原因: {}", entry.fileId(), e.getMessage());
        }
    }

    @Override
    public AttachmentEntry get(String fileId) {
        try {
            Object value = redisTemplate.opsForValue().get(KEY_PREFIX + fileId);
            if (value == null) {
                return null;
            }
            String json = value instanceof String s ? s : value.toString();
            return fromMap(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            }));
        } catch (Exception e) {
            log.warn("附件读取 Redis 失败 fileId={} 原因: {}", fileId, e.getMessage());
            return null;
        }
    }

    @Override
    public AttachmentEntry remove(String fileId) {
        AttachmentEntry removed = get(fileId);
        try {
            redisTemplate.delete(KEY_PREFIX + fileId);
            redisTemplate.opsForSet().remove(INDEX_KEY, fileId);
        } catch (Exception e) {
            log.warn("附件移除 Redis 失败 fileId={} 原因: {}", fileId, e.getMessage());
        }
        if (removed != null) {
            log.debug("附件注册表项已移除 fileId={}", fileId);
        }
        return removed;
    }

    @Override
    public List<AttachmentEntry> all() {
        List<AttachmentEntry> result = new ArrayList<>();
        try {
            Set<Object> ids = redisTemplate.opsForSet().members(INDEX_KEY);
            if (ids == null) {
                return result;
            }
            for (Object id : ids) {
                if (id == null) {
                    continue;
                }
                AttachmentEntry entry = get(id.toString());
                if (entry != null) {
                    result.add(entry);
                }
            }
        } catch (Exception e) {
            log.warn("附件枚举 Redis 失败 原因: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public int size() {
        try {
            Long size = redisTemplate.opsForSet().size(INDEX_KEY);
            return size == null ? 0 : size.intValue();
        } catch (Exception e) {
            log.warn("附件数量读取 Redis 失败 原因: {}", e.getMessage());
            return 0;
        }
    }

    private Map<String, Object> toMap(AttachmentEntry entry) {
        Map<String, Object> map = new HashMap<>();
        map.put("fileId", entry.fileId());
        map.put("sessionId", entry.sessionId());
        map.put("name", entry.name());
        map.put("mimeType", entry.mimeType());
        map.put("rawPath", entry.rawPath() == null ? null : entry.rawPath().toString());
        map.put("txtPath", entry.txtPath() == null ? null : entry.txtPath().toString());
        map.put("size", entry.size());
        map.put("expiresAt", entry.expiresAt());
        return map;
    }

    private AttachmentEntry fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        Object rawPath = map.get("rawPath");
        Object txtPath = map.get("txtPath");
        return new AttachmentEntry(
                str(map.get("fileId")),
                str(map.get("sessionId")),
                str(map.get("name")),
                str(map.get("mimeType")),
                rawPath == null ? null : Path.of(str(rawPath)),
                txtPath == null ? null : Path.of(str(txtPath)),
                num(map.get("size")),
                num(map.get("expiresAt"))
        );
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    private long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
