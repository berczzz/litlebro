package com.litlebro.agent.attachment;

import com.litlebro.agent.attachment.resolver.AttachmentInput;
import com.litlebro.agent.attachment.resolver.AttachmentResolverFactory;
import com.litlebro.agent.attachment.resolver.ResolvedAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 附件组装器，阻塞式 {@code AgentService} 与流式 {@code AgentStreamService} 共用。
 *
 * <p>统一处理三类附件：
 * <ul>
 *   <li>图片附件 → 压缩后转为 {@link Media}（写短期记忆）与 data URI（多模态直传模型）</li>
 *   <li>文档/文本附件 → 落盘登记 fileId，提示 LLM 用 read_file / grep_file 工具读取</li>
 *   <li>解析失败的附件 → 追加失败说明，不阻断对话</li>
 * </ul>
 */
@Component
public class AttachmentAssembler {

    private static final Logger log = LoggerFactory.getLogger(AttachmentAssembler.class);

    private final AttachmentStore attachmentStore;
    private final AttachmentResolverFactory attachmentResolverFactory;
    private final ImageCompressor imageCompressor;

    public AttachmentAssembler(AttachmentStore attachmentStore,
                               AttachmentResolverFactory attachmentResolverFactory,
                               ImageCompressor imageCompressor) {
        this.attachmentStore = attachmentStore;
        this.attachmentResolverFactory = attachmentResolverFactory;
        this.imageCompressor = imageCompressor;
    }

    /**
     * 组装附件与提示词。
     *
     * @param userMessage 用户原始提问
     * @param sessionId   会话标识（附件按会话归属）
     * @param attachments 附件来源列表，可为空
     * @return 组装结果（提示词、图片媒体、OpenAI 图片 parts）
     */
    public Result build(String userMessage, String sessionId, List<AttachmentInput> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return new Result(userMessage, List.of(), List.of());
        }
        StringBuilder fileNotice = new StringBuilder();
        int imageCount = 0;
        List<Map<String, Object>> imageParts = new ArrayList<>();
        List<Media> mediaList = new ArrayList<>();
        for (AttachmentInput input : attachments) {
            ResolvedAttachment resolved;
            try {
                resolved = attachmentResolverFactory.resolve(input);
            } catch (IOException e) {
                log.warn("附件解析失败 name={} 原因: {}", input.name(), e.getMessage());
                fileNotice.append("\n[附件解析失败: ").append(input.name()).append(" - ").append(e.getMessage()).append("]");
                continue;
            }
            String mime = resolved.mimeType() != null ? resolved.mimeType() : "application/octet-stream";
            if (isImageMime(mime)) {
                try {
                    // 大图先压缩（等比缩小 + 重编码），避免输入 token 过大拖慢模型思考而触发读超时
                    ResolvedAttachment compressed = imageCompressor.compress(resolved);
                    MimeType mt = MimeType.valueOf(compressed.mimeType());
                    mediaList.add(new Media(mt, new ByteArrayResource(compressed.bytes())));
                    String dataUri = "data:" + compressed.mimeType() + ";base64,"
                            + Base64.getEncoder().encodeToString(compressed.bytes());
                    imageParts.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUri)));
                    imageCount++;
                } catch (Exception e) {
                    log.warn("图片附件组装失败 name={} 原因: {}", resolved.name(), e.getMessage());
                }
            } else {
                try {
                    String fileId = attachmentStore.store(sessionId, resolved);
                    fileNotice.append("\n- ").append(resolved.name())
                            .append(" [fileId: ").append(fileId)
                            .append(", mime: ").append(mime).append("]");
                } catch (IOException e) {
                    log.warn("附件落盘失败 name={} 原因: {}", resolved.name(), e.getMessage());
                    fileNotice.append("\n[附件存储失败: ").append(resolved.name()).append(" - ").append(e.getMessage()).append("]");
                }
            }
        }

        StringBuilder sb = new StringBuilder(userMessage);
        if (imageCount > 0) {
            sb.append("\n\n[图片已随本条消息直接提供，请结合图片内容回答]");
        }
        if (!fileNotice.isEmpty()) {
            sb.append("\n\n[随消息附带以下文件，回答时可使用 read_file / grep_file 工具读取文件内容]")
                    .append(fileNotice);
        }
        return new Result(sb.toString(), mediaList, imageParts);
    }

    private boolean isImageMime(String mime) {
        return mime != null && mime.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    /**
     * 附件组装结果：
     *
     * @param promptText  带附件说明的提示词文本（写短期/长期记忆用）
     * @param media       图片媒体列表（写短期记忆用）
     * @param imageParts  OpenAI 图片 parts（多模态 content 数组用）
     */
    public record Result(String promptText, List<Media> media, List<Map<String, Object>> imageParts) {

        /**
         * 发往模型的 content：无图片时返回纯文本字符串，有图片时返回 OpenAI 多模态数组。
         */
        public Object openAiContent() {
            if (imageParts.isEmpty()) {
                return promptText;
            }
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Map.of("type", "text", "text", promptText));
            content.addAll(imageParts);
            return content;
        }
    }
}