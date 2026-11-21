package com.dental.analyzer.model.dto;

import lombok.Data;

@Data
public class SheetRequest {
    private String fileName;
    private String sheetName;
    private int analysisType;
}