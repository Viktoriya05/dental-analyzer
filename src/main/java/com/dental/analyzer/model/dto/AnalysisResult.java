package com.dental.analyzer.model.dto;

import lombok.Builder;
import lombok.Data;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class AnalysisResult {
    private String title;
    private String summary;

    @Builder.Default
    private Map<String, String> stats = new LinkedHashMap<>();

    @Builder.Default
    private List<List<String>> tableData = new ArrayList<>();

    @Builder.Default
    private List<String> tableHeaders = new ArrayList<>();

    /**
     * Разбивка по листам: Map<sheetKey, Map<показатель, значение>>.
     * Заполняется только при мультивыборе листов.
     */
    @Builder.Default
    private Map<String, Map<String, String>> perSheetStats = new LinkedHashMap<>();

    /** Список листов-источников (fileName::sheetName). */
    @Builder.Default
    private List<String> sourceSheets = new ArrayList<>();
}