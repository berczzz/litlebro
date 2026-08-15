package com.litlebro.agent.rag.parser;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.SAXParserFactory;

/**
 * 表格文档解析策略：支持 xlsx / xls / csv。
 *
 * <p>三种格式统一输出「第N行: 值1 | 值2」便于切块与语义检索：
 * <ul>
 *   <li>xlsx — 使用 {@link XSSFReader} + SAX 事件模型流式读取，避免整本加载进内存，
 *       适合大文件；配合行数上限与文本长度上限防止内存溢出</li>
 *   <li>xls — 使用 {@link HSSFWorkbook} 读取，按行遍历，同样受行数与文本长度上限约束</li>
 *   <li>csv — RFC 4180 子集手写解析（零新依赖），按 UTF-8 读取并剥离 BOM，
 *       处理引号包裹字段、逗号内嵌、{@code ""} 转义、引号内换行；首行识别为表头输出「第N行(表头): ...」</li>
 * </ul>
 *
 * <p>单元格统一用 {@link DataFormatter} 格式化为文本，超过 {@code MAX_ROWS} 行
 * 或 {@code MAX_TEXT_LENGTH} 字符时截断。
 */
@Component
public class SpreadsheetDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(SpreadsheetDocumentParser.class);

    /** 最多读取的行数，防止超大表格导致内存与耗时失控 */
    private static final int MAX_ROWS = 100_000;
    /** 解析文本长度上限（字符），超限截断 */
    private static final int MAX_TEXT_LENGTH = 3_000_000;

    @Override
    public String parse(byte[] fileBytes, String filename) throws IOException {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx")) {
            return parseXlsx(fileBytes, filename);
        }
        if (lower.endsWith(".xls")) {
            return parseXls(fileBytes, filename);
        }
        if (lower.endsWith(".csv")) {
            return parseCsv(fileBytes, filename);
        }
        throw new IOException("不支持的表格格式: " + filename);
    }

    // ==================== xlsx：SAX 流式读取 ====================

    private String parseXlsx(byte[] fileBytes, String filename) throws IOException {
        try (InputStream in = new ByteArrayInputStream(fileBytes);
             OPCPackage pkg = OPCPackage.open(in)) {
            XSSFReader reader = new XSSFReader(pkg);
            SharedStrings sst = reader.getSharedStringsTable();
            StylesTable styles = reader.getStylesTable();

            SheetCollector collector = new SheetCollector();
            SAXParserFactory factory = SAXParserFactory.newInstance();
            XMLReader parser = factory.newSAXParser().getXMLReader();
            parser.setContentHandler(new XSSFSheetXMLHandler(styles, sst, collector, false));

            Iterator<InputStream> sheets = reader.getSheetsData();
            int sheetIndex = 1;
            while (sheets.hasNext() && !collector.isDone()) {
                try (InputStream sheet = sheets.next()) {
                    collector.beginSheet(sheetIndex);
                    parser.parse(new InputSource(sheet));
                    collector.endSheet();
                } catch (Exception e) {
                    log.warn("xlsx sheet 解析失败 sheetIndex={} 原因: {}", sheetIndex, e.getMessage());
                }
                sheetIndex++;
            }
            return collector.result();
        } catch (Exception e) {
            log.warn("xlsx 解析失败 filename={} 原因: {}", filename, e.getMessage());
            throw new IOException("xlsx 解析失败: " + e.getMessage(), e);
        }
    }

    /** 收集 xlsx 单元格，按行输出，兼顾行数/文本长度上限 */
    private static class SheetCollector implements SheetContentsHandler {
        private final StringBuilder sb = new StringBuilder();
        private final List<String> currentRow = new ArrayList<>();
        private int rowIndex = 0;
        private boolean done = false;

        boolean isDone() {
            return done;
        }

        void beginSheet(int sheetIndex) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append("【第").append(sheetIndex).append("个工作表】\n");
        }

        public void endSheet() {
            // 尾部空行处理
        }

        @Override
        public void startRow(int rowNum) {
            currentRow.clear();
        }

        @Override
        public void endRow(int rowNum) {
            if (done) {
                return;
            }
            rowIndex++;
            if (rowIndex > MAX_ROWS || sb.length() >= MAX_TEXT_LENGTH) {
                done = true;
                log.warn("Excel 解析达到上限（行数 {} / 文本长度 {}），已截断", rowIndex, sb.length());
                return;
            }
            appendRow(sb, rowIndex, currentRow, false);
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (formattedValue != null) {
                currentRow.add(formattedValue);
            }
        }

        String result() {
            return sb.toString();
        }
    }

    // ==================== xls：HSSFWorkbook ====================

    private String parseXls(byte[] fileBytes, String filename) throws IOException {
        StringBuilder sb = new StringBuilder();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = new HSSFWorkbook(new ByteArrayInputStream(fileBytes))) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append("【第").append(s + 1).append("个工作表】\n");
                Sheet sheet = workbook.getSheetAt(s);
                Iterator<Row> rows = sheet.iterator();
                int rowIndex = 0;
                while (rows.hasNext()) {
                    rowIndex++;
                    if (rowIndex > MAX_ROWS || sb.length() >= MAX_TEXT_LENGTH) {
                        log.warn("xls 解析达到上限（行数 {} / 文本长度 {}），已截断", rowIndex, sb.length());
                        return sb.toString();
                    }
                    Row row = rows.next();
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        String value = formatter.formatCellValue(cell);
                        if (value != null && !value.isBlank()) {
                            cells.add(value);
                        }
                    }
                    appendRow(sb, rowIndex, cells, false);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("xls 解析失败 filename={} 原因: {}", filename, e.getMessage());
            throw new IOException("xls 解析失败: " + e.getMessage(), e);
        }
    }

    // ==================== csv：RFC 4180 子集手写解析 ====================

    /**
     * 解析 csv 文件为「第N行: 值1 | 值2」格式，首行（首个非空记录）标记为表头。
     * 按 UTF-8 读取并剥离 BOM，空记录跳过但占用行号，与 Excel 物理行号语义一致。
     */
    private String parseCsv(byte[] fileBytes, String filename) throws IOException {
        String content = new String(fileBytes, StandardCharsets.UTF_8);
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }
        StringBuilder sb = new StringBuilder();
        try {
            int rowIndex = 0;
            boolean headerDone = false;
            for (List<String> record : parseCsvRecords(content)) {
                rowIndex++;
                if (rowIndex > MAX_ROWS || sb.length() >= MAX_TEXT_LENGTH) {
                    log.warn("csv 解析达到上限（行数 {} / 文本长度 {}），已截断", rowIndex, sb.length());
                    break;
                }
                if (appendRow(sb, rowIndex, record, !headerDone)) {
                    headerDone = true;
                }
            }
        } catch (Exception e) {
            log.warn("csv 解析失败 filename={} 原因: {}", filename, e.getMessage());
            throw new IOException("csv 解析失败: " + e.getMessage(), e);
        }
        return sb.toString();
    }

    /**
     * RFC 4180 子集解析器：逐字符扫描，支持引号包裹字段、引号内逗号/换行、
     * {@code ""} 转义与 CRLF/LF 行尾。返回全部记录（含空记录），由调用方过滤。
     */
    private List<List<String>> parseCsvRecords(String content) {
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int n = content.length();
        while (i < n) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                field.append(c);
                i++;
                continue;
            }
            if (c == '"' && field.isEmpty()) {
                inQuotes = true;
                i++;
                continue;
            }
            if (c == ',') {
                current.add(field.toString());
                field.setLength(0);
                i++;
                continue;
            }
            if (c == '\n') {
                current.add(field.toString());
                field.setLength(0);
                records.add(current);
                current = new ArrayList<>();
                i++;
                continue;
            }
            if (c == '\r') {
                current.add(field.toString());
                field.setLength(0);
                records.add(current);
                current = new ArrayList<>();
                i++;
                if (i < n && content.charAt(i) == '\n') {
                    i++;
                }
                continue;
            }
            field.append(c);
            i++;
        }
        if (!field.isEmpty() || !current.isEmpty()) {
            current.add(field.toString());
            records.add(current);
        }
        return records;
    }

    // ==================== 公共渲染 ====================

    /**
     * 将一行单元格渲染为「第N行: 值1 | 值2」追加到输出。
     * 空行（全部单元格为空白）跳过；首行可标记为表头。
     *
     * @return 是否实际输出了该行（空行返回 false）
     */
    private static boolean appendRow(StringBuilder sb, int rowIndex, List<String> cells, boolean header) {
        String line = String.join(" | ", cells.stream()
                .filter(v -> v != null && !v.isBlank())
                .toList());
        if (line.isBlank()) {
            return false;
        }
        sb.append("第").append(rowIndex).append(header ? "行(表头)" : "行").append(": ")
                .append(line).append("\n");
        return true;
    }
}