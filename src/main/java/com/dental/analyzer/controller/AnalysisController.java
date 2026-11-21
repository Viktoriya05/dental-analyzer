package com.dental.analyzer.controller;

import com.dental.analyzer.model.PatientRecord;
import com.dental.analyzer.model.dto.AnalysisResult;
import com.dental.analyzer.model.dto.SheetRequest;
import com.dental.analyzer.service.AnalysisDispatcherService;
import com.dental.analyzer.service.ExcelExportService;
import com.dental.analyzer.service.ExcelReaderService;
import com.dental.analyzer.service.RecordParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class AnalysisController {

    private final ExcelReaderService excelReaderService;
    private final RecordParserService recordParserService;
    private final AnalysisDispatcherService dispatcherService;
    private final ExcelExportService excelExportService;

    /** Одиночный выбор листа (совместимость). */
    @GetMapping("/analyze")
    public String chooseAnalysis(@RequestParam String fileName, @RequestParam String sheetName, Model model) {
        SheetRequest request = new SheetRequest();
        request.setFileName(fileName);
        request.setSheetName(sheetName);
        model.addAttribute("request", request);
        return "choose_analysis";
    }

    /**
     * Мультивыбор: получаем список "fileName::sheetName", показываем форму
     * выбора пункта ТЗ.
     */
    @PostMapping("/analyze-multi")
    public String chooseAnalysisMulti(@RequestParam(value = "sheets", required = false) List<String> sheets,
                                      Model model) {
        if (sheets == null || sheets.isEmpty()) {
            model.addAttribute("error", "Выберите хотя бы один лист");
            return "index";
        }
        model.addAttribute("sheets", sheets);
        return "choose_analysis_multi";
    }

    /** Одиночный запуск. */
    @PostMapping("/run")
    public String runAnalysis(@ModelAttribute SheetRequest request, Model model) {
        try {
            Path filePath = Paths.get(UploadController.UPLOAD_DIR).resolve(request.getFileName());
            List<List<String>> rows = excelReaderService.readSheetFully(filePath, request.getSheetName());
            String sheetKey = request.getFileName() + "::" + request.getSheetName();
            List<PatientRecord> records = recordParserService.parse(rows, sheetKey);

            AnalysisResult result = dispatcherService.runAnalysis(request.getAnalysisType(), records);

            model.addAttribute("result", result);
            model.addAttribute("sheetName", request.getSheetName());
            model.addAttribute("fileName", request.getFileName());
            model.addAttribute("analysisType", request.getAnalysisType());
            model.addAttribute("sheetsCsv", sheetKey);
            return "analysis_result";
        } catch (Exception e) {
            log.error("Ошибка анализа", e);
            model.addAttribute("error", "Ошибка анализа: " + e.getMessage());
            return "index";
        }
    }

    /**
     * Мультизапуск: читаем все выбранные листы, объединяем записи,
     * запускаем один анализ. Для каждого листа дополнительно считаем
     * агрегаты и кладём в perSheetStats.
     */
    @PostMapping("/run-multi")
    public String runAnalysisMulti(@RequestParam("sheets") List<String> sheets,
                                   @RequestParam("analysisType") int analysisType,
                                   Model model) {
        try {
            List<PatientRecord> all = new ArrayList<>();
            List<String> sourceSheets = new ArrayList<>();

            for (String s : sheets) {
                String[] parts = s.split("::", 2);
                if (parts.length != 2) continue;
                String fileName = parts[0];
                String sheetName = parts[1];
                Path filePath = Paths.get(UploadController.UPLOAD_DIR).resolve(fileName);
                List<List<String>> rows = excelReaderService.readSheetFully(filePath, sheetName);
                List<PatientRecord> records = recordParserService.parse(rows, s);
                all.addAll(records);
                sourceSheets.add(s);
            }

            if (all.isEmpty()) {
                model.addAttribute("error", "Не удалось прочитать данные из выбранных листов");
                return "index";
            }

            AnalysisResult combined = dispatcherService.runAnalysis(analysisType, all);

            Map<String, Map<String, String>> perSheet = new LinkedHashMap<>();
            for (String s : sourceSheets) {
                List<PatientRecord> sub = all.stream()
                        .filter(r -> s.equals(r.getSheetKey()))
                        .toList();
                AnalysisResult subResult = dispatcherService.runAnalysis(analysisType, sub);
                perSheet.put(s, subResult.getStats());
            }
            combined.setPerSheetStats(perSheet);
            combined.setSourceSheets(sourceSheets);

            model.addAttribute("result", combined);
            model.addAttribute("sheetName", String.join(", ", sourceSheets));
            model.addAttribute("analysisType", analysisType);
            model.addAttribute("sheetsCsv", String.join(",", sheets));
            return "analysis_result";
        } catch (Exception e) {
            log.error("Ошибка мультианализа", e);
            model.addAttribute("error", "Ошибка анализа: " + e.getMessage());
            return "index";
        }
    }

    /**
     * Экспорт в Excel. Работает и для одиночного, и для мультивыбора:
     * sheetsCsv — список "fileName::sheetName" через запятую.
     */
    @PostMapping("/download")
    public ResponseEntity<byte[]> download(@RequestParam String sheetsCsv,
                                           @RequestParam int analysisType) {
        try {
            List<PatientRecord> all = new ArrayList<>();
            List<String> sourceSheets = new ArrayList<>();
            for (String s : sheetsCsv.split(",")) {
                String[] parts = s.split("::", 2);
                if (parts.length != 2) continue;
                Path filePath = Paths.get(UploadController.UPLOAD_DIR).resolve(parts[0]);
                List<List<String>> rows = excelReaderService.readSheetFully(filePath, parts[1]);
                all.addAll(recordParserService.parse(rows, s));
                sourceSheets.add(s);
            }
            AnalysisResult result = dispatcherService.runAnalysis(analysisType, all);
            result.setSourceSheets(sourceSheets);

            byte[] bytes = excelExportService.export(result, "combined");
            String outName = "analysis_" + analysisType + "_" + System.currentTimeMillis() + ".xlsx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + outName + "\"")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bytes);
        } catch (Exception e) {
            log.error("Ошибка экспорта", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}