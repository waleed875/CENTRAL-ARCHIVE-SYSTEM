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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
                            .filter(source -> "ACTIVE".equalsIgnoreCase(source.getStatus()))
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

    /**
     * The first sheet is one row per logical project (project_uid), so a project with several
     * versions is not counted repeatedly. A second sheet retains the per-version audit detail.
     * Package/file byte sizes are intentionally omitted from PDF/XLSX business reports.
     */
    private void excel(Path target, JsonNode manifest) throws Exception {
        try (SXSSFWorkbook book = new SXSSFWorkbook(100)) {
            book.setCompressTempFiles(true);
            CellStyle headStyle = book.createCellStyle();
            Font font = book.createFont();
            font.setBold(true);
            headStyle.setFont(font);

            Sheet projects = book.createSheet("المشاريع");
            projects.setRightToLeft(true);
            String[] projectHeadings = {"اسم المشروع", "رقم المشروع", "السنة", "عدد الإصدارات",
                    "أحدث إصدار", "الحالة الحالية", "إجمالي الملفات"};
            writeHeadings(projects, projectHeadings, headStyle);
            int projectRow = 1;
            for (List<JsonNode> versions : groupByLogicalProject(manifest).values()) {
                JsonNode latest = versions.get(versions.size() - 1);
                Row row = projects.createRow(projectRow++);
                textCell(row, 0, latest.path("name").asText());
                textCell(row, 1, latest.path("project_number").asText());
                row.createCell(2).setCellValue(latest.path("year").asInt());
                row.createCell(3).setCellValue(versions.size());
                row.createCell(4).setCellValue(latest.path("version_no").asInt(1));
                textCell(row, 5, latest.path("source_status").asText(""));
                row.createCell(6).setCellValue(totalFiles(versions));
            }
            projects.createFreezePane(0, 1);

            Sheet versionSheet = book.createSheet("تفاصيل الإصدارات");
            versionSheet.setRightToLeft(true);
            String[] versionHeadings = {"اسم المشروع", "رقم المشروع", "رقم الإصدار",
                    "حالة الإصدار", "أحدث إصدار", "عدد الملفات"};
            writeHeadings(versionSheet, versionHeadings, headStyle);
            int versionRow = 1;
            for (List<JsonNode> versions : groupByLogicalProject(manifest).values()) {
                for (JsonNode version : versions) {
                    Row row = versionSheet.createRow(versionRow++);
                    textCell(row, 0, version.path("name").asText());
                    textCell(row, 1, version.path("project_number").asText());
                    row.createCell(2).setCellValue(version.path("version_no").asInt(1));
                    textCell(row, 3, version.path("source_status").asText(""));
                    textCell(row, 4, version.path("is_latest_version").asBoolean(true) ? "نعم" : "لا");
                    row.createCell(5).setCellValue(version.path("files").size());
                }
            }
            versionSheet.createFreezePane(0, 1);
            try (OutputStream out = Files.newOutputStream(target)) { book.write(out); }
        }
    }

    private static void writeHeadings(Sheet sheet, String[] headings, CellStyle style) {
        Row top = sheet.createRow(0);
        for (int i = 0; i < headings.length; i++) {
            Cell cell = top.createCell(i);
            cell.setCellValue(headings[i]);
            cell.setCellStyle(style);
            sheet.setColumnWidth(i, i == 0 ? 10500 : 5200);
        }
    }

    private static Map<String, List<JsonNode>> groupByLogicalProject(JsonNode manifest) {
        Map<String, List<JsonNode>> groups = new LinkedHashMap<>();
        for (JsonNode project : manifest.path("projects")) {
            String key = project.path("project_uid").asText();
            if (key.isBlank()) key = project.path("project_id").asText();
            if (key.isBlank()) key = "unidentified-" + groups.size();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(project);
        }
        for (List<JsonNode> versions : groups.values()) {
            versions.sort(Comparator.comparingInt(version -> version.path("version_no").asInt(1)));
        }
        return groups;
    }

    private static int totalFiles(List<JsonNode> versions) {
        return versions.stream().mapToInt(version -> version.path("files").size()).sum();
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
                y = line(stream, font, 10, "كل مشروع منطقي يظهر مرة واحدة مع ملخص إصداراته", y - 10);
                for (List<JsonNode> versions : groupByLogicalProject(manifest).values()) {
                    if (y < 105) {
                        stream.close();
                        page = new PDPage(PDRectangle.A4);
                        document.addPage(page);
                        stream = new PDPageContentStream(document, page);
                        y = 785;
                    }
                    JsonNode latest = versions.get(versions.size() - 1);
                    String heading = "المشروع: " + latest.path("name").asText()
                            + " | رقم: " + latest.path("project_number").asText();
                    y = line(stream, font, 11, heading, y - 12);
                    y = line(stream, font, 10, "السنة: " + latest.path("year").asText()
                            + " | عدد الإصدارات: " + versions.size()
                            + " | الحالة الحالية: " + latest.path("source_status").asText(""), y);
                    StringBuilder history = new StringBuilder("الإصدارات: ");
                    for (int i = 0; i < versions.size(); i++) {
                        if (i > 0) history.append("، ");
                        JsonNode version = versions.get(i);
                        history.append("v").append(version.path("version_no").asInt(i + 1))
                                .append(" ").append(version.path("source_status").asText(""));
                    }
                    y = line(stream, font, 9, history.toString(), y);
                    y = line(stream, font, 9, "إجمالي الملفات المسجلة: " + totalFiles(versions), y);
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
