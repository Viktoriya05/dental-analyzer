package com.dental.analyzer.controller;

import com.dental.analyzer.model.dto.SheetInfo;
import com.dental.analyzer.service.ExcelReaderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Controller
@RequiredArgsConstructor
public class UploadController {

    private final ExcelReaderService excelReaderService;
    public static final String UPLOAD_DIR = System.getProperty("java.io.tmpdir") + File.separator + "dental_uploads";

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping("/upload")
    public String upload(@RequestParam(value = "files", required = false) List<MultipartFile> files, Model model) {
        // --- Диагностика ---
        log.info("=== upload: files={} ===", files == null ? "null" : files.size());
        if (files != null) {
            for (MultipartFile f : files) {
                log.info("  file: name='{}', size={}, empty={}",
                        f.getOriginalFilename(), f.getSize(), f.isEmpty());
            }
        }

        // --- Валидация: хотя бы один непустой ---
        boolean hasAny = files != null && files.stream().anyMatch(f -> f != null && !f.isEmpty());
        if (!hasAny) {
            model.addAttribute("error", "Выберите хотя бы один файл");
            return "index";
        }

        try {
            Path uploadPath = Paths.get(UPLOAD_DIR);

            // Чистим папку перед новой загрузкой
            if (Files.exists(uploadPath)) {
                try (var stream = Files.list(uploadPath)) {
                    stream.forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                }
            } else {
                Files.createDirectories(uploadPath);
            }

            List<String> uploadedNames = new ArrayList<>();
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) continue;
                String original = file.getOriginalFilename();
                if (original == null || !original.toLowerCase().endsWith(".xlsx")) {
                    log.warn("Пропущен файл (не .xlsx): {}", original);
                    continue;
                }
                Path filePath = uploadPath.resolve(original);
                try {
                    Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
                    uploadedNames.add(original);
                    log.info("Сохранён файл: {} ({} байт)", original, file.getSize());
                } catch (IOException ex) {
                    log.warn("Не удалось сохранить файл {}: {}", original, ex.getMessage());
                }
            }

            if (uploadedNames.isEmpty()) {
                model.addAttribute("error", "Не найдено ни одного .xlsx файла");
                return "index";
            }

            List<SheetInfo> sheets = excelReaderService.getFilesAndSheets(uploadPath, uploadedNames);
            model.addAttribute("sheets", sheets);
            return "select_sheets";
        } catch (Exception e) {
            log.error("Ошибка загрузки", e);
            model.addAttribute("error", "Ошибка: " + e.getMessage());
            return "index";
        }
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String handleMaxSize(MaxUploadSizeExceededException e, Model model) {
        model.addAttribute("error", "Слишком большой файл. Превышен лимит.");
        return "index";
    }
}