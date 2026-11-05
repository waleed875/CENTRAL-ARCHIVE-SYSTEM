package com.centralArchiveSystem.Archive.import_.package_.export.export;

import com.centralArchiveSystem.Archive.project.file.FileEntity;
import com.centralArchiveSystem.Archive.project.file.FileRepository;
import com.centralArchiveSystem.Archive.project.file.MinioService;
import com.fasterxml.jackson.databind.JsonNode;
import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Bidi;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Delivery formats, not importable PKG archives.
 * The caller has already checked clearance on every project and file in the manifest.
 */
@Component
public class ExportAlternativeWriter {
    private final FileRepository files;
    private final MinioService storage;
    private final String configuredPdfFont;

    public ExportAlternativeWriter(FileRepository files,
                                   MinioService storage,
                                   @Value("${archive.export.pdf-font-path:}") String pdfFont) {
        this.files = files;
        this.storage = storage;
        this.configuredPdfFont = pdfFont;
    }

    public void write(String format, Path target, JsonNode manifest) throws Exception {
        switch (format) {
            case "ZIP" -> zip(target, manifest);
            case "XLSX" -> excel(target, manifest);
            case "PDF" -> pdf(target, manifest);
            default -> throw new IllegalArgumentException("Unsupported delivery format: " + format);
        }
    }

    /**
     * ZIP contains original documents in separated version directories plus a
     * UTF-8 CSV inventory. It is NOT a .pkg and cannot be used for system import.
     */
    private void zip(Path target, JsonNode manifest) throws Exception {
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("inventory.csv"));
            String header = "\uFEFF" + "Project,Project number,Version,File,Bytes,SHA-256\r\n";
            zip.write(header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (JsonNode project : manifest.path("projects")) {
                for (JsonNode file : project.path("files")) {
                    String csv = csv(project.path("name").asText()) + ","
                            + csv(project.path("project_number").asText()) + ","
                            + project.path("version_no").asInt(1) + ","
                            + csv(file.path("original_name").asText()) + ","
                            + file.path("size_bytes").asLong() + ","
                            + csv(file.path("checksum_sha256").asText()) + "\r\n";
                    zip.write(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            zip.closeEntry();

            for (JsonNode project : manifest.path("projects")) {
                UUID projectId = UUID.fromString(project.path("project_id").asText());
                int version = project.path("version_no").asInt(1);
                for (JsonNode file : project.path("files")) {
                    UUID fileId = UUID.fromString(file.path("file_id").asText());
                    FileEntity entity = files.findById(fileId)
                            .filter(source -> source.getProjectId().equals(projectId))
                            .filter(source -> source.getDeletedAt() == null)
                            .orElseThrow(() -> new IllegalStateException("Source export file disappeared"));
                    String name = safeName(file.path("original_name").asText("document"));
                    String entry = "projects/" + projectId + "/version-" + version + "/"
                            + fileId + "-" + name;
                    zip.putNextEntry(new ZipEntry(entry));
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    long size = 0;
                    try (InputStream content = storage.downloadFile(entity.getBucketName(), entity.getObjectKey())) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = content.read(buffer)) != -1) {
                            zip.write(buffer, 0, count);
                            digest.update(buffer, 0, count);
                            size += count;
                        }
                    }
                    zip.closeEntry();
                    String actual = HexFormat.of().formatHex(digest.digest());
                    if (!actual.equalsIgnoreCase(file.path("checksum_sha256").asText())
                            || (file.hasNonNull("size_bytes") && size != file.path("size_bytes").asLong())) {
                        throw new IllegalStateException("Source export file integrity check failed");
                    }
                }
            }
        }
    }

    private static String csv(String value) {
        // Neutralize formulas when CSV is opened in a spreadsheet application.
        String text = value == null ? "" : value;
        if (text.matches("^\\s*[=+@\\-\\t\\r].*")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static String safeName(String value) {
        String sanitized = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("^\\.+", "_");
        if (sanitized.isEmpty()) sanitized = "document";
        return sanitized.substring(0, Math.min(sanitized.length(), 120));
    }

    /** Arabic RTL sheet with the original name, number, version and file inventory. */
    private void excel(Path target, JsonNode manifest) throws Exception {
        try (SXSSFWorkbook book = new SXSSFWorkbook(100)) {
            book.setCompressTempFiles(true);
            Sheet sheet = book.createSheet("المشاريع");
            sheet.setRightToLeft(true);
            String[] headings = {"اسم المشروع", "رقم المشروع", "السنة", "الإصدار",
                    "أحدث إصدار", "حالة المصدر", "عدد الملفات", "اسم الملف", "حجم الملف (بايت)", "SHA-256"};
            Row top = sheet.createRow(0);
            CellStyle headStyle = book.createCellStyle();
            Font font = book.createFont();
            font.setBold(true);
            headStyle.setFont(font);
            for (int i = 0; i < headings.length; i++) {
                Cell cell = top.createCell(i);
                cell.setCellValue(headings[i]);
                cell.setCellStyle(headStyle);
                sheet.setColumnWidth(i, i == 0 || i == 7 ? 9000 : 5200);
            }
            int index = 1;
            for (JsonNode project : manifest.path("projects")) {
                JsonNode projectFiles = project.path("files");
                if (!projectFiles.isArray() || projectFiles.isEmpty()) {
                    fillRow(sheet.createRow(index++), project, null);
                    continue;
                }
                for (JsonNode file : projectFiles) fillRow(sheet.createRow(index++), project, file);
            }
            sheet.createFreezePane(0, 1);
            try (OutputStream out = Files.newOutputStream(target)) { book.write(out); }
        }
    }

    private void fillRow(Row row, JsonNode project, JsonNode file) {
        textCell(row, 0, project.path("name").asText());
        textCell(row, 1, project.path("project_number").asText());
        row.createCell(2).setCellValue(project.path("year").asInt());
        row.createCell(3).setCellValue(project.path("version_no").asInt(1));
        textCell(row, 4, project.path("is_latest_version").asBoolean(true) ? "نعم" : "لا");
        textCell(row, 5, project.path("source_status").asText(""));
        row.createCell(6).setCellValue(project.path("files").size());
        if (file != null) {
            textCell(row, 7, file.path("original_name").asText());
            row.createCell(8).setCellValue(file.path("size_bytes").asDouble());
            textCell(row, 9, file.path("checksum_sha256").asText());
        }
    }

    private static void textCell(Row row, int column, String value) {
        // Explicit string type prevents spreadsheet formula evaluation.
        row.createCell(column, CellType.STRING).setCellValue(value);
    }

    /**
     * The PDF generator needs a locally licensed Arabic-capable TrueType font.
     * Set EXPORT_PDF_FONT_PATH on Windows or Linux when no common system font exists.
     */
    private void pdf(Path target, JsonNode manifest) throws Exception {
        Path fontPath = resolvePdfFont();
        try (PDDocument document = new PDDocument()) {
            PDType0Font font = PDType0Font.load(document, fontPath.toFile());
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDPageContentStream stream = new PDPageContentStream(document, page);
            float y = 780;
            try {
                y = line(stream, font, 16, "تقرير بيانات المشاريع", y);
                y = line(stream, font, 10, "يتضمن بيانات جميع الإصدارات المحددة (دون الملفات الأصلية)", y - 10);
                for (JsonNode project : manifest.path("projects")) {
                    if (y < 90) {
                        stream.close();
                        page = new PDPage(PDRectangle.A4);
                        document.addPage(page);
                        stream = new PDPageContentStream(document, page);
                        y = 785;
                    }
                    String heading = "المشروع: " + project.path("name").asText()
                            + " | رقم: " + project.path("project_number").asText();
                    y = line(stream, font, 11, heading, y - 12);
                    y = line(stream, font, 10, "السنة: " + project.path("year").asText()
                            + " | الإصدار: " + project.path("version_no").asText("1")
                            + " | الحالة: " + project.path("source_status").asText(""), y);
                    y = line(stream, font, 9, "عدد الملفات المسجلة: " + project.path("files").size(), y);
                }
            } finally { stream.close(); }
            document.save(target.toFile());
        }
    }

    private Path resolvePdfFont() {
        List<String> paths = configuredPdfFont.isBlank() ?
                List.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                        "C:/Windows/Fonts/arial.ttf",
                        "C:/Windows/Fonts/tahoma.ttf") :
                List.of(configuredPdfFont);
        for (String candidate : paths) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) return path;
        }
        throw new IllegalStateException(
                "لا يمكن إنشاء PDF دون خط عربي؛ اضبط EXPORT_PDF_FONT_PATH على خط TrueType يدعم العربية");
    }

    private float line(PDPageContentStream stream, PDType0Font font, int size,
                       String text, float y) throws Exception {
        String content = visualArabic(text);
        // Long metadata stays visible within one line by ellipsizing before shaping.
        while (content.length() > 8 && font.getStringWidth(content) / 1000 * size > 490) {
            text = text.substring(0, Math.max(1, text.length() - 5));
            content = visualArabic(text + "…");
        }
        stream.beginText();
        stream.setFont(font, size);
        float width = font.getStringWidth(content) / 1000 * size;
        stream.newLineAtOffset(550 - width, y);
        stream.showText(content);
        stream.endText();
        return y - size - 9;
    }

    private String visualArabic(String text) throws ArabicShapingException {
        ArabicShaping shaper = new ArabicShaping(ArabicShaping.LETTERS_SHAPE
                | ArabicShaping.TEXT_DIRECTION_LOGICAL);
        String shaped = shaper.shape(text);
        Bidi bidi = new Bidi(shaped, Bidi.DIRECTION_RIGHT_TO_LEFT);
        if (!bidi.isMixed()) return bidi.isRightToLeft() ? reverse(shaped) : shaped;
        StringBuilder result = new StringBuilder();
        for (int run = bidi.getRunCount() - 1; run >= 0; run--) {
            int start = bidi.getRunStart(run), end = bidi.getRunLimit(run);
            String part = shaped.substring(start, end);
            result.append((bidi.getRunLevel(run) & 1) != 0 ? reverse(part) : part);
        }
        return result.toString();
    }

    private static String reverse(String value) { return new StringBuilder(value).reverse().toString(); }
}
