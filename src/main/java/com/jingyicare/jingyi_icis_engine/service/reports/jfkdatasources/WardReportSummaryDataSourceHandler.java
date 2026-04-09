package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.entity.reports.WardReport;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.WardReportPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class WardReportSummaryDataSourceHandler extends AbstractJfkDataSourceHandler {
    public WardReportSummaryDataSourceHandler(JfkDataSourceSupport support) {
        super(support);
    }

    @Override
    public String getMetaId() {
        return META_ID;
    }

    @Override
    public Pair<ReturnCode, JfkDataSourcePB> handle(JfkDataSourcePB input) {
        String deptId = getDeptIdInput(input, "dept_id");
        String shiftTimeIso = getStringInput(input, "shift_time");

        if (StrUtils.isBlank(deptId) || StrUtils.isBlank(shiftTimeIso)) {
            List<String> missingFields = new ArrayList<>();
            if (StrUtils.isBlank(deptId)) missingFields.add("dept_id");
            if (StrUtils.isBlank(shiftTimeIso)) missingFields.add("shift_time");
            return error(StatusCode.JFK_MISSING_REQUIRED_FIELD, joinMissingFields(missingFields));
        }

        LocalDateTime shiftStartTime = TimeUtils.fromIso8601String(shiftTimeIso, "UTC");
        if (shiftStartTime == null) {
            return error(StatusCode.INVALID_TIME_FORMAT);
        }

        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        RbacDepartment dept = support.getDeptRepo().findByDeptId(deptId).orElse(null);
        if (dept == null) {
            support.fillWardReportEmpty(outputBuilder);
            return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
        }

        support.addStrOutput(outputBuilder, "dept_name", dept.getDeptName());
        support.addStrOutput(outputBuilder, "report_date", TimeUtils.getYearMonthDay2(shiftStartTime));

        WardReport wardReport = support.getWardReportRepo()
            .findByDeptIdAndShiftStartTimeAndIsDeletedFalse(deptId, shiftStartTime)
            .orElse(null);
        if (wardReport == null) {
            support.fillWardReportEmpty(outputBuilder);
            return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
        }

        WardReportPB wardReportPb = ProtoUtils.decodeWardReport(wardReport.getWardReportPb());
        if (wardReportPb == null) {
            support.fillWardReportEmpty(outputBuilder);
            return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
        }

        support.addStrOutput(outputBuilder, "day_shift_line1", support.buildLine1(wardReportPb.getDayShiftCount()));
        support.addStrOutput(outputBuilder, "day_shift_line2", support.buildLine2(wardReportPb.getDayShiftCount()));
        support.addStrOutput(outputBuilder, "evening_shift_line1", support.buildLine1(wardReportPb.getEveningShiftCount()));
        support.addStrOutput(outputBuilder, "evening_shift_line2", support.buildLine2(wardReportPb.getEveningShiftCount()));
        support.addStrOutput(outputBuilder, "night_shift_line1", support.buildLine1(wardReportPb.getNightShiftCount()));
        support.addStrOutput(outputBuilder, "night_shift_line2", support.buildLine2(wardReportPb.getNightShiftCount()));

        support.addStrOutput(outputBuilder, "day_shift_outgoing_handover_signature",
            support.getSignature(wardReportPb.getDayShiftCount().getOutgoingHandoverAccountId()));
        support.addStrOutput(outputBuilder, "day_shift_incoming_handover_signature",
            support.getSignature(wardReportPb.getDayShiftCount().getIncomingHandoverAccountId()));
        support.addStrOutput(outputBuilder, "evening_shift_outgoing_handover_signature",
            support.getSignature(wardReportPb.getEveningShiftCount().getOutgoingHandoverAccountId()));
        support.addStrOutput(outputBuilder, "evening_shift_incoming_handover_signature",
            support.getSignature(wardReportPb.getEveningShiftCount().getIncomingHandoverAccountId()));
        support.addStrOutput(outputBuilder, "night_shift_outgoing_handover_signature",
            support.getSignature(wardReportPb.getNightShiftCount().getOutgoingHandoverAccountId()));
        support.addStrOutput(outputBuilder, "night_shift_incoming_handover_signature",
            support.getSignature(wardReportPb.getNightShiftCount().getIncomingHandoverAccountId()));

        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private static final String META_ID = "ward_report_summary";
}
