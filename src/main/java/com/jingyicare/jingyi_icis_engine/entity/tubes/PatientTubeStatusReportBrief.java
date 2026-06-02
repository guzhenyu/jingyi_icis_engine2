package com.jingyicare.jingyi_icis_engine.entity.tubes;

import java.time.LocalDateTime;

public interface PatientTubeStatusReportBrief {
    Long getTubeRecordId();
    String getTubeName();
    String getStatusName();
    LocalDateTime getRecordedAt();
    String getValue();
    Long getStatusRecordId();
}
