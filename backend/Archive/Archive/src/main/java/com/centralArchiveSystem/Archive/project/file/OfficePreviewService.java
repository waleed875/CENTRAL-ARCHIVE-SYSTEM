package com.centralArchiveSystem.Archive.project.file;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Read-only bounded Office previews. Returns structured text, not HTML:
 * React escapes all user-supplied cell/paragraph contents, so documents cannot
 * inject scripts, remote resources, or untrusted CSS into the archive UI.
 */
@Service
public class OfficePreviewService {
    static final int MAX_INPUT_BYTES = 20 * 1024 * 1024;
    static final int MAX_CHARACTERS = 120_000;
    static final int MAX_WORD_BLOCKS = 250;
    static final int MAX_TABLE_ROWS = 80;
    static final int MAX_TABLE_COLUMNS = 20;
    static final int MAX_SHEETS = 8;
    static final int MAX_ROWS = 150;
    static final int MAX_COLUMNS = 30;
    static final int MAX_SLIDES = 60;

    public record Run(String text, boolean bold, boolean italic, boolean underline) {}
    public record WordBlock(String type, String align, List<Run> runs, List<List<String>> rows) {}
    public record SheetView(String name, List<List<String>> rows, boolean truncated) {}
    public record SlideView(int number, List<String> lines) {}
    public record Preview(String type, List<WordBlock> blocks, List<SheetView> sheets,
                          List<SlideView> slides, boolean truncated) {}

    private final FileService files;

    public OfficePreviewService(FileService files) {
        this.files = files;
    }

    public Preview preview(UUID id, String extension) {
        try (InputStream input = files.downloadFile(id)) {
            byte[] bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
            if (bytes.length > MAX_INPUT_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "حجم ملف Office أكبر من 20 ميغابايت؛ استخدم التنزيل");
            }
            return parse(bytes, extension);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "تعذر قراءة مستند Office للمعاينة", e);
        }
    }

    public static Preview parse(byte[] bytes, String extension) throws Exception {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "docx" -> readWord(bytes);
            case "xlsx" -> readExcel(bytes);
            case "pptx" -> readPowerPoint(bytes);
            default -> throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        };
    }

    private static Preview readWord(byte[] bytes) throws Exception {
        List<WordBlock> blocks = new ArrayList<>();
        int remaining = MAX_CHARACTERS;
        boolean truncated = false;
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (IBodyElement element : document.getBodyElements()) {
                if (blocks.size() >= MAX_WORD_BLOCKS || remaining <= 0) {
                    truncated = true;
                    break;
                }
                if (element instanceof XWPFParagraph paragraph) {
                    String style = paragraph.getStyle();
                    String type = style != null && style.toLowerCase(Locale.ROOT).contains("heading")
                            ? "heading" : "paragraph";
                    String align = paragraph.getAlignment().name();
                    List<Run> runs = new ArrayList<>();
                    for (XWPFRun run : paragraph.getRuns()) {
                        if (remaining <= 0) { truncated = true; break; }
                        String value = run.text();
                        if (value == null || value.isEmpty()) continue;
                        value = abbreviate(value, remaining);
                        remaining -= value.length();
                        runs.add(new Run(value, run.isBold(), run.isItalic(),
                                run.getUnderline() != UnderlinePatterns.NONE));
                    }
                    if (!runs.isEmpty()) blocks.add(new WordBlock(type, align, runs, List.of()));
                } else if (element instanceof XWPFTable table) {
                    List<List<String>> rows = new ArrayList<>();
                    int rowIndex = 0;
                    for (XWPFTableRow row : table.getRows()) {
                        if (rowIndex++ >= MAX_TABLE_ROWS || remaining <= 0) {
                            truncated = true; break;
                        }
                        List<String> cells = new ArrayList<>();
                        int columnIndex = 0;
                        for (XWPFTableCell cell : row.getTableCells()) {
                            if (columnIndex++ >= MAX_TABLE_COLUMNS || remaining <= 0) {
                                truncated = true; break;
                            }
                            String text = abbreviate(cell.getText(), Math.min(remaining, 800));
                            cells.add(text);
                            remaining -= text.length();
                        }
                        rows.add(cells);
                    }
                    if (!rows.isEmpty()) blocks.add(new WordBlock("table", "LEFT", List.of(), rows));
                }
            }
        }
        return new Preview("word", blocks, List.of(), List.of(), truncated);
    }

    private static Preview readExcel(byte[] bytes) throws Exception {
        List<SheetView> sheets = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        int remaining = MAX_CHARACTERS;
        boolean truncated = false;
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            int sheetCount = Math.min(workbook.getNumberOfSheets(), MAX_SHEETS);
            if (workbook.getNumberOfSheets() > sheetCount) truncated = true;
            for (int i = 0; i < sheetCount; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                List<List<String>> rows = new ArrayList<>();
                boolean sheetTruncated = sheet.getLastRowNum() >= MAX_ROWS;
                int rowLimit = Math.min(sheet.getLastRowNum() + 1, MAX_ROWS);
                for (int r = 0; r < rowLimit; r++) {
                    if (remaining <= 0) { sheetTruncated = true; break; }
                    Row row = sheet.getRow(r);
                    int cellCount = row == null ? 0 : Math.min(row.getLastCellNum(), MAX_COLUMNS);
                    if (row != null && row.getLastCellNum() > MAX_COLUMNS) sheetTruncated = true;
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < Math.max(0, cellCount); c++) {
                        Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        String value = cell == null ? "" : formatter.formatCellValue(cell);
                        value = abbreviate(value, Math.min(remaining, 400));
                        remaining -= value.length();
                        cells.add(value);
                    }
                    rows.add(cells);
                }
                sheets.add(new SheetView(sheet.getSheetName(), rows, sheetTruncated));
                truncated |= sheetTruncated;
                if (remaining <= 0) { truncated = true; break; }
            }
        }
        return new Preview("excel", List.of(), sheets, List.of(), truncated);
    }

    private static Preview readPowerPoint(byte[] bytes) throws Exception {
        List<SlideView> slides = new ArrayList<>();
        int remaining = MAX_CHARACTERS;
        boolean truncated = false;
        try (XMLSlideShow document = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            int slideNo = 0;
            for (XSLFSlide slide : document.getSlides()) {
                if (slideNo >= MAX_SLIDES || remaining <= 0) {
                    truncated = true; break;
                }
                List<String> lines = new ArrayList<>();
                for (XSLFShape shape : slide.getShapes()) {
                    if (remaining <= 0) { truncated = true; break; }
                    if (shape instanceof XSLFTextShape textShape) {
                        String value = abbreviate(textShape.getText(), remaining);
                        remaining -= value.length();
                        if (!value.isBlank()) lines.add(value);
                    }
                }
                slides.add(new SlideView(++slideNo, lines));
            }
        }
        return new Preview("powerpoint", List.of(), List.of(), slides, truncated);
    }

    private static String abbreviate(String text, int count) {
        if (text == null || count <= 0) return "";
        return text.substring(0, Math.min(text.length(), count));
    }
}
