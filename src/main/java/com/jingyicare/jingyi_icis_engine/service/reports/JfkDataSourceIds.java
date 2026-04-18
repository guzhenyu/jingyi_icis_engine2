package com.jingyicare.jingyi_icis_engine.service.reports;

import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

public final class JfkDataSourceIds {
    private JfkDataSourceIds() {
    }

    public static final String PATIENT_MONITORING_RECORDS = "patient_monitoring_records";
    public static final String PATIENT_INFO_EXTENDED = "patient_info_extended";
    public static final String PATIENT_BGA_RECORDS = "patient_bga_records";
    public static final String PATIENT_BALANCE_RECORDS = "patient_balance_records";
    public static final String MEDEXE_RECORDS = "medexe_records";

    public static boolean isCompactTableScoped(String metaId) {
        return PATIENT_MONITORING_RECORDS.equals(metaId)
            || PATIENT_BGA_RECORDS.equals(metaId)
            || PATIENT_BALANCE_RECORDS.equals(metaId)
            || MEDEXE_RECORDS.equals(metaId);
    }

    public static String compactTableScoped(String metaId, String tableId) {
        return "compact-" + metaId + "-" + (StrUtils.isBlank(tableId) ? "unknown" : tableId);
    }
}
