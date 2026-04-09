package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.scores.DeptScoreGroup;
import com.jingyicare.jingyi_icis_engine.entity.scores.PatientScore;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.ScoreGroupMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.ScoreGroupPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class RassDataSourceHandler extends AbstractJfkDataSourceHandler {
    public RassDataSourceHandler(JfkDataSourceSupport support) {
        super(support);
    }

    @Override
    public String getMetaId() {
        return META_ID;
    }

    @Override
    public Pair<ReturnCode, JfkDataSourcePB> handle(JfkDataSourcePB input) {
        Long pid = getInt64Input(input, "pid");
        if (pid == null) {
            return error(StatusCode.JFK_MISSING_REQUIRED_FIELD, "pid");
        }

        PatientRecord patient = support.getPatientService().getPatientRecord(pid);
        if (patient == null) {
            return error(StatusCode.PATIENT_NOT_FOUND, "pid: " + pid);
        }

        String deptId = patient.getDeptId();
        DeptScoreGroup deptScoreGroup = support.getDeptScoreGroupRepo()
            .findByDeptIdAndScoreGroupCodeAndIsDeletedFalse(deptId, META_ID)
            .orElse(null);
        if (deptScoreGroup == null) {
            return error(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND, "deptId: " + deptId);
        }

        ScoreGroupMetaPB scoreGroupMeta = ProtoUtils.decodeScoreGroupMetaPB(deptScoreGroup.getScoreGroupMeta());
        if (scoreGroupMeta == null) {
            return error(StatusCode.INTERNAL_EXCEPTION);
        }

        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        List<JfkValPB> scoreDateTimeList = new ArrayList<>();
        List<JfkValPB> scoreValueList = new ArrayList<>();
        List<JfkValPB> signatureList = new ArrayList<>();

        List<PatientScore> patientScores = support.getPatientScoreRepo()
            .findByPidAndScoreGroupCodeAndIsDeletedFalse(pid, META_ID)
            .stream()
            .sorted(Comparator.comparing(PatientScore::getEffectiveTime))
            .toList();

        for (PatientScore record : patientScores) {
            ScoreGroupPB scoreGroupPb = ProtoUtils.decodeScoreGroupPB(record.getScore());
            if (scoreGroupPb == null) continue;
            scoreDateTimeList.add(JfkValPB.newBuilder()
                .setStrVal(TimeUtils.toIso8601String(record.getEffectiveTime(), support.getZoneId()))
                .build());
            scoreValueList.add(JfkValPB.newBuilder()
                .setStrVal(String.valueOf(scoreGroupPb.getGroupScore().getInt32Val()))
                .build());
            signatureList.add(JfkValPB.newBuilder()
                .setStrVal(support.getSignatureByAutoId(record.getModifiedBy()))
                .build());
        }

        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("score_datetime")
            .addAllVals(scoreDateTimeList)
            .build());
        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("score")
            .addAllVals(scoreValueList)
            .build());
        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("signature")
            .addAllVals(signatureList)
            .build());

        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private static final String META_ID = "rass";
}
