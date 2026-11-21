package com.dental.analyzer.service;

import com.dental.analyzer.model.PatientRecord;
import com.dental.analyzer.model.dto.AnalysisResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisDispatcherService {

    private final ServiceClassifierService classifier;

    public AnalysisResult runAnalysis(int type, List<PatientRecord> records) {
        switch (type) {
            case 1:  return analyzeUetPerPatient(records);
            case 2:  return analyzeSurgicalTherapeutic(records);
            case 3:  return analyzeVisits(records);
            case 4:  return analyzeCariesK02(records);
            case 5:  return analyzeComplicatedCariesK04(records);
            case 6:  return analyzeK04Visits(records);
            case 7:  return analyzeK04SurgicalAvg(records);
            case 8:  return analyzeWithPriorK04(records, "K10.2", "П.8. K10.2 (хир.) + связь с K04");
            case 9:  return analyzeWithPriorK04(records, "K10.3", "П.9. K10.3 (хир.) + связь с K04");
            case 10: return analyzeK04_0_3(records);
            case 11: return analyzeSurgicalOtherDiag(records);
            case 12: return analyzeCompletedTreatment(records);
            default:
                return AnalysisResult.builder()
                        .title("В разработке")
                        .summary("Этот пункт ТЗ еще не реализован.")
                        .build();
        }
    }

    // ============================================================
    // П.1. УЕТ на пациента
    // ============================================================
    /**
     * ТЗ п.1. УЕТ на одного пациента:
     *  a) за один приём (группировка по patientId + visitDate);
     *  b) за год (сумма всех УЕТ пациента);
     *  c) по диагнозу (группировка по diagnosis);
     *  d) по виду лечения (хир./тер., см. ServiceClassifierService + контекст анестезий).
     *
     * Ход:
     *  1. Группируем записи по patientKey (sheetKey::patientId) — чтобы
     *     пациенты из разных листов не смешивались.
     *  2. (a) — sub-группировка по visitDate → среднее по посещениям.
     *  3. (b) — сумма uet по всем записям пациента.
     *  4. (c) — группировка по diagnosis → топ-диагноз по сумме УЕТ.
     *  5. (d) — для анестезии наследуем вид лечения «соседа» в рамках
     *     (visitDate, toothNumber); если хирурга рядом нет — терапия.
     */
    private AnalysisResult analyzeUetPerPatient(List<PatientRecord> records) {
        Map<String, List<PatientRecord>> byPatient = records.stream()
                .filter(r -> r.getPatientId() != null && !r.getPatientId().isBlank())
                .collect(Collectors.groupingBy(this::patientKey));

        List<List<String>> table = new ArrayList<>();
        for (var e : new TreeMap<>(byPatient).entrySet()) {
            String key = e.getKey();
            String[] parts = key.split("::", 2);
            String sheetKey = parts.length > 0 ? parts[0] : "";
            String pid = parts.length > 1 ? parts[1] : key;

            List<PatientRecord> recs = e.getValue();

            Map<LocalDate, Double> perVisit = recs.stream()
                    .filter(r -> r.getVisitDate() != null)
                    .collect(Collectors.groupingBy(PatientRecord::getVisitDate,
                            Collectors.summingDouble(this::uet)));
            double avgPerVisit = perVisit.values().stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0);

            double yearUet = recs.stream().mapToDouble(this::uet).sum();

            String topDiag = recs.stream()
                    .filter(r -> r.getDiagnosis() != null)
                    .collect(Collectors.groupingBy(PatientRecord::getDiagnosis,
                            Collectors.summingDouble(this::uet)))
                    .entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("-");

            double[] split = splitSurgicalTherapeutic(recs);

            table.add(List.of(
                    sheetKey,
                    pid,
                    String.format("%.2f", avgPerVisit),
                    String.format("%.2f", yearUet),
                    topDiag,
                    String.format("%.2f", split[0]),
                    String.format("%.2f", split[1])));
        }

        return AnalysisResult.builder()
                .title("П.1. УЕТ на пациента")
                .summary("Средние УЕТ за приём, УЕТ за год, топ-диагноз, разбивка хир./тер.")
                .tableHeaders(List.of("Лист", "Пациент", "Ср. УЕТ/приём", "УЕТ за год",
                        "Топ-диагноз", "УЕТ хир.", "УЕТ тер."))
                .tableData(table)
                .build();
    }

    // ============================================================
    // П.2. Хирургия / терапия
    // ============================================================
    /**
     * ТЗ п.2:
     *  a) количество хир./тер. манипуляций за год с указанием услуги (D);
     *  b) УЕТ по хир./тер. помесячно;
     *  c) количество хир./тер. по населённому пункту (M) — «некорректный» по ТЗ;
     *  d) УЕТ по хир./тер. по населённому пункту — тоже «некорректный».
     *
     * Ход: классифицируем с учётом анестезий; группируем по месяцу и locality.
     */
    private AnalysisResult analyzeSurgicalTherapeutic(List<PatientRecord> records) {
        Map<String, Long> countByService = records.stream()
                .filter(r -> r.getServiceCode() != null)
                .collect(Collectors.groupingBy(PatientRecord::getServiceCode, Collectors.counting()));

        Map<String, double[]> monthSplit = new TreeMap<>();
        Map<String, double[]> localitySplit = new TreeMap<>();

        Map<String, List<PatientRecord>> byVisitTooth = records.stream()
                .filter(r -> r.getVisitDate() != null && r.getToothNumber() != null)
                .collect(Collectors.groupingBy(r -> patientKey(r) + "_"
                        + r.getVisitDate() + "_" + r.getToothNumber()));

        for (var g : byVisitTooth.entrySet()) {
            List<PatientRecord> list = g.getValue();
            boolean hasSurg = list.stream().anyMatch(r -> classifier.isSurgical(r.getServiceCode()));
            for (PatientRecord r : list) {
                boolean surg = classifier.isSurgical(r.getServiceCode())
                        || (classifier.isAnesthesia(r.getServiceCode()) && hasSurg);
                String month = (r.getMonth() == null || r.getMonth().isBlank()) ? "00" : r.getMonth();
                String loc = (r.getLocality() == null || r.getLocality().isBlank()) ? "—" : r.getLocality();
                monthSplit.computeIfAbsent(month, k -> new double[2])[surg ? 0 : 1] += uet(r);
                localitySplit.computeIfAbsent(loc, k -> new double[2])[surg ? 0 : 1] += uet(r);
            }
        }

        List<List<String>> table = new ArrayList<>();
        for (var e : monthSplit.entrySet()) {
            table.add(List.of(e.getKey(),
                    String.format("%.2f", e.getValue()[0]),
                    String.format("%.2f", e.getValue()[1])));
        }

        long servicesTotal = countByService.values().stream().mapToLong(Long::longValue).sum();
        Map<String, String> stats = new LinkedHashMap<>();
        stats.put("Всего услуг", String.valueOf(servicesTotal));
        stats.put("Уникальных услуг", String.valueOf(countByService.size()));

        return AnalysisResult.builder()
                .title("П.2. Хирургия/терапия")
                .summary("УЕТ по видам лечения помесячно; счётчики по услугам (колонка D)")
                .stats(stats)
                .tableHeaders(List.of("Месяц", "УЕТ хир.", "УЕТ тер."))
                .tableData(table)
                .build();
    }

    // ============================================================
    // П.3. Обращения
    // ============================================================
    /**
     * ТЗ п.3:
     *  a-i)  обратившиеся = уникальные patientKey (sheetKey::patientId);
     *  a-ii) обращения = уникальные (patientKey, visitDate);
     *  b)    то же помесячно;
     *  c)    удельный вес диагноза = пациенты с диагнозом / все обратившиеся;
     *  d)    то же помесячно;
     *  e)    на одну дату у пациента может быть несколько строк с разными
     *        диагнозами — считаем обращение один раз по (patientKey, visitDate).
     */
    private AnalysisResult analyzeVisits(List<PatientRecord> records) {
        Set<String> patients = records.stream()
                .filter(r -> r.getPatientId() != null && !r.getPatientId().isBlank())
                .map(this::patientKey)
                .collect(Collectors.toSet());

        Set<String> visits = records.stream()
                .filter(r -> r.getPatientId() != null && r.getVisitDate() != null)
                .map(this::visitKey)
                .collect(Collectors.toSet());

        Map<String, Set<String>> patientsByDiag = new HashMap<>();
        for (PatientRecord r : records) {
            if (r.getDiagnosis() == null || r.getDiagnosis().isBlank()) continue;
            patientsByDiag.computeIfAbsent(r.getDiagnosis(), k -> new HashSet<>()).add(patientKey(r));
        }
        List<List<String>> diagTable = new ArrayList<>();
        for (var e : new TreeMap<>(patientsByDiag).entrySet()) {
            double share = patients.isEmpty() ? 0 : (double) e.getValue().size() / patients.size() * 100;
            diagTable.add(List.of(e.getKey(),
                    String.valueOf(e.getValue().size()),
                    String.format("%.2f %%", share)));
        }

        Map<String, Set<String>> monthPatients = new TreeMap<>();
        Map<String, Set<String>> monthVisits = new TreeMap<>();
        for (PatientRecord r : records) {
            String m = (r.getMonth() == null || r.getMonth().isBlank()) ? "00" : r.getMonth();
            if (r.getPatientId() != null) {
                monthPatients.computeIfAbsent(m, k -> new HashSet<>()).add(patientKey(r));
            }
            if (r.getPatientId() != null && r.getVisitDate() != null) {
                monthVisits.computeIfAbsent(m, k -> new HashSet<>()).add(visitKey(r));
            }
        }
        List<List<String>> monthTable = new ArrayList<>();
        for (String m : monthPatients.keySet()) {
            monthTable.add(List.of(m,
                    String.valueOf(monthPatients.getOrDefault(m, Set.of()).size()),
                    String.valueOf(monthVisits.getOrDefault(m, Set.of()).size())));
        }

        Map<String, String> stats = new LinkedHashMap<>();
        stats.put("Обратившихся за год", String.valueOf(patients.size()));
        stats.put("Обращений за год", String.valueOf(visits.size()));

        return AnalysisResult.builder()
                .title("П.3. Обращения")
                .summary("Уникальные пациенты и обращения; удельный вес диагнозов за год")
                .stats(stats)
                .tableHeaders(List.of("Диагноз", "Пациентов", "Удельный вес"))
                .tableData(diagTable)
                .build();
    }

    // ============================================================
    // П.4. Удельный вес кариеса (K02)
    // ============================================================
    private AnalysisResult analyzeCariesK02(List<PatientRecord> records) {
        return analyzeDiagnosisShare(records, "K02", "П.4. Удельный вес кариеса (K02)");
    }

    // ============================================================
    // П.5. Удельный вес осложнённого кариеса (K04)
    // ============================================================
    private AnalysisResult analyzeComplicatedCariesK04(List<PatientRecord> records) {
        return analyzeDiagnosisShare(records, "K04", "П.5. Удельный вес осложн. кариеса (K04)");
    }

    /** Общий метод для п.4 и п.5. */
    private AnalysisResult analyzeDiagnosisShare(List<PatientRecord> records, String diagPrefix, String title) {
        Set<String> allVisits = records.stream()
                .filter(r -> r.getPatientId() != null && r.getVisitDate() != null)
                .map(this::visitKey).collect(Collectors.toSet());
        Set<String> targetVisits = records.stream()
                .filter(r -> r.getDiagnosis() != null && r.getDiagnosis().startsWith(diagPrefix))
                .filter(r -> r.getPatientId() != null && r.getVisitDate() != null)
                .map(this::visitKey).collect(Collectors.toSet());

        double yearShare = allVisits.isEmpty() ? 0 : (double) targetVisits.size() / allVisits.size() * 100;

        Map<String, String> stats = new LinkedHashMap<>();
        stats.put("Всего обращений за год", String.valueOf(allVisits.size()));
        stats.put("Обращений с " + diagPrefix, String.valueOf(targetVisits.size()));
        stats.put("Удельный вес за год", String.format("%.2f %%", yearShare));

        Map<String, Set<String>> monthAll = new TreeMap<>();
        Map<String, Set<String>> monthTarget = new TreeMap<>();
        for (PatientRecord r : records) {
            if (r.getPatientId() == null || r.getVisitDate() == null) continue;
            String m = (r.getMonth() == null || r.getMonth().isBlank()) ? "00" : r.getMonth();
            monthAll.computeIfAbsent(m, k -> new HashSet<>()).add(visitKey(r));
            if (r.getDiagnosis() != null && r.getDiagnosis().startsWith(diagPrefix)) {
                monthTarget.computeIfAbsent(m, k -> new HashSet<>()).add(visitKey(r));
            }
        }

        List<List<String>> table = new ArrayList<>();
        for (String m : monthAll.keySet()) {
            long total = monthAll.get(m).size();
            long target = monthTarget.getOrDefault(m, Set.of()).size();
            double share = total == 0 ? 0 : (double) target / total * 100;
            table.add(List.of(m, String.valueOf(total), String.valueOf(target), String.format("%.2f %%", share)));
        }

        return AnalysisResult.builder()
                .title(title)
                .summary("Расчёт удельного веса по обращению (Пациент + Дата)")
                .stats(stats)
                .tableHeaders(List.of("Месяц", "Всего обращений", "Целевых обращений", "Удельный вес"))
                .tableData(table)
                .build();
    }

    // ============================================================
    // П.6. Хир./тер. обращения по K04
    // ============================================================
    /**
     * ТЗ п.6. Кол-во хир./тер. ОБРАЩЕНИЙ (не манипуляций!) для K04.
     * На одно посещение может быть несколько манипуляций — считаем одно обращение.
     *
     * Ход:
     *  1. Фильтр: diagnosis.startsWith("K04").
     *  2. Группировка по (patientKey, visitDate) — уникальное обращение.
     *  3. Внутри обращения: есть хир. услуга → хир. обращение.
     *  4. Помесячно — по month.
     */
    private AnalysisResult analyzeK04Visits(List<PatientRecord> records) {
        Map<String, List<PatientRecord>> visits = records.stream()
                .filter(r -> r.getDiagnosis() != null && r.getDiagnosis().startsWith("K04"))
                .filter(r -> r.getPatientId() != null && r.getVisitDate() != null)
                .collect(Collectors.groupingBy(this::visitKey));

        Map<String, long[]> byMonth = new TreeMap<>();
        for (var v : visits.values()) {
            boolean surg = hasSurgicalInVisit(v);
            String m = (v.get(0).getMonth() == null || v.get(0).getMonth().isBlank()) ? "00" : v.get(0).getMonth();
            byMonth.computeIfAbsent(m, k -> new long[2])[surg ? 0 : 1]++;
        }

        List<List<String>> table = new ArrayList<>();
        long totalSurg = 0, totalTher = 0;
        for (var e : byMonth.entrySet()) {
            table.add(List.of(e.getKey(),
                    String.valueOf(e.getValue()[0]),
                    String.valueOf(e.getValue()[1])));
            totalSurg += e.getValue()[0];
            totalTher += e.getValue()[1];
        }

        Map<String, String> stats = new LinkedHashMap<>();
        stats.put("Хир. обращений по K04", String.valueOf(totalSurg));
        stats.put("Тер. обращений по K04", String.valueOf(totalTher));

        return AnalysisResult.builder()
                .title("П.6. Обращения по K04 (хир./тер.)")
                .summary("Одно посещение = одно обращение, даже если несколько манипуляций")
                .stats(stats)
                .tableHeaders(List.of("Месяц", "Хир. обращений", "Тер. обращений"))
                .tableData(table)
                .build();
    }

    // ============================================================
    // П.7. Средние посещения/УЕТ при K04 (хир.)
    // ============================================================
    /**
     * ТЗ п.7. Среднее кол-во посещений и УЕТ на хирургическое лечение K04.
     *
     * Ход:
     *  1. Фильтр: K04 + в записи есть хир. услуга.
     *  2. Группировка по patientKey.
     *  3. visits = число уникальных visitDate; uet = сумма uet.
     *  4. Среднее по всем пациентам.
     */
    private AnalysisResult analyzeK04SurgicalAvg(List<PatientRecord> records) {
        Map<String, List<PatientRecord>> byPatient = records.stream()
                .filter(r -> r.getDiagnosis() != null && r.getDiagnosis().startsWith("K04"))
                .filter(r -> classifier.isSurgical(r.getServiceCode()))
                .filter(r -> r.getPatientId() != null)
                .collect(Collectors.groupingBy(this::patientKey));

        double sumVisits = 0, sumUet = 0;
        int n = 0;
        List<List<String>> table = new ArrayList<>();
        for (var e : new TreeMap<>(byPatient).entrySet()) {
            String key = e.getKey();
            String[] parts = key.split("::", 2);
            String sheetKey = parts.length > 0 ? parts[0] : "";
            String pid = parts.length > 1 ? parts[1] : key;
            List<PatientRecord> recs = e.getValue();

            long visits = recs.stream()
                    .filter(r -> r.getVisitDate() != null)
                    .map(PatientRecord::getVisitDate).distinct().count();
            double uetSum = recs.stream().mapToDouble(this::uet).sum();
            sumVisits += visits;
            sumUet += uetSum;
            n++;
            table.add(List.of(sheetKey, pid, String.valueOf(visits), String.format("%.2f", uetSum)));
        }

        Map<String, String> stats = new LinkedHashMap<>();
        stats.put("Пациентов (хир. K04)", String.valueOf(n));
        stats.put("Ср. посещений на пациента", n == 0 ? "0" : String.format("%.2f", sumVisits / n));
        stats.put("Ср. УЕТ на пациента", n == 0 ? "0" : String.format("%.2f", sumUet / n));

        return AnalysisResult.builder()
                .title("П.7. Средние посещения/УЕТ при хир. K04")
                .stats(stats)
                .tableHeaders(List.of("Лист", "Пациент", "Посещений", "УЕТ"))
                .tableData(table)
                .build();
    }

    // ============================================================
    // П.8 и П.9. K10.2 / K10.3 + связь с K04
    // ============================================================
    /**
     * ТЗ п.8/п.9. K10.2 (K10.3) хир.: среднее кол-во посещений и УЕТ,
     *   a) отдельно — если пациент был в течение 1–10 дней ранее лечен
     *      хирургически по K04.
     *
     * Ход:
     *  1. Для каждого пациента (patientKey) собираем даты хир. K04-лечения.
     *  2. Для K10.x-хир. посещений проверяем: есть ли K04-хир. посещение
     *     в интервале [visitDate-10, visitDate-1].
     *  3. Средние по двум группам (связанные / несвязанные).
     */
    private AnalysisResult analyzeWithPriorK04(List<PatientRecord> records, String diagPrefix, String title) {
        Map<String, Set<LocalDate>> k04SurgDates = records.stream()
                .filter(r -> r.getDiagnosis() != null && r.getDiagnosis().startsWith("K04"))
                .filter(r -> classifier.isSurgical(r.getServiceCode()))
                .filter(r -> r.getPatientId() != null && r.getVisitDate() != null)
                .collect(Collectors.groupingBy(this::patientKey,
                        Collectors.mapping(PatientRecord::getVisitDate, Collectors.toSet())));

        Map<String, List<PatientRecord>> byVisit = records.stream()
                .filter(r -> r.getDiagnosis() != null && r.getDiagnosis().startsWith(diagPrefix))
                .filter(r -> classifier.isSurgical(r.getServiceCode()))
                .filter(r -> r.getPatientId() != null && r.getVisitDate() != null)
                .collect(Collectors.groupingBy(this::visitKey));

        double sumU1 = 0, sumU2 = 0;
        int n1 = 0, n2 = 0;

        for (var e : byVisit.entrySet()) {
            List<PatientRecord> v = e.getValue();
            PatientRecord first = v.get(0);
            LocalDate d = first.getVisitDate();
            Set<LocalDate> prior = k04SurgDates.getOrDefault(patientKey(first), Set.of());
            boolean linked = prior.stream().anyMatch(pd -> {
                long days = ChronoUnit.DAYS.between(pd, d);
                return days >= 1 && days <= 10;
            });
            double uetSum = v.stream().mapToDouble(this::uet).sum();
            if (linked) { sumU1 += uetSum; n1++; } else { sumU2 += uetSum; n2++; }
        }

        Map<String, String> stats = new LinkedHashMap<>();
        stats.put("Связанных с K04 (1–10 дн.): обращений", String.valueOf(n1));
        stats.put("Ср. УЕТ (связанные)", n1 == 0 ? "0" : String.format("%.2f", sumU1 / n1));
        stats.put("Несвязанных: обращений", String.valueOf(n2));
        stats.put("Ср. УЕТ (несвязанные)", n2 == 0 ? "0" : String.format("%.2f", sumU2 / n2));

        return AnalysisResult.builder()
                .title(title)
                .summary("Хир. обращения по " + diagPrefix + "; связь с K04 — в течение 1–10 дней ранее")
                .stats(stats)
                .build();
    }

    // ============================================================
    // П.10. K04.0–K04.3 (хир.) + зубы 18/28/38/48
    // ============================================================
    /**
     * ТЗ п.10. Средние посещения/УЕТ на хир. лечение K04.0–K04.3,
     *   a) отдельно — если лечение касалось зубов 18, 28, 38, 48.
     *
     * Ход:
     *  1. Фильтр: диагноз K04.0/K04.1/K04.2/K04.3 + хир. услуга.
     *  2. Группировка по (patientKey, toothNumber) — один зуб.
     *  3. Для каждой группы: visits = уникальные visitDate, uet = сумма.
     *  4. Отдельно считаем группу зубов 18/28/38/48.
     */
    private AnalysisResult analyzeK04_0_3(List<PatientRecord> records) {
        Set<String> targetDiags = Set.of("K04.0", "K04.1", "K04.2", "K04.3");
        Set<Integer> targetTeeth = Set.of(18, 28, 38, 48);

        List<PatientRecord> filtered = records.stream()
                .filter(r -> r.getDiagnosis() != null && targetDiags.contains(r.getDiagnosis()))
                .filter(r -> classifier.isSurgical(r.getServiceCode()))
                .filter(r -> r.getPatientId() != null && r.getToothNumber() != null)
                .collect(Collectors.toList());

        Map<String, List<PatientRecord>> byPatientTooth = filtered.stream()
                .collect(Collectors.groupingBy(r -> patientKey(r) + "_" + r.getToothNumber()));

        double sumV1 = 0, sumU1 = 0, sumV2 = 0, sumU2 = 0;
        int n1 = 0, n2 = 0;
        List<List<String>> table = new ArrayList<>();

        for (var e : new TreeMap<>(byPatientTooth).entrySet()) {
            List<PatientRecord> recs = e.getValue();
            long visits = recs.stream()
                    .filter(r -> r.getVisitDate() != null)
                    .map(PatientRecord::getVisitDate).distinct().count();
            double uetSum = recs.stream().mapToDouble(this::uet).sum();
            boolean isTargetTooth = targetTeeth.contains(recs.get(0).getToothNumber());

            if (isTargetTooth) { sumV1 += visits; sumU1 += uetSum; n1++; }
            else { sumV2 += visits; sumU2 += uetSum; n2++; }

            table.add(List.of(
                    recs.get(0).getSheetKey() == null ? "" : recs.get(0).getSheetKey(),
                    recs.get(0).getPatientId(),
                    String.valueOf(recs.get(0).getToothNumber()),
                    String.valueOf(visits),
                    String.format("%.2f", uetSum),
                    isTargetTooth ? "18/28/38/48" : "прочие"));
        }

        Map<String, String> stats = new LinkedHashMap<>();
        stats.put("Зубы 18/28/38/48: случаев", String.valueOf(n1));
        stats.put("Ср. посещений (18/28/38/48)", n1 == 0 ? "0" : String.format("%.2f", sumV1 / n1));
        stats.put("Ср. УЕТ (18/28/38/48)", n1 == 0 ? "0" : String.format("%.2f", sumU1 / n1));
        stats.put("Прочие зубы: случаев", String.valueOf(n2));
        stats.put("Ср. посещений (прочие)", n2 == 0 ? "0" : String.format("%.2f", sumV2 / n2));
        stats.put("Ср. УЕТ (прочие)", n2 == 0 ? "0" : String.format("%.2f", sumU2 / n2));

        return AnalysisResult.builder()
                .title("П.10. K04.0–K04.3 (хир.) + зубы 18/28/38/48")
                .stats(stats)
                .tableHeaders(List.of("Лист", "Пациент", "Зуб", "Посещений", "УЕТ", "Категория зуба"))
                .tableData(table)
                .build();
    }

    // ============================================================
    // П.11. Хир. обращения по диагнозам, кроме K04.5–K04.7
    // ============================================================
    /**
     * ТЗ п.11. Кол-во хир. ОБРАЩЕНИЙ по разным диагнозам (кроме K04.5/6/7)
     * с указанием диагноза — за год и помесячно.
     *
     * Ход:
     *  1. Фильтр: хир. услуга, диагноз НЕ K04.5/K04.6/K04.7.
     *  2. Группировка по (diagnosis, month) → счётчик уникальных обращений.
     */
    private AnalysisResult analyzeSurgicalOtherDiag(List<PatientRecord> records) {
        Set<String> excluded = Set.of("K04.5", "K04.6", "K04.7");

        Map<String, Set<String>> byDiagMonth = new TreeMap<>();
        Map<String, Set<String>> byDiag = new TreeMap<>();

        for (PatientRecord r : records) {
            if (r.getDiagnosis() == null) continue;
            if (excluded.contains(r.getDiagnosis())) continue;
            if (!classifier.isSurgical(r.getServiceCode())) continue;
            if (r.getPatientId() == null || r.getVisitDate() == null) continue;

            String m = (r.getMonth() == null || r.getMonth().isBlank()) ? "00" : r.getMonth();
            String k = r.getDiagnosis() + "|" + m;
            byDiagMonth.computeIfAbsent(k, x -> new HashSet<>()).add(visitKey(r));
            byDiag.computeIfAbsent(r.getDiagnosis(), x -> new HashSet<>()).add(visitKey(r));
        }

        List<List<String>> table = new ArrayList<>();
        for (var e : byDiagMonth.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            table.add(List.of(parts[0], parts[1], String.valueOf(e.getValue().size())));
        }

        Map<String, String> stats = new LinkedHashMap<>();
        stats.put("Уникальных диагнозов", String.valueOf(byDiag.size()));
        long total = byDiag.values().stream().mapToLong(Set::size).sum();
        stats.put("Всего хир. обращений", String.valueOf(total));

        return AnalysisResult.builder()
                .title("П.11. Хир. обращения (кроме K04.5–K04.7)")
                .stats(stats)
                .tableHeaders(List.of("Диагноз", "Месяц", "Обращений"))
                .tableData(table)
                .build();
    }

    // ============================================================
    // П.12. Законченное лечение (A16.07.002)
    // ============================================================
    /**
     * ТЗ п.12. Удельный вес законченного лечения зуба
     * (код услуги начинается с A16.07.002) относительно всех обращений
     * за год и помесячно;
     *   a) среднее кол-во УЕТ на вылеченный зуб;
     *   b) среднее кол-во обращений на вылеченный зуб.
     *
     * Ход:
     *  1. Обращение = (patientKey, visitDate).
     *  2. Целевые обращения = где есть услуга A16.07.002*.
     *  3. Удельный вес = целевые / все.
     *  4. УЕТ на зуб: группировка по (patientKey, toothNumber) с A16.07.002*.
     *  5. Обращений на зуб: уникальные visitDate в такой группе.
     */
    private AnalysisResult analyzeCompletedTreatment(List<PatientRecord> records) {
        Set<String> allVisits = records.stream()
                .filter(r -> r.getPatientId() != null && r.getVisitDate() != null)
                .map(this::visitKey).collect(Collectors.toSet());

        Set<String> targetVisits = records.stream()
                .filter(r -> r.getServiceCode() != null && r.getServiceCode().startsWith("A16.07.002"))
                .filter(r -> r.getPatientId() != null && r.getVisitDate() != null)
                .map(this::visitKey).collect(Collectors.toSet());

        double yearShare = allVisits.isEmpty() ? 0 : (double) targetVisits.size() / allVisits.size() * 100;

        Map<String, List<PatientRecord>> byTooth = records.stream()
                .filter(r -> r.getServiceCode() != null && r.getServiceCode().startsWith("A16.07.002"))
                .filter(r -> r.getPatientId() != null && r.getToothNumber() != null)
                .collect(Collectors.groupingBy(r -> patientKey(r) + "_" + r.getToothNumber()));

        double sumUet = 0, sumVisits = 0;
        int n = 0;
        for (var e : byTooth.entrySet()) {
            List<PatientRecord> recs = e.getValue();
            double uetSum = recs.stream().mapToDouble(this::uet).sum();
            long visits = recs.stream()
                    .filter(r -> r.getVisitDate() != null)
                    .map(PatientRecord::getVisitDate).distinct().count();
            sumUet += uetSum;
            sumVisits += visits;
            n++;
        }

        Map<String, String> stats = new LinkedHashMap<>();
        stats.put("Всего обращений за год", String.valueOf(allVisits.size()));
        stats.put("С законченным лечением", String.valueOf(targetVisits.size()));
        stats.put("Удельный вес за год", String.format("%.2f %%", yearShare));
        stats.put("Вылечено зубов", String.valueOf(n));
        stats.put("Ср. УЕТ на зуб", n == 0 ? "0" : String.format("%.2f", sumUet / n));
        stats.put("Ср. обращений на зуб", n == 0 ? "0" : String.format("%.2f", sumVisits / n));

        Map<String, Set<String>> monthAll = new TreeMap<>();
        Map<String, Set<String>> monthTarget = new TreeMap<>();
        for (PatientRecord r : records) {
            if (r.getPatientId() == null || r.getVisitDate() == null) continue;
            String m = (r.getMonth() == null || r.getMonth().isBlank()) ? "00" : r.getMonth();
            monthAll.computeIfAbsent(m, k -> new HashSet<>()).add(visitKey(r));
            if (r.getServiceCode() != null && r.getServiceCode().startsWith("A16.07.002")) {
                monthTarget.computeIfAbsent(m, k -> new HashSet<>()).add(visitKey(r));
            }
        }

        List<List<String>> table = new ArrayList<>();
        for (String m : monthAll.keySet()) {
            long total = monthAll.get(m).size();
            long target = monthTarget.getOrDefault(m, Set.of()).size();
            double share = total == 0 ? 0 : (double) target / total * 100;
            table.add(List.of(m, String.valueOf(total), String.valueOf(target), String.format("%.2f %%", share)));
        }

        return AnalysisResult.builder()
                .title("П.12. Законченное лечение (A16.07.002)")
                .stats(stats)
                .tableHeaders(List.of("Месяц", "Всего обращений", "С A16.07.002", "Удельный вес"))
                .tableData(table)
                .build();
    }

    // ============================================================
    // Вспомогательные методы
    // ============================================================

    private double uet(PatientRecord r) {
        return r.getUet() == null ? 0 : r.getUet();
    }

    /** Ключ пациента: sheetKey + "::" + patientId. */
    private String patientKey(PatientRecord r) {
        String sk = r.getSheetKey() == null ? "" : r.getSheetKey();
        return sk + "::" + (r.getPatientId() == null ? "null" : r.getPatientId());
    }

    /** Ключ обращения: patientKey + "_" + visitDate. */
    private String visitKey(PatientRecord r) {
        return patientKey(r) + "_" + (r.getVisitDate() != null ? r.getVisitDate().toString() : "null");
    }

    private boolean hasSurgicalInVisit(List<PatientRecord> visit) {
        return visit.stream().anyMatch(r -> classifier.isSurgical(r.getServiceCode()));
    }

    /**
     * Контекстная классификация: анестезия относится к тому виду лечения,
     * к которому относится «соседняя» услуга в рамках (visitDate, toothNumber).
     */
    private double[] splitSurgicalTherapeutic(List<PatientRecord> recs) {
        double surgical = 0, therapeutic = 0;
        Map<String, List<PatientRecord>> byVisitTooth = recs.stream()
                .filter(r -> r.getVisitDate() != null && r.getToothNumber() != null)
                .collect(Collectors.groupingBy(r -> r.getVisitDate() + "_" + r.getToothNumber()));

        for (PatientRecord r : recs) {
            if (r.getVisitDate() == null || r.getToothNumber() == null) {
                if (classifier.isSurgical(r.getServiceCode())) surgical += uet(r);
                else therapeutic += uet(r);
            }
        }

        for (var group : byVisitTooth.entrySet()) {
            List<PatientRecord> list = group.getValue();
            boolean hasSurgical = list.stream().anyMatch(r -> classifier.isSurgical(r.getServiceCode()));
            for (PatientRecord r : list) {
                double u = uet(r);
                if (classifier.isSurgical(r.getServiceCode())) surgical += u;
                else if (classifier.isAnesthesia(r.getServiceCode())) {
                    if (hasSurgical) surgical += u; else therapeutic += u;
                } else therapeutic += u;
            }
        }
        return new double[]{surgical, therapeutic};
    }
}