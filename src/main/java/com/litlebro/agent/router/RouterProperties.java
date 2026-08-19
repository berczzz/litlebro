package com.litlebro.agent.router;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 检索路由配置（{@code app.router.*}）。
 *
 * <p>控制检索前路由层是否启用、歧义词是否走轻量 LLM 兜底、
 * 路由专用模型的独立端点/密钥/模型（空值回落主对话配置），以及各档关键词表。
 */
@ConfigurationProperties(prefix = "app.router")
public class RouterProperties {

    /** 总开关：false 时路由层完全失效，退回"全部检索工具常驻"的旧行为 */
    private boolean enabled = true;

    /** 歧义词是否启用轻量 LLM 兜底分类；false 时歧义词一律默认 both（保召回） */
    private boolean llmFallback = true;

    /** 喂给路由器的最近对话历史条数，用于消解"这个/那份"等指代 */
    private int historySize = 6;

    /** 路由专用 LLM 配置（OpenAI 兼容端点，可独立指向 qwen/DeepSeek） */
    private Llm llm = new Llm();

    /** 强文档知识库指示词：命中即硬路由 document */
    private List<String> strongDocumentKeywords = List.of(
            "知识库", "文档库", "之前上传的文档", "上传到知识库", "全局文档", "库里有没有", "知识库里有");

    /** 强会话记忆指示词：命中即硬路由 memory */
    private List<String> strongMemoryKeywords = List.of(
            "之前", "刚才", "你说过", "你答过", "我们聊过", "上次", "记得吗", "上一轮", "前面说过");

    /** 弱/歧义检索词：命中不硬路由，走 LLM 兜底或默认 both */
    private List<String> weakKeywords = List.of(
            "文档", "文件", "数据", "报表", "资料", "记录", "手册", "报告", "内容", "信息",
            "查询", "查一下", "查查", "检索", "看看", "里面的", "里写", "中提到", "提到");

    /** 附件指代词：本次带附件且命中时优先判定为附件问题（目标 none，不拉全局库） */
    private List<String> attachmentRefWords = List.of(
            "附件", "这个文档", "这份", "上传的文件", "上传的", "我刚发的", "我发的", "随消息");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isLlmFallback() {
        return llmFallback;
    }

    public void setLlmFallback(boolean llmFallback) {
        this.llmFallback = llmFallback;
    }

    public int getHistorySize() {
        return historySize;
    }

    public void setHistorySize(int historySize) {
        this.historySize = historySize;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public List<String> getStrongDocumentKeywords() {
        return strongDocumentKeywords;
    }

    public void setStrongDocumentKeywords(List<String> strongDocumentKeywords) {
        this.strongDocumentKeywords = strongDocumentKeywords;
    }

    public List<String> getStrongMemoryKeywords() {
        return strongMemoryKeywords;
    }

    public void setStrongMemoryKeywords(List<String> strongMemoryKeywords) {
        this.strongMemoryKeywords = strongMemoryKeywords;
    }

    public List<String> getWeakKeywords() {
        return weakKeywords;
    }

    public void setWeakKeywords(List<String> weakKeywords) {
        this.weakKeywords = weakKeywords;
    }

    public List<String> getAttachmentRefWords() {
        return attachmentRefWords;
    }

    public void setAttachmentRefWords(List<String> attachmentRefWords) {
        this.attachmentRefWords = attachmentRefWords;
    }

    /**
     * 路由专用 LLM 配置（OpenAI 兼容协议，可独立指向 qwen/DeepSeek 等端点）。
     * 三项均支持空值回落主对话配置：base-url→spring.ai.openai.base-url，
     * api-key→spring.ai.openai.api-key，model→spring.ai.openai.chat.options.model。
     */
    public static class Llm {
        /** OpenAI 兼容端点地址，空值复用主对话端点 */
        private String baseUrl = "";
        /** API Key，空值复用主对话 Key */
        private String apiKey = "";
        /** 路由模型名，空值复用主模型 */
        private String model = "";
        /** 路由调用温度：分类任务固定低温，默认 0 */
        private double temperature = 0.0;
        /** 路由调用输出 token 上限：只需输出一个短 JSON */
        private int maxTokens = 256;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }
    }
}