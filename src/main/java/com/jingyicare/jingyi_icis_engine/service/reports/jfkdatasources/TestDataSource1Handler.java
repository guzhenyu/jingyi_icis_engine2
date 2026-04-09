package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class TestDataSource1Handler extends AbstractJfkDataSourceHandler {
    public TestDataSource1Handler(JfkDataSourceSupport support) {
        super(support);
    }

    @Override
    public String getMetaId() {
        return META_ID;
    }

    @Override
    public Pair<ReturnCode, JfkDataSourcePB> handle(JfkDataSourcePB input) {
        Long pid = getInt64Input(input, "pid");
        LocalDateTime queryStartUtc = getUtcDateTimeInput(input, "query_start");
        LocalDateTime queryEndUtc = getUtcDateTimeInput(input, "query_end");

        if (pid == null || queryStartUtc == null || queryEndUtc == null) {
            List<String> missingFields = new ArrayList<>();
            if (pid == null) missingFields.add("pid");
            if (queryStartUtc == null) missingFields.add("query_start");
            if (queryEndUtc == null) missingFields.add("query_end");
            return error(StatusCode.JFK_MISSING_REQUIRED_FIELD, joinMissingFields(missingFields));
        }

        log.info("### Test Data Source 1: pid={}, queryStartUtc={}, queryEndUtc={}",
            pid, queryStartUtc, queryEndUtc);

        PatientRecord patient = support.getPatientService().getPatientRecord(pid);
        if (patient == null) {
            return error(StatusCode.PATIENT_NOT_FOUND, "pid: " + pid);
        }

        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        List<JfkValPB> scoreDateTimeList = new ArrayList<>();
        List<JfkValPB> scoreValueList = new ArrayList<>();

        LocalDateTime recordTime = LocalDateTime.of(2025, 1, 1, 0, 0);
        for (int i = 1; i <= 106; i++) {
            scoreDateTimeList.add(JfkValPB.newBuilder()
                .setStrVal(TimeUtils.toIso8601String(recordTime, "UTC"))
                .build());
            scoreValueList.add(JfkValPB.newBuilder()
                .setStrVal(i + "分")
                .build());
            recordTime = recordTime.plusHours(1);
        }

        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("score_datetime")
            .addAllVals(scoreDateTimeList)
            .build());
        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("score")
            .addAllVals(scoreValueList)
            .build());

        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private static final Logger log = LoggerFactory.getLogger(TestDataSource1Handler.class);
    private static final String META_ID = "test_data_source1";
}
