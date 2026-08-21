package com.litlebro.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.litlebro.agent.common.SystemPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史压缩服务，使用 LLM 将冗长的对话历史提炼为结构化摘要与持久事实。
 *
 * <ul>
 *   <li>一次调用同时产出「摘要 + 事实」，避免二次 prefill 拖慢压缩路径</li>
 *   <li>使用结构化模板（目标/重要细节/工作状态/下一步/相关文件）约束摘要格式</li>
 *   <li>事实为跨会话仍有价值的持久信息（决定/约定/偏好），供长期记忆语义检索</li>
 *   <li>支持增量压缩：传入上一次摘要，LLM 在此基础之上更新，而非每次从零总结</li>
 *   <li>摘要是"任务导向"的——回答"做了什么、决定了什么、什么还没做"</li>
 * </ul>
 */
public class CompressionService {

    private static final Logger log = LoggerFactory.getLogger(CompressionService.class);

    private final ChatClient chatClient;

    public CompressionService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 压缩一段对话历史，返回摘要与持久事实。
     *
     * @param historyMessages 待压缩的对话消息（不含最近保留的原文）
     * @param previousSummary 上一次压缩摘要（增量更新），可为 null
     * @return 压缩结果（summary / facts / cost）；失败或解析异常时返回空结果，绝不抛异常
     */
    public CompactionResult compactHistory(List<Message> historyMessages, String previousSummary) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return CompactionResult.empty();
        }

        StringBuilder historyText = new StringBuilder();
        for (Message msg : historyMessages) {
            historyText.append(msg.getMessageType().name()).append(": ")
                    .append(msg.getText()).append("\n");
        }

        String previousSection = (previousSummary != null && !previousSummary.isBlank())
                ? SystemPrompt.COMPACTION_UPDATE.formatted(previousSummary)
                : SystemPrompt.COMPACTION_INITIAL;

        String prompt = SystemPrompt.COMPACTION_REQUEST.formatted(
                previousSection,
                SystemPrompt.COMPACTION_TEMPLATE,
                historyText.toString()
        );

        try {
            BeanOutputConverter<CompactionOutput> converter = new BeanOutputConverter<>(CompactionOutput.class);
            CompactionOutput output = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(converter);
            if (output == null) {
                log.warn("历史压缩返回为空，本次压缩跳过（原文 {} 条）", historyMessages.size());
                return CompactionResult.empty();
            }
            String summary = output.getSummary() == null ? "" : output.getSummary().trim();
            List<String> facts = output.getFacts() == null ? List.of() : output.getFacts();
            log.debug("历史压缩完成，原文 {} 条消息，摘要 {} 字符，事实 {} 条，前次摘要 {} 字符",
                    historyMessages.size(), summary.length(), facts.size(),
                    previousSummary != null ? previousSummary.length() : 0);
            return new CompactionResult(summary, new ArrayList<>(facts), 0);
        } catch (Exception e) {
            log.warn("历史压缩调用失败，本次压缩跳过 原因: {}", e.getMessage());
            return CompactionResult.empty();
        }
    }

    /**
     * 压缩结果：摘要 + 持久事实列表 + 消耗（保留字段，便于后续成本核算扩展）。
     */
    public record CompactionResult(String summary, List<String> facts, int cost) {
        public static CompactionResult empty() {
            return new CompactionResult("", List.of(), 0);
        }

        public boolean isEmpty() {
            return summary == null || summary.isBlank();
        }
    }

    /**
     * 结构化输出载体：{summary, facts[]}。
     * Jackson 直接反序列化，忽略未知字段。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CompactionOutput {
        private String summary;
        private List<String> facts;

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public List<String> getFacts() {
            return facts;
        }

        public void setFacts(List<String> facts) {
            this.facts = facts;
        }
    }
}