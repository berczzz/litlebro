package com.litlebro.agent.attachment.resolver;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 附件解析策略工厂：按来源类型选择对应的 {@link AttachmentResolver} 实现。
 *
 * <p>策略模式入口，自动收集容器中全部解析器并按 {@link AttachmentResolver#type()} 建索引。
 * 新增来源时只需实现 {@link AttachmentResolver} 并声明为 Bean，无需改动本类。
 */
@Component
public class AttachmentResolverFactory {

    private final Map<String, AttachmentResolver> resolvers;

    public AttachmentResolverFactory(List<AttachmentResolver> resolverList) {
        this.resolvers = resolverList.stream()
                .collect(Collectors.toUnmodifiableMap(AttachmentResolver::type, Function.identity()));
    }

    /**
     * 解析指定来源的附件为统一字节形态。
     *
     * @param input 附件来源输入
     * @return 解析结果
     * @throws IOException 解析失败时抛出
     */
    public ResolvedAttachment resolve(AttachmentInput input) throws IOException {
        if (input == null || input.type() == null) {
            throw new IOException("附件缺少来源类型");
        }
        AttachmentResolver resolver = resolvers.get(input.type());
        if (resolver == null) {
            throw new IOException("不支持的附件来源类型: " + input.type() + "，支持: " + resolvers.keySet());
        }
        return resolver.resolve(input);
    }
}