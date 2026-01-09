package com.jingyicare.jingyi_icis_engine.service.doctors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDateTime;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisDoctorScore.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.doctors.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.doctors.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Slf4j
public class ApacheIIScorerTests extends TestsBase {
    public ApacheIIScorerTests(@Autowired ConfigProtoService protoService) {
        this.protoService = protoService;
        this.apacheMeta = protoService.getConfig().getDoctorScore().getApacheIiMeta();
        this.apacheRules = protoService.getConfig().getDoctorScore().getApacheIiRules();
        this.statusCodeMsgList = protoService.getConfig().getText().getStatusCodeMsgList();
        this.factorCodeMetaMap = ApacheIIScorer.extractValueMetaMap(apacheMeta);
    }

    @Test
    public void testProtoEntityConversion() {
        // 创建测试用的 ApacheIIScorePB
        ApacheIIScorePB.Builder apacheIiScoreBuilder = ApacheIIScorePB.newBuilder();

        // 设置 APS 参数，从 1 开始递增
        float paramValue = 1.0f;
        addApsParam(apacheIiScoreBuilder, "body_temperature", paramValue++, TypeEnumPB.FLOAT);
        addApsParam(apacheIiScoreBuilder, "mean_arterial_pressure", paramValue++, TypeEnumPB.FLOAT);
        addApsParam(apacheIiScoreBuilder, "heart_rate", paramValue++, TypeEnumPB.FLOAT);
        addApsParam(apacheIiScoreBuilder, "respiratory_rate", paramValue++, TypeEnumPB.FLOAT);
        addApsParam(apacheIiScoreBuilder, "fio2", paramValue++, TypeEnumPB.FLOAT);
        addApsParam(apacheIiScoreBuilder, "a_a_do2", paramValue++, TypeEnumPB.FLOAT);
        addApsParam(apacheIiScoreBuilder, "pao2", paramValue++, TypeEnumPB.FLOAT);
        addApsParam(apacheIiScoreBuilder, "ph", paramValue++, TypeEnumPB.FLOAT);
        addApsParam(apacheIiScoreBuilder, "hco3", paramValue++, TypeEnumPB.FLOAT);
        addApsParam(apacheIiScoreBuilder, "sodium", paramValue++, TypeEnumPB.FLOAT);
        addApsParam(apacheIiScoreBuilder, "potassium", paramValue++, TypeEnumPB.FLOAT);
        addApsParam(apacheIiScoreBuilder, "creatinine", paramValue++, TypeEnumPB.FLOAT);
        addApsParam(apacheIiScoreBuilder, "has_acute_renal_failure", true, TypeEnumPB.BOOL);
        addApsParam(apacheIiScoreBuilder, "hematocrit", paramValue++, TypeEnumPB.FLOAT);
        addApsParam(apacheIiScoreBuilder, "white_blood_cell_count", paramValue++, TypeEnumPB.FLOAT);
        addApsParam(apacheIiScoreBuilder, "glasgow_coma_scale", 12, TypeEnumPB.INT32);

        // 设置 CHC 参数，布尔值交替
        boolean boolValue = true;
        addChcParam(apacheIiScoreBuilder, "has_chronic_conditions", boolValue);
        addChcParam(apacheIiScoreBuilder, "cardiovascular_system", !boolValue);
        addChcParam(apacheIiScoreBuilder, "respiratory_system", boolValue);
        addChcParam(apacheIiScoreBuilder, "liver_system", !boolValue);
        addChcParam(apacheIiScoreBuilder, "kidney_system", boolValue);
        addChcParam(apacheIiScoreBuilder, "immune_dysfunction", !boolValue);
        addChcParam(apacheIiScoreBuilder, "non_operative_or_emergency_surgery", boolValue);

        // 设置年龄，病死率主要原因代码
        apacheIiScoreBuilder.setAge(43)
                .setIsOperative(true)
                .setIsEmergencyOperation(false)
                .setMortalityFactorCode("op_multiple_trauma");
        ApacheIIScorePB originalScorePb = apacheIiScoreBuilder.build();

        // 创建 ScorerInfo
        ScoreUtils.ScorerInfo scorerInfo = new ScoreUtils.ScorerInfo(
            123L, "DEPT001", LocalDateTime.now(), "doctor1", "Doctor One",
            LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(), "system",
            false, null, null, null
        );

        // 测试 toEntity
        Pair<ReturnCode, ApacheIIScore> toEntityResult = ApacheIIScorer.toEntity(
                apacheMeta, originalScorePb, scorerInfo, statusCodeMsgList);
        assertThat(toEntityResult.getFirst().getCode()).isEqualTo(0);

        ApacheIIScore scoreEntity = toEntityResult.getSecond();
        assertThat(scoreEntity).isNotNull();

        // 校验 ScorerInfo 字段
        assertThat(scoreEntity.getPid()).isEqualTo(scorerInfo.pid);
        assertThat(scoreEntity.getDeptId()).isEqualTo(scorerInfo.deptId);
        assertThat(scoreEntity.getScoreTime()).isEqualTo(scorerInfo.scoreTime);
        assertThat(scoreEntity.getScoredBy()).isEqualTo(scorerInfo.scoredBy);
        assertThat(scoreEntity.getScoredByAccountName()).isEqualTo(scorerInfo.scoredByAccountName);
        assertThat(scoreEntity.getModifiedAt()).isEqualTo(scorerInfo.modifiedAt);
        assertThat(scoreEntity.getModifiedBy()).isEqualTo(scorerInfo.modifiedBy);
        assertThat(scoreEntity.getIsDeleted()).isEqualTo(scorerInfo.isDeleted);

        // 校验 APS 参数
        assertThat(scoreEntity.getBodyTemperature()).isEqualTo(1.0f);
        assertThat(scoreEntity.getMeanArterialPressure()).isEqualTo(2.0f);
        assertThat(scoreEntity.getHeartRate()).isEqualTo(3.0f);
        assertThat(scoreEntity.getRespiratoryRate()).isEqualTo(4.0f);
        assertThat(scoreEntity.getFio2()).isEqualTo(5.0f);
        assertThat(scoreEntity.getAADo2()).isEqualTo(6.0f);
        assertThat(scoreEntity.getPao2()).isEqualTo(7.0f);
        assertThat(scoreEntity.getPh()).isEqualTo(8.0f);
        assertThat(scoreEntity.getHco3()).isEqualTo(9.0f);
        assertThat(scoreEntity.getSodium()).isEqualTo(10.0f);
        assertThat(scoreEntity.getPotassium()).isEqualTo(11.0f);
        assertThat(scoreEntity.getCreatinine()).isEqualTo(12.0f);
        assertThat(scoreEntity.getHasAcuteRenalFailure()).isTrue();
        assertThat(scoreEntity.getHematocrit()).isEqualTo(13.0f);
        assertThat(scoreEntity.getWhiteBloodCellCount()).isEqualTo(14.0f);
        assertThat(scoreEntity.getGlasgowComaScale()).isEqualTo(12);

        // 校验 CHC 参数
        assertThat(scoreEntity.getHasChronicConditions()).isTrue();
        assertThat(scoreEntity.getChcCardio()).isFalse();
        assertThat(scoreEntity.getChcResp()).isTrue();
        assertThat(scoreEntity.getChcLiver()).isFalse();
        assertThat(scoreEntity.getChcKidney()).isTrue();
        assertThat(scoreEntity.getChcImmune()).isFalse();
        assertThat(scoreEntity.getNonOperativeOrEmergencySurgery()).isTrue();

        assertThat(scoreEntity.getAge()).isEqualTo(43);
        assertThat(scoreEntity.getIsOperative()).isTrue();
        // assertThat(scoreEntity.getIsOperative()).isFalse();
        assertThat(scoreEntity.getIsEmergencyOperation()).isFalse();
        assertThat(scoreEntity.getMortalityFactorCode()).isEqualTo("op_multiple_trauma");

        // 测试 toProto
        Pair<ReturnCode, ApacheIIScorePB> toProtoResult = ApacheIIScorer.toProto(
                apacheMeta, apacheRules, scoreEntity, statusCodeMsgList);
        assertThat(toProtoResult.getFirst().getCode()).isEqualTo(0);

        ApacheIIScorePB newScorePb = toProtoResult.getSecond();
        assertThat(newScorePb).isNotNull();

        // 验证新生成的 ApacheIIScorePB 与原始的相似
        assertThat(newScorePb.getAge()).isEqualTo(originalScorePb.getAge());
        assertThat(newScorePb.getIsOperative()).isEqualTo(originalScorePb.getIsOperative());
        assertThat(newScorePb.getIsEmergencyOperation()).isEqualTo(originalScorePb.getIsEmergencyOperation());
        assertThat(newScorePb.getMortalityFactorCode()).isEqualTo(originalScorePb.getMortalityFactorCode());
        assertThat(newScorePb.getApsParamCount()).isEqualTo(originalScorePb.getApsParamCount());
        assertThat(newScorePb.getChcParamCount()).isEqualTo(originalScorePb.getChcParamCount());

        // 校验 APS 参数值
        assertThat(getApsParamValue(newScorePb, "body_temperature")).isEqualTo(1.0f);
        assertThat(getApsParamValue(newScorePb, "mean_arterial_pressure")).isEqualTo(2.0f);
        assertThat(getApsParamValue(newScorePb, "heart_rate")).isEqualTo(3.0f);
        assertThat(getApsParamValue(newScorePb, "respiratory_rate")).isEqualTo(4.0f);
        assertThat(getApsParamValue(newScorePb, "fio2")).isEqualTo(5.0f);
        assertThat(getApsParamValue(newScorePb, "a_a_do2")).isEqualTo(6.0f);
        assertThat(getApsParamValue(newScorePb, "pao2")).isEqualTo(7.0f);
        assertThat(getApsParamValue(newScorePb, "ph")).isEqualTo(8.0f);
        assertThat(getApsParamValue(newScorePb, "hco3")).isEqualTo(9.0f);
        assertThat(getApsParamValue(newScorePb, "sodium")).isEqualTo(10.0f);
        assertThat(getApsParamValue(newScorePb, "potassium")).isEqualTo(11.0f);
        assertThat(getApsParamValue(newScorePb, "creatinine")).isEqualTo(12.0f);
        assertThat(getApsParamBoolValue(newScorePb, "has_acute_renal_failure")).isTrue();
        assertThat(getApsParamValue(newScorePb, "hematocrit")).isEqualTo(13.0f);
        assertThat(getApsParamValue(newScorePb, "white_blood_cell_count")).isEqualTo(14.0f);
        assertThat(getApsParamIntValue(newScorePb, "glasgow_coma_scale")).isEqualTo(12);

        // 校验 CHC 参数值
        assertThat(getChcParamBoolValue(newScorePb, "has_chronic_conditions")).isTrue();
        assertThat(getChcParamBoolValue(newScorePb, "cardiovascular_system")).isFalse();
        assertThat(getChcParamBoolValue(newScorePb, "respiratory_system")).isTrue();
        assertThat(getChcParamBoolValue(newScorePb, "liver_system")).isFalse();
        assertThat(getChcParamBoolValue(newScorePb, "kidney_system")).isTrue();
        assertThat(getChcParamBoolValue(newScorePb, "immune_dysfunction")).isFalse();
        assertThat(getChcParamBoolValue(newScorePb, "non_operative_or_emergency_surgery")).isTrue();
    }

    @Test
    public void testApacheScore() {
        // 创建 ApacheIIScorePB
        ApacheIIScorePB.Builder apacheIiScoreBuilder = ApacheIIScorePB.newBuilder();

        // 设置 APS 参数，赋予特定的值以便验证分数
        addApsParam(apacheIiScoreBuilder, "body_temperature", 39.0f, TypeEnumPB.FLOAT); // 假设分数 3
        addApsParam(apacheIiScoreBuilder, "mean_arterial_pressure", 130.0f, TypeEnumPB.FLOAT); // 假设分数 3
        addApsParam(apacheIiScoreBuilder, "heart_rate", 110.0f, TypeEnumPB.FLOAT); // 假设分数 2
        addApsParam(apacheIiScoreBuilder, "respiratory_rate", 25.0f, TypeEnumPB.FLOAT); // 假设分数 1
        addApsParam(apacheIiScoreBuilder, "fio2", 0.4f, TypeEnumPB.FLOAT); // FiO2 < 0.5，使用 a_a_do2
        addApsParam(apacheIiScoreBuilder, "a_a_do2", 200.0f, TypeEnumPB.FLOAT); // 假设分数 2
        addApsParam(apacheIiScoreBuilder, "pao2", 70.0f, TypeEnumPB.FLOAT); // 应被忽略
        addApsParam(apacheIiScoreBuilder, "ph", 7.45f, TypeEnumPB.FLOAT); // 假设分数 0
        addApsParam(apacheIiScoreBuilder, "hco3", 22.0f, TypeEnumPB.FLOAT); // 应被忽略（因 pH 存在）
        addApsParam(apacheIiScoreBuilder, "sodium", 145.0f, TypeEnumPB.FLOAT); // 假设分数 0
        addApsParam(apacheIiScoreBuilder, "potassium", 4.5f, TypeEnumPB.FLOAT); // 假设分数 0
        addApsParam(apacheIiScoreBuilder, "creatinine", 50.0f, TypeEnumPB.FLOAT); // 假设分数 2
        addApsParam(apacheIiScoreBuilder, "has_acute_renal_failure", true, TypeEnumPB.BOOL); // 肌酐分数翻倍
        addApsParam(apacheIiScoreBuilder, "hematocrit", 40.0f, TypeEnumPB.FLOAT); // 假设分数 0
        addApsParam(apacheIiScoreBuilder, "white_blood_cell_count", 10.0f, TypeEnumPB.FLOAT); // 假设分数 0
        addApsParam(apacheIiScoreBuilder, "glasgow_coma_scale", 15, TypeEnumPB.INT32); // 分数 0 (15 - 15)

        // 设置 CHC 参数，模拟慢性疾病和急诊情况
        addChcParam(apacheIiScoreBuilder, "has_chronic_conditions", true);
        addChcParam(apacheIiScoreBuilder, "cardiovascular_system", true);
        addChcParam(apacheIiScoreBuilder, "respiratory_system", false);
        addChcParam(apacheIiScoreBuilder, "liver_system", false);
        addChcParam(apacheIiScoreBuilder, "kidney_system", false);
        addChcParam(apacheIiScoreBuilder, "immune_dysfunction", false);
        addChcParam(apacheIiScoreBuilder, "non_operative_or_emergency_surgery", true);

        // 设置年龄
        apacheIiScoreBuilder.setAge(43); // 假设年龄分数为 2（基于常见 Apache II 规则）

        ApacheIIScorePB scorePb = apacheIiScoreBuilder.build();

        // 调用 score 方法
        Pair<ReturnCode, ApacheIIScorePB> scoreResult = ApacheIIScorer.score(
                apacheMeta, apacheRules, scorePb, statusCodeMsgList);
        assertThat(scoreResult.getFirst().getCode()).isEqualTo(0);

        ApacheIIScorePB newScorePb = scoreResult.getSecond();
        assertThat(newScorePb).isNotNull();

        // 验证每个 APS 参数的分数
        assertThat(getApsParamScore(newScorePb, "body_temperature")).isEqualTo(3); // 39.0°C
        assertThat(getApsParamScore(newScorePb, "mean_arterial_pressure")).isEqualTo(3); // 130 mmHg
        assertThat(getApsParamScore(newScorePb, "heart_rate")).isEqualTo(2); // 110 bpm
        assertThat(getApsParamScore(newScorePb, "respiratory_rate")).isEqualTo(1); // 25 bpm
        assertThat(getApsParamScore(newScorePb, "fio2")).isEqualTo(2); // FiO2 < 0.5，使用 a_a_do2 分数
        assertThat(getApsParamScore(newScorePb, "a_a_do2")).isEqualTo(0); // 被忽略
        assertThat(getApsParamScore(newScorePb, "pao2")).isEqualTo(0); // 被忽略
        assertThat(getApsParamScore(newScorePb, "ph")).isEqualTo(0); // 7.45
        assertThat(getApsParamScore(newScorePb, "hco3")).isEqualTo(0); // 被忽略（因 pH 存在）
        assertThat(getApsParamScore(newScorePb, "sodium")).isEqualTo(0); // 145 mmol/L
        assertThat(getApsParamScore(newScorePb, "potassium")).isEqualTo(0); // 4.5 mmol/L
        assertThat(getApsParamScore(newScorePb, "creatinine")).isEqualTo(4); // 1.5 mg/dL，分数 2 * 2（因急性肾衰竭）
        assertThat(getApsParamScore(newScorePb, "has_acute_renal_failure")).isEqualTo(0); // 无分数
        assertThat(getApsParamScore(newScorePb, "hematocrit")).isEqualTo(0); // 40%
        assertThat(getApsParamScore(newScorePb, "white_blood_cell_count")).isEqualTo(0); // 10.0 x10^9/L
        assertThat(getApsParamScore(newScorePb, "glasgow_coma_scale")).isEqualTo(0); // 15 - 15 = 0

        // 验证 APS 总分
        // 3 + 3 + 2 + 1 + 2 + 0 + 0 + 0 + 0 + 0 + 0 + 4 + 0 + 0 + 0 + 0 = 15
        assertThat(newScorePb.getApsScore()).isEqualTo(15);

        // 验证年龄分数
        assertThat(newScorePb.getAgeScore()).isEqualTo(0); // 43 岁，Apache II 年龄分数通常为 0

        // 验证 CHC 分数
        // 有慢性疾病（cardiovascular_system = true）且为急诊，分数为 5
        assertThat(newScorePb.getChcScore()).isEqualTo(5);

        // 验证总分
        // aps_score (15) + age_score (0) + chc_score (5) = 20
        assertThat(newScorePb.getApacheIiScore()).isEqualTo(20);
    }

    @Test
    public void testApsFio2Score() {
        // 测试 FiO2 分数逻辑：FiO2 < 0.5 使用 a_a_do2 分数，FiO2 >= 0.5 使用 pao2 分数

        // 测试用例 1：FiO2 < 0.5
        ApacheIIScorePB.Builder apacheIiScoreBuilder = ApacheIIScorePB.newBuilder();
        addApsParam(apacheIiScoreBuilder, "fio2", 0.4f, TypeEnumPB.FLOAT); // FiO2 < 0.5
        addApsParam(apacheIiScoreBuilder, "a_a_do2", 200.0f, TypeEnumPB.FLOAT); // 假设分数 2
        addApsParam(apacheIiScoreBuilder, "pao2", 70.0f, TypeEnumPB.FLOAT); // 应被忽略

        ApacheIIScorePB scorePb = apacheIiScoreBuilder.build();

        Pair<ReturnCode, ApacheIIScorePB> scoreResult = ApacheIIScorer.score(
                apacheMeta, apacheRules, scorePb, statusCodeMsgList);
        assertThat(scoreResult.getFirst().getCode()).isEqualTo(0);

        ApacheIIScorePB newScorePb = scoreResult.getSecond();
        assertThat(newScorePb).isNotNull();

        // 验证 FiO2 分数使用 a_a_do2
        assertThat(getApsParamScore(newScorePb, "fio2")).isEqualTo(2);
        assertThat(getApsParamScore(newScorePb, "a_a_do2")).isEqualTo(0); // 被忽略
        assertThat(getApsParamScore(newScorePb, "pao2")).isEqualTo(0); // 被忽略
        assertThat(newScorePb.getApsScore()).isEqualTo(2); // 仅 FiO2 贡献分数

        // 测试用例 2：FiO2 >= 0.5
        apacheIiScoreBuilder.clear();
        addApsParam(apacheIiScoreBuilder, "fio2", 0.6f, TypeEnumPB.FLOAT); // FiO2 >= 0.5
        addApsParam(apacheIiScoreBuilder, "a_a_do2", 200.0f, TypeEnumPB.FLOAT); // 应被忽略
        addApsParam(apacheIiScoreBuilder, "pao2", 60.0f, TypeEnumPB.FLOAT); // 假设分数 3

        scorePb = apacheIiScoreBuilder.build();
        scoreResult = ApacheIIScorer.score(apacheMeta, apacheRules, scorePb, statusCodeMsgList);
        assertThat(scoreResult.getFirst().getCode()).isEqualTo(0);

        newScorePb = scoreResult.getSecond();
        assertThat(newScorePb).isNotNull();

        // 验证 FiO2 分数使用 pao2
        assertThat(getApsParamScore(newScorePb, "fio2")).isEqualTo(3);
        assertThat(getApsParamScore(newScorePb, "a_a_do2")).isEqualTo(0); // 被忽略
        assertThat(getApsParamScore(newScorePb, "pao2")).isEqualTo(0); // 被忽略
        assertThat(newScorePb.getApsScore()).isEqualTo(3); // 仅 FiO2 贡献分数
    }

    @Test
    public void testApsPhScore() {
        // 测试 pH 和 HCO3 分数逻辑：pH 存在时忽略 HCO3，否则使用 HCO3 分数

        // 测试用例 1：pH 和 HCO3 同时存在
        ApacheIIScorePB.Builder apacheIiScoreBuilder = ApacheIIScorePB.newBuilder();
        addApsParam(apacheIiScoreBuilder, "ph", 7.45f, TypeEnumPB.FLOAT); // 假设分数 0
        addApsParam(apacheIiScoreBuilder, "hco3", 22.0f, TypeEnumPB.FLOAT); // 应被忽略

        ApacheIIScorePB scorePb = apacheIiScoreBuilder.build();

        Pair<ReturnCode, ApacheIIScorePB> scoreResult = ApacheIIScorer.score(
                apacheMeta, apacheRules, scorePb, statusCodeMsgList);
        assertThat(scoreResult.getFirst().getCode()).isEqualTo(0);

        ApacheIIScorePB newScorePb = scoreResult.getSecond();
        assertThat(newScorePb).isNotNull();

        // 验证 pH 分数优先
        assertThat(getApsParamScore(newScorePb, "ph")).isEqualTo(0);
        assertThat(getApsParamScore(newScorePb, "hco3")).isEqualTo(0); // 被忽略
        assertThat(newScorePb.getApsScore()).isEqualTo(0); // 无分数贡献

        // 测试用例 2：仅 HCO3 存在
        apacheIiScoreBuilder.clear();
        addApsParam(apacheIiScoreBuilder, "hco3", 18.0f, TypeEnumPB.FLOAT); // 假设分数 2

        scorePb = apacheIiScoreBuilder.build();
        scoreResult = ApacheIIScorer.score(apacheMeta, apacheRules, scorePb, statusCodeMsgList);
        assertThat(scoreResult.getFirst().getCode()).isEqualTo(0);

        newScorePb = scoreResult.getSecond();
        assertThat(newScorePb).isNotNull();

        // 验证 HCO3 分数
        assertThat(getApsParamScore(newScorePb, "hco3")).isEqualTo(2);
        assertThat(getApsParamScore(newScorePb, "ph")).isEqualTo(0); // 未设置
        assertThat(newScorePb.getApsScore()).isEqualTo(2); // 仅 HCO3 贡献分数
    }

    @Test
    public void testCreatinineScore() {
        // 测试肌酐分数逻辑：有急性肾衰竭时分数翻倍

        // 测试用例 1：无急性肾衰竭
        ApacheIIScorePB.Builder apacheIiScoreBuilder = ApacheIIScorePB.newBuilder();
        addApsParam(apacheIiScoreBuilder, "creatinine", 1.5f, TypeEnumPB.FLOAT); // 假设分数 2
        addApsParam(apacheIiScoreBuilder, "has_acute_renal_failure", false, TypeEnumPB.BOOL);

        ApacheIIScorePB scorePb = apacheIiScoreBuilder.build();

        Pair<ReturnCode, ApacheIIScorePB> scoreResult = ApacheIIScorer.score(
                apacheMeta, apacheRules, scorePb, statusCodeMsgList);
        assertThat(scoreResult.getFirst().getCode()).isEqualTo(0);

        ApacheIIScorePB newScorePb = scoreResult.getSecond();
        assertThat(newScorePb).isNotNull();

        // 验证肌酐分数
        assertThat(getApsParamScore(newScorePb, "creatinine")).isEqualTo(2);
        assertThat(getApsParamScore(newScorePb, "has_acute_renal_failure")).isEqualTo(0);
        assertThat(newScorePb.getApsScore()).isEqualTo(2); // 仅肌酐贡献分数

        // 测试用例 2：有急性肾衰竭
        apacheIiScoreBuilder.clear();
        addApsParam(apacheIiScoreBuilder, "creatinine", 1.5f, TypeEnumPB.FLOAT); // 基础分数 2
        addApsParam(apacheIiScoreBuilder, "has_acute_renal_failure", true, TypeEnumPB.BOOL);

        scorePb = apacheIiScoreBuilder.build();
        scoreResult = ApacheIIScorer.score(apacheMeta, apacheRules, scorePb, statusCodeMsgList);
        assertThat(scoreResult.getFirst().getCode()).isEqualTo(0);

        newScorePb = scoreResult.getSecond();
        assertThat(newScorePb).isNotNull();

        // 验证肌酐分数翻倍
        assertThat(getApsParamScore(newScorePb, "creatinine")).isEqualTo(4); // 2 * 2
        assertThat(getApsParamScore(newScorePb, "has_acute_renal_failure")).isEqualTo(0);
        assertThat(newScorePb.getApsScore()).isEqualTo(4); // 仅肌酐贡献分数
    }

    @Test
    public void testMortalityRate() {
        // 创建 ApacheIIScorePB
        ApacheIIScorePB.Builder apacheIiScoreBuilder = ApacheIIScorePB.newBuilder();

        // 设置 apache_ii_score = 0，不设置 APS 和 CHC 参数
        apacheIiScoreBuilder.setApacheIiScore(0);

        // 设置年龄为 43（不影响病死率，因为 age_score = 0）
        apacheIiScoreBuilder.setAge(43);

        // 勾选非手术
        apacheIiScoreBuilder.setIsOperative(false);
        apacheIiScoreBuilder.setMortalityFactorCode("asthma_or_allergy");

        ApacheIIScorePB scorePb = apacheIiScoreBuilder.build();

        // 调用 score 方法
        Pair<ReturnCode, ApacheIIScorePB> scoreResult = ApacheIIScorer.score(
                apacheMeta, apacheRules, scorePb, statusCodeMsgList);
        assertThat(scoreResult.getFirst().getCode()).isEqualTo(0);

        ApacheIIScorePB newScorePb = scoreResult.getSecond();
        assertThat(newScorePb).isNotNull();

        // 计算预期病死率
        // 系数来自 ApacheIIScoreMetaPB（之前提供的配置）
        float coefSum = -3.517f + 0.146f * 0 - 2.108f; // apache_ii_score = 0

        // 预期病死率：1 / (1 + e^(-coefSum))
        float expectedMortalityRate = (float) (1.0 / (1.0 + Math.exp(-coefSum)));

        // 验证病死率（允许浮点数误差）
        assertThat(newScorePb.getPredictedMortalityRate()).isCloseTo(expectedMortalityRate, within(0.0001f));

        // 验证其他分数
        assertThat(newScorePb.getApsScore()).isEqualTo(0);
        assertThat(newScorePb.getAgeScore()).isEqualTo(0);
        assertThat(newScorePb.getChcScore()).isEqualTo(0);
        assertThat(newScorePb.getApacheIiScore()).isEqualTo(0);
    }

    // 辅助方法：添加 APS 参数
    private void addApsParam(ApacheIIScorePB.Builder builder, String code, Object value, TypeEnumPB type) {
        ValueMetaPB valueMeta = apacheMeta.getApsParamList().stream()
                .filter(f -> f.getCode().equals(code))
                .map(DoctorScoreFactorMetaPB::getValueMeta)
                .findFirst()
                .orElse(ValueMetaPB.newBuilder().setValueType(type).build());

        GenericValuePB genericValue;
        switch (type) {
            case FLOAT:
                genericValue = ValueMetaUtils.getValue(valueMeta, ((Float) value).doubleValue());
                break;
            case INT32:
                genericValue = ValueMetaUtils.getValue(valueMeta, ((Integer) value).doubleValue());
                break;
            case BOOL:
                genericValue = GenericValuePB.newBuilder().setBoolVal((Boolean) value).build();
                break;
            default:
                throw new IllegalArgumentException("Unsupported type: " + type);
        }

        builder.addApsParam(DoctorScoreFactorPB.newBuilder()
                .setCode(code)
                .setValue(genericValue)
                .setValueStr(ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta))
                .build());
    }

    // 辅助方法：添加 CHC 参数
    private void addChcParam(ApacheIIScorePB.Builder builder, String code, boolean value) {
        ValueMetaPB valueMeta = apacheMeta.getChcParamList().stream()
                .filter(f -> f.getCode().equals(code))
                .map(DoctorScoreFactorMetaPB::getValueMeta)
                .findFirst()
                .orElse(ValueMetaPB.newBuilder().setValueType(TypeEnumPB.BOOL).build());

        GenericValuePB genericValue = GenericValuePB.newBuilder().setBoolVal(value).build();
        builder.addChcParam(DoctorScoreFactorPB.newBuilder()
                .setCode(code)
                .setValue(genericValue)
                .setValueStr(ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta))
                .build());
    }

    // 辅助方法：获取 APS 参数值（浮点数）
    private float getApsParamValue(ApacheIIScorePB scorePb, String code) {
        return scorePb.getApsParamList().stream()
                .filter(f -> f.getCode().equals(code))
                .findFirst()
                .map(f -> f.getValue().getFloatVal())
                .orElse(0.0f);
    }

    // 辅助方法：获取 APS 参数值（布尔值）
    private boolean getApsParamBoolValue(ApacheIIScorePB scorePb, String code) {
        return scorePb.getApsParamList().stream()
                .filter(f -> f.getCode().equals(code))
                .findFirst()
                .map(f -> f.getValue().getBoolVal())
                .orElse(false);
    }

    // 辅助方法：获取 APS 参数值（整数）
    private int getApsParamIntValue(ApacheIIScorePB scorePb, String code) {
        return scorePb.getApsParamList().stream()
                .filter(f -> f.getCode().equals(code))
                .findFirst()
                .map(f -> f.getValue().getInt32Val())
                .orElse(0);
    }

    // 辅助方法：获取 CHC 参数值（布尔值）
    private boolean getChcParamBoolValue(ApacheIIScorePB scorePb, String code) {
        return scorePb.getChcParamList().stream()
                .filter(f -> f.getCode().equals(code))
                .findFirst()
                .map(f -> f.getValue().getBoolVal())
                .orElse(false);
    }

    // 辅助方法：获取 APS 参数分数
    private int getApsParamScore(ApacheIIScorePB scorePb, String code) {
        return scorePb.getApsParamList().stream()
                .filter(f -> f.getCode().equals(code))
                .findFirst()
                .map(DoctorScoreFactorPB::getScore)
                .orElse(0);
    }

    final private ConfigProtoService protoService;
    final private ApacheIIScoreMetaPB apacheMeta;
    final private ApacheIIScoreRulesPB apacheRules;
    final private List<String> statusCodeMsgList;
    final private Map<String, ValueMetaPB> factorCodeMetaMap;
}