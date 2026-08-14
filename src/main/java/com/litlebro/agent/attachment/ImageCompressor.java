package com.litlebro.agent.attachment;

import com.litlebro.agent.attachment.resolver.ResolvedAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

/**
 * 图片直传前压缩器。
 *
 * <p>超大分辨率图片（如壁纸）直传多模态模型时，会显著放大输入 token 数并拖慢推理，
 * 甚至导致 DashScope 端长时间思考而触发客户端读超时。此组件在组装 {@link org.springframework.ai.model.Media}
 * 前对图片做等比缩小 + JPEG/PNG 重编码，降低传输体积与推理耗时。
 */
@Component
public class ImageCompressor {

    private static final Logger log = LoggerFactory.getLogger(ImageCompressor.class);

    /** 最长边像素上限，超过则等比缩小 */
    private final int maxDimension;

    /** JPEG 重编码质量（0~1），PNG 透图不适用 */
    private final float jpegQuality;

    public ImageCompressor(
            @Value("${app.attachment.image.max-dimension:1024}") int maxDimension,
            @Value("${app.attachment.image.jpeg-quality:0.85}") float jpegQuality) {
        this.maxDimension = maxDimension;
        this.jpegQuality = jpegQuality;
    }

    /**
     * 压缩图片附件字节。
     *
     * <ul>
     *   <li>非图片或无法解码（如 webp/gif）时原样返回，不阻断流程</li>
     *   <li>最长边未超限时不重编码，避免无谓的画质损耗</li>
     *   <li>有透明通道（PNG）的图保持 PNG 编码，其余重编码为 JPEG</li>
     * </ul>
     *
     * @return 压缩后的附件（mimeType 与 bytes 可能被替换），失败时返回原始附件
     */
    public ResolvedAttachment compress(ResolvedAttachment attachment) {
        if (attachment == null || attachment.bytes() == null || attachment.bytes().length == 0) {
            return attachment;
        }
        String mime = attachment.mimeType();
        if (mime == null || !mime.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return attachment;
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(attachment.bytes()));
            if (source == null) {
                // 解码失败（如 webp 未注册 ImageIO 插件），原样透传
                return attachment;
            }
            int width = source.getWidth();
            int height = source.getHeight();
            if (Math.max(width, height) <= maxDimension) {
                return attachment;
            }
            double scale = (double) maxDimension / Math.max(width, height);
            int newWidth = Math.max(1, (int) Math.round(width * scale));
            int newHeight = Math.max(1, (int) Math.round(height * scale));

            boolean hasAlpha = source.getColorModel().hasAlpha();
            int type = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
            BufferedImage resized = new BufferedImage(newWidth, newHeight, type);
            Graphics2D g = resized.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(source, 0, 0, newWidth, newHeight, null);
            } finally {
                g.dispose();
            }

            byte[] outBytes;
            String outMime;
            if (hasAlpha) {
                outBytes = encode(resized, "png", null);
                outMime = "image/png";
            } else {
                outBytes = encode(resized, "jpeg", jpegQuality);
                outMime = "image/jpeg";
            }
            if (outBytes == null || outBytes.length == 0) {
                return attachment;
            }
            log.info("图片压缩: name={} {}x{} -> {}x{} ({} -> {} bytes)",
                    attachment.name(), width, height, newWidth, newHeight,
                    attachment.bytes().length, outBytes.length);
            return new ResolvedAttachment(attachment.name(), outMime, outBytes);
        } catch (IOException e) {
            log.warn("图片压缩失败 name={} 原因: {}", attachment.name(), e.getMessage());
            return attachment;
        }
    }

    private byte[] encode(BufferedImage image, String format, Float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) {
            return null;
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageWriter writer = writers.next();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                if (quality != null) {
                    ImageWriteParam param = writer.getDefaultWriteParam();
                    if (param.canWriteCompressed()) {
                        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                        param.setCompressionQuality(quality);
                    }
                    writer.write(null, new IIOImage(image, null, null), param);
                } else {
                    writer.write(image);
                }
            } finally {
                writer.dispose();
            }
            return baos.toByteArray();
        } catch (IOException e) {
            log.warn("图片重编码失败 format={} 原因: {}", format, e.getMessage());
            return null;
        }
    }
}