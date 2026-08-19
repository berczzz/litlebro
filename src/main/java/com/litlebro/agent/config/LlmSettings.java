package com.litlebro.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * LLM 相关配置的单一读取点。
 *
 * <p>将散落在各组件里的 {@code @Value} 读取集中于此，统一从
 * {@code spring.ai.openai.*}（主对话）、{@code app.stream.*}（流式）、
 * {@code app.rag.vision.*}（视觉）读取，并集中处理"空值回落主对话配置"的规则。
 *
 * <p>视觉/路由的 api-key、base-url 均支持独立覆盖，空值时回落主对话配置
 * （{@code spring.ai.openai.*}），与既有环境变量语义保持一致。
 */
@Component
public class LlmSettings {

    // ==================== 主对话（spring.ai.openai.*）====================

    @Value("${spring.ai.openai.base-url:https://dashscope.aliyuncs.com/compatible-mode}")
    private String chatBaseUrl;

    @Value("${spring.ai.openai.api-key:}")
    private String chatApiKey;

    @Value("${spring.ai.openai.chat.options.model:qwen3.8-max}")
    private String chatModel;

    @Value("${spring.ai.openai.chat.options.temperature:0.7}")
    private double chatTemperature;

    @Value("${spring.ai.openai.chat.options.max-tokens:131072}")
    private int chatMaxTokens;

    // ==================== 流式（app.stream.*）====================

    @Value("${app.stream.enable-thinking:false}")
    private boolean streamEnableThinking;

    @Value("${app.stream.completions-path:/v1/chat/completions}")
    private String streamCompletionsPath;

    // ==================== 视觉（app.rag.vision.*）====================

    @Value("${app.rag.vision.enabled:false}")
    private boolean visionEnabled;

    @Value("${app.rag.vision.model:}")
    private String visionModel;

    @Value("${app.rag.vision.api-key:}")
    private String visionApiKey;

    @Value("${app.rag.vision.base-url:}")
    private String visionBaseUrl;

    @Value("${app.rag.vision.max-image-bytes:10485760}")
    private long visionMaxImageBytes;

    @Value("${app.rag.vision.max-concurrency:2}")
    private int visionMaxConcurrency;

    public String getChatBaseUrl() {
        return chatBaseUrl;
    }

    public String getChatApiKey() {
        return chatApiKey;
    }

    public String getChatModel() {
        return chatModel;
    }

    public double getChatTemperature() {
        return chatTemperature;
    }

    public int getChatMaxTokens() {
        return chatMaxTokens;
    }

    public boolean isStreamEnableThinking() {
        return streamEnableThinking;
    }

    public String getStreamCompletionsPath() {
        return streamCompletionsPath;
    }

    public boolean isVisionEnabled() {
        return visionEnabled;
    }

    public long getVisionMaxImageBytes() {
        return visionMaxImageBytes;
    }

    public int getVisionMaxConcurrency() {
        return visionMaxConcurrency;
    }

    /** 视觉 API Key：配置了独立视觉 Key 则用之，否则回落主对话 Key。 */
    public String resolveVisionApiKey() {
        return StringUtils.hasText(visionApiKey) ? visionApiKey : chatApiKey;
    }

    /** 视觉 Base URL：配置了独立视觉端点则用之，否则回落主对话端点。 */
    public String resolveVisionBaseUrl() {
        return StringUtils.hasText(visionBaseUrl) ? visionBaseUrl : chatBaseUrl;
    }

    /** 视觉模型：配置了独立视觉模型则用之，否则回落主对话模型（{@code spring.ai.openai.chat.options.model}）。 */
    public String resolveVisionModel() {
        return StringUtils.hasText(visionModel) ? visionModel : chatModel;
    }
}