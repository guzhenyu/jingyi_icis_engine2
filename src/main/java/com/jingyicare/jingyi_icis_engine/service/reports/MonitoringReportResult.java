package com.jingyicare.jingyi_icis_engine.service.reports;

import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MonitoringReportResult {
    private ReturnCode returnCode;
    private int rotationDegree;
}
