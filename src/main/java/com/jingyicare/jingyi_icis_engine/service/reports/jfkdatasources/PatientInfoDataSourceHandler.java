package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

@Component
public class PatientInfoDataSourceHandler extends AbstractJfkDataSourceHandler {
    public PatientInfoDataSourceHandler(JfkDataSourceSupport support) {
        super(support);
    }

    @Override
    public String getMetaId() {
        return META_ID;
    }

    @Override
    public Pair<ReturnCode, JfkDataSourcePB> handle(JfkDataSourcePB input) {
        Long pid = getInt64Input(input, "pid");
        LocalDateTime shiftTimeStart = getUtcDateTimeInput(input, "shift_time");

        if (pid == null || shiftTimeStart == null) {
            List<String> missingFields = new ArrayList<>();
            if (pid == null) missingFields.add("pid");
            if (shiftTimeStart == null) missingFields.add("shift_time");
            return error(StatusCode.JFK_MISSING_REQUIRED_FIELD, joinMissingFields(missingFields));
        }

        Pair<ReturnCode, JfkPatientInfo> result = support.getJfkPatientInfo(pid, shiftTimeStart);
        if (result.getFirst().getCode() != StatusCode.OK.ordinal()) {
            return new Pair<>(result.getFirst(), null);
        }

        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        JfkPatientInfo info = result.getSecond();
        support.addStrOutput(outputBuilder, "dept_name", info.deptName);
        support.addStrOutput(outputBuilder, "bed_no", info.bedNumber);
        support.addStrOutput(outputBuilder, "patient_name", info.name);
        support.addStrOutput(outputBuilder, "gender", info.gender);
        support.addStrOutput(outputBuilder, "age", info.ageStr);
        support.addStrOutput(outputBuilder, "mrn", info.mrn);
        support.addStrOutput(outputBuilder, "diagnosis", info.diagnosis);

        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private static final String META_ID = "patient_info";
}
