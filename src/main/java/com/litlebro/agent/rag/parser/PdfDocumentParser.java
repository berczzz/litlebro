package com.litlebro.agent.rag.parser;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

/**
 * PDF 文档解析策略：优先提取 PDF 内嵌文本层，图片型（扫描件）走视觉模型描述内容。
 *
 * <p>处理流程：
 * <ol>
 *   <li>用 PDFBox {@link PDFTextStripper} 提取内嵌文本层</li>
 *   <li>若提取文本过少（含图片页），判定为图片型文档，逐页渲染为图片调用 {@link VisionDescribeService} 描述内容</li>
 *   <li>视觉描述未启用或失败时，保留已提取文本并记录提示</li>
 * </ol>
 *
 * <p>文本过少阈值与渲染 DPI 均可配置（{@code app.rag.vision.pdf-dpi}）。
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentParser.class);

    /** 文本层字符数低于该值视为"可能含图片页"，触发图片页检测 */
    private static final int MIN_TEXT_LENGTH = 50;

    private final VisionDescribeService visionService;
    private final float dpi;

    public PdfDocumentParser(VisionDescribeService visionService,
                             @Value("${app.rag.vision.pdf-dpi:150}") int dpi) {
        this.visionService = visionService;
        this.dpi = dpi;
    }

    @Override
    public String parse(byte[] fileBytes, String filename) throws IOException {
        try (PDDocument pdf = PDDocument.load(fileBytes)) {
            String text = new PDFTextStripper().getText(pdf);
            if (text != null && text.trim().length() >= MIN_TEXT_LENGTH) {
                return text;
            }
            // 文本层过少：检测是否含图片页，含则走视觉描述
            if (containsImagePage(pdf)) {
                String visionText = describeViaRendering(pdf);
                if (visionText != null && !visionText.isBlank()) {
                    return text.trim() + "\n" + visionText;
                }
                log.warn("PDF 含图片页但视觉描述未启用或失败，仅返回文本层 filename={}", filename);
            }
            return text == null ? "" : text;
        }
    }

    /**
     * 检测文档中是否存在含图片资源的页面（扫描件特征）。
     */
    private boolean containsImagePage(PDDocument pdf) throws IOException {
        for (PDPage page : pdf.getPages()) {
            var resources = page.getResources();
            if (resources == null) {
                continue;
            }
            for (COSName name : resources.getXObjectNames()) {
                PDXObject xObject = resources.getXObject(name);
                if (xObject instanceof PDImageXObject) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 逐页渲染为图片并调用视觉模型描述，返回拼接文本。
     * 渲染出的临时图片保存在系统临时目录，使用后删除。
     */
    private String describeViaRendering(PDDocument pdf) throws IOException {
        if (!visionService.isAvailable()) {
            return null;
        }
        PDFRenderer renderer = new PDFRenderer(pdf);
        StringBuilder sb = new StringBuilder();
        File tempDir = Files.createTempDirectory("litlebro-vision-").toFile();
        try {
            int pageCount = pdf.getNumberOfPages();
            for (int i = 0; i < pageCount; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, dpi);
                File imageFile = new File(tempDir, "page-" + i + "-" + UUID.randomUUID() + ".png");
                ImageIO.write(image, "png", imageFile);

                String pageText = visionService.describeImage(imageFile.getAbsolutePath());
                log.info("PDF 视觉描述页面 {} 完成，长度={}", i, pageText == null ? 0 : pageText.length());
                if (pageText != null && !pageText.isBlank()) {
                    sb.append("【第").append(i + 1).append("页】\n").append(pageText).append("\n");
                }
                if (!imageFile.delete()) {
                    imageFile.deleteOnExit();
                }
            }
        } finally {
            // 清理临时目录
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.delete()) {
                        f.deleteOnExit();
                    }
                }
            }
            if (!tempDir.delete()) {
                tempDir.deleteOnExit();
            }
        }
        return sb.toString();
    }
}