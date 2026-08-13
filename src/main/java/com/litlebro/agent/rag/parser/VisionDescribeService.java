package com.litlebro.agent.rag.parser;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationOutput;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationMessage;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemBase;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemImage;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 图片内容视觉描述服务。
 *
 * <p>基于 DashScope 多模态理解模型（qwen-vl 系列），将图片内容「描述」为文字：
 * 不局限于转录文字，而是理解画面整体——是什么图表/场景、关键元素、
 * 数据趋势与数值、布局结构等，供后续以纯文本方式入库向量库参与检索。
 *
 * <p>该服务替代旧版 OCR（仅转录文字），对带文字扫描件与无文字图表均适用。
 *
 * <p>未启用或调用失败时返回 {@code null}，由上层决定是否降级。
 */
@Component
public class VisionDescribeService {

    private static final Logger log = LoggerFactory.getLogger(VisionDescribeService.class);

    /** 视觉描述提示词：要求模型完整理解并描述图片内容 */
    private static final String DESCRIBE_PROMPT =
            "请仔细观察这张图片，用中文详细描述它的内容："
            + "1) 图片类型（照片/图表/流程图/表格/扫描文档等）；"
            + "2) 主要元素与文字信息（若含文字请完整保留）；"
            + "3) 若为图表，描述坐标轴、数据趋势与关键数值；"
            + "4) 整体布局结构。"
            + "只输出描述内容，不要添加任何解释、评论或标记。";

    private final boolean enabled;
    private final String model;
    private final String apiKey;
    private final String baseUrl;

    public VisionDescribeService(@Value("${app.rag.vision.enabled:false}") boolean enabled,
                                 @Value("${app.rag.vision.model:qwen-vl-plus}") String model,
                                 @Value("${app.rag.vision.api-key:}") String apiKey,
                                 @Value("${app.rag.vision.base-url:}") String baseUrl) {
        this.enabled = enabled;
        this.model = model;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    /**
     * 判断视觉描述是否已启用且配置了 API Key。
     */
    public boolean isAvailable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /**
     * 描述单张图片的内容。
     *
     * @param imagePath 本地图片文件路径（dashscope sdk 会自行上传）
     * @return 图片内容描述文字；失败或未启用时返回 null
     */
    public String describeImage(String imagePath) {
        if (!isAvailable()) {
            log.warn("视觉描述未启用或缺少 API Key，跳过识别 imagePath={}", imagePath);
            return null;
        }
        try {
            List<MultiModalMessageItemBase> content = new ArrayList<>();
            content.add(new MultiModalMessageItemText(DESCRIBE_PROMPT));
            content.add(new MultiModalMessageItemImage(imagePath));

            MultiModalConversationMessage message = MultiModalConversationMessage.builder()
                    .role("user")
                    .content(content)
                    .build();

            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .model(model)
                    .message(message)
                    .apiKey(apiKey)
                    .build();

            MultiModalConversation conversation;
            if (baseUrl != null && !baseUrl.isBlank()) {
                // 二参构造器 (protocol, baseUrl)：protocol 传 "http"，实际地址走 baseUrl
                conversation = new MultiModalConversation("http", baseUrl);
            } else {
                conversation = new MultiModalConversation();
            }
            MultiModalConversationResult result = conversation.call(param);
            return extractText(result);
        } catch (Exception e) {
            log.warn("视觉描述失败 imagePath={} 原因: {}", imagePath, e.getMessage());
            return null;
        }
    }

    /**
     * 从 DashScope 多模态响应中提取文本内容。
     * 响应结构：output.choices[0].message.content 为 [{ "text": "..." }]
     */
    private String extractText(MultiModalConversationResult result) {
        if (result == null) {
            return null;
        }
        MultiModalConversationOutput output = result.getOutput();
        if (output == null || output.getChoices() == null || output.getChoices().isEmpty()) {
            return null;
        }
        var message = output.getChoices().get(0).getMessage();
        if (message == null || message.getContent() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> item : message.getContent()) {
            Object text = item.get("text");
            if (text != null) {
                sb.append(text);
            }
        }
        return sb.toString();
    }
}