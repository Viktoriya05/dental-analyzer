package com.dental.analyzer.model;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDate;

@Data
@Builder
public class PatientRecord {
    private String patientId;
    private String gender;
    private Integer age;
    private Integer toothNumber;
    private LocalDate visitDate;
    private String diagnosis;
    private Double uet;
    private String locality;
    private String serviceCode;
    private String month;

    /**
     * Идентификатор листа-источника: fileName + "::" + sheetName.
     * Нужен, чтобы при объединении нескольких листов не смешивать
     * пациентов с одинаковыми patientId из разных листов.
     */
    private String sheetKey;
}