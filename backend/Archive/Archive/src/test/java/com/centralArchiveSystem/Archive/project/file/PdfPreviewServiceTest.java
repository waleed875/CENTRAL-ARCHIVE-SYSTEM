package com.centralArchiveSystem.Archive.project.file;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PdfPreviewServiceTest {
    @Test
    void rendersRealPdfPageToPngAndReportsItsPageCount() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(PDRectangle.A4));
            document.addPage(new PDPage(PDRectangle.LETTER));
            document.save(out);
            pdf = out.toByteArray();
        }
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(2, PdfPreviewService.pageInfo(document).pageCount());
        }
        byte[] png = PdfPreviewService.renderPdf(pdf, 1);
        assertTrue(png.length > 100);
        assertEquals((byte)0x89, png[0]);
        assertEquals((byte)'P', png[1]);
        assertEquals((byte)'N', png[2]);
        assertEquals((byte)'G', png[3]);
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        assertTrue(image.getWidth() > 500);
        assertTrue((long)image.getWidth() * image.getHeight() <= PdfPreviewService.MAX_RENDER_PIXELS + 10000);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> PdfPreviewService.renderPdf(pdf, 3)).getStatusCode().value());
    }

    @Test
    void rejectsCorruptedPdf() {
        assertEquals(422, assertThrows(ResponseStatusException.class,
                () -> PdfPreviewService.renderPdf(new byte[]{1, 2, 3, 4}, 1)).getStatusCode().value());
    }

    @Test
    void refusesEmptyDocuments() throws Exception {
        try (PDDocument document = new PDDocument()) {
            assertEquals(422, assertThrows(ResponseStatusException.class,
                    () -> PdfPreviewService.pageInfo(document)).getStatusCode().value());
        }
    }
}
