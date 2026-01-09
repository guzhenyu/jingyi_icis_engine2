package com.jingyicare.jingyi_icis_engine.service.doctors;

import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDiagnosis.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDoctorScore.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.doctors.*;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.service.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.scores.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

/**
 * 核心函数：
 *   ApacheIIScorer.toEntity: 将ApacheII proto（已打分）转换为ApacheIIScore实体
 *   ApacheIIScorer.toProto: 将ApacheIIScore实体转换为ApacheII proto
 *   ApacheIIScorer.score: 计算ApacheIIScore的分数并返回proto
 *   ApacheIIScorer.mergeProto: 合并两个ApacheII proto（未打分）
 *   ApacheIIScorer.getApacheIIScoreFactors: 获取ApacheII评分项的列表
 *   ApacheIIScorer.getApacheFactorDetails: 获取ApacheII评分项的详细信息
 */
@Service
@Slf4j
public class ApacheIIScorer {
    public static Pair<ReturnCode, ApacheIIScore> toEntity(
        ApacheIIScoreMetaPB metaPb, ApacheIIScorePB scorePb, ScoreUtils.ScorerInfo scorerInfo,
        List<String> statusCodeMsgList
    ) {
        // 构建 ApacheIIScore 对象
        ApacheIIScore.ApacheIIScoreBuilder builder = ApacheIIScore.builder();

        // 设置apacheII病人，评分人，评分时间等附加信息
        setScorerInfo(builder, scorerInfo);

        // 在DoctorConfig中已经检查了所有的code都不重复
        Map<String, ValueMetaPB> codeValueMetaMap = extractValueMetaMap(metaPb);

        // 处理APS Params
        Pair<ReturnCode, ApacheIIScore> result = processApsParams(
            builder, scorePb.getApsParamList(), codeValueMetaMap, statusCodeMsgList
        );
        if (result != null) return result;

        // 设置 CHC 参数（慢性健康状况评分参数）
        result = processChcParams(
            builder, scorePb.getChcParamList(), codeValueMetaMap, statusCodeMsgList
        );
        if (result != null) return result;

        // 查找预计病死率主因（手术/非手术）
        Map<String, DoctorScoreFactorMetaPB> codeMetaMap = extractFactorMetaMap(metaPb);
        DoctorScoreFactorMetaPB mortalityFactorMeta = codeMetaMap.get(scorePb.getMortalityFactorCode());
        if (mortalityFactorMeta == null) {
            log.error("Mortality factor meta not found for code: {}", scorePb.getMortalityFactorCode());
            return new Pair<>(
                ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.UNEXPECTED_APACHE_II_SCORE_FACTOR,
                    scorePb.getMortalityFactorCode()),
                null
            );
        }
        String mortalityFactorName = mortalityFactorMeta.getName();

        // 设置评分结果
        return new Pair<ReturnCode, ApacheIIScore>(
            ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK),
            builder.apsScore(scorePb.getApsScore())
                .age(scorePb.getAge())
                .ageScore(scorePb.getAgeScore())
                .chcScore(scorePb.getChcScore())
                .apacheIiScore(scorePb.getApacheIiScore())
                .predictedMortalityRate(scorePb.getPredictedMortalityRate())
                .isOperative(scorePb.getIsOperative())
                .isEmergencyOperation(scorePb.getIsEmergencyOperation())
                .mortalityFactorCode(scorePb.getMortalityFactorCode())
                .mortalityFactorName(mortalityFactorName)
                .build()
        );
    }

    private static void setScorerInfo(
        ApacheIIScore.ApacheIIScoreBuilder builder, ScoreUtils.ScorerInfo scorerInfo
    ) {
        if (scorerInfo == null) return;
        if (scorerInfo.pid != null) builder.pid(scorerInfo.pid);
        if (scorerInfo.deptId != null) builder.deptId(scorerInfo.deptId);
        if (scorerInfo.scoreTime != null) builder.scoreTime(scorerInfo.scoreTime);
        if (scorerInfo.scoredBy != null) builder.scoredBy(scorerInfo.scoredBy);
        if (scorerInfo.scoredByAccountName != null) builder.scoredByAccountName(scorerInfo.scoredByAccountName);
        if (scorerInfo.evalStartAt != null) builder.evalStartAt(scorerInfo.evalStartAt);
        if (scorerInfo.evalEndAt != null) builder.evalEndAt(scorerInfo.evalEndAt);
        if (scorerInfo.modifiedAt != null) builder.modifiedAt(scorerInfo.modifiedAt);
        if (scorerInfo.modifiedBy != null) builder.modifiedBy(scorerInfo.modifiedBy);
        if (scorerInfo.isDeleted != null) builder.isDeleted(scorerInfo.isDeleted);
        if (scorerInfo.deletedBy != null) builder.deletedBy(scorerInfo.deletedBy);
        if (scorerInfo.deletedByAccountName != null) builder.deletedByAccountName(scorerInfo.deletedByAccountName);
        if (scorerInfo.deletedAt != null) builder.deletedAt(scorerInfo.deletedAt);
    }

    public static Map<String, ValueMetaPB> extractValueMetaMap(ApacheIIScoreMetaPB metaPb) {
        Map<String, ValueMetaPB> valueMetaMap = new HashMap<>();

        // Helper method to process a list of DoctorScoreFactorMetaPB
        Consumer<List<DoctorScoreFactorMetaPB>> processMetaList = metaList -> {
            for (DoctorScoreFactorMetaPB factorMeta : metaList) {
                valueMetaMap.put(factorMeta.getCode(), factorMeta.getValueMeta());
            }
        };

        // Process all relevant fields in ApacheIIScoreMetaPB
        processMetaList.accept(metaPb.getApsParamList());
        processMetaList.accept(metaPb.getChcParamList());
        processMetaList.accept(metaPb.getNopRespiratoryFailureList());
        processMetaList.accept(metaPb.getNopCardiovascularFailureList());
        processMetaList.accept(metaPb.getNopTraumaList());
        processMetaList.accept(metaPb.getNopNeurologicalDisorderList());
        processMetaList.accept(metaPb.getNopOtherConditionsList());
        processMetaList.accept(metaPb.getNopOrganDamageList());
        processMetaList.accept(metaPb.getOpSurgeryList());
        processMetaList.accept(metaPb.getOpPostoperativeComplicationsList());
        processMetaList.accept(metaPb.getOpNosList());

        return valueMetaMap;
    }

    public static Map<String, DoctorScoreFactorMetaPB> extractFactorMetaMap(ApacheIIScoreMetaPB metaPb) {
        Map<String, DoctorScoreFactorMetaPB> factorMetaMap = new HashMap<>();

        // Helper method to process a list of DoctorScoreFactorMetaPB
        Consumer<List<DoctorScoreFactorMetaPB>> processMetaList = metaList -> {
            for (DoctorScoreFactorMetaPB factorMeta : metaList) {
                factorMetaMap.put(factorMeta.getCode(), factorMeta);
            }
        };

        // Process all relevant fields in ApacheIIScoreMetaPB
        processMetaList.accept(metaPb.getApsParamList());
        processMetaList.accept(metaPb.getChcParamList());
        processMetaList.accept(metaPb.getNopRespiratoryFailureList());
        processMetaList.accept(metaPb.getNopCardiovascularFailureList());
        processMetaList.accept(metaPb.getNopTraumaList());
        processMetaList.accept(metaPb.getNopNeurologicalDisorderList());
        processMetaList.accept(metaPb.getNopOtherConditionsList());
        processMetaList.accept(metaPb.getNopOrganDamageList());
        processMetaList.accept(metaPb.getOpSurgeryList());
        processMetaList.accept(metaPb.getOpPostoperativeComplicationsList());
        processMetaList.accept(metaPb.getOpNosList());

        return factorMetaMap;
    }

    private static Pair<ReturnCode, ApacheIIScore> processApsParams(
        ApacheIIScore.ApacheIIScoreBuilder builder, List<DoctorScoreFactorPB> apsParamList,
        Map<String, ValueMetaPB> factorCodeMetaMap, List<String> statusCodeMsgList
    ) {
        for (DoctorScoreFactorPB factor : apsParamList) {
            String code = factor.getCode();
            ValueMetaPB valueMeta = factorCodeMetaMap.get(code);

            if (valueMeta == null) {
                log.error("Unhandled APS factor code in toEntity: {}", code);
                ReturnCode returnCode = ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.UNEXPECTED_APACHE_II_SCORE_FACTOR, code);
                return new Pair<>(returnCode, null);
            }

            GenericValuePB value = factor.getValue();

            // 根据值类型设置字段
            switch (code) {
                case "body_temperature":
                    builder.bodyTemperature(ValueMetaUtils.convertToFloatObj(value, valueMeta));
                    break;
                case "mean_arterial_pressure":
                    builder.meanArterialPressure(ValueMetaUtils.convertToFloatObj(value, valueMeta));
                    break;
                case "heart_rate":
                    builder.heartRate(ValueMetaUtils.convertToFloatObj(value, valueMeta));
                    break;
                case "respiratory_rate":
                    builder.respiratoryRate(ValueMetaUtils.convertToFloatObj(value, valueMeta));
                    break;
                case "fio2":
                    builder.fio2(ValueMetaUtils.convertToFloatObj(value, valueMeta));
                    break;
                case "a_a_do2":
                    builder.aADo2(ValueMetaUtils.convertToFloatObj(value, valueMeta));
                    break;
                case "pao2":
                    builder.pao2(ValueMetaUtils.convertToFloatObj(value, valueMeta));
                    break;
                case "ph":
                    builder.ph(ValueMetaUtils.convertToFloatObj(value, valueMeta));
                    break;
                case "hco3":
                    builder.hco3(ValueMetaUtils.convertToFloatObj(value, valueMeta));
                    break;
                case "sodium":
                    builder.sodium(ValueMetaUtils.convertToFloatObj(value, valueMeta));
                    break;
                case "potassium":
                    builder.potassium(ValueMetaUtils.convertToFloatObj(value, valueMeta));
                    break;
                case "creatinine":
                    builder.creatinine(ValueMetaUtils.convertToFloatObj(value, valueMeta));
                    break;
                case "has_acute_renal_failure":
                    builder.hasAcuteRenalFailure(ValueMetaUtils.getBooleanObj(value));
                    break;
                case "hematocrit":
                    builder.hematocrit(ValueMetaUtils.convertToFloatObj(value, valueMeta));
                    break;
                case "white_blood_cell_count":
                    builder.whiteBloodCellCount(ValueMetaUtils.convertToFloatObj(value, valueMeta));
                    break;
                case "glasgow_coma_scale":
                    builder.glasgowComaScale(ValueMetaUtils.convertToInt32Obj(value, valueMeta));
                    break;
                default:
                    log.error("Unhandled APS factor code in toEntity: {}", code);
                    ReturnCode returnCode = ReturnCodeUtils.getReturnCode(
                            statusCodeMsgList, StatusCode.UNEXPECTED_APACHE_II_SCORE_FACTOR, code);
                    return new Pair<>(returnCode, null);
            }
        }
        return null;
    }

    private static Pair<ReturnCode, ApacheIIScore> processChcParams(
        ApacheIIScore.ApacheIIScoreBuilder builder, List<DoctorScoreFactorPB> chcParamList,
        Map<String, ValueMetaPB> factorCodeMetaMap, List<String> statusCodeMsgList
    ) {
        for (DoctorScoreFactorPB factor : chcParamList) {
            String code = factor.getCode();
            ValueMetaPB valueMeta = factorCodeMetaMap.get(code);

            if (valueMeta == null) {
                log.error("Unhandled CHC factor code in toEntity: {}", code);
                ReturnCode returnCode = ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.UNEXPECTED_APACHE_II_SCORE_FACTOR, code);
                return new Pair<>(returnCode, null);
            }

            GenericValuePB value = factor.getValue();

            switch (code) {
                case "has_chronic_conditions":
                    builder.hasChronicConditions(ValueMetaUtils.getBooleanObj(value));
                    break;
                case "cardiovascular_system":
                    builder.chcCardio(ValueMetaUtils.getBooleanObj(value));
                    break;
                case "respiratory_system":
                    builder.chcResp(ValueMetaUtils.getBooleanObj(value));
                    break;
                case "liver_system":
                    builder.chcLiver(ValueMetaUtils.getBooleanObj(value));
                    break;
                case "kidney_system":
                    builder.chcKidney(ValueMetaUtils.getBooleanObj(value));
                    break;
                case "immune_dysfunction":
                    builder.chcImmune(ValueMetaUtils.getBooleanObj(value));
                    break;
                case "non_operative_or_emergency_surgery":
                    builder.nonOperativeOrEmergencySurgery(ValueMetaUtils.getBooleanObj(value));
                    break;
                default:
                    log.error("Unhandled CHC factor code in toEntity: {}", code);
                    ReturnCode returnCode = ReturnCodeUtils.getReturnCode(
                            statusCodeMsgList, StatusCode.UNEXPECTED_APACHE_II_SCORE_FACTOR, code);
                    return new Pair<>(returnCode, null);
            }
        }
        return null;
    }

    public static Pair<ReturnCode, ApacheIIScorePB> score(
        ApacheIIScoreMetaPB metaPb, ApacheIIScoreRulesPB rulesPb,
        ApacheIIScorePB scorePb, List<String> statusCodeMsgList
    ) {
        ApacheIIScorePB.Builder scorePbBuilder = ApacheIIScorePB.newBuilder();

        // 在DoctorConfig中已经检查了所有的code都不重复
        Map<String, ValueMetaPB> factorCodeMetaMap = extractValueMetaMap(metaPb);

        // 获取计算规则
        Map<String, FactorScoreRulePB> factorCodeRuleMap = rulesPb.getRuleList()
            .stream()
            .collect(Collectors.toMap(FactorScoreRulePB::getCode, Function.identity()));

        // 处理 APS Params
        ReturnCode returnCode = calcApsParamsScore(
            scorePbBuilder, scorePb.getApsParamList(),
            factorCodeMetaMap, factorCodeRuleMap, statusCodeMsgList
        );
        if (returnCode.getCode() != 0) {
            return new Pair<>(returnCode, null);
        }

        // 计算年龄分数
        FactorScoreRulePB ageScoreRule = factorCodeRuleMap.get("age");
        if (ageScoreRule == null) {
            log.error("Age score rule not found");
            returnCode = ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.UNEXPECTED_APACHE_II_SCORE_FACTOR, "age");
            return new Pair<>(returnCode, null);
        }
        int ageScore = calcFactorScore("age",
            GenericValuePB.newBuilder().setInt32Val(scorePb.getAge()).build(),
            ValueMetaPB.newBuilder().setValueType(TypeEnumPB.INT32).build(),
            ageScoreRule
        );
        scorePbBuilder.setAge(scorePb.getAge());
        scorePbBuilder.setAgeScore(ageScore);

        // 计算 CHC 分数
        returnCode = calcChcParamsScore(
            scorePbBuilder, scorePb.getChcParamList(), factorCodeMetaMap, statusCodeMsgList
        );
        if (returnCode.getCode() != 0) {
            return new Pair<>(returnCode, null);
        }

        // 汇总ApacheII评分
        int apacheIiScore = scorePbBuilder.getApsScore() + scorePbBuilder.getAgeScore() +
            scorePbBuilder.getChcScore();
        scorePbBuilder.setApacheIiScore(apacheIiScore);

        // 计算预计病死率
        boolean isOperative = scorePb.getIsOperative();
        boolean isEmergencyOperation = scorePb.getIsEmergencyOperation();
        scorePbBuilder.setIsOperative(isOperative);
        scorePbBuilder.setIsEmergencyOperation(isEmergencyOperation);

        // 获取系数
        Pair<ReturnCode, Float> coefPair = getCoef(
            isOperative, scorePb.getMortalityFactorCode(), metaPb, statusCodeMsgList
        );
        if (coefPair.getFirst().getCode() != 0) {
            return new Pair<>(coefPair.getFirst(), null);
        }
        Float coef = coefPair.getSecond();
        if (coef != 0f) scorePbBuilder.setMortalityFactorCode(scorePb.getMortalityFactorCode());
        if (isOperative && isEmergencyOperation) coef += 0.603f;

        // 计算预计病死率
        float predictedMortalityRate = (float) (1 / (1 + Math.exp(3.517f - 0.146f * apacheIiScore - coef)));
        scorePbBuilder.setPredictedMortalityRate(predictedMortalityRate);

        return new Pair<>(
            ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK),
            scorePbBuilder.build()
        );
    }

    private static ReturnCode calcApsParamsScore(
        ApacheIIScorePB.Builder scorePbBuilder, List<DoctorScoreFactorPB> apsParamList,
        Map<String, ValueMetaPB> factorCodeMetaMap,
        Map<String, FactorScoreRulePB> factorCodeRuleMap,
        List<String> statusCodeMsgList
    ) {
        List<String> factorCodeList = new ArrayList<>();
        Map<String, DoctorScoreFactorPB> factorCodeMap = new HashMap<>();
        Float fio2 = null;
        boolean hasAcuteRenalFailure = false;

        for (DoctorScoreFactorPB factor : apsParamList) {
            // 处理每个 factor
            String code = factor.getCode();
            ValueMetaPB valueMeta = factorCodeMetaMap.get(code);
            if (valueMeta == null) {
                log.error("Unhandled APS factor code in calcApsParamsScore: {}", code);
                return ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.UNEXPECTED_APACHE_II_SCORE_FACTOR, code);
            }
            factorCodeList.add(code);
            String valueStr = ValueMetaUtils.extractAndFormatParamValue(factor.getValue(), valueMeta);
            factorCodeMap.put(code, factor.toBuilder().setValueStr(valueStr).build());

            // 处理 fio2 和 has_acute_renal_failure 特例
            if (code.equals("fio2")) {
                fio2 = ValueMetaUtils.convertToFloatObj(factor.getValue(), valueMeta);
                continue;
            }
            if (code.equals("has_acute_renal_failure")) {
                hasAcuteRenalFailure = ValueMetaUtils.getBooleanObj(factor.getValue());
                continue;
            }

            // 计算单项得分
            FactorScoreRulePB factorScoreRule = factorCodeRuleMap.get(code);
            if (!code.equals("glasgow_coma_scale") && factorScoreRule == null) {
                log.error("Unhandled APS factor code in calcApsParamsScore: {}", code);
                return ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.UNEXPECTED_APACHE_II_SCORE_FACTOR, code);
            }

            int score = calcFactorScore(code, factor.getValue(), valueMeta, factorScoreRule);
            factorCodeMap.put(code, factor.toBuilder().setScore(score).build());
        }

        // Oxygenation
        DoctorScoreFactorPB fio2Factor = factorCodeMap.get("fio2");
        DoctorScoreFactorPB aaDo2Factor = factorCodeMap.get("a_a_do2");
        DoctorScoreFactorPB pao2Factor = factorCodeMap.get("pao2");
        if (fio2 != null) {
            if (fio2 < 0.5) {
                factorCodeMap.put("fio2", fio2Factor.toBuilder().setScore(
                    aaDo2Factor == null ? 0 : aaDo2Factor.getScore()).build());
            } else {
                factorCodeMap.put("fio2", fio2Factor.toBuilder().setScore(
                    pao2Factor == null ? 0 : pao2Factor.getScore()).build());
            }
        }
        if (aaDo2Factor != null) factorCodeMap.put("a_a_do2", aaDo2Factor.toBuilder().setScore(0).build());
        if (pao2Factor != null) factorCodeMap.put("pao2", pao2Factor.toBuilder().setScore(0).build());

        // PH & HCO3
        DoctorScoreFactorPB phFactor = factorCodeMap.get("ph");
        DoctorScoreFactorPB hco3Factor = factorCodeMap.get("hco3");
        if (phFactor != null && hco3Factor != null) {
            factorCodeMap.put("hco3", hco3Factor.toBuilder().setScore(0).build());
        }

        // Creatinine & Acute Renal Failure
        DoctorScoreFactorPB creatinineFactor = factorCodeMap.get("creatinine");
        if (creatinineFactor != null && hasAcuteRenalFailure) {
            final Integer creatinineScore = creatinineFactor.getScore();
            factorCodeMap.put("creatinine", creatinineFactor.toBuilder().setScore(creatinineScore * 2).build());
        }

        int apsScore = 0;
        for (String code : factorCodeList) {
            DoctorScoreFactorPB factor = factorCodeMap.get(code);
            if (factor == null) {
                log.error("Factor code {} not found in factorCodeMap", code);
                continue;
            }
            apsScore += factor.getScore();
            scorePbBuilder.addApsParam(factor);
        }
        scorePbBuilder.setApsScore(apsScore);

        return ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK);
    }

    public static int calcFactorScore(
        String code, GenericValuePB value, ValueMetaPB valueMeta, FactorScoreRulePB factorScoreRule
    ) {
        // 通过您已有的工具函数，将输入的值转化为 Float
        Float numericVal = ValueMetaUtils.convertToFloatObj(value, valueMeta);
        if (numericVal == null) {
            log.error("Value for code {} is null, value {}", code, value);
            return 0;
        }

        if (code.equals("glasgow_coma_scale")) {
            // 处理 GCS 特例
            if (numericVal < 0) {
                numericVal = 0f;
            } else if (numericVal > 15) {
                numericVal = 15f;
            }
            return 15 - Math.round(numericVal);
        }

        return ScoreUtils.calcFactorScore(code, value, valueMeta, factorScoreRule);
    }

    private static ReturnCode calcChcParamsScore(
        ApacheIIScorePB.Builder scorePbBuilder, List<DoctorScoreFactorPB> chcParamList,
        Map<String, ValueMetaPB> factorCodeMetaMap, List<String> statusCodeMsgList
    ) {
        DoctorScoreFactorPB hasChronicConditionsFactor = null;
        boolean hasChronicCondition = false;

        DoctorScoreFactorPB isNonOpOrEmergencyFactor = null;
        boolean isNonOpOrEmergency = false;

        List<DoctorScoreFactorPB> newChcParamList = new ArrayList<>();

        for (DoctorScoreFactorPB factor : chcParamList) {
            String code = factor.getCode();
            ValueMetaPB valueMeta = factorCodeMetaMap.get(code);
            if (valueMeta == null) {
                log.error("Unhandled CHC factor code in calcChcParamsScore: {}", code);
                return ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.UNEXPECTED_APACHE_II_SCORE_FACTOR, code);
            }

            GenericValuePB value = factor.getValue();
            Boolean boolValue = ValueMetaUtils.getBooleanObj(value);
            if (code.equals("has_chronic_conditions")) {
                hasChronicCondition = boolValue || hasChronicCondition;
                hasChronicConditionsFactor = factor;
                continue;
            }
            if (code.equals("non_operative_or_emergency_surgery")) {
                isNonOpOrEmergency = boolValue;
                isNonOpOrEmergencyFactor = factor;
                continue;
            }

            if (boolValue) hasChronicCondition = true;
            newChcParamList.add(factor);
        }
        if (hasChronicConditionsFactor != null || !newChcParamList.isEmpty()) {
            scorePbBuilder.addChcParam(DoctorScoreFactorPB.newBuilder()
                .setCode("has_chronic_conditions")
                .setValue(GenericValuePB.newBuilder().setBoolVal(hasChronicCondition).build())
                .build());
        }
        scorePbBuilder.addAllChcParam(newChcParamList);
        if (isNonOpOrEmergencyFactor != null) {
            scorePbBuilder.addChcParam(isNonOpOrEmergencyFactor);
        }

        if (!hasChronicCondition) {
            scorePbBuilder.setChcScore(0);
        } else {
            if (hasChronicConditionsFactor != null) {
                if (isNonOpOrEmergency) {
                    scorePbBuilder.setChcScore(5);
                } else {
                    scorePbBuilder.setChcScore(2);
                }
            } else {
                scorePbBuilder.setChcScore(0);
            }
        } 

        return ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK);
    }

    private static Pair<ReturnCode, Float> getCoef(
        Boolean isOperative, String mortalityFactorCode, ApacheIIScoreMetaPB metaPb,
        List<String> statusCodeMsgList
    ) {
        if (mortalityFactorCode == null || mortalityFactorCode.isEmpty()) {
            return new Pair<>(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK), 0f);
        }

        DoctorScoreFactorPB factor = null;
        if (isOperative) {
            factor = Stream.of(
                metaPb.getOpSurgeryCoefList(),
                metaPb.getOpPostoperativeComplicationsCoefList(),
                metaPb.getOpNosCoefList()
            ).flatMap(List::stream)
            .filter(f -> f.getCode().equals(mortalityFactorCode))
            .findFirst()
            .orElse(null);
        } else {
            factor = Stream.of(
                metaPb.getNopRespiratoryFailureCoefList(),
                metaPb.getNopCardiovascularFailureCoefList(),
                metaPb.getNopTraumaCoefList(),
                metaPb.getNopNeurologicalDisorderCoefList(),
                metaPb.getNopOtherConditionsCoefList(),
                metaPb.getNopOrganDamageCoefList()
            ).flatMap(List::stream)
            .filter(f -> f.getCode().equals(mortalityFactorCode))
            .findFirst()
            .orElse(null);
        }
        if (factor == null) {
            log.error("Mortality factor code {} not found in metaPb", mortalityFactorCode);
            return new Pair<>(
                ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.UNEXPECTED_APACHE_II_SCORE_FACTOR,
                    mortalityFactorCode),
                null
            );
        }
        return new Pair<>(
            ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK),
            factor.getValue().getFloatVal()
        );
    }

    public static Pair<ReturnCode, ApacheIIScorePB> toProto(
        ApacheIIScoreMetaPB metaPb, ApacheIIScoreRulesPB rulesPb,
        ApacheIIScore score, List<String> statusCodeMsgList
    ) {
        // 构建 ApacheIIScorePB
        ApacheIIScorePB.Builder scorePbBuilder = ApacheIIScorePB.newBuilder();

        // 在DoctorConfig中已经检查了所有的code都不重复
        Map<String, ValueMetaPB> factorCodeMetaMap = extractValueMetaMap(metaPb);

        // 处理 APS 参数
        List<DoctorScoreFactorPB> apsParams = new ArrayList<>();
        addApsParam(apsParams, "body_temperature", score.getBodyTemperature(), factorCodeMetaMap);
        addApsParam(apsParams, "mean_arterial_pressure", score.getMeanArterialPressure(), factorCodeMetaMap);
        addApsParam(apsParams, "heart_rate", score.getHeartRate(), factorCodeMetaMap);
        addApsParam(apsParams, "respiratory_rate", score.getRespiratoryRate(), factorCodeMetaMap);
        addApsParam(apsParams, "fio2", score.getFio2(), factorCodeMetaMap);
        addApsParam(apsParams, "a_a_do2", score.getAADo2(), factorCodeMetaMap);
        addApsParam(apsParams, "pao2", score.getPao2(), factorCodeMetaMap);
        addApsParam(apsParams, "ph", score.getPh(), factorCodeMetaMap);
        addApsParam(apsParams, "hco3", score.getHco3(), factorCodeMetaMap);
        addApsParam(apsParams, "sodium", score.getSodium(), factorCodeMetaMap);
        addApsParam(apsParams, "potassium", score.getPotassium(), factorCodeMetaMap);
        addApsParam(apsParams, "creatinine", score.getCreatinine(), factorCodeMetaMap);
        addApsParam(apsParams, "has_acute_renal_failure", score.getHasAcuteRenalFailure(), factorCodeMetaMap);
        addApsParam(apsParams, "hematocrit", score.getHematocrit(), factorCodeMetaMap);
        addApsParam(apsParams, "white_blood_cell_count", score.getWhiteBloodCellCount(), factorCodeMetaMap);
        addApsParam(apsParams, "glasgow_coma_scale", score.getGlasgowComaScale(), factorCodeMetaMap);
        scorePbBuilder.addAllApsParam(apsParams);
        scorePbBuilder.setApsScore(score.getApsScore());

        // 处理年龄
        scorePbBuilder.setAge(score.getAge());
        scorePbBuilder.setAgeScore(score.getAgeScore());

        // 处理 CHC 参数
        List<DoctorScoreFactorPB> chcParams = new ArrayList<>();
        addChcParam(chcParams, "has_chronic_conditions", score.getHasChronicConditions(), factorCodeMetaMap);
        addChcParam(chcParams, "cardiovascular_system", score.getChcCardio(), factorCodeMetaMap);
        addChcParam(chcParams, "respiratory_system", score.getChcResp(), factorCodeMetaMap);
        addChcParam(chcParams, "liver_system", score.getChcLiver(), factorCodeMetaMap);
        addChcParam(chcParams, "kidney_system", score.getChcKidney(), factorCodeMetaMap);
        addChcParam(chcParams, "immune_dysfunction", score.getChcImmune(), factorCodeMetaMap);
        addChcParam(chcParams, "non_operative_or_emergency_surgery", score.getNonOperativeOrEmergencySurgery(), factorCodeMetaMap);
        scorePbBuilder.addAllChcParam(chcParams);
        scorePbBuilder.setChcScore(score.getChcScore());

        // apache评分
        scorePbBuilder.setApacheIiScore(score.getApacheIiScore());

        // 预计病死率
        scorePbBuilder.setIsOperative(score.getIsOperative());
        Boolean isEmergencyOperation = score.getIsEmergencyOperation() == null ? false : score.getIsEmergencyOperation();
        scorePbBuilder.setIsEmergencyOperation(isEmergencyOperation);
        scorePbBuilder.setMortalityFactorCode(score.getMortalityFactorCode());
        scorePbBuilder.setPredictedMortalityRate(score.getPredictedMortalityRate());

        // 调用 score 方法重新计算评分
        ApacheIIScorePB initialScorePb = scorePbBuilder.build();
        return score(metaPb, rulesPb, initialScorePb, statusCodeMsgList);
    }

    // 辅助方法：添加 APS 参数
    private static void addApsParam(
        List<DoctorScoreFactorPB> params, String code, Object value,
        Map<String, ValueMetaPB> factorCodeMetaMap
    ) {
        if (value == null) return;

        ValueMetaPB valueMeta = factorCodeMetaMap.get(code);
        if (valueMeta == null) {
            log.warn("No ValueMetaPB found for APS factor code: {}", code);
            return;
        }

        GenericValuePB genericValue;
        switch (valueMeta.getValueType()) {
            case TypeEnumPB.FLOAT:
                genericValue = ValueMetaUtils.getValue(valueMeta, ((Float) value).doubleValue());
                break;
            case TypeEnumPB.INT32:
                genericValue = ValueMetaUtils.getValue(valueMeta, ((Integer) value).doubleValue());
                break;
            case TypeEnumPB.BOOL:
                genericValue = GenericValuePB.newBuilder().setBoolVal((Boolean) value).build();
                break;
            default:
                log.warn("Unsupported value type for APS factor code: {}", code);
                return;
        }

        params.add(DoctorScoreFactorPB.newBuilder()
                .setCode(code)
                .setValue(genericValue)
                .setValueStr(ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta))
                .build());
    }

    // 辅助方法：添加 CHC 参数
    private static void addChcParam(
        List<DoctorScoreFactorPB> params, String code, Boolean value,
        Map<String, ValueMetaPB> factorCodeMetaMap
    ) {
        if (value == null) return;

        ValueMetaPB valueMeta = factorCodeMetaMap.get(code);
        if (valueMeta == null) {
            log.warn("No ValueMetaPB found for CHC factor code: {}", code);
            return;
        }

        GenericValuePB genericValue = GenericValuePB.newBuilder().setBoolVal(value).build();
        params.add(DoctorScoreFactorPB.newBuilder()
                .setCode(code)
                .setValue(genericValue)
                .setValueStr(ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta))
                .build());
    }

    public static ApacheIIScorePB mergeProto(ApacheIIScorePB origScorePb, ApacheIIScorePB scorePb) {
        // 创建 origScorePb 的 Builder，用于修改
        ApacheIIScorePB.Builder builder = origScorePb.toBuilder();

        // 合并 APS 参数
        mergeFactorList(builder.getApsParamBuilderList(), scorePb.getApsParamList(), builder::addApsParam);

        // 合并 CHC 参数
        mergeFactorList(builder.getChcParamBuilderList(), scorePb.getChcParamList(), builder::addChcParam);

        // 覆盖非 repeated 字段（如果 scorePb 中有值）
        if (scorePb.getAge() > 0) {
            builder.setAge(scorePb.getAge());
        }

        return builder.build();
    }

    // 辅助方法：合并 DoctorScoreFactorPB 列表
    private static void mergeFactorList(
            List<DoctorScoreFactorPB.Builder> origList,
            List<DoctorScoreFactorPB> scoreList,
            Consumer<DoctorScoreFactorPB> addMethod) {
        // 创建 origList 的 code 到索引的映射
        Map<String, Integer> origCodeToIndex = new HashMap<>();
        for (int i = 0; i < origList.size(); i++) {
            origCodeToIndex.put(origList.get(i).getCode(), i);
        }

        // 用于跟踪已处理的 code，避免重复添加
        Set<String> processedCodes = new HashSet<>();

        // 遍历 scoreList，更新或添加因子
        for (DoctorScoreFactorPB scoreFactor : scoreList) {
            String code = scoreFactor.getCode();
            processedCodes.add(code);

            if (origCodeToIndex.containsKey(code)) {
                // 如果 origList 中有该 code，覆盖原因子
                int index = origCodeToIndex.get(code);
                origList.set(index, scoreFactor.toBuilder());
            } else {
                // 如果 origList 中没有该 code，添加到列表
                addMethod.accept(scoreFactor);
            }
        }

        // 保留 origList 中未被 scoreList 覆盖的因子（已在 origList 中处理）
    }

    public ApacheIIScorer(
        @Autowired ConfigProtoService protoService,
        @Autowired PatientMonitoringService patientMonitoringService,
        @Autowired BgaService bgaService,
        @Autowired ScoreService scoreService
    ) {
        this.protoService = protoService;
        this.statusCodeMsgList = protoService.getConfig().getText().getStatusCodeMsgList();
        this.metaPb = protoService.getConfig().getDoctorScore().getApacheIiMeta();
        this.rulesPb = protoService.getConfig().getDoctorScore().getApacheIiRules();
        this.factorCodeMetaMap = extractValueMetaMap(metaPb);
        factorCodeRuleMap = rulesPb.getRuleList()
            .stream()
            .collect(Collectors.toMap(FactorScoreRulePB::getCode, Function.identity()));

        this.patientMonitoringService = patientMonitoringService;
        this.bgaService = bgaService;
        this.scoreService = scoreService;
    }

    public Pair<ReturnCode, ApacheIIScorePB> getApacheIIScoreFactors(
        Long pid, String deptId, LocalDateTime evalStartUtc, LocalDateTime evalEndUtc
    ) {
        List<DoctorScoreFactorPB> apsFactors = new ArrayList<>();

        for (String factorCode : List.of(
            "body_temperature", "mean_arterial_pressure", "heart_rate", "respiratory_rate",
            // "fio2", "a_a_do2", "pao2", "ph", "hco3",
            // "sodium", "potassium", "creatinine", "hematocrit", "white_blood_cell_count",
            "glasgow_coma_scale"
        )) {
            Pair<ReturnCode, DiseaseItemDetailsPB> detailsPair = getApacheFactorDetails(
                pid, deptId, factorCode, evalStartUtc, evalEndUtc
            );
            if (detailsPair == null) continue;
            if (detailsPair.getFirst().getCode() != 0) {
                return new Pair<>(detailsPair.getFirst(), null);
            }
            DiseaseItemPB diseaseItem = detailsPair.getSecond().getItem();
            DoctorScoreFactorPB factor = DoctorScoreFactorPB.newBuilder()
                .setCode(factorCode)
                .setValue(diseaseItem.getValue())
                .setValueStr(diseaseItem.getOrigValueStr())
                .build();
            apsFactors.add(factor);
        }

        return new Pair<ReturnCode, ApacheIIScorePB>(
            ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK),
            ApacheIIScorePB.newBuilder()
                .addAllApsParam(apsFactors)
                .build()
        );
    }

    public Pair<ReturnCode, DiseaseItemDetailsPB> getApacheFactorDetails(
        Long pid, String deptId, String factorCode,
        LocalDateTime evalStartUtc, LocalDateTime evalEndUtc
    ) {
        if (evalStartUtc == null || evalEndUtc == null) {
            log.error("evalStartUtc or evalEndUtc is null");
            return new Pair<ReturnCode, DiseaseItemDetailsPB>(
                ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList,
                    StatusCode.INVALID_TIME_RANGE
                ),
                null);
        }

        Pair<ValueMetaPB, List<TimedGenericValuePB>> origValuePair = null;
        if (factorCode.equals("body_temperature") ||
            factorCode.equals("mean_arterial_pressure") ||
            factorCode.equals("heart_rate") ||
            factorCode.equals("respiratory_rate")
        ) {
            List<String> monitoringCode = new ArrayList<>();
            if (factorCode.equals("body_temperature")) {
                monitoringCode.add("temperature");
            } else if (factorCode.equals("mean_arterial_pressure")) {
                monitoringCode.add("ibp_m");
                monitoringCode.add("nibp_m");
            } else if (factorCode.equals("heart_rate")) {
                monitoringCode.add("hr");
            } else if (factorCode.equals("respiratory_rate")) {
                monitoringCode.add("respiratory_rate");
            }
            for (String code : monitoringCode) {
                origValuePair = patientMonitoringService.fetchMonitoringItem(
                    pid, deptId, code, evalStartUtc, evalEndUtc);
                if (origValuePair != null &&
                    origValuePair.getSecond() != null &&
                    !origValuePair.getSecond().isEmpty()
                ) {
                    break;
                }
            }
        } else if (factorCode.equals("fio2") ||
            factorCode.equals("a_a_do2") ||
            factorCode.equals("pao2") ||
            factorCode.equals("ph") ||
            factorCode.equals("hco3")
        ) {
            String bgaCode = factorCode.equals("fio2") ? "bga_fio2"
                : (factorCode.equals("a_a_do2") ? "bga_aado2" :
                  (factorCode.equals("pao2") ? "bga_pao2" :
                  (factorCode.equals("ph") ? "bga_ph" : "bga_hco3-")
                ));
            origValuePair = bgaService.fetchArterialBgaRecords(
                pid, deptId, bgaCode, evalStartUtc, evalEndUtc
            );
        } else if (factorCode.equals("sodium") ||
            factorCode.equals("potassium") ||
            factorCode.equals("creatinine") ||
            factorCode.equals("hematocrit") ||
            factorCode.equals("white_blood_cell_count")
        ) {
            // 实验室检查
            return null;
        } else if (factorCode.equals("glasgow_coma_scale")) {
            origValuePair = scoreService.fetchScoreGroup(pid, "gcs", evalStartUtc, evalEndUtc);
        } else {
            log.error("Unhandled factor code: {}", factorCode);
            return null;
        }

        if (origValuePair == null) return null;
        ValueMetaPB origValueMeta = origValuePair.getFirst();
        List<TimedGenericValuePB> origValueList = origValuePair.getSecond();
        if (origValueMeta == null || origValueList.isEmpty()) return null;

        ValueMetaPB valueMeta = factorCodeMetaMap.get(factorCode);
        FactorScoreRulePB factorScoreRule = factorCodeRuleMap.get(factorCode);
        if (valueMeta == null || (!factorCode.equals("glasgow_coma_scale") && factorScoreRule == null)) {
            log.error("ValueMetaPB or FactorScoreRulePB not found for factor code: {}", factorCode);
            return null;
        }

        int score = -1;
        float numericVal = 0f;
        GenericValuePB factorValue = null;
        String timeStr = "";
        for (TimedGenericValuePB timedValue : origValueList) {
            GenericValuePB tmpfactorValue = ValueMetaUtils.convertGenericValue(
                timedValue.getValue(), origValueMeta, valueMeta
            );
            int tmpScore = calcFactorScore(
                factorCode, tmpfactorValue, valueMeta, factorScoreRule
            );

            float tmpNumericVal = ValueMetaUtils.convertToFloatObj(
                timedValue.getValue(), origValueMeta
            );
            if (tmpScore > score || (tmpScore == score && tmpNumericVal > numericVal)) {
                score = tmpScore;
                numericVal = tmpNumericVal;
                factorValue = tmpfactorValue;
                timeStr = timedValue.getRecordedAtIso8601();
            }
        }

        if (factorValue == null) {
            log.error("No valid factor value found for factor code: {}", factorCode);
            return null;
        }
        DiseaseItemPB diseaseItem = DiseaseItemPB.newBuilder()
            .setCode(factorCode)
            .setValue(factorValue)
            .setOrigValueStr(ValueMetaUtils.extractAndFormatParamValue(factorValue, valueMeta))
            .setEffectiveTimeIso8601(timeStr)
            .build();

        DiseaseItemDetailsPB diseaseItemDetails = DiseaseItemDetailsPB.newBuilder()
            .setItem(diseaseItem)
            .setOrigValueMeta(origValueMeta)
            .addAllOrigValue(origValueList)
            .build();
        return new Pair<ReturnCode, DiseaseItemDetailsPB>(
            ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK),
            diseaseItemDetails
        );
    }

    private final ConfigProtoService protoService;
    private final PatientMonitoringService patientMonitoringService;
    private final BgaService bgaService;
    private final ScoreService scoreService;

    private List<String> statusCodeMsgList;
    private ApacheIIScoreMetaPB metaPb;
    private ApacheIIScoreRulesPB rulesPb;
    private Map<String, ValueMetaPB> factorCodeMetaMap;
    private Map<String, FactorScoreRulePB> factorCodeRuleMap;
}