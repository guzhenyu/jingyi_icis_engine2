package com.jingyicare.jingyi_icis_engine.service.scores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.EnumValue;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;

/** 使用真实计算器验证提交总分，不通过模拟校验结果绕过一致性检查。 */
class ScoreCalculatorTests {
    @BeforeEach
    void prepare() {
        EnumValue one = EnumValue.newBuilder().setId(1).build();
        EnumValue two = EnumValue.newBuilder().setId(2).build();
        EnumValue three = EnumValue.newBuilder().setId(3).build();
        ScoreEnums enums = ScoreEnums.newBuilder()
            .setScoreItemTypeSingleSelection(one).setScoreItemTypeMultiSelection(two)
            .setScoreGroupAggregatorSum(one).setScoreGroupAggregatorFirst(two)
            .setScoreGroupAggregatorDelirium(three).setDeliriumCode("delirium")
            .setPositiveStr("阳性").setNegativeStr("阴性").setUnknownStr("无法评估").build();
        ConfigProtoService proto = mock(ConfigProtoService.class);
        when(proto.getConfig()).thenReturn(Config.newBuilder().setZoneId("UTC")
            .setScore(ScorePB.newBuilder().setEnums(enums)).build());
        calculator = new ScoreCalculator(proto);
    }

    @ParameterizedTest
    @CsvSource({"15, OK", "3, SCORE_GROUP_SCORE_NOT_MATCH"})
    void validatesNumericTotalAgainstSelectedOptions(int submitted, StatusCode expected) {
        ScoreGroupMetaPB meta = metadata(TypeEnumPB.INT32,
            GenericValuePB.newBuilder().setInt32Val(15).build(), 1);
        ScoreGroupPB record = record(GenericValuePB.newBuilder().setInt32Val(submitted).build());

        assertThat(calculator.validateScoreGroup(meta, record)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"level-1, OK", "level-2, SCORE_GROUP_SCORE_NOT_MATCH"})
    void validatesStringResultAgainstSelectedOptions(String submitted, StatusCode expected) {
        ScoreGroupMetaPB meta = metadata(TypeEnumPB.STRING,
            GenericValuePB.newBuilder().setStrVal("level-1").build(), 2);
        ScoreGroupPB record = record(GenericValuePB.newBuilder().setStrVal(submitted).build());

        assertThat(calculator.validateScoreGroup(meta, record)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"阴性, OK", "无法评估, OK", "阳性, SCORE_GROUP_DELIRIUM_GROUP_SCORE_NOT_MATCH"})
    void preservesDeliriumMatchingUnknownAndMismatchRules(String submitted, StatusCode expected) {
        ScoreGroupMetaPB meta = ScoreGroupMetaPB.newBuilder().setCode("delirium")
            .setScoreGroupAggregator(3)
            .setValueMeta(ValueMetaPB.newBuilder().setValueType(TypeEnumPB.STRING)).build();
        // 无阳性特征时计算结果为阴性，未知值仍由原有谵妄规则放行。
        ScoreGroupPB record = ScoreGroupPB.newBuilder().setCode("delirium")
            .setGroupScore(GenericValuePB.newBuilder().setStrVal(submitted)).build();

        assertThat(calculator.validateScoreGroup(meta, record)).isEqualTo(expected);
    }

    private ScoreGroupMetaPB metadata(
        TypeEnumPB type, GenericValuePB optionScore, int aggregator
    ) {
        return ScoreGroupMetaPB.newBuilder().setCode("ordinary")
            .setValueMeta(ValueMetaPB.newBuilder().setValueType(type))
            .setScoreGroupAggregator(aggregator)
            .addItem(ScoreItemMetaPB.newBuilder().setCode("item").setScoreItemType(1)
                .addOption(ScoreOptionPB.newBuilder().setCode("option").setScore(optionScore)))
            .build();
    }

    private ScoreGroupPB record(GenericValuePB submitted) {
        return ScoreGroupPB.newBuilder().setCode("ordinary")
            .addItem(ScoreItemPB.newBuilder().setCode("item").addScoreOptionCode("option"))
            .setGroupScore(submitted).build();
    }

    private ScoreCalculator calculator;
}
