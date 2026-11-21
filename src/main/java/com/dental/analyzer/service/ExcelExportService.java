package com.dental.analyzer.service;

import com.dental.analyzer.model.dto.AnalysisResult;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Экспорт AnalysisResult в .xlsx.
 * ТЗ: возможность сохранить результат в Excel.
 * Поддерживает общую статистику, таблицу и разбивку по листам.
 */
@Service
public class ExcelExportService {

    public byte[] export(AnalysisResult result, String sheetName) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sanitizeSheetName(sheetName));

            CellStyle titleStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            titleStyle.setFont(bold);

            int rowIdx = 0;

            // Заголовок
            Row titleRow = sheet.createRow(rowIdx++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(result.getTitle() == null ? "" : result.getTitle());
            titleCell.setCellStyle(titleStyle);

            // Лист-источник
            Row srcRow = sheet.createRow(rowIdx++);
            srcRow.createCell(0).setCellValue("Лист:");
            srcRow.createCell(1).setCellValue(sheetName == null ? "" : sheetName);

            // Список листов-источников
            if (result.getSourceSheets() != null && !result.getSourceSheets().isEmpty()) {
                Row shRow = sheet.createRow(rowIdx++);
                shRow.createCell(0).setCellValue("Источники:");
                shRow.createCell(1).setCellValue(String.join(", ", result.getSourceSheets()));
            }

            // Summary
            if (result.getSummary() != null && !result.getSummary().isBlank()) {
                sheet.createRow(rowIdx++).createCell(0).setCellValue(result.getSummary());
            }
            rowIdx++;

            // Статистика
            if (result.getStats() != null && !result.getStats().isEmpty()) {
                sheet.createRow(rowIdx++).createCell(0).setCellValue("Показатели:");
                for (Map.Entry<String, String> e : result.getStats().entrySet()) {
                    Row r = sheet.createRow(rowIdx++);
                    r.createCell(0).setCellValue(e.getKey());
                    r.createCell(1).setCellValue(e.getValue() == null ? "" : e.getValue());
                }
                rowIdx++;
            }

            // Разбивка по листам
            if (result.getPerSheetStats() != null && !result.getPerSheetStats().isEmpty()) {
                Row h = sheet.createRow(rowIdx++);
                Cell c0 = h.createCell(0);
                c0.setCellValue("Разбивка по листам");
                c0.setCellStyle(titleStyle);

                for (Map.Entry<String, Map<String, String>> sheetEntry : result.getPerSheetStats().entrySet()) {
                    Row sh = sheet.createRow(rowIdx++);
                    Cell shc = sh.createCell(0);
                    shc.setCellValue("Лист: " + sheetEntry.getKey());
                    shc.setCellStyle(titleStyle);
                    for (Map.Entry<String, String> e : sheetEntry.getValue().entrySet()) {
                        Row r = sheet.createRow(rowIdx++);
                        r.createCell(1).setCellValue(e.getKey());
                        r.createCell(2).setCellValue(e.getValue() == null ? "" : e.getValue());
                    }
                }
                rowIdx++;
            }

            // Таблица
            List<String> headers = result.getTableHeaders();
            List<List<String>> data = result.getTableData();
            if (headers != null && !headers.isEmpty()) {
                Row h = sheet.createRow(rowIdx++);
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = h.createCell(c);
                    cell.setCellValue(headers.get(c));
                    cell.setCellStyle(titleStyle);
                }
                if (data != null) {
                    for (List<String> row : data) {
                        Row r = sheet.createRow(rowIdx++);
                        for (int c = 0; c < row.size(); c++) {
                            r.createCell(c).setCellValue(row.get(c) == null ? "" : row.get(c));
                        }
                    }
                }
            }

            for (int c = 0; c < 10; c++) sheet.autoSizeColumn(c);

            wb.write(out);
            return out.toByteArray();
        }
    }

    private String sanitizeSheetName(String name) {
        if (name == null || name.isBlank()) return "Result";
        String s = name.replaceAll("[\\\\/*?:\\[\\]]", "_");
        return s.length() > 31 ? s.substring(0, 31) : s;
    }
}