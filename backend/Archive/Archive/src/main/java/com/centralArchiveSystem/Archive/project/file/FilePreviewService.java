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
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static java.util.Map.entry;

/**
 * Preview content is served only for explicitly allowlisted types detected at upload.
 * HTML/SVG/XML are deliberately exposed as plain text, never as executable documents.
 * Office files are summarized as text on the server; they are never sent to an online viewer.
 */
@Service
public class FilePreviewService {
    public enum Kind { INLINE, TEXT, OFFICE, UNSUPPORTED }

    private static final int MAX_OFFICE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 120_000;
    private static final Map<String, String> INLINE_TYPES = Map.ofEntries(
            entry("pdf", "application/pdf"),
            entry("png", "image/png"), entry("jpg", "image/jpeg"), entry("jpeg", "image/jpeg"),
            entry("gif", "image/gif"), entry("webp", "image/webp"), entry("bmp", "image/bmp"),
            entry("avif", "image/avif"),
            entry("mp3", "audio/mpeg"), entry("wav", "audio/wav"),
            entry("flac", "audio/flac"), entry("m4a", "audio/mp4"),
            entry("mp4", "video/mp4"), entry("webm", "video/webm"),
            entry("mov", "video/quicktime")
    );
    private static final Map<String, String> TEXT_TYPES = Map.ofEntries(
            entry("txt", "text/plain"), entry("csv", "text/csv"),
            entry("md", "text/markdown"), entry("json", "application/json"),
            entry("xml", "application/xml"), entry("sql", "application/sql"),
            entry("html", "text/html"), entry("htm", "text/html"),
            entry("svg", "image/svg+xml"), entry("css", "text/css"),
            entry("js", "text/javascript")
    );
    private static final Map<String, String> OFFICE_TYPES = Map.of(
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    );

    private final FileService fileService;

    public FilePreviewService(FileService fileService) {
        this.fileService = fileService;
    }

    public static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static Kind classify(String filename, String detectedMime) {
        if (detectedMime == null) return Kind.UNSUPPORTED;
        String ext = extension(filename);
        String mime = detectedMime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (mime.equals(INLINE_TYPES.get(ext))) return Kind.INLINE;
        if (mime.equals(TEXT_TYPES.get(ext))) return Kind.TEXT;
        if (mime.equals(OFFICE_TYPES.get(ext))) return Kind.OFFICE;
        return Kind.UNSUPPORTED;
    }

    public String officeText(UUID fileId, String extension) {
        // Bound compressed input size before handing an untrusted Office archive to POI.
        try (InputStream input = fileService.downloadFile(fileId)) {
            byte[] bytes = input.readNBytes(MAX_OFFICE_BYTES + 1);
            if (bytes.length > MAX_OFFICE_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "حجم مستند Office أكبر من الحد المسموح للمعاينة (20 ميغابايت)");
            }
            return switch (extension) {
                case "docx" -> wordText(bytes);
                case "xlsx" -> spreadsheetText(bytes);
                case "pptx" -> slidesText(bytes);
                default -> throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            };
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "تعذر إنشاء معاينة نصية لهذا المستند", e);
        }
    }

    private static String wordText(byte[] bytes) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return limit(extractor.getText());
        }
    }

    private static String spreadsheetText(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        DataFormatter formatter = new DataFormatter();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (Sheet sheet : workbook) {
                append(result, "\n### " + sheet.getSheetName() + "\n");
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (result.length() >= MAX_TEXT_CHARS) break;
                        if (cell.getColumnIndex() > 0) append(result, "\t");
                        append(result, formatter.formatCellValue(cell));
                    }
                    append(result, "\n");
                    if (result.length() >= MAX_TEXT_CHARS) break;
                }
                if (result.length() >= MAX_TEXT_CHARS) break;
            }
        }
        return result.toString();
    }

    private static String slidesText(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        try (XMLSlideShow presentation = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            int number = 0;
            for (XSLFSlide slide : presentation.getSlides()) {
                append(result, "\n### شريحة " + ++number + "\n");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        append(result, textShape.getText() + "\n");
                    }
                    if (result.length() >= MAX_TEXT_CHARS) break;
                }
                if (result.length() >= MAX_TEXT_CHARS) break;
            }
        }
        return result.toString();
    }

    private static String limit(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_TEXT_CHARS) return text;
        return text.substring(0, MAX_TEXT_CHARS) + "\n… (تم اختصار المعاينة)";
    }

    private static void append(StringBuilder builder, String value) {
        if (value == null || builder.length() >= MAX_TEXT_CHARS) return;
        builder.append(value, 0, Math.min(value.length(), MAX_TEXT_CHARS - builder.length()));
    }
}
