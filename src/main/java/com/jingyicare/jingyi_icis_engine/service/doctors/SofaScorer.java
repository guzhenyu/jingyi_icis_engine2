package com.jingyicare.jingyi_icis_engine.service.doctors;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import lombok.*;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDiagnosis.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDoctorScore.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.doctors.SofaScore;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.service.scores.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

/**
 * 核心函数：
 * - toEntity(...)：将 proto 转为实体对象
 * - toProto(...)：将实体对象转为 proto
 * - score(...)：根据规则计算分数
 * - mergeProto(...)：合并两个 proto （未打分）
 */
@Slf4j
@Service
public class SofaScorer {
    public static Pair<ReturnCode, SofaScore> toEntity(
        SofaScoreMetaPB metaPb, SOFAScorePB scorePb, ScoreUtils.ScorerInfo scorerInfo,
        List<String> statusCodeMsgList
    ) {
        SofaScore.SofaScoreBuilder builder = SofaScore.builder();

        // 设置评分相关的附加信息（病人ID、评分人、时间等）
        setScorerInfo(builder, scorerInfo);

        // 提取 meta 中的 (code -> ValueMetaPB) 映射, 方便校验/转换
        Map<String, ValueMetaPB> codeMetaMap = extractValueMetaMap(metaPb);

        // 处理 Sofa 参数
        for (DoctorScoreFactorPB factor : scorePb.getSofaParamList()) {
            String code = factor.getCode();
            ValueMetaPB valueMeta = codeMetaMap.get(code);
            if (valueMeta == null) {
                // 未定义的 code
                log.error("Unhandled Sofa factor code in toEntity: {}", code);
                ReturnCode rc = ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.UNEXPECTED_SOFA_SCORE_FACTOR, code
                );
                return new Pair<>(rc, null);
            }

            // 根据 code 映射到实体对应字段
            switch (code) {
                case "pao2_fio2_ratio":
                    builder.pao2Fio2Ratio(ValueMetaUtils.convertToFloatObj(factor.getValue(), valueMeta));
                    break;
                case "respiratory_support":
                    builder.respiratorySupport(ValueMetaUtils.getBooleanObj(factor.getValue()));
                    break;
                case "platelet_count":
                    builder.plateletCount(ValueMetaUtils.convertToFloatObj(factor.getValue(), valueMeta));
                    break;
                case "bilirubin":
                    builder.bilirubin(ValueMetaUtils.convertToFloatObj(factor.getValue(), valueMeta));
                    break;
                case "circulation_mean_arterial_pressure":
                    builder.circulationMeanArterialPressure(ValueMetaUtils.convertToFloatObj(factor.getValue(), valueMeta));
                    break;
                case "circulation_dopamine_dose":
                    builder.circulationDopamineDose(ValueMetaUtils.convertToFloatObj(factor.getValue(), valueMeta));
                    break;
                case "circulation_epinephrine_dose":
                    builder.circulationEpinephrineDose(ValueMetaUtils.convertToFloatObj(factor.getValue(), valueMeta));
                    break;
                case "circulation_norepinephrine_dose":
                    builder.circulationNorepinephrineDose(ValueMetaUtils.convertToFloatObj(factor.getValue(), valueMeta));
                    break;
                case "circulation_dobutamine_is_used":
                    builder.circulationDobutamineIsUsed(ValueMetaUtils.getBooleanObj(factor.getValue()));
                    break;
                case "glasgow_coma_scale":
                    builder.glasgowComaScale(ValueMetaUtils.convertToInt32Obj(factor.getValue(), valueMeta));
                    break;
                case "renal_creatinine":
                    builder.renalCreatinine(ValueMetaUtils.convertToFloatObj(factor.getValue(), valueMeta));
                    break;
                case "renal_urine_output":
                    builder.renalUrineOutput(ValueMetaUtils.convertToFloatObj(factor.getValue(), valueMeta));
                    break;
                default:
                    log.error("Unhandled sofa_param code in toEntity: {}", code);
                    ReturnCode rc = ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.UNEXPECTED_SOFA_SCORE_FACTOR, code
                    );
                    return new Pair<>(rc, null);
            }
        }

        // 设置最终总分（如果 proto 中已经存在）
        builder.sofaScore(scorePb.getSofaScore());

        // 返回成功
        ReturnCode ok = ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK);
        return new Pair<>(ok, builder.build());
    }

    /**
     * 将 scorerInfo 中的信息设置到实体 builder 中。
     */
    private static void setScorerInfo(SofaScore.SofaScoreBuilder builder, ScoreUtils.ScorerInfo scorerInfo) {
        if (scorerInfo == null) return;
        if (scorerInfo.pid != null) builder.pid(scorerInfo.pid);
        if (scorerInfo.deptId != null) builder.deptId(scorerInfo.deptId);
        if (scorerInfo.scoreTime != null) builder.scoreTime(scorerInfo.scoreTime);
        if (scorerInfo.scoredBy != null) builder.scoredBy(scorerInfo.scoredBy);
        if (scorerInfo.scoredByAccountName != null) builder.scoredByAccountName(scorerInfo.scoredByAccountName);
        if (scorerInfo.evalStartAt != null) builder.evalStartAt(scorerInfo.evalStartAt);
        if (scorerInfo.evalEndAt != null) builder.evalEndAt(scorerInfo.evalEndAt);

        if (scorerInfo.modifiedBy != null) builder.modifiedBy(scorerInfo.modifiedBy);
        if (scorerInfo.modifiedAt != null) builder.modifiedAt(scorerInfo.modifiedAt);
        if (scorerInfo.isDeleted != null) builder.isDeleted(scorerInfo.isDeleted);
        if (scorerInfo.deletedBy != null) builder.deletedBy(scorerInfo.deletedBy);
        if (scorerInfo.deletedByAccountName != null) builder.deletedByAccountName(scorerInfo.deletedByAccountName);
        if (scorerInfo.deletedAt != null) builder.deletedAt(scorerInfo.deletedAt);
    }

    public static Pair<ReturnCode, SOFAScorePB> toProto(
        SofaScoreMetaPB metaPb, SofaScoreRulesPB rulesPb, SofaScore sofaScore,
        List<String> statusCodeMsgList
    ) {
        SOFAScorePB.Builder builder = SOFAScorePB.newBuilder();

        // 构造 (code -> ValueMetaPB) 的映射，供取值/格式化时使用
        Map<String, ValueMetaPB> codeMetaMap = extractValueMetaMap(metaPb);

        // 添加各项 sofa_param
        ReturnCode rc = addSofaParam(builder, "pao2_fio2_ratio", sofaScore.getPao2Fio2Ratio(), codeMetaMap, statusCodeMsgList);
        if (rc.getCode() != 0) return new Pair<>(rc, null);

        rc = addSofaParam(builder, "respiratory_support", sofaScore.getRespiratorySupport(), codeMetaMap, statusCodeMsgList);
        if (rc.getCode() != 0) return new Pair<>(rc, null);

        rc = addSofaParam(builder, "platelet_count", sofaScore.getPlateletCount(), codeMetaMap, statusCodeMsgList);
        if (rc.getCode() != 0) return new Pair<>(rc, null);

        rc = addSofaParam(builder, "bilirubin", sofaScore.getBilirubin(), codeMetaMap, statusCodeMsgList);
        if (rc.getCode() != 0) return new Pair<>(rc, null);

        rc = addSofaParam(builder, "circulation_mean_arterial_pressure", sofaScore.getCirculationMeanArterialPressure(), codeMetaMap, statusCodeMsgList);
        if (rc.getCode() != 0) return new Pair<>(rc, null);

        rc = addSofaParam(builder, "circulation_dopamine_dose", sofaScore.getCirculationDopamineDose(), codeMetaMap, statusCodeMsgList);
        if (rc.getCode() != 0) return new Pair<>(rc, null);

        rc = addSofaParam(builder, "circulation_epinephrine_dose", sofaScore.getCirculationEpinephrineDose(), codeMetaMap, statusCodeMsgList);
        if (rc.getCode() != 0) return new Pair<>(rc, null);

        rc = addSofaParam(builder, "circulation_norepinephrine_dose", sofaScore.getCirculationNorepinephrineDose(), codeMetaMap, statusCodeMsgList);
        if (rc.getCode() != 0) return new Pair<>(rc, null);

        rc = addSofaParam(builder, "circulation_dobutamine_is_used", sofaScore.getCirculationDobutamineIsUsed(), codeMetaMap, statusCodeMsgList);
        if (rc.getCode() != 0) return new Pair<>(rc, null);

        rc = addSofaParam(builder, "glasgow_coma_scale", sofaScore.getGlasgowComaScale(), codeMetaMap, statusCodeMsgList);
        if (rc.getCode() != 0) return new Pair<>(rc, null);

        rc = addSofaParam(builder, "renal_creatinine", sofaScore.getRenalCreatinine(), codeMetaMap, statusCodeMsgList);
        if (rc.getCode() != 0) return new Pair<>(rc, null);

        rc = addSofaParam(builder, "renal_urine_output", sofaScore.getRenalUrineOutput(), codeMetaMap, statusCodeMsgList);
        if (rc.getCode() != 0) return new Pair<>(rc, null);

        // 将数据库中已有的 sofaScore 设置到 proto（此处仅为占位，真正计算时在 score() 方法中会覆盖）
        builder.setSofaScore(
            sofaScore.getSofaScore() == null ? 0 : sofaScore.getSofaScore()
        );

        // 调用 score(...) 进行重新评分，并返回
        SOFAScorePB initialPb = builder.build();
        return score(metaPb, rulesPb, initialPb, statusCodeMsgList);
    }

    /**
     * 添加单个 sofa_param 到 SOFAScorePB.Builder
     */
    private static ReturnCode addSofaParam(
        SOFAScorePB.Builder builder, String code, Object fieldValue,
        Map<String, ValueMetaPB> codeMetaMap,
        List<String> statusCodeMsgList
    ) {
        if (fieldValue == null) return ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK);

        ValueMetaPB valueMeta = codeMetaMap.get(code);
        if (valueMeta == null) {
            // 若 meta 里没有找到，按理应当是未定义的 code
            log.error("No ValueMetaPB found for code: {}", code);
            ReturnCode rc = ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.UNEXPECTED_SOFA_SCORE_FACTOR, code
            );
            return rc;
        }

        // 转为 GenericValuePB
        GenericValuePB genericValue = buildGenericValue(fieldValue, valueMeta);
        if (genericValue == null) {
            // 转换失败，可能是类型不匹配或其他问题
            log.error("Failed to convert fieldValue to GenericValuePB for code: {}", code);
            ReturnCode rc = ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.UNEXPECTED_SOFA_SCORE_FACTOR, code
            );
            return rc;
        }

        // 转为 DoctorScoreFactorPB
        DoctorScoreFactorPB factor = DoctorScoreFactorPB.newBuilder()
            .setCode(code)
            .setValue(genericValue)
            .setValueStr(ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta))
            .build();

        builder.addSofaParam(factor);

        return ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK);
    }

    /**
     * 根据 ValueMetaPB 将任意字段值转换为 GenericValuePB。
     */
    private static GenericValuePB buildGenericValue(Object fieldValue, ValueMetaPB valueMeta) {
        if (fieldValue == null) return null;

        switch (valueMeta.getValueType()) {
            case BOOL:
                // fieldValue 应该是 Boolean
                return GenericValuePB.newBuilder()
                    .setBoolVal((Boolean) fieldValue)
                    .build();
            case INT32:
                // fieldValue 应该是 Integer
                return GenericValuePB.newBuilder()
                    .setInt32Val((Integer) fieldValue)
                    .build();
            case FLOAT:
                // fieldValue 应该是 Float/Double
                // 如果是 Float，需要先转 double 再 set
                if (fieldValue instanceof Number) {
                    double dv = ((Number) fieldValue).doubleValue();
                    return ValueMetaUtils.getValue(valueMeta, dv);
                }
                break;
            default:
                log.warn("Unsupported valueType: {}", valueMeta.getValueType());
                break;
        }
        return null;
    }

    public static Pair<ReturnCode, SOFAScorePB> score(
        SofaScoreMetaPB metaPb, SofaScoreRulesPB rulesPb, SOFAScorePB scorePb,
        List<String> statusCodeMsgList
    ) {
        // 提取 (code -> ValueMetaPB) & (code -> FactorScoreRulePB) 方便后续计算
        Map<String, ValueMetaPB> codeMetaMap = extractValueMetaMap(metaPb);
        Map<String, FactorScoreRulePB> codeRuleMap = rulesPb.getRuleList().stream()
            .collect(Collectors.toMap(FactorScoreRulePB::getCode, r -> r, (r1, r2) -> r1));

        // 单项打分
        boolean hasRespiratorySupport = false;
        Map<String, DoctorScoreFactorPB> paramMap = new HashMap<>();
        for (DoctorScoreFactorPB factor : scorePb.getSofaParamList()) {
            String code = factor.getCode();
            ValueMetaPB valueMeta = codeMetaMap.get(code);
            if (valueMeta == null) {
                // 未定义的 code
                log.error("Unhandled Sofa factor code in score: {}", code);
                ReturnCode rc = ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.UNEXPECTED_SOFA_SCORE_FACTOR, code
                );
                return new Pair<>(rc, null);
            }

            int score = 0;
            if (code.equals("respiratory_support")) {
                // 处理呼吸支持的特殊逻辑
                hasRespiratorySupport = ValueMetaUtils.getBooleanObj(factor.getValue());
            } else if (code.equals("circulation_dobutamine_is_used")) {
                // 多巴酚丁胺使用与否
                score = ValueMetaUtils.getBooleanObj(factor.getValue()) ? 2 : 0;
            } else {
                FactorScoreRulePB rule = codeRuleMap.get(code);
                if (rule == null) {
                    log.error("Factor score rule not found for code: {}", code);
                    ReturnCode rc = ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.UNEXPECTED_SOFA_SCORE_FACTOR, code
                    );
                    return new Pair<>(rc, null);
                }
                score = ScoreUtils.calcFactorScore(code, factor.getValue(), valueMeta, rule);
            }

            DoctorScoreFactorPB newFactor = factor.toBuilder()
                .setValueStr(ValueMetaUtils.extractAndFormatParamValue(factor.getValue(), valueMeta))
                .setScore(score)
                .build();
            paramMap.put(code, newFactor);
        }

        // 呼吸
        int respScore = 0;
        {
            DoctorScoreFactorPB pao2Fio2Ratio = paramMap.get("pao2_fio2_ratio");
            if (pao2Fio2Ratio != null) {
                respScore = pao2Fio2Ratio.getScore();
                if (hasRespiratorySupport) {
                    if (respScore < 3) {
                        // 如果有呼吸支持，分数至少为3
                        respScore = 3;
                        paramMap.put("pao2_fio2_ratio",
                            pao2Fio2Ratio.toBuilder().setScore(respScore).build()
                        );
                    }
                } else {
                    if (respScore > 2) {
                        // 如果没有呼吸支持，分数不能大于2
                        respScore = 2;
                        paramMap.put("pao2_fio2_ratio",
                            pao2Fio2Ratio.toBuilder().setScore(respScore).build()
                        );
                    }
                }
            }
        }

        // 凝血
        int coagScore = 0;
        {
            DoctorScoreFactorPB plateletCount = paramMap.get("platelet_count");
            if (plateletCount != null) {
                coagScore = plateletCount.getScore();
            }
        }

        // 肝脏
        int liverScore = 0;
        {
            DoctorScoreFactorPB bilirubin = paramMap.get("bilirubin");
            if (bilirubin != null) {
                liverScore = bilirubin.getScore();
            }
        }

        // 循环
        int circulationScore = 0;
        {
            for (String code : List.of(
                "circulation_mean_arterial_pressure",
                "circulation_dopamine_dose",
                "circulation_epinephrine_dose",
                "circulation_norepinephrine_dose",
                "circulation_dobutamine_is_used"
            )) {
                DoctorScoreFactorPB factor = paramMap.get(code);
                if (factor != null) {
                    circulationScore = Math.max(circulationScore, factor.getScore());
                }
            }
        }

        // 神经
        int neuroScore = 0;
        {
            DoctorScoreFactorPB glasgowComaScale = paramMap.get("glasgow_coma_scale");
            if (glasgowComaScale != null) {
                neuroScore = glasgowComaScale.getScore();
            }
        }

        // 肾脏
        int renalScore = 0;
        {
            for (String code : List.of(
                "renal_creatinine",
                "renal_urine_output"
            )) {
                DoctorScoreFactorPB factor = paramMap.get(code);
                if (factor != null) {
                    renalScore = Math.max(renalScore, factor.getScore());
                }
            }
        }

        // 计算总分
        int totalSofaScore = respScore + coagScore + liverScore + circulationScore + neuroScore + renalScore;

        List<DoctorScoreFactorPB> newParamList = new ArrayList<>();
        for (DoctorScoreFactorPB factor : scorePb.getSofaParamList()) {
            String code = factor.getCode();
            DoctorScoreFactorPB newFactor = paramMap.get(code);
            if (newFactor != null) {
                newParamList.add(newFactor);
            } else {
                // 如果没有找到对应的因子，可能是未定义的 code
                log.error("Unhandled Sofa factor code in score: {}", code);
                ReturnCode rc = ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.UNEXPECTED_SOFA_SCORE_FACTOR, code
                );
                return new Pair<>(rc, null);
            }
        }
        // 重新构建 SOFAScorePB
        SOFAScorePB newScorePb = scorePb.toBuilder()
            .clearSofaParam()
            .addAllSofaParam(newParamList)
            .setSofaScore(totalSofaScore)
            .build();

        return new Pair<>(
            ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK),
            newScorePb
        );
    }

    public static SOFAScorePB mergeProto(
        SofaScoreMetaPB meta, SOFAScorePB origScorePb, SOFAScorePB newScorePb
    ) {
        Map<String, DoctorScoreFactorPB> origMap = origScorePb.getSofaParamList().stream()
            .collect(Collectors.toMap(DoctorScoreFactorPB::getCode, f -> f, (f1, f2) -> f1));
        Map<String, DoctorScoreFactorPB> newMap = newScorePb.getSofaParamList().stream()
            .collect(Collectors.toMap(DoctorScoreFactorPB::getCode, f -> f, (f1, f2) -> f1));

        List<DoctorScoreFactorPB> mergedList = new ArrayList<>();
        for (DoctorScoreFactorMetaPB factorMeta : meta.getSofaParamList()) {
            String code = factorMeta.getCode();
            DoctorScoreFactorPB newFactor = newMap.get(code);
            if (newFactor != null) {
                mergedList.add(newFactor);
                continue;
            }

            DoctorScoreFactorPB origFactor = origMap.get(code);
            if (origFactor != null) {
                mergedList.add(origFactor);
                continue;
            }
        }

        return SOFAScorePB.newBuilder()
            .addAllSofaParam(mergedList)
            .build();
    }

    /**
     * 从 SofaScoreMetaPB 提取出 (code -> ValueMetaPB) 的映射。
     */
    private static Map<String, ValueMetaPB> extractValueMetaMap(SofaScoreMetaPB metaPb) {
        Map<String, ValueMetaPB> result = new HashMap<>();
        for (DoctorScoreFactorMetaPB factorMeta : metaPb.getSofaParamList()) {
            result.put(factorMeta.getCode(), factorMeta.getValueMeta());
        }
        return result;
    }

    public SofaScorer(
        @Autowired ConfigProtoService protoService,
        @Autowired ScoreService scoreService
    ) {
        this.protoService = protoService;
        this.statusCodeMsgList = protoService.getConfig().getText().getStatusCodeMsgList();
        this.metaPb = protoService.getConfig().getDoctorScore().getSofaMeta();
        this.rulesPb = protoService.getConfig().getDoctorScore().getSofaRules();
        this.factorCodeMetaMap = extractValueMetaMap(metaPb);
        this.factorCodeRuleMap = rulesPb.getRuleList()
            .stream()
            .collect(Collectors.toMap(FactorScoreRulePB::getCode, Function.identity()));

        this.scoreService = scoreService;
    }

    public Pair<ReturnCode, SOFAScorePB> getSofaScoreFactors(
        Long pid, String deptId, LocalDateTime evalStartUtc, LocalDateTime evalEndUtc
    ) {
        List<DoctorScoreFactorPB> sofaFactors = new ArrayList<>();

        // 获取格拉斯哥评分评分
        Pair<ReturnCode, DiseaseItemDetailsPB> detailsPair = getSofaFactorDetails(
            pid, deptId, "glasgow_coma_scale", evalStartUtc, evalEndUtc
        );
        if (detailsPair == null) {
            return new Pair<>(
                ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK),
                SOFAScorePB.newBuilder().build()
            );
        }
        if (detailsPair.getFirst().getCode() != 0) {
            return new Pair<>(detailsPair.getFirst(), null);
        }
        if (detailsPair.getSecond() != null) {
            DiseaseItemDetailsPB details = detailsPair.getSecond();
            if (details.getOrigValueMeta() != null) {
                sofaFactors.add(DoctorScoreFactorPB.newBuilder()
                    .setCode("glasgow_coma_scale")
                    .setValue(details.getItem().getValue())
                    .setValueStr(details.getItem().getOrigValueStr())
                    .build());
            }
        }

        return new Pair<>(
            ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK),
            SOFAScorePB.newBuilder()
                .addAllSofaParam(sofaFactors)
                .build()
        );
    }

    public Pair<ReturnCode, DiseaseItemDetailsPB> getSofaFactorDetails(
        Long pid, String deptId, String factorCode,
        LocalDateTime evalStartUtc, LocalDateTime evalEndUtc
    ) {
        if (evalStartUtc == null || evalEndUtc == null) {
            log.error("evalStartUtc or evalEndUtc is null");
            return new Pair<>(
                ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList,
                    StatusCode.INVALID_TIME_RANGE
                ),
                null
            );
        }

        Pair<ValueMetaPB, List<TimedGenericValuePB>> origValuePair = null;
        if (factorCode.equals("pao2_fio2_ratio") || factorCode.equals("respiratory_support")) {
            return null;
        } else if (factorCode.equals("platelet_count") || factorCode.equals("bilirubin") ||
                   factorCode.equals("renal_creatinine") || factorCode.equals("renal_urine_output")) {
            return null;
        } else if (factorCode.equals("circulation_mean_arterial_pressure") ||
                   factorCode.equals("circulation_dopamine_dose") ||
                   factorCode.equals("circulation_epinephrine_dose") ||
                   factorCode.equals("circulation_norepinephrine_dose") ||
                   factorCode.equals("circulation_dobutamine_is_used")) {
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
        if (valueMeta == null || factorScoreRule == null) {
            log.error("ValueMetaPB or FactorScoreRulePB not found for factor code: {}", factorCode);
            return null;
        }

        int score = -1;
        float numericVal = 0f;
        GenericValuePB factorValue = null;
        String timeStr = "";
        for (TimedGenericValuePB timedValue : origValueList) {
            GenericValuePB tmpFactorValue = ValueMetaUtils.convertGenericValue(
                timedValue.getValue(), origValueMeta, valueMeta
            );
            int tmpScore = ScoreUtils.calcFactorScore(
                factorCode, tmpFactorValue, valueMeta, factorScoreRule
            );

            float tmpNumericVal = ValueMetaUtils.convertToFloatObj(
                timedValue.getValue(), origValueMeta
            );
            if (tmpScore > score || (tmpScore == score && tmpNumericVal > numericVal)) {
                score = tmpScore;
                numericVal = tmpNumericVal;
                factorValue = tmpFactorValue;
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
        return new Pair<>(
            ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK),
            diseaseItemDetails
        );
    }

    private final ConfigProtoService protoService;
    private final ScoreService scoreService;

    private List<String> statusCodeMsgList;
    private SofaScoreMetaPB metaPb;
    private SofaScoreRulesPB rulesPb;
    private Map<String, ValueMetaPB> factorCodeMetaMap;
    private Map<String, FactorScoreRulePB> factorCodeRuleMap;
}