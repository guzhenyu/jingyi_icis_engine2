package com.jingyicare.jingyi_icis_engine.service.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.reports.WardReport;
import com.jingyicare.jingyi_icis_engine.repository.reports.WardReportRepository;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

public class ReportServiceTests extends TestsBase {
    public ReportServiceTests(
        @Autowired ReportService reportService,
        @Autowired WardReportRepository wardReportRepo
    ) {
        this.reportService = reportService;
        this.wardReportRepo = wardReportRepo;
    }

    @Test
    public void testSetWardReportAndGetWardReport() {
        setAuthentication("admin");
        long deptId = 10042L;
        String shiftStart = "2025-01-01T00:00:00Z";

        WardPatientCountPB dayCount = WardPatientCountPB.newBuilder()
            .setTotalPatientCount(10)
            .setAdmissionCount(2)
            .setTransferInCount(1)
            .setDischargeCount(3)
            .build();
        WardPatientStatsPB patientStats = WardPatientStatsPB.newBuilder()
            .setPid(1234L)
            .setDiagnosis("diag-1")
            .setDayShiftHandoverNote("note-1")
            .setEveningShiftHandoverNote("evening-note-1")
            .setNightShiftHandoverNote("night-note-1")
            .build();

        SetWardReportReq setReq = SetWardReportReq.newBuilder()
            .setDeptId(deptId)
            .setShiftStartIso8601(shiftStart)
            .setSetDayShiftCount(true)
            .setDayShiftCount(dayCount)
            .setSetEveningShiftCount(true)
            .setEveningShiftCount(dayCount.toBuilder().setTotalPatientCount(20).build())
            .setSetNightShiftCount(true)
            .setNightShiftCount(dayCount.toBuilder().setTotalPatientCount(30).build())
            .setSetPatientStats(true)
            .addPatientStats(patientStats)
            .build();

        GenericResp setResp = reportService.setWardReport(ProtoUtils.protoToJson(setReq));
        assertThat(setResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        WardReport saved = wardReportRepo
            .findByDeptIdAndShiftStartTimeAndIsDeletedFalse(String.valueOf(deptId),
                TimeUtils.fromIso8601String(shiftStart, "UTC"))
            .orElse(null);
        assertThat(saved).isNotNull();

        GetWardReportReq getReq = GetWardReportReq.newBuilder()
            .setDeptId(deptId)
            .setShiftStartIso8601(shiftStart)
            .build();
        GetWardReportResp getResp = reportService.getWardReport(ProtoUtils.protoToJson(getReq));
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getPdfUrl()).isEmpty();
        assertThat(getResp.getDayShiftCount().getTotalPatientCount()).isEqualTo(10);
        assertThat(getResp.getEveningShiftCount().getTotalPatientCount()).isEqualTo(20);
        assertThat(getResp.getNightShiftCount().getTotalPatientCount()).isEqualTo(30);
        assertThat(getResp.getPatientStatsList()).hasSize(1);
        assertThat(getResp.getPatientStatsList().get(0).getPid()).isEqualTo(1234L);
        assertThat(getResp.getPatientStatsList().get(0).getDiagnosis()).isEqualTo("diag-1");
        assertThat(getResp.getPatientStatsList().get(0).getDayShiftHandoverNote()).isEqualTo("note-1");
        assertThat(getResp.getPatientStatsList().get(0).getEveningShiftHandoverNote()).isEqualTo("evening-note-1");
        assertThat(getResp.getPatientStatsList().get(0).getNightShiftHandoverNote()).isEqualTo("night-note-1");
    }

    @Test
    public void testSetWardReportPartialUpdateKeepsExistingData() {
        setAuthentication("admin");
        long deptId = 10042L;
        String shiftStart = "2025-02-01T00:00:00Z";

        SetWardReportReq initReq = SetWardReportReq.newBuilder()
            .setDeptId(deptId)
            .setShiftStartIso8601(shiftStart)
            .setSetDayShiftCount(true)
            .setDayShiftCount(WardPatientCountPB.newBuilder().setTotalPatientCount(5).build())
            .setSetPatientStats(true)
            .addPatientStats(WardPatientStatsPB.newBuilder().setPid(999L).setNightShiftHandoverNote("n1").build())
            .build();
        GenericResp initResp = reportService.setWardReport(ProtoUtils.protoToJson(initReq));
        assertThat(initResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        SetWardReportReq updateReq = SetWardReportReq.newBuilder()
            .setDeptId(deptId)
            .setShiftStartIso8601(shiftStart)
            .setSetEveningShiftCount(true)
            .setEveningShiftCount(WardPatientCountPB.newBuilder().setTotalPatientCount(15).build())
            .setSetPatientStats(false)
            .build();
        GenericResp updateResp = reportService.setWardReport(ProtoUtils.protoToJson(updateReq));
        assertThat(updateResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        GetWardReportReq getReq = GetWardReportReq.newBuilder()
            .setDeptId(deptId)
            .setShiftStartIso8601(shiftStart)
            .build();
        GetWardReportResp getResp = reportService.getWardReport(ProtoUtils.protoToJson(getReq));
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getDayShiftCount().getTotalPatientCount()).isEqualTo(5);
        assertThat(getResp.getEveningShiftCount().getTotalPatientCount()).isEqualTo(15);
        assertThat(getResp.getPatientStatsList()).extracting(WardPatientStatsPB::getPid)
            .containsExactly(999L);
        assertThat(getResp.getPatientStatsList().get(0).getNightShiftHandoverNote()).isEqualTo("n1");
    }

    private void setAuthentication(String accountId) {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private final ReportService reportService;
    private final WardReportRepository wardReportRepo;
}
