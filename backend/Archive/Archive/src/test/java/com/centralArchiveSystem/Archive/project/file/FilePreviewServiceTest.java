package com.centralArchiveSystem.Archive.project.file;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FilePreviewServiceTest {
    @Test
    void allowsKnownInlineTypesOnlyWhenMimeMatchesExtension() {
        assertEquals(FilePreviewService.Kind.INLINE,
                FilePreviewService.classify("scan.pdf", "application/pdf"));
        assertEquals(FilePreviewService.Kind.INLINE,
                FilePreviewService.classify("photo.JPG", "image/jpeg"));
        assertEquals(FilePreviewService.Kind.INLINE,
                FilePreviewService.classify("lecture.mp4", "video/mp4"));
        assertEquals(FilePreviewService.Kind.UNSUPPORTED,
                FilePreviewService.classify("fake.pdf", "text/html"));
        assertEquals(FilePreviewService.Kind.UNSUPPORTED,
                FilePreviewService.classify("unsafe.exe", "application/pdf"));
    }

    @Test
    void marksMarkupAsTextAndNotActiveBrowserContent() {
        assertEquals(FilePreviewService.Kind.TEXT,
                FilePreviewService.classify("template.html", "text/html"));
        assertEquals(FilePreviewService.Kind.TEXT,
                FilePreviewService.classify("diagram.svg", "image/svg+xml"));
        assertEquals(FilePreviewService.Kind.TEXT,
                FilePreviewService.classify("data.json", "application/json"));
        assertEquals(FilePreviewService.Kind.UNSUPPORTED,
                FilePreviewService.classify("diagram.svg", "image/png"));
    }

    @Test
    void officePreviewRequiresMatchingTypeAndUnsupportedLegacyFilesStayDownloadOnly() {
        assertEquals(FilePreviewService.Kind.OFFICE,
                FilePreviewService.classify("letter.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertEquals(FilePreviewService.Kind.OFFICE,
                FilePreviewService.classify("report.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        assertEquals(FilePreviewService.Kind.OFFICE,
                FilePreviewService.classify("slides.pptx",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"));
        assertEquals(FilePreviewService.Kind.UNSUPPORTED,
                FilePreviewService.classify("letter.docx", "application/zip"));
        assertEquals(FilePreviewService.Kind.UNSUPPORTED,
                FilePreviewService.classify("old.doc", "application/msword"));
    }
}
