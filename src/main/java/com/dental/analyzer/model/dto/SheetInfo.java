package com.dental.analyzer.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SheetInfo {
    private String fileName;
    private String sheetName;
}