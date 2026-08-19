package com.litlebro.agent.rag.parser;

import com.litlebro.agent.attachment.ImageCompressor;
import com.litlebro.agent.attachment.resolver.ResolvedAttachment;
import com.litlebro.agent.common.SystemPrompt;
import com.litlebro.agent.config.LlmSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.Media;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;

/**
 * 图片内容视觉描述服务。
 *
 * <p>基于 OpenAI 兼容的多模态模型（默认 dashscope qwen-vl 系列，也可指向任意
 * OpenAI 兼容视觉端点），将图片内容「描述」为文字：不局限于转录文字，而是理解画面
 * 整体——是什么图表/场景、关键元素、数据趋势与数值、布局结构等，供后续以纯文本方式
 * 入库向量库参与检索。
 *
 * <p>调用链路（与对话图片直传对齐）：图片字节 → {@link ImageCompressor} 压缩 →
 * {@link Media}（base64 data URI）→ {@code visionChatClient}（无 advisor，独立于主对话）。
 *
 * <p>三重防护：压缩降体积；超 {@code app.rag.vision.max-image-bytes} 上限时跳过降级；
 * {@code app.rag.vision.max-concurrency} 信号量限流封顶瞬时内存峰值。
 *
 * <p>未启用或调用失败时返回 {@code null}，由上层决定是否降级。
 */
@Component
public class VisionDescribeService {

    private static final Logger log = LoggerFactory.getLogger(VisionDescribeService.class);

    private final LlmSettings settings;
    private final ObjectProvider<ChatClient> visionChatClientProvider;
    private final ImageCompressor imageCompressor;
    private final Semaphore visionSemaphore;

    public VisionDescribeService(LlmSettings settings,
                                 @Qualifier("visionChatClient") ObjectProvider<ChatClient> visionChatClientProvider,
                                 ImageCompressor imageCompressor) {
        this.settings = settings;
        this.visionChatClientProvider = visionChatClientProvider;
        this.imageCompressor = imageCompressor;
        this.visionSemaphore = new Semaphore(Math.max(1, settings.getVisionMaxConcurrency()));
    }

    /**
     * 判断视觉描述是否已启用且配置了 API Key。
     */
    public boolean isAvailable() {
        return settings.isVisionEnabled() && StringUtils.hasText(settings.resolveVisionApiKey());
    }

    /**
     * 描述单张图片的内容（按文件路径读取字节）。
     *
     * @param imagePath 本地图片文件路径
     * @return 图片内容描述文字；失败或未启用时返回 null
     */
    public String describeImage(String imagePath) {
        if (imagePath == null) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(Path.of(imagePath));
            return describeImage(bytes, guessMime(imagePath));
        } catch (IOException e) {
            log.warn("读取图片失败 path={} 原因: {}", imagePath, e.getMessage());
            return null;
        }
    }

    /**
     * 描述单张图片的内容（直接传字节，避免临时文件 IO）。
     *
     * @param imageBytes 图片字节内容
     * @param mimeType   图片 MIME 类型（如 image/png / image/jpeg）
     * @return 图片内容描述文字；失败或未启用时返回 null
     */
    public String describeImage(byte[] imageBytes, String mimeType) {
        if (!isAvailable()) {
            log.warn("视觉描述未启用或缺少 API Key，跳过识别");
            return null;
        }
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        ChatClient chatClient = visionChatClientProvider.getIfAvailable();
        if (chatClient == null) {
            log.warn("visionChatClient 未装配（app.rag.vision.enabled=false），跳过识别");
            return null;
        }
        try {
            // 1. 压缩：超大图等比缩小 + JPEG/PNG 重编码，大幅降低 payload 与推理 token
            ResolvedAttachment compressed = imageCompressor.compress(
                    new ResolvedAttachment("vision-image", mimeType, imageBytes));
            byte[] payload = compressed.bytes();
            String mime = compressed.mimeType();
            // 2. 超限防护：压缩后仍超上限（如 ImageIO 解不了码的原样透传格式）则降级
            if (payload.length > settings.getVisionMaxImageBytes()) {
                log.warn("图片过大 {} bytes 超过上限 {}，跳过识别",
                        payload.length, settings.getVisionMaxImageBytes());
                return null;
            }
            // 3. 信号量限流：封顶并发视觉请求数，控制瞬时内存峰值
            visionSemaphore.acquire();
            try {
                UserMessage message = new UserMessage(SystemPrompt.VISION_DESCRIBE,
                        List.of(new Media(MimeType.valueOf(mime), new ByteArrayResource(payload))));
                String text = chatClient.prompt()
                        .messages(List.of(message))
                        .call()
                        .content();
                return text == null || text.isBlank() ? null : text;
            } finally {
                visionSemaphore.release();
            }
        } catch (Exception e) {
            log.warn("视觉描述失败 原因: {}", e.getMessage());
            return null;
        }
    }

    /** 按文件扩展名推断图片 MIME 类型（不依赖外部检测）。 */
    private String guessMime(String imagePath) {
        String lower = imagePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/png";
    }
}