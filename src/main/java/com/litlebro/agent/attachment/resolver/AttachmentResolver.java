package com.litlebro.agent.attachment.resolver;

import java.io.IOException;

/**
 * 附件解析策略接口：将不同来源（base64/url/multipart）的附件输入
 * 统一解析为字节形态的 {@link ResolvedAttachment}。
 *
 * <p>策略模式入口，由 {@link AttachmentResolverFactory} 按来源类型选择实现。
 * 新增来源类型时：实现本接口 + 在工厂注册 {@link #type()}，无需改动调用方。
 */
public interface AttachmentResolver {

    /**
     * 该策略支持的来源类型，与 {@link AttachmentInput#type()} 对应。
     *
     * @return 来源类型标识
     */
    String type();

    /**
     * 将输入解析为统一字节形态。
     *
     * @param input 附件来源输入
     * @return 解析结果
     * @throws IOException 数据获取或解码失败时抛出
     */
    ResolvedAttachment resolve(AttachmentInput input) throws IOException;
}
