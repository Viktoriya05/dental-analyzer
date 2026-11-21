package com.dental.analyzer.service;

import com.dental.analyzer.model.dto.SheetInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ExcelReaderService {

    /**
     * Читает листы только что загруженных файлов (по списку имён).
     * Так мы не тянем в выбор листов старые файлы из папки.
     */
    public List<SheetInfo> getFilesAndSheets(Path uploadDir, List<String> onlyFileNames) throws IOException {
        List<SheetInfo> result = new ArrayList<>();
        if (!Files.exists(uploadDir)) return result;

        for (String name : onlyFileNames) {
            Path file = uploadDir.resolve(name);
            if (!Files.exists(file)) continue;
            try (InputStream is = Files.newInputStream(file);
                 Workbook wb = new XSSFWorkbook(is)) {
                for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                    result.add(new SheetInfo(name, wb.getSheetName(i)));
                }
            }
        }
        return result;
    }

    /** Старая сигнатура — читает всю папку (оставлена для совместимости). */
    public List<SheetInfo> getFilesAndSheets(Path uploadDir) throws IOException {
        List<SheetInfo> result = new ArrayList<>();
        if (!Files.exists(uploadDir)) return result;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(uploadDir, "*.xlsx")) {
            for (Path file : stream) {
                try (InputStream is = Files.newInputStream(file);
                     Workbook wb = new XSSFWorkbook(is)) {
                    for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                        result.add(new SheetInfo(file.getFileName().toString(), wb.getSheetName(i)));
                    }
                }
            }
        }
        return result;
    }

    public List<List<String>> readSheetFully(Path filePath, String sheetName) throws IOException {
        try (InputStream is = Files.newInputStream(filePath);
             Workbook wb = new XSSFWorkbook(is)) {

            Sheet sheet = wb.getSheet(sheetName);
            if (sheet == null) return new ArrayList<>();

            DataFormatter fmt = new DataFormatter();
            List<List<String>> rows = new ArrayList<>();

            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        Cell cell = row.getCell(c);
                        cells.add(cell == null ? "" : fmt.formatCellValue(cell).trim());
                    }
                }
                rows.add(cells);
            }
            return rows;
        }
    }
}