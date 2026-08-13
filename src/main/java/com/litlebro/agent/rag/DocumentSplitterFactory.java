package com.litlebro.agent.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

/**
 * 文档切块策略工厂：根据配置项 {@code app.rag.splitter.strategy} 返回具体的切块器。
 *
 * <p>两种策略，均继承统一的 {@link TextSplitter} 抽象：
 * <ul>
 *   <li>{@code fixed} — 固定 token 大小切块（Spring AI 内置 {@link TokenTextSplitter}）</li>
 *   <li>{@code semantic} — 语义切块（自定义 {@link SemanticTextSplitter}，按 embedding 相似度断点）</li>
 * </ul>
 *
 * <p>策略切换只需修改配置文件，无需改动业务代码。
 */
public class DocumentSplitterFactory {

    private final String strategy;
    private final int fixedChunkSize;
    private final SemanticTextSplitter semanticTextSplitter;

    public DocumentSplitterFactory(String strategy, int fixedChunkSize,
                                   SemanticTextSplitter semanticTextSplitter) {
        this.strategy = strategy;
        this.fixedChunkSize = fixedChunkSize;
        this.semanticTextSplitter = semanticTextSplitter;
    }

    /**
     * 返回当前配置对应的切块器。
     *
     * @return 切块器实例
     */
    public TextSplitter getSplitter() {
        if ("semantic".equalsIgnoreCase(strategy)) {
            return semanticTextSplitter;
        }
        // 默认 fixed
        return TokenTextSplitter.builder()
                .withChunkSize(fixedChunkSize)
                .build();
    }
}
