package com.centralArchiveSystem.Archive.project.file;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class OfficePreviewServiceTest {
    @Test
    void wordPreservesParagraphsBoldAndTableRows() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFParagraph para = doc.createParagraph();
            para.createRun().setText("مرحبا ");
            var bold = para.createRun();
            bold.setBold(true);
            bold.setText("بالعالم");
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("العنوان");
            table.getRow(1).getCell(0).setText("القيمة");
            doc.write(output);
            bytes = output.toByteArray();
        }
        var result = OfficePreviewService.parse(bytes, "docx");
        assertEquals("word", result.type());
        assertTrue(result.blocks().stream().anyMatch(b -> b.runs().stream().anyMatch(r -> r.bold() && r.text().contains("بالعالم"))));
        assertTrue(result.blocks().stream().anyMatch(b -> b.type().equals("table") && b.rows().size() == 2));
    }

    @Test
    void excelDisplaysSheetsAndFormattedCells() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("المبيعات");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("المنتج");
            row.createCell(1).setCellValue(25.5);
            workbook.write(output);
            bytes = output.toByteArray();
        }
        var result = OfficePreviewService.parse(bytes, "xlsx");
        assertEquals("excel", result.type());
        assertEquals("المبيعات", result.sheets().get(0).name());
        assertEquals("المنتج", result.sheets().get(0).rows().get(0).get(0));
        assertEquals("25.5", result.sheets().get(0).rows().get(0).get(1));
    }

    @Test
    void powerpointDisplaysSlideText() throws Exception {
        byte[] bytes;
        try (XMLSlideShow presentation = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFSlide slide = presentation.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText("ملخص المشروع");
            presentation.write(output);
            bytes = output.toByteArray();
        }
        var result = OfficePreviewService.parse(bytes, "pptx");
        assertEquals("powerpoint", result.type());
        assertEquals(1, result.slides().size());
        assertTrue(result.slides().get(0).lines().contains("ملخص المشروع"));
    }
}
