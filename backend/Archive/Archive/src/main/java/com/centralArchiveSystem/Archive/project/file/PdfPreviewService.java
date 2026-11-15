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

    static final int MAX_PDF_BYTES = 50 * 1024 * 1024;
    static final int MAX_PAGES = 500;
    static final int MAX_IMAGE_SIDE = 2200;
    static final long MAX_RENDER_PIXELS = 3_000_000L;
    private static final Semaphore RENDER_SLOTS = new Semaphore(2);

    private final FileService files;

    public PdfPreviewService(FileService files) {
        this.files = files;
    }

    public PdfInfo inspect(UUID id, Long declaredSize) {
        byte[] bytes = readPdf(id, declaredSize);
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return pageInfo(document);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "تعذر فتح PDF، قد يكون مشفرًا أو تالفًا", e);
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
            return renderPdf(readPdf(id, declaredSize), pageNumber);
        } finally {
            RENDER_SLOTS.release();
        }
    }

    private byte[] readPdf(UUID id, Long declaredSize) {
        if (declaredSize != null && declaredSize > MAX_PDF_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "ملف PDF أكبر من الحد المسموح للمعاينة (50 ميغابايت)");
        }
        try (InputStream stream = files.downloadFile(id)) {
            byte[] bytes = stream.readNBytes(MAX_PDF_BYTES + 1);
            if (bytes.length > MAX_PDF_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "ملف PDF أكبر من الحد المسموح للمعاينة (50 ميغابايت)");
            }
            return bytes;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "تعذر قراءة PDF من مخزن الملفات", e);
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

    static byte[] renderPdf(byte[] bytes, int pageNumber) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
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
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "تعذر عرض صفحة PDF، قد يكون الملف تالفًا أو محميًا بكلمة مرور", e);
        }
    }
}
