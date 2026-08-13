package com.litlebro.agent.rag.parser;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

/**
 * Word 文档解析策略：支持 docx（Office Open XML）。
 *
 * <p>基于 Apache POI {@link XWPFDocument} 解析，按文档顺序读取段落与表格：
 * 段落文本原样保留；表格按行转成「单元格值 | 单元格值」文本。
 * 每个表格行单独成行，便于切块与语义检索。
 */
@Component
public class WordDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(WordDocumentParser.class);

    @Override
    public String parse(byte[] fileBytes, String filename) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(fileBytes))) {
            StringBuilder sb = new StringBuilder();

            // 合并段落与表格为统一的顺序流（段落在前、表格在后的情况最常见，
            // 更精确的顺序需遍历 body elements，这里取工程简化方案）
            List<XWPFParagraph> paragraphs = doc.getParagraphs();
            List<XWPFTable> tables = doc.getTables();

            for (XWPFParagraph p : paragraphs) {
                String t = p.getText();
                if (t != null && !t.isBlank()) {
                    sb.append(t).append("\n");
                }
            }
            for (XWPFTable table : tables) {
                for (XWPFTableRow row : table.getRows()) {
                    String line = appendRow(row);
                    if (!line.isBlank()) {
                        sb.append(line).append("\n");
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("docx 解析失败 filename={} 原因: {}", filename, e.getMessage());
            throw new IOException("docx 解析失败: " + e.getMessage(), e);
        }
    }

    private String appendRow(XWPFTableRow row) {
        StringBuilder line = new StringBuilder();
        for (XWPFTableCell cell : row.getTableCells()) {
            String text = cell.getText().trim();
            if (text.isBlank()) {
                continue;
            }
            if (!line.isEmpty()) {
                line.append(" | ");
            }
            line.append(text);
        }
        return line.toString();
    }
}