package com.centralArchiveSystem.Archive.project.file;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * PDF pages are rasterized by the server, so preview does not depend on the
 * user's built-in browser PDF extension, iframe policies, or external viewers.
 * All input comes from FileService, which enforces project and file clearance.
 * Rendering is bounded to avoid unbounded memory use on hostile PDF pages.
 */
@Service
public class PdfPreviewService {
    public record PdfInfo(int pageCount) {}

    static final long MAX_PDF_BYTES = 512L * 1024 * 1024;
    static final int PDF_COPY_BUFFER_BYTES = 64 * 1024;
    static final int MAX_PAGES = 500;
    static final int MAX_IMAGE_SIDE = 2200;
    static final long MAX_RENDER_PIXELS = 3_000_000L;
    private static final Semaphore RENDER_SLOTS = new Semaphore(2);

    @org.springframework.beans.factory.annotation.Value("${archive.storage.temp-dir:}")
    private String storageTempDir;

    private final FileService files;

    public PdfPreviewService(FileService files) {
        this.files = files;
    }

    public PdfInfo inspect(UUID id, Long declaredSize) {
        if (!RENDER_SLOTS.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "يوجد عدد كبير من معاينات PDF قيد التنفيذ؛ حاول مجددًا");
        }
        try {
            Path spool = spoolPdf(id, declaredSize);
            try {
                try (PDDocument document = Loader.loadPDF(spool.toFile())) {
                    return pageInfo(document);
                }
            } catch (ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "تعذر فتح PDF، قد يكون مشفرًا أو تالفًا", e);
            } finally {
                Files.deleteIfExists(spool);
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "تعذر تنظيف ملف معاينة PDF المؤقت", e);
        } finally {
            RENDER_SLOTS.release();
        }
    }

    public byte[] renderPage(UUID id, Long declaredSize, int pageNumber) {
        if (pageNumber < 1 || pageNumber > MAX_PAGES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "رقم صفحة PDF غير صالح");
        }
        if (!RENDER_SLOTS.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "يوجد عدد كبير من معاينات PDF قيد التنفيذ؛ حاول مجددًا");
        }
        try {
            Path spool = spoolPdf(id, declaredSize);
            try {
                try (PDDocument document = Loader.loadPDF(spool.toFile())) {
                    return renderDocument(document, pageNumber);
                } catch (ResponseStatusException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "تعذر عرض صفحة PDF، قد يكون الملف تالفًا أو محميًا بكلمة مرور", e);
                }
            } finally {
                Files.deleteIfExists(spool);
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "تعذر تنظيف ملف معاينة PDF المؤقت", e);
        } finally {
            RENDER_SLOTS.release();
        }
    }

    /**
     * Stage authorized PDF data to an isolated temporary file. A large document
     * is never converted into one JVM byte array before PDFBox opens it.
     * Keep a strict disk budget as a second check against forged size metadata.
     */
    private Path spoolPdf(UUID id, Long declaredSize) {
        if (declaredSize != null && declaredSize > MAX_PDF_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "ملف PDF أكبر من حد المعاينة المؤقتة (512 ميغابايت)");
        }
        Path spool = null;
        try {
            Path tempDirectory = (storageTempDir == null || storageTempDir.isBlank())
                    ? Path.of(System.getProperty("java.io.tmpdir"))
                    : Path.of(storageTempDir);
            Files.createDirectories(tempDirectory);
            spool = Files.createTempFile(tempDirectory, "archive-pdf-preview-", ".pdf");
            try (InputStream input = files.downloadFile(id);
                 OutputStream output = Files.newOutputStream(spool, StandardOpenOption.TRUNCATE_EXISTING)) {
                copyBounded(input, output, MAX_PDF_BYTES);
            }
            return spool;
        } catch (ResponseStatusException e) {
            cleanup(spool);
            throw e;
        } catch (Exception e) {
            cleanup(spool);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "تعذر قراءة PDF من مخزن الملفات", e);
        }
    }

    static long copyBounded(InputStream input, OutputStream output, long maxBytes) throws java.io.IOException {
        long copied = 0;
        byte[] buffer = new byte[PDF_COPY_BUFFER_BYTES];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) continue;
            if (copied > maxBytes - read) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "ملف PDF أكبر من حد المعاينة المؤقتة (512 ميغابايت)");
            }
            output.write(buffer, 0, read);
            copied += read;
        }
        return copied;
    }

    private static void cleanup(Path spool) {
        if (spool != null) {
            try { Files.deleteIfExists(spool); }
            catch (java.io.IOException ignored) { /* spool remains OS-managed */ }
        }
    }

    static PdfInfo pageInfo(PDDocument document) {
        int pages = document.getNumberOfPages();
        if (pages < 1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "مستند PDF لا يحتوي صفحات");
        }
        if (pages > MAX_PAGES) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "يحتوي PDF على أكثر من 500 صفحة؛ لا تتوفر معاينة لهذا المستند");
        }
        return new PdfInfo(pages);
    }

    /** Render from authenticated package bytes without requiring a persisted FileEntity. */
    public static PdfInfo inspectBytes(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return pageInfo(document);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "تعذر قراءة PDF داخل الحزمة؛ قد يكون مشفرًا أو تالفًا", e);
        }
    }

    public static byte[] renderPackagePdfPage(byte[] bytes, int pageNumber) {
        if (pageNumber < 1 || pageNumber > MAX_PAGES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "رقم صفحة PDF غير صالح");
        }
        if (!RENDER_SLOTS.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "عدد معاينات PDF كبير حاليًا؛ حاول مجددًا");
        }
        try {
            return renderPdf(bytes, pageNumber);
        } finally {
            RENDER_SLOTS.release();
        }
    }

    static byte[] renderPdf(byte[] bytes, int pageNumber) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return renderDocument(document, pageNumber);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "تعذر عرض صفحة PDF، قد يكون الملف تالفًا أو محميًا بكلمة مرور", e);
        }
    }

    private static byte[] renderDocument(PDDocument document, int pageNumber) throws java.io.IOException {
            PdfInfo info = pageInfo(document);
            if (pageNumber < 1 || pageNumber > info.pageCount()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "رقم صفحة PDF غير موجود");
            }
            PDPage page = document.getPage(pageNumber - 1);
            PDRectangle box = page.getCropBox();
            float width = box.getWidth();
            float height = box.getHeight();
            if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "أبعاد صفحة PDF غير صالحة");
            }
            double largest = Math.max(width, height);
            double pixels = (double) width * height;
            double factor = Math.min(1.7d,
                    Math.min(MAX_IMAGE_SIDE / largest, Math.sqrt(MAX_RENDER_PIXELS / pixels)));
            if (!(factor > 0) || !Double.isFinite(factor)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "أبعاد صفحة PDF غير صالحة");
            }
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            BufferedImage image = renderer.renderImage(pageNumber - 1, (float)factor, ImageType.RGB);
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!ImageIO.write(image, "png", output)) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "تعذر تحويل صفحة PDF إلى صورة");
                }
                return output.toByteArray();
            } finally {
                image.flush();
            }
    }
}
