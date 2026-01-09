package com.jingyicare.jingyi_icis_engine.service.scores;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Component
@Slf4j
public class ScoreCalculator {
    public ScoreCalculator(
        @Autowired ConfigProtoService protoService,
        @Autowired ScoreConfig scoreConfig
    ) {
        this.protoService = protoService;
        this.ZONE_ID = protoService.getConfig().getZoneId();

        this.scorePb = protoService.getConfig().getScore();
        this.SCORE_ITEM_TYPE_SINGLE_SELECTION = scorePb.getEnums().getScoreItemTypeSingleSelection().getId();
        this.SCORE_ITEM_TYPE_MULTI_SELECTION = scorePb.getEnums().getScoreItemTypeMultiSelection().getId();
        this.SCORE_ITEM_AGGREGATOR_SUM = scorePb.getEnums().getScoreItemAggregatorSum().getId();
        this.SCORE_ITEM_AGGREGATOR_MAX = scorePb.getEnums().getScoreItemAggregatorMax().getId();
        this.SCORE_ITEM_AGGREGATOR_FIRST = scorePb.getEnums().getScoreItemAggregatorFirst().getId();
        this.SCORE_ITEM_AGGREGATOR_CATETHER_SLIPPAGE = scorePb.getEnums().getScoreItemAggregatorCatheterSlippage().getId();
        this.SCORE_GROUP_AGGREGATOR_SUM = scorePb.getEnums().getScoreGroupAggregatorSum().getId();
        this.SCORE_GROUP_AGGREGATOR_FIRST = scorePb.getEnums().getScoreGroupAggregatorFirst().getId();
        this.SCORE_GROUP_AGGREGATOR_DELIRIUM = scorePb.getEnums().getScoreGroupAggregatorDelirium().getId();
        this.SCORE_GROUP_PRINTER_DEFAULT = scorePb.getEnums().getScoreGroupPrinterDefault().getId();

        this.DELIRIUM_CODE = scorePb.getEnums().getDeliriumCode();
        this.POSITIVE_STR = scorePb.getEnums().getPositiveStr();
        this.NEGATIVE_STR = scorePb.getEnums().getNegativeStr();
        this.UNKNOWN_STR = scorePb.getEnums().getUnknownStr();

        this.scoreConfig = scoreConfig;
    }

    public Pair<StatusCode, List<GenericValuePB>> getScoreItemValues(
        ScoreGroupMetaPB scoreGroupMeta,
        List<ScoreItemPB> scoreItems
    ) {
        // 谵妄评分特殊处理
        if (Objects.equals(scoreGroupMeta.getCode(), DELIRIUM_CODE)) {
            List<GenericValuePB> deliriumScores = new ArrayList<>();
            for (int i = 0; i < 4; ++i) {
                deliriumScores.add(GenericValuePB.newBuilder().setStrVal(NEGATIVE_STR).build());
            }
            for (ScoreItemPB scoreItem : scoreItems) {
                String scoreItemCode = scoreItem.getCode();
                String optVal = scoreItem.getScoreOptionCodeList().size() <= 0 ? NEGATIVE_STR :
                    (Objects.equals(scoreItem.getScoreOptionCode(0), "negative") ?
                     NEGATIVE_STR : POSITIVE_STR
                    );
                if (Objects.equals(scoreItemCode, "feature_1_acute_mental_change")) {
                    deliriumScores.set(0, GenericValuePB.newBuilder().setStrVal(optVal).build());
                } else if (Objects.equals(scoreItemCode, "feature_2_inattention")) {
                    deliriumScores.set(1, GenericValuePB.newBuilder().setStrVal(optVal).build());
                } else if (Objects.equals(scoreItemCode, "feature_3_clarity_change")) {
                    deliriumScores.set(2, GenericValuePB.newBuilder().setStrVal(optVal).build());
                } else if (Objects.equals(scoreItemCode, "feature_4_thought_disorder")) {
                    deliriumScores.set(3, GenericValuePB.newBuilder().setStrVal(optVal).build());
                } else {
                    log.warn("Unexpected score item code for delirium: {}", scoreItemCode);
                }
            }
            return new Pair<>(StatusCode.OK, deliriumScores);
        }

        // 通用处理
        ValueMetaPB valueMeta = scoreGroupMeta.getValueMeta();

        List<GenericValuePB> scoreItemScores = new ArrayList<>();
        for (ScoreItemPB scoreItem : scoreItems) {
            final ScoreItemMetaPB scoreItemMeta = scoreConfig.getScoreItemMeta(scoreGroupMeta, scoreItem.getCode());
            if (scoreItemMeta == null) return new Pair<>(StatusCode.SCORE_ITEM_NOT_FOUND, Collections.emptyList());

            GenericValuePB.Builder itemScoreBuilder = GenericValuePB.newBuilder();
            StatusCode statusCode = calcItemScore(valueMeta, scoreItemMeta, scoreItem, itemScoreBuilder);
            if (statusCode != StatusCode.OK) return new Pair<>(statusCode, Collections.emptyList());
            scoreItemScores.add(itemScoreBuilder.build());
        }
        return new Pair<>(StatusCode.OK, scoreItemScores);
    }

    public StatusCode calcItemScore(
        ValueMetaPB valueMeta,
        ScoreItemMetaPB scoreItemMeta,
        ScoreItemPB scoreItem,
        GenericValuePB.Builder itemScoreBuilder
    ) {
        if (Objects.equals(scoreItemMeta.getScoreItemType(), SCORE_ITEM_TYPE_SINGLE_SELECTION)) {
            if (scoreItem.getScoreOptionCodeCount() != 1) {
                return StatusCode.SCORE_ITEM_SINGLE_SELECTION_COUNT_ERROR;
            }
            return getScoreOptionScore(scoreItemMeta, scoreItem.getScoreOptionCode(0), itemScoreBuilder);
        }

        // Multi-selection
        List<GenericValuePB> scoreOptionScores = new ArrayList<>();
        Map<String, GenericValuePB> scoreOptionScoreMap = new HashMap<>();
        for (String scoreOptionCode : scoreItem.getScoreOptionCodeList()) {
            GenericValuePB.Builder scoreOptionScoreBuilder = GenericValuePB.newBuilder();
            StatusCode statusCode = getScoreOptionScore(scoreItemMeta, scoreOptionCode, scoreOptionScoreBuilder);
            if (statusCode != StatusCode.OK) {
                return statusCode;
            }
            GenericValuePB scoreOptionScore = scoreOptionScoreBuilder.build();
            scoreOptionScores.add(scoreOptionScore);
            scoreOptionScoreMap.put(scoreOptionCode, scoreOptionScore);
        }

        if (Objects.equals(scoreItemMeta.getScoreItemAggregator(), SCORE_ITEM_AGGREGATOR_SUM)) {
            GenericValuePB sum = ValueMetaUtils.getDefaultValue(valueMeta);
            for (GenericValuePB scoreOptionScore : scoreOptionScores) {
                sum = ValueMetaUtils.addGenericValue(sum, scoreOptionScore, valueMeta);
            }
            itemScoreBuilder.mergeFrom(sum);
        } else if (Objects.equals(scoreItemMeta.getScoreItemAggregator(), SCORE_ITEM_AGGREGATOR_MAX)) {
            GenericValuePB max = ValueMetaUtils.getDefaultValue(valueMeta);
            for (GenericValuePB scoreOptionScore : scoreOptionScores) {
                max = ValueMetaUtils.maxGenericValue(max, scoreOptionScore, valueMeta);
            }
            itemScoreBuilder.mergeFrom(max);
        } else if (Objects.equals(scoreItemMeta.getScoreItemAggregator(), SCORE_ITEM_AGGREGATOR_FIRST)) {
            itemScoreBuilder.mergeFrom(scoreOptionScores.get(0));
        } else if (Objects.equals(scoreItemMeta.getScoreItemAggregator(), SCORE_ITEM_AGGREGATOR_CATETHER_SLIPPAGE)) {
            // 统计导管的滑脱数量
            Map<String/*itemCode*/, Integer/*slippageCount*/> slippageCountMap = new HashMap<>();
            for (StrKeyValPB kv : scoreItem.getCodeNoteList()) {
                String itemCode = kv.getKey();
                Integer slippageCount = null;
                try {
                    slippageCount = Integer.parseInt(kv.getVal());
                } catch (NumberFormatException e) {
                    log.error("Invalid slippage count for itemCode {}: {}", itemCode, kv.getVal());
                }
                if (slippageCount != null) {
                    slippageCountMap.put(itemCode, slippageCount);
                }
            }

            // 统计滑脱导管的加权分
            GenericValuePB sum = ValueMetaUtils.getDefaultValue(valueMeta);
            for (Map.Entry<String, GenericValuePB> entry : scoreOptionScoreMap.entrySet()) {
                String itemCode = entry.getKey();
                GenericValuePB scoreOptionScore = entry.getValue();
                Integer slippageCount = slippageCountMap.get(itemCode);
                if (slippageCount != null) {
                    // 计算加权分
                    GenericValuePB weightedScore = ValueMetaUtils.multiplyGenericValue(scoreOptionScore, slippageCount, valueMeta);
                    sum = ValueMetaUtils.addGenericValue(sum, weightedScore, valueMeta);
                }
            }
            itemScoreBuilder.mergeFrom(sum);
        } else {
            return StatusCode.SCORE_ITEM_AGGREGATOR_NOT_SUPPORTED;
        }

        return StatusCode.OK;
    }

    public StatusCode calcGroupScore(
        ScoreGroupMetaPB scoreGroupMeta,
        List<GenericValuePB> scoreItemScores,
        GenericValuePB.Builder groupScoreBuilder
    ) {
        ValueMetaPB valueMeta = scoreGroupMeta.getValueMeta();
        if (Objects.equals(scoreGroupMeta.getScoreGroupAggregator(), SCORE_GROUP_AGGREGATOR_SUM)) {
            GenericValuePB sum = ValueMetaUtils.getDefaultValue(valueMeta);
            for (GenericValuePB scoreItemScore : scoreItemScores) {
                sum = ValueMetaUtils.addGenericValue(sum, scoreItemScore, valueMeta);
            }
            groupScoreBuilder.mergeFrom(sum);
        } else if (Objects.equals(scoreGroupMeta.getScoreGroupAggregator(), SCORE_GROUP_AGGREGATOR_FIRST)) {
            groupScoreBuilder.mergeFrom(scoreItemScores.get(0));
        } else if (Objects.equals(scoreGroupMeta.getScoreGroupAggregator(), SCORE_GROUP_AGGREGATOR_DELIRIUM)) {
            if (scoreItemScores == null || scoreItemScores.size() != 4) {  // 自定义评分：谵妄
                log.error("Delirium score group requires exactly 4 items, but got: {}", scoreItemScores.size());
                return StatusCode.SCORE_GROUP_DELIRIUM_NOT_ENOUGH_ITEMS;
            }
            if (Objects.equals(scoreItemScores.get(0).getStrVal(), POSITIVE_STR) &&
                Objects.equals(scoreItemScores.get(1).getStrVal(), POSITIVE_STR) &&
                (Objects.equals(scoreItemScores.get(2).getStrVal(), POSITIVE_STR) ||
                 Objects.equals(scoreItemScores.get(3).getStrVal(), POSITIVE_STR))
            ) {
                groupScoreBuilder.setStrVal(POSITIVE_STR);
            } else {
                groupScoreBuilder.setStrVal(NEGATIVE_STR);
            }
        } else {
            return StatusCode.SCORE_GROUP_AGGREGATOR_NOT_SUPPORTED;
        }

        return StatusCode.OK;
    }

    public StatusCode printGroupScore(
        ScoreGroupMetaPB scoreGroupMeta,
        GenericValuePB groupScore,
        StringBuilder groupScoreTextBuilder
    ) {
        if (Objects.equals(scoreGroupMeta.getScoreGroupPrinter(), SCORE_GROUP_PRINTER_DEFAULT)) {
            final ValueMetaPB valueMeta = scoreGroupMeta.getValueMeta();
            String valStr = ValueMetaUtils.extractAndFormatParamValue(groupScore, valueMeta);
            groupScoreTextBuilder.setLength(0);
            groupScoreTextBuilder.append(valStr);
            groupScoreTextBuilder.append(valueMeta.getUnit());
            return StatusCode.OK;
        } else {
            return StatusCode.SCORE_GROUP_PRINTER_NOT_SUPPORTED;
        }
    }

    public StatusCode validateScoreGroup(ScoreGroupMetaPB scoreGroupMeta, ScoreGroupPB scoreGroup) {
        Pair<StatusCode, List<GenericValuePB>> itemScoresPair = getScoreItemValues(
            scoreGroupMeta, scoreGroup.getItemList()
        );
        StatusCode statusCode = itemScoresPair.getFirst();
        if (statusCode != StatusCode.OK) return statusCode;

        GenericValuePB.Builder groupScoreBuilder = GenericValuePB.newBuilder();
        statusCode = calcGroupScore(scoreGroupMeta, itemScoresPair.getSecond(), groupScoreBuilder);
        if (statusCode != StatusCode.OK) return statusCode;
        GenericValuePB groupScore = groupScoreBuilder.build();

        String scoreGroupCode = scoreGroupMeta.getCode();
        if (Objects.equals(scoreGroupCode, DELIRIUM_CODE)) {
            String submittedScore = scoreGroup.getGroupScore().getStrVal();
            if (!Objects.equals(submittedScore, groupScore.getStrVal()) &&
                !Objects.equals(submittedScore, UNKNOWN_STR)
            ) {
                log.error("Delirium score group validation failed: expected={}, actual={}",
                    groupScore.getStrVal(), submittedScore);
                return StatusCode.SCORE_GROUP_DELIRIUM_GROUP_SCORE_NOT_MATCH;
            }
        }
        return StatusCode.OK;
    }

    private StatusCode getScoreOptionScore(
        ScoreItemMetaPB scoreItemMeta, String scoreOptionCode, GenericValuePB.Builder scoreOptionScoreBuilder
    ) {
        for (ScoreOptionPB scoreOption : scoreItemMeta.getOptionList()) {
            if (scoreOption.getCode().equals(scoreOptionCode)) {
                scoreOptionScoreBuilder.mergeFrom(scoreOption.getScore());
                return StatusCode.OK;
            }
        }
        return StatusCode.SCORE_OPTION_NOT_FOUND;
    }

    private final ConfigProtoService protoService;
    private final String ZONE_ID;
    private final ScorePB scorePb;
    private final int SCORE_ITEM_TYPE_SINGLE_SELECTION;
    private final int SCORE_ITEM_TYPE_MULTI_SELECTION;
    private final int SCORE_ITEM_AGGREGATOR_SUM;
    private final int SCORE_ITEM_AGGREGATOR_MAX;
    private final int SCORE_ITEM_AGGREGATOR_FIRST;
    private final int SCORE_ITEM_AGGREGATOR_CATETHER_SLIPPAGE;
    private final int SCORE_GROUP_AGGREGATOR_SUM;
    private final int SCORE_GROUP_AGGREGATOR_FIRST;
    private final int SCORE_GROUP_AGGREGATOR_DELIRIUM;
    private final int SCORE_GROUP_PRINTER_DEFAULT;

    private final String DELIRIUM_CODE;
    private final String POSITIVE_STR;
    private final String NEGATIVE_STR;
    private final String UNKNOWN_STR;

    private final ScoreConfig scoreConfig;
}