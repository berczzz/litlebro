package com.litlebro.agent.rag.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;

/**
 * 图片文档解析策略：支持 png / jpg / jpeg / gif / webp / bmp。
 *
 * <p>图片无法直接参与文本切块与向量化，因此调用 {@link VisionDescribeService}
 * 让多模态模型「描述」图片内容，把描述文本作为可检索文本返回。
 * 这样后续检索与回答全部走纯文本链路，多模态能力只在入库时使用一次。
 *
 * <p>上传字节直接交给视觉服务（内部压缩 + base64 直传），不写临时文件。
 */
@Component
public class ImageDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(ImageDocumentParser.class);

    private final VisionDescribeService visionService;

    public ImageDocumentParser(VisionDescribeService visionService) {
        this.visionService = visionService;
    }

    @Override
    public String parse(byte[] fileBytes, String filename) throws IOException {
        if (!visionService.isAvailable()) {
            log.warn("视觉描述未启用或缺少 API Key，无法解析图片 filename={}", filename);
            throw new IOException("图片解析需要启用视觉描述（app.rag.vision.enabled）并配置 API Key");
        }
        String mime = mimeOf(filename);
        String description = visionService.describeImage(fileBytes, mime);
        if (description == null || description.isBlank()) {
            throw new IOException("视觉模型未返回图片描述");
        }
        log.info("图片描述完成 filename={} 长度={}", filename, description.length());
        return description;
    }

    private String mimeOf(String filename) {
        return switch (extensionOf(filename)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            default -> "image/png";
        };
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "png" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}