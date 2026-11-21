package com.dental.analyzer.service;

import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Классификация услуг на хирургические / терапевтические.
 *
 * ТЗ п.1d: расшифровка колонки D на листе «Номенклатура»; хирургические
 * манипуляции выделены жёлтым, «спорные» (анестезии) — красным.
 *
 * Так как в загруженных файлах листа «Номенклатура» может не быть,
 * реализована классификация по префиксам кодов услуг.
 * Анестезии (B01.003.004.*) — «спорные»: относятся к хирургии,
 * если в том же посещении (пациент+дата+зуб) есть хирургическая услуга,
 * иначе — к терапии.
 */
@Service
public class ServiceClassifierService {

    /** Явно хирургические префиксы услуг (уточнять по «Номенклатуре»). */
    private static final Set<String> SURGICAL_PREFIXES = Set.of(
            "A16.07.008",  // хирургические вмешательства на корне
            "A16.07.009",
            "A16.07.010",
            "A16.07.030",
            "A16.07.091",
            "A16.07.092",
            "A16.07.093"
    );

    /** Префикс анестезий — «спорные» услуги. */
    private static final String ANESTHESIA_PREFIX = "B01.003.004";

    public boolean isSurgical(String serviceCode) {
        if (serviceCode == null) return false;
        for (String p : SURGICAL_PREFIXES) {
            if (serviceCode.startsWith(p)) return true;
        }
        return false;
    }

    public boolean isAnesthesia(String serviceCode) {
        return serviceCode != null && serviceCode.startsWith(ANESTHESIA_PREFIX);
    }

    public boolean isTherapeutic(String serviceCode) {
        return !isSurgical(serviceCode) && !isAnesthesia(serviceCode);
    }
}