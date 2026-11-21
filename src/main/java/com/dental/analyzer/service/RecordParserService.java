package com.dental.analyzer.service;

import com.dental.analyzer.model.PatientRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class RecordParserService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Старая сигнатура — без sheetKey (для совместимости). */
    public List<PatientRecord> parse(List<List<String>> sheetRows) {
        return parse(sheetRows, null);
    }

    public List<PatientRecord> parse(List<List<String>> sheetRows, String sheetKey) {
        List<PatientRecord> records = new ArrayList<>();
        if (sheetRows == null) return records;

        for (int i = 2; i < sheetRows.size(); i++) {
            List<String> row = sheetRows.get(i);
            if (row == null || row.isEmpty() || row.get(0).trim().isEmpty()) continue;

            try {
                String month = getSafe(row, 2);
                LocalDate date = parseDate(getSafe(row, 9));

                if (month == null || month.isEmpty()) {
                    if (date != null) month = String.format("%02d", date.getMonthValue());
                    else month = "00";
                }

                PatientRecord record = PatientRecord.builder()
                        .patientId(getSafe(row, 1))
                        .month(month)
                        .serviceCode(getSafe(row, 3))
                        .gender(getSafe(row, 5))
                        .age(parseInt(getSafe(row, 6)))
                        .toothNumber(parseInt(getSafe(row, 7)))
                        .visitDate(date)
                        .diagnosis(getSafe(row, 10))
                        .uet(parseDouble(getSafe(row, 11)))
                        .locality(getSafe(row, 12))
                        .sheetKey(sheetKey)
                        .build();

                records.add(record);
            } catch (Exception e) {
                log.warn("Ошибка парсинга строки {}: {}", i + 1, e.getMessage());
            }
        }
        return records;
    }

    private String getSafe(List<String> row, int index) {
        if (index >= row.size()) return "";
        return row.get(index).trim();
    }

    private Integer parseInt(String val) {
        if (val == null || val.isEmpty()) return null;
        try {
            if (val.contains(".")) val = val.substring(0, val.indexOf("."));
            return Integer.parseInt(val);
        } catch (NumberFormatException e) { return null; }
    }

    private Double parseDouble(String val) {
        if (val == null || val.isEmpty()) return 0.0;
        try { return Double.parseDouble(val.replace(",", ".")); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private LocalDate parseDate(String val) {
        if (val == null || val.isEmpty()) return null;
        try { return LocalDate.parse(val, DATE_FORMAT); }
        catch (Exception e) { return null; }
    }
}