package com.jingyicare.jingyi_icis_engine.service.reports;

import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

public final class JfkDataSourceIds {
    private JfkDataSourceIds() {
    }

    public static final String PATIENT_MONITORING_RECORDS = "patient_monitoring_records";

    public static String compactTableScoped(String metaId, String tableId) {
        return "compact-" + metaId + "-" + (StrUtils.isBlank(tableId) ? "unknown" : tableId);
    }
}
