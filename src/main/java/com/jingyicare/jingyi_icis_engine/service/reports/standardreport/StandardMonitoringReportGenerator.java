package com.jingyicare.jingyi_icis_engine.service.reports.standardreport;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetPatientMonitoringGroupsResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetPatientMonitoringRecordsResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.PatientMonitoringService;
import com.jingyicare.jingyi_icis_engine.service.reports.MonitoringReportGenerator;
import com.jingyicare.jingyi_icis_engine.service.reports.MonitoringReportRequest;
import com.jingyicare.jingyi_icis_engine.service.reports.MonitoringReportResult;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ReturnCodeUtils;

@Service
public class StandardMonitoringReportGenerator implements MonitoringReportGenerator {
    public StandardMonitoringReportGenerator(
        @Autowired ConfigProtoService protoService,
        @Autowired MonitoringService monitoringService,
        @Autowired PatientMonitoringService patientMonitoringService,
        @Autowired MonitoringReportService monitoringReportService
    ) {
        this.statusCodeMsgs = protoService.getConfig().getText().getStatusCodeMsgList();
        this.monitoringService = monitoringService;
        this.patientMonitoringService = patientMonitoringService;
        this.monitoringReportService = monitoringReportService;
    }

    @Override
    public String version() {
        return "standard";
    }

    @Override
    public MonitoringReportResult generate(MonitoringReportRequest request) {
        GetPatientMonitoringGroupsResp groupsResp =
            monitoringService.getPatientMonitoringGroups(request.getRawRequestJson());
        if (groupsResp.getRt().getCode() != StatusCode.OK.ordinal()) {
            return new MonitoringReportResult(groupsResp.getRt(), 0);
        }

        GetPatientMonitoringRecordsResp recordsResp =
            patientMonitoringService.getPatientMonitoringRecords(request.getRawRequestJson());
        if (recordsResp.getRt().getCode() != StatusCode.OK.ordinal()) {
            return new MonitoringReportResult(recordsResp.getRt(), 0);
        }

        Pair<StatusCode, Integer> reportStatus = monitoringReportService.generateMonitoringReport(
            request.getReportPath(),
            request.getShiftStartTime(),
            request.isBalanceReport(),
            request.getPatientRecord(),
            groupsResp,
            recordsResp
        );
        if (reportStatus.getFirst() != StatusCode.OK) {
            ReturnCode returnCode = ReturnCodeUtils.getReturnCode(statusCodeMsgs, reportStatus.getFirst());
            return new MonitoringReportResult(returnCode, 0);
        }

        ReturnCode returnCode = ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK);
        return new MonitoringReportResult(returnCode, reportStatus.getSecond());
    }

    private final List<String> statusCodeMsgs;
    private final MonitoringService monitoringService;
    private final PatientMonitoringService patientMonitoringService;
    private final MonitoringReportService monitoringReportService;
}
