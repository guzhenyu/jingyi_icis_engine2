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
import com.jingyicare.jingyi_icis_engine.repository.doctors.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Slf4j
public class SofaScorerTests extends TestsBase {
    @Autowired
    public SofaScorerTests(ConfigProtoService protoService) {
        this.sofaMeta = protoService.getConfig().getDoctorScore().getSofaMeta();
        this.sofaRules = protoService.getConfig().getDoctorScore().getSofaRules();
        this.statusCodeMsgList = protoService.getConfig().getText().getStatusCodeMsgList();
    }

    @Test
    public void testToEntityAndToProto() {
        // 1) 构造一个示例的 SOFAScorePB
        SOFAScorePB.Builder pbBuilder = SOFAScorePB.newBuilder();

        // 下面是示例的赋值，可根据您在 sofa_rules 中的区间自行调整
        addSofaParam(pbBuilder, "pao2_fio2_ratio", 85.0f, TypeEnumPB.FLOAT); // 测试呼吸
        addSofaParam(pbBuilder, "respiratory_support", true, TypeEnumPB.BOOL);
        addSofaParam(pbBuilder, "platelet_count", 100.0f, TypeEnumPB.FLOAT);
        addSofaParam(pbBuilder, "bilirubin", 25.0f, TypeEnumPB.FLOAT);
        addSofaParam(pbBuilder, "circulation_mean_arterial_pressure", 65.0f, TypeEnumPB.FLOAT);
        addSofaParam(pbBuilder, "circulation_dopamine_dose", 6.0f, TypeEnumPB.FLOAT);
        addSofaParam(pbBuilder, "circulation_epinephrine_dose", 0.0f, TypeEnumPB.FLOAT);
        addSofaParam(pbBuilder, "circulation_norepinephrine_dose", 0.2f, TypeEnumPB.FLOAT);
        addSofaParam(pbBuilder, "circulation_dobutamine_is_used", false, TypeEnumPB.BOOL);
        addSofaParam(pbBuilder, "glasgow_coma_scale", 12, TypeEnumPB.INT32);
        addSofaParam(pbBuilder, "renal_creatinine", 250.0f, TypeEnumPB.FLOAT);
        addSofaParam(pbBuilder, "renal_urine_output", 400.0f, TypeEnumPB.FLOAT);

        // 先不计算分数，手动设置 sofa_score = 0
        pbBuilder.setSofaScore(0);

        // 构建 proto
        SOFAScorePB originalPb = pbBuilder.build();

        // 2) toEntity
        ScoreUtils.ScorerInfo scorerInfo = new ScoreUtils.ScorerInfo(
            999L, "ICU-01", LocalDateTime.now(),
            "tester", "Test Doctor",
            LocalDateTime.now(), LocalDateTime.now(),
            LocalDateTime.now(), "admin",
            false, null, null, null
        );
        Pair<ReturnCode, SofaScore> entityResult = SofaScorer.toEntity(
            sofaMeta, originalPb, scorerInfo, statusCodeMsgList
        );
        assertThat(entityResult.getFirst().getCode()).isEqualTo(0);
        SofaScore sofaEntity = entityResult.getSecond();
        assertThat(sofaEntity).isNotNull();

        // 校验字段
        assertThat(sofaEntity.getPao2Fio2Ratio()).isEqualTo(85.0f);
        assertThat(sofaEntity.getRespiratorySupport()).isTrue();
        assertThat(sofaEntity.getPlateletCount()).isEqualTo(100.0f);
        assertThat(sofaEntity.getBilirubin()).isEqualTo(25.0f);
        assertThat(sofaEntity.getCirculationMeanArterialPressure()).isEqualTo(65.0f);
        assertThat(sofaEntity.getCirculationDopamineDose()).isEqualTo(6.0f);
        assertThat(sofaEntity.getCirculationEpinephrineDose()).isEqualTo(0.0f);
        assertThat(sofaEntity.getCirculationNorepinephrineDose()).isEqualTo(0.2f);
        assertThat(sofaEntity.getCirculationDobutamineIsUsed()).isFalse();
        assertThat(sofaEntity.getGlasgowComaScale()).isEqualTo(12);
        assertThat(sofaEntity.getRenalCreatinine()).isEqualTo(250.0f);
        assertThat(sofaEntity.getRenalUrineOutput()).isEqualTo(400.0f);

        // 3) 再用 toProto
        Pair<ReturnCode, SOFAScorePB> protoResult = SofaScorer.toProto(
            sofaMeta, sofaRules, sofaEntity, statusCodeMsgList
        );
        assertThat(protoResult.getFirst().getCode()).isEqualTo(0);

        SOFAScorePB newPb = protoResult.getSecond();
        // 检查几个关键字段是否能还原
        assertThat(getParamFloatValue(newPb, "pao2_fio2_ratio")).isEqualTo(85.0f);
        assertThat(getParamBoolValue(newPb, "respiratory_support")).isTrue();
        assertThat(getParamFloatValue(newPb, "platelet_count")).isEqualTo(100.0f);
        assertThat(getParamFloatValue(newPb, "bilirubin")).isEqualTo(25.0f);
        assertThat(getParamFloatValue(newPb, "circulation_mean_arterial_pressure")).isEqualTo(65.0f);
        assertThat(getParamFloatValue(newPb, "circulation_dopamine_dose")).isEqualTo(6.0f);
        assertThat(getParamFloatValue(newPb, "circulation_epinephrine_dose")).isEqualTo(0.0f);
        assertThat(getParamFloatValue(newPb, "circulation_norepinephrine_dose")).isEqualTo(0.2f);
        assertThat(getParamBoolValue(newPb, "circulation_dobutamine_is_used")).isFalse();
        assertThat(getParamIntValue(newPb, "glasgow_coma_scale")).isEqualTo(12);
        assertThat(getParamFloatValue(newPb, "renal_creatinine")).isEqualTo(250.0f);
        assertThat(getParamFloatValue(newPb, "renal_urine_output")).isEqualTo(400.0f);
    }

    @Test
    public void testSofaScoreLogic() {
        // 构造一个 SOFAScorePB
        SOFAScorePB.Builder pbBuilder = SOFAScorePB.newBuilder();
        // 呼吸(2)：p/f = 90 (<100 =>4分)
        addSofaParam(pbBuilder, "pao2_fio2_ratio", 90.0f, TypeEnumPB.FLOAT);
        // 无呼吸机 / 无机械通气
        addSofaParam(pbBuilder, "respiratory_support", false, TypeEnumPB.BOOL);

        // 凝血(2)：platelet_count = 80 (<100 =>2分)
        addSofaParam(pbBuilder, "platelet_count", 80.0f, TypeEnumPB.FLOAT);

        // 肝脏(4)：bilirubin = 210 (>=204 =>4分，示例)
        addSofaParam(pbBuilder, "bilirubin", 210.0f, TypeEnumPB.FLOAT);

        // 循环(3)：MAP=60 (<70 =>1分)，多巴胺=10(3分)，去甲肾上腺素=0(0分)，肾上腺素=0(0分)，多巴酚丁胺=未使用(0分)
        addSofaParam(pbBuilder, "circulation_mean_arterial_pressure", 60.0f, TypeEnumPB.FLOAT);
        addSofaParam(pbBuilder, "circulation_dopamine_dose", 10.0f, TypeEnumPB.FLOAT);
        addSofaParam(pbBuilder, "circulation_epinephrine_dose", 0.0f, TypeEnumPB.FLOAT);
        addSofaParam(pbBuilder, "circulation_norepinephrine_dose", 0.0f, TypeEnumPB.FLOAT);
        addSofaParam(pbBuilder, "circulation_dobutamine_is_used", false, TypeEnumPB.BOOL);

        // 神经：GCS=10 => 2分 (示例： <=5=>4分, 6~9=>3分, 10~12=>2分, 13~14=>1分,15=>0分)
        addSofaParam(pbBuilder, "glasgow_coma_scale", 10, TypeEnumPB.INT32);

        // 肾脏(4)：creatinine=450(>=441 =>4分)，尿量=600(>=500 =>0分)
        addSofaParam(pbBuilder, "renal_creatinine", 450.0f, TypeEnumPB.FLOAT);
        addSofaParam(pbBuilder, "renal_urine_output", 600.0f, TypeEnumPB.FLOAT);

        // 构建
        SOFAScorePB initialPb = pbBuilder.build();

        // 执行打分
        Pair<ReturnCode, SOFAScorePB> scoreResult = SofaScorer.score(
            sofaMeta, sofaRules, initialPb, statusCodeMsgList
        );
        assertThat(scoreResult.getFirst().getCode()).isEqualTo(0);
        SOFAScorePB scoredPb = scoreResult.getSecond();

        // 分项分数检查（实际值需与 sofa_rules 中配置一致）
        assertThat(getParamScore(scoredPb, "pao2_fio2_ratio"))
            .as("p/f=90 => 4分，未安装呼吸装置设置为2分").isEqualTo(2);
        assertThat(getParamScore(scoredPb, "respiratory_support"))
            .as("呼吸支持 不单独计分").isEqualTo(0);

        assertThat(getParamScore(scoredPb, "platelet_count"))
            .as("platelet=80 => 2分").isEqualTo(2);
        assertThat(getParamScore(scoredPb, "bilirubin"))
            .as("bilirubin=210 => 4分").isEqualTo(4);

        // 循环系统，MAP=60 =>1分，dopamine => 3分，其它血管活性药=0 => 0分 => 取max =>3
        assertThat(getParamScore(scoredPb, "circulation_mean_arterial_pressure"))
            .as("MAP=60 => 1分").isEqualTo(1);
        assertThat(getParamScore(scoredPb, "circulation_dopamine_dose"))
            .as("5 < 10(dopamine) < 15 => 3分").isEqualTo(3);
        assertThat(getParamScore(scoredPb, "circulation_epinephrine_dose")).isEqualTo(0);
        assertThat(getParamScore(scoredPb, "circulation_norepinephrine_dose")).isEqualTo(0);
        assertThat(getParamScore(scoredPb, "circulation_dobutamine_is_used")).isEqualTo(0);

        // 神经：GCS=10 =>2分
        assertThat(getParamScore(scoredPb, "glasgow_coma_scale")).isEqualTo(2);

        // 肾脏： creatinine=450 =>4分,  urine_output=600 =>0分 => 取最大4
        assertThat(getParamScore(scoredPb, "renal_creatinine"))
            .as("Cr=450 => 4分").isEqualTo(4);
        assertThat(getParamScore(scoredPb, "renal_urine_output"))
            .as("尿量=600 => 0分").isEqualTo(0);

        // 总分 = 呼吸2 + 凝血2 + 肝脏4 + 循环3 + 神经2 + 肾脏4 = 17
        assertThat(scoredPb.getSofaScore()).isEqualTo(17);
    }

    @Test
    public void testSofaScoreRespRatio() {
        // 构造一个 SOFAScorePB
        SOFAScorePB.Builder pbBuilder = SOFAScorePB.newBuilder();
        // 呼吸(2)：p/f = 90 (<100 =>4分)
        addSofaParam(pbBuilder, "pao2_fio2_ratio", 90.0f, TypeEnumPB.FLOAT);
        // 有机械通气
        addSofaParam(pbBuilder, "respiratory_support", true, TypeEnumPB.BOOL);
        SOFAScorePB initialPb = pbBuilder.build();
        Pair<ReturnCode, SOFAScorePB> scoreResult = SofaScorer.score(
            sofaMeta, sofaRules, initialPb, statusCodeMsgList
        );
        assertThat(scoreResult.getFirst().getCode()).isEqualTo(0);
        SOFAScorePB scoredPb = scoreResult.getSecond();
        assertThat(getParamScore(scoredPb, "pao2_fio2_ratio"))
            .as("p/f=90 => 4分，安装呼吸装置").isEqualTo(4);
        assertThat(scoredPb.getSofaScore())
            .as("总分 = 呼吸4 + 其它0 = 4").isEqualTo(4);

        pbBuilder = SOFAScorePB.newBuilder();
        // 呼吸(2)：p/f = 90 (<100 =>4分)
        addSofaParam(pbBuilder, "pao2_fio2_ratio", 90.0f, TypeEnumPB.FLOAT);
        // 有机械通气
        addSofaParam(pbBuilder, "respiratory_support", false, TypeEnumPB.BOOL);
        initialPb = pbBuilder.build();
        scoreResult = SofaScorer.score(
            sofaMeta, sofaRules, initialPb, statusCodeMsgList
        );
        assertThat(scoreResult.getFirst().getCode()).isEqualTo(0);
        scoredPb = scoreResult.getSecond();
        assertThat(getParamScore(scoredPb, "pao2_fio2_ratio"))
            .as("p/f=90 => 4分，未安装呼吸装置").isEqualTo(2);
        assertThat(scoredPb.getSofaScore())
            .as("总分 = 呼吸2 + 其它0 = 2").isEqualTo(2);

        pbBuilder = SOFAScorePB.newBuilder();
        // 呼吸(2)：p/f = 500 (>= 400 =>0分)
        addSofaParam(pbBuilder, "pao2_fio2_ratio", 500.0f, TypeEnumPB.FLOAT);
        // 有机械通气
        addSofaParam(pbBuilder, "respiratory_support", true, TypeEnumPB.BOOL);
        initialPb = pbBuilder.build();
        scoreResult = SofaScorer.score(
            sofaMeta, sofaRules, initialPb, statusCodeMsgList
        );
        assertThat(scoreResult.getFirst().getCode()).isEqualTo(0);
        scoredPb = scoreResult.getSecond();
        assertThat(getParamScore(scoredPb, "pao2_fio2_ratio"))
            .as("p/f=500 => 0分，安装呼吸装置，3分").isEqualTo(3);
        assertThat(scoredPb.getSofaScore())
            .as("总分 = 呼吸3 + 其它0 = 3").isEqualTo(3);

        pbBuilder = SOFAScorePB.newBuilder();
        // 呼吸(2)：p/f = 500 (>= 400 =>0分)
        addSofaParam(pbBuilder, "pao2_fio2_ratio", 500.0f, TypeEnumPB.FLOAT);
        // 没有机械通气
        addSofaParam(pbBuilder, "respiratory_support", false, TypeEnumPB.BOOL);
        initialPb = pbBuilder.build();
        scoreResult = SofaScorer.score(
            sofaMeta, sofaRules, initialPb, statusCodeMsgList
        );
        assertThat(scoreResult.getFirst().getCode()).isEqualTo(0);
        scoredPb = scoreResult.getSecond();
        assertThat(getParamScore(scoredPb, "pao2_fio2_ratio"))
            .as("p/f=500 => 0分，未安装呼吸装置，0分").isEqualTo(0);
        assertThat(scoredPb.getSofaScore())
            .as("总分 = 呼吸0 + 其它0 = 0").isEqualTo(0);
    }

    /**
     * 测试合并 mergeProto(...) 的逻辑
     */
    @Test
    public void testMergeProto() {
        // original
        SOFAScorePB.Builder origBuilder = SOFAScorePB.newBuilder();
        addSofaParam(origBuilder, "pao2_fio2_ratio", 300.0f, TypeEnumPB.FLOAT);
        addSofaParam(origBuilder, "respiratory_support", false, TypeEnumPB.BOOL);
        addSofaParam(origBuilder, "platelet_count", 150.0f, TypeEnumPB.FLOAT);
        SOFAScorePB origPb = origBuilder.build();

        // new
        SOFAScorePB.Builder newBuilder = SOFAScorePB.newBuilder();
        addSofaParam(newBuilder, "respiratory_support", true, TypeEnumPB.BOOL); // 更新
        addSofaParam(newBuilder, "bilirubin", 50.0f, TypeEnumPB.FLOAT);         // 新增
        // 未覆盖 platelet_count => 会保留原值
        SOFAScorePB newPb = newBuilder.build();

        // merge
        SOFAScorePB mergedPb = SofaScorer.mergeProto(sofaMeta, origPb, newPb);

        // 断言：respiratory_support => true (被覆盖)，pao2_fio2_ratio =>300(原值保留)
        assertThat(getParamBoolValue(mergedPb, "respiratory_support")).isTrue();
        assertThat(getParamFloatValue(mergedPb, "pao2_fio2_ratio")).isEqualTo(300.0f);
        // 新增的 bilirubin => 50
        assertThat(getParamFloatValue(mergedPb, "bilirubin")).isEqualTo(50.0f);
        // platelet_count 保留
        assertThat(getParamFloatValue(mergedPb, "platelet_count")).isEqualTo(150.0f);
    }

    // =============== 辅助方法：构造与读取 SOFAScorePB 因子 ===============

    /**
     * 向 builder 中添加某个 SOFA 参数
     */
    private void addSofaParam(
        SOFAScorePB.Builder builder, String code, Object value, TypeEnumPB type
    ) {
        // 仅做简单示例；实际可与 SofaScorer 中的 buildGenericValue(...) 类似
        ValueMetaPB valueMeta = sofaMeta.getSofaParamList().stream()
                .filter(f -> f.getCode().equals(code))
                .map(DoctorScoreFactorMetaPB::getValueMeta)
                .findFirst()
                .orElse(ValueMetaPB.newBuilder().setValueType(type).build());

        GenericValuePB genericValue;
        switch (type) {
            case BOOL:
                genericValue = GenericValuePB.newBuilder().setBoolVal((Boolean) value).build();
                break;
            case INT32:
                genericValue = GenericValuePB.newBuilder().setInt32Val((Integer) value).build();
                break;
            case FLOAT:
                if (value instanceof Number) {
                    double dv = ((Number) value).doubleValue();
                    genericValue = ValueMetaUtils.getValue(valueMeta, dv);
                } else {
                    genericValue = null;
                }
                break;
            default:
                genericValue = null;
                break;
        }
        if (genericValue == null) return;

        DoctorScoreFactorPB factor = DoctorScoreFactorPB.newBuilder()
            .setCode(code)
            .setValue(genericValue)
            .setValueStr(ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta))
            .build();

        builder.addSofaParam(factor);
    }

    /**
     * 获取指定 code 的分数
     */
    private int getParamScore(SOFAScorePB scorePb, String code) {
        for (DoctorScoreFactorPB factor : scorePb.getSofaParamList()) {
            if (factor.getCode().equals(code)) {
                return factor.getScore();
            }
        }
        return 0;
    }

    /**
     * 获取指定 code 的 float 值
     */
    private float getParamFloatValue(SOFAScorePB scorePb, String code) {
        for (DoctorScoreFactorPB factor : scorePb.getSofaParamList()) {
            if (factor.getCode().equals(code)) {
                return factor.getValue().getFloatVal();
            }
        }
        return 0.0f;
    }

    /**
     * 获取指定 code 的 bool 值
     */
    private boolean getParamBoolValue(SOFAScorePB scorePb, String code) {
        for (DoctorScoreFactorPB factor : scorePb.getSofaParamList()) {
            if (factor.getCode().equals(code)) {
                return factor.getValue().getBoolVal();
            }
        }
        return false;
    }

    /**
     * 获取指定 code 的 int 值
     */
    private int getParamIntValue(SOFAScorePB scorePb, String code) {
        for (DoctorScoreFactorPB factor : scorePb.getSofaParamList()) {
            if (factor.getCode().equals(code)) {
                return factor.getValue().getInt32Val();
            }
        }
        return 0;
    }

    private final SofaScoreMetaPB sofaMeta;
    private final SofaScoreRulesPB sofaRules;
    private final List<String> statusCodeMsgList;
}