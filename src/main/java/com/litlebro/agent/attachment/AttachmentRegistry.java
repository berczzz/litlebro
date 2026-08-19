package com.litlebro.agent.attachment;

import java.util.List;

/**
 * 附件注册表抽象接口：统一管理「fileId → 附件条目」的映射。
 *
 * <p>存储后端由配置 {@code app.attachment.registry.type} 显式决定：
 * <ul>
 *   <li>{@code local}（默认）— {@code attachment/local} 本地内存实现，进程内共享，重启丢失</li>
 *   <li>{@code redis} — {@code attachment/external} Redis 实现（TTL），重启不丢</li>
 * </ul>
 * 与短期记忆、会话状态的存储选择互不影响，可自由组合。
 *
 * <p>新增存储后端时实现本接口并注册为 Bean 即可，调用方无需感知。
 */
public interface AttachmentRegistry {

    /**
     * 注册一个新附件条目。
     *
     * @param entry 附件条目
     */
    void register(AttachmentEntry entry);

    /**
     * 按 fileId 查询附件条目。
     *
     * @param fileId 附件唯一标识
     * @return 附件条目，不存在返回 null
     */
    AttachmentEntry get(String fileId);

    /**
     * 移除附件条目（物理文件删除由 {@link AttachmentStore} 负责）。
     *
     * @param fileId 附件唯一标识
     * @return 被移除的条目，不存在返回 null
     */
    AttachmentEntry remove(String fileId);

    /**
     * 返回当前全部附件条目（快照副本，不影响内部存储）。
     *
     * @return 全部附件条目列表
     */
    List<AttachmentEntry> all();

    /**
     * 当前注册的附件数量。
     *
     * @return 数量
     */
    int size();

    /**
     * 按会话查询其名下全部存活附件条目（快照副本）。
     *
     * <p>实现应维护「sessionId → fileId 集合」的索引（register/remove 时同步），
     * 避免每次请求全库扫描 {@link #all()}——附件多、会话多时全库扫描会随总量线性劣化。
     * 查询结果跳过已过期/已移除的条目（与 {@link #all()} 语义一致）。
     *
     * @param sessionId 会话标识
     * @return 该会话名下存活的附件条目列表
     */
    List<AttachmentEntry> bySession(String sessionId);
}
