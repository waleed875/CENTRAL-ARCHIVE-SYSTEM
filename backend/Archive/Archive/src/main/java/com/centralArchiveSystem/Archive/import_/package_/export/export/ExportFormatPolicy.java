package com.centralArchiveSystem.Archive.import_.package_.export.export;

/** Published choices for UI clients; mirrored by ExportRequest.validateOptions. */
public final class ExportFormatPolicy {
    private ExportFormatPolicy() {}
    public static String extension(String format, boolean encrypted) {
        if (encrypted) return "nxpkg";
        return switch (format) {
            case "PKG" -> "pkg";
            case "ZIP" -> "zip";
            case "XLSX" -> "xlsx";
            case "PDF" -> "pdf";
            default -> throw new IllegalArgumentException("نوع التصدير غير مدعوم");
        };
    }
    public static org.springframework.http.MediaType mediaType(String filename) {
        if (filename.endsWith(".pdf")) return org.springframework.http.MediaType.APPLICATION_PDF;
        if (filename.endsWith(".zip")) return org.springframework.http.MediaType.parseMediaType("application/zip");
        if (filename.endsWith(".xlsx")) return org.springframework.http.MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        return org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
    }
}
