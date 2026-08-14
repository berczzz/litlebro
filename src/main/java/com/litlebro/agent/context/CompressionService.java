package com.litlebro.agent.context;

import com.litlebro.agent.common.SystemPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话历史压缩服务，使用 LLM 将冗长的对话历史提炼为结构化摘要。
 * <ul>
 *   <li>使用结构化模板（目标/重要细节/工作状态/下一步/相关文件），而非自由文本</li>
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

    public String summarizeHistory(List<Message> historyMessages) {
        return summarizeHistory(historyMessages, null).getOrDefault("summary", "").toString();
    }

    public Map<String, Object> summarizeHistory(List<Message> historyMessages, String previousSummary) {
        Map<String, Object> map = new HashMap<>(2);
        map.put("summary", "");
        map.put("cost", 0);
        if (historyMessages == null || historyMessages.isEmpty()) {
            return map;
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

        ChatResponse summaryResp = chatClient.prompt()
                .user(prompt)
                .call()
                .chatResponse();
        log.debug("历史压缩完成，原文 {} 条消息，前次摘要 {} 字符",
                historyMessages.size(), previousSummary != null ? previousSummary.length() : 0);

        if (summaryResp == null || summaryResp.getResult() == null
                || summaryResp.getResult().getOutput() == null
                || summaryResp.getResult().getOutput().getText() == null) {
            log.warn("历史压缩返回为空，本次压缩跳过");
            return map;
        }
        String summary = summaryResp.getResult().getOutput().getText();
        map.put("summary", summary);
        int cost = 0;
        if (summaryResp.getMetadata() != null && summaryResp.getMetadata().getUsage() != null
                && summaryResp.getMetadata().getUsage().getCompletionTokens() != null) {
            cost = summaryResp.getMetadata().getUsage().getCompletionTokens();
        }
        map.put("cost", cost);
        return map;
    }
}