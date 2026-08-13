package com.litlebro.agent.rag.parser;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 文档解析策略工厂：按扩展名选择对应的 {@link DocumentParser} 实现。
 *
 * <p>策略模式入口，集中维护「扩展名 → 解析器」的映射。新增文件格式时：
 * <ol>
 *   <li>实现 {@link DocumentParser} 并注册为 Spring Bean</li>
 *   <li>在 {@link #resolve(String)} 中补充扩展名到该解析器的映射</li>
 * </ol>
 */
@Component
public class DocumentParserFactory {

    private final TextDocumentParser textParser;
    private final PdfDocumentParser pdfParser;
    private final ExcelDocumentParser excelParser;
    private final WordDocumentParser wordParser;
    private final ImageDocumentParser imageParser;

    public DocumentParserFactory(TextDocumentParser textParser,
                                 PdfDocumentParser pdfParser,
                                 ExcelDocumentParser excelParser,
                                 WordDocumentParser wordParser,
                                 ImageDocumentParser imageParser) {
        this.textParser = textParser;
        this.pdfParser = pdfParser;
        this.excelParser = excelParser;
        this.wordParser = wordParser;
        this.imageParser = imageParser;
    }

    /** 当前支持的全部扩展名（小写，不含点） */
    public List<String> supportedExtensions() {
        return List.of("txt", "md", "json", "pdf", "xlsx", "xls", "docx",
                "png", "jpg", "jpeg", "gif", "webp", "bmp");
    }

    /**
     * 根据文件名解析出对应的解析器策略。
     *
     * @param filename 原始文件名（含扩展名）
     * @return 对应的解析器
     * @throws IllegalArgumentException 不支持的格式时抛出
     */
    public DocumentParser resolve(String filename) {
        String ext = extensionOf(filename);
        return switch (ext) {
            case "txt", "md", "json" -> textParser;
            case "pdf" -> pdfParser;
            case "xlsx", "xls" -> excelParser;
            case "docx" -> wordParser;
            case "png", "jpg", "jpeg", "gif", "webp", "bmp" -> imageParser;
            default -> throw new IllegalArgumentException("不支持的文件格式: " + ext
                    + "，支持: " + String.join("/", supportedExtensions()));
        };
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
