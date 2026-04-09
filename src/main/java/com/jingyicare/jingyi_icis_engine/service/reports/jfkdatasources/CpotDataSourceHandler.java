package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.scores.DeptScoreGroup;
import com.jingyicare.jingyi_icis_engine.entity.scores.PatientScore;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourceMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.ScoreGroupMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.ScoreGroupPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.ScoreItemPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.ScoreOptionPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class CpotDataSourceHandler extends AbstractJfkDataSourceHandler {
    public CpotDataSourceHandler(JfkDataSourceSupport support) {
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

        JfkDataSourceMetaPB configuredMeta = support.getConfiguredDataSourceMeta(META_ID).orElse(null);
        if (configuredMeta == null) {
            return error(StatusCode.INTERNAL_EXCEPTION);
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

        Map<String, Map<String, Integer>> itemOptionScoreMap = new LinkedHashMap<>();
        scoreGroupMeta.getItemList().forEach(itemMeta -> {
            Map<String, Integer> optionScoreMap = new LinkedHashMap<>();
            for (ScoreOptionPB option : itemMeta.getOptionList()) {
                optionScoreMap.put(option.getCode(), option.getScore().getInt32Val());
            }
            itemOptionScoreMap.put(itemMeta.getCode(), optionScoreMap);
        });

        List<JfkFieldMetaPB> outputFields = configuredMeta.getOutputFieldsList();
        Set<String> reservedFieldIds = Set.of("score_datetime", "score", "notes", "signature");
        Map<String, List<JfkValPB>> itemScoreLists = new LinkedHashMap<>();
        for (JfkFieldMetaPB outputField : outputFields) {
            if (!reservedFieldIds.contains(outputField.getId())) {
                itemScoreLists.put(outputField.getId(), new ArrayList<>());
            }
        }

        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        List<JfkValPB> scoreDateTimeList = new ArrayList<>();
        List<JfkValPB> scoreValueList = new ArrayList<>();
        List<JfkValPB> notesList = new ArrayList<>();
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
            notesList.add(JfkValPB.newBuilder()
                .setStrVal(record.getNote() == null ? "" : record.getNote())
                .build());
            signatureList.add(JfkValPB.newBuilder()
                .setStrVal(support.getSignatureByAutoId(record.getModifiedBy()))
                .build());

            Map<String, Integer> itemScoreSum = new LinkedHashMap<>();
            for (ScoreItemPB itemPb : scoreGroupPb.getItemList()) {
                Map<String, Integer> optionScoreMap = itemOptionScoreMap.get(itemPb.getCode());
                if (optionScoreMap == null) continue;
                int sum = 0;
                boolean filled = false;
                for (String optionCode : itemPb.getScoreOptionCodeList()) {
                    Integer score = optionScoreMap.get(optionCode);
                    if (score != null) {
                        sum += score;
                        filled = true;
                    }
                }
                if (filled) {
                    itemScoreSum.put(itemPb.getCode(), sum);
                }
            }

            for (Map.Entry<String, List<JfkValPB>> entry : itemScoreLists.entrySet()) {
                Integer score = itemScoreSum.get(entry.getKey());
                entry.getValue().add(JfkValPB.newBuilder()
                    .setStrVal(score == null ? "" : String.valueOf(score))
                    .build());
            }
        }

        for (JfkFieldMetaPB outputField : outputFields) {
            String fieldId = outputField.getId();
            if ("score_datetime".equals(fieldId)) {
                outputBuilder.addOutputData(JfkFieldDataPB.newBuilder().setId(fieldId).addAllVals(scoreDateTimeList).build());
                continue;
            }
            if ("score".equals(fieldId)) {
                outputBuilder.addOutputData(JfkFieldDataPB.newBuilder().setId(fieldId).addAllVals(scoreValueList).build());
                continue;
            }
            if ("notes".equals(fieldId)) {
                outputBuilder.addOutputData(JfkFieldDataPB.newBuilder().setId(fieldId).addAllVals(notesList).build());
                continue;
            }
            if ("signature".equals(fieldId)) {
                outputBuilder.addOutputData(JfkFieldDataPB.newBuilder().setId(fieldId).addAllVals(signatureList).build());
                continue;
            }
            outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
                .setId(fieldId)
                .addAllVals(itemScoreLists.getOrDefault(fieldId, List.of()))
                .build());
        }

        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private static final String META_ID = "cpot";
}
