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
import java.io.IOException;
import java.io.OutputStream;
import org.mockito.Mockito;
import java.util.UUID;
import static org.mockito.Mockito.*;

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
    void handlesLargeDeclaredPdfWithoutInMemorySizeRejection() throws Exception {
        byte[] content;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(PDRectangle.A4));
            document.save(bytes);
            content = bytes.toByteArray();
        }
        FileService files = Mockito.mock(FileService.class);
        UUID fileId = UUID.randomUUID();
        when(files.downloadFile(fileId)).thenAnswer(invocation -> new ByteArrayInputStream(content));
        PdfPreviewService service = new PdfPreviewService(files);
        // A 101 MiB declared file should no longer be rejected merely because of metadata size.
        assertEquals(1, service.inspect(fileId, 101L * 1024 * 1024).pageCount());
        byte[] page = service.renderPage(fileId, 101L * 1024 * 1024, 1);
        assertTrue(page.length > 100);
        verify(files, times(2)).downloadFile(fileId);
    }

    @Test
    void boundedCopyRejectsOversizedStreamRegardlessOfDeclaredMetadata() {
        var input = new ByteArrayInputStream(new byte[1025]);
        var output = new ByteArrayOutputStream();
        assertEquals(413, assertThrows(ResponseStatusException.class,
                () -> PdfPreviewService.copyBounded(input, output, 1024)).getStatusCode().value());
        assertTrue(output.size() <= 1024);
    }

    @Test
    void refusesEmptyDocuments() throws Exception {
        try (PDDocument document = new PDDocument()) {
            assertEquals(422, assertThrows(ResponseStatusException.class,
                    () -> PdfPreviewService.pageInfo(document)).getStatusCode().value());
        }
    }
}
