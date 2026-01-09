package com.jingyicare.jingyi_icis_engine.service.doctors;

import java.time.LocalDateTime;
import java.util.*;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDoctorScore.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.utils.*;

@Slf4j
public class ScoreUtils {
    @AllArgsConstructor
    public static class ScorerInfo {
        public Long pid;
        public String deptId;
        public LocalDateTime scoreTime;
        public String scoredBy;
        public String scoredByAccountName;
        public LocalDateTime evalStartAt;
        public LocalDateTime evalEndAt;
        public LocalDateTime modifiedAt;
        public String modifiedBy;
        public Boolean isDeleted;
        public String deletedBy;
        public String deletedByAccountName;
        public LocalDateTime deletedAt;
    }

    public static int calcFactorScore(
        String code, GenericValuePB value, ValueMetaPB valueMeta, FactorScoreRulePB factorScoreRule
    ) {
        Float numericVal = ValueMetaUtils.convertToFloatObj(value, valueMeta);
        if (numericVal == null) {
            log.error("Value for code {} is null, value {}", code, value);
            return 0;
        }

        // 遍历该 rule 内所有区间，找到第一个匹配的分数并返回
        for (FactorScoreRangePB range : factorScoreRule.getRangeList()) {
            if (isValueInRange(numericVal, range)) {
                return range.getScore();
            }
        }

        // 如果没有任何区间匹配，可自定义返回默认值
        return 0;
    }

    public static boolean isValueInRange(Float numericVal, FactorScoreRangePB range) {
        // 下界检查
        if (!range.getIsLowerUnbounded()) {
            if (range.getIncludeLowerBound()) {
                // [lower_bound, ...
                if (numericVal < range.getLowerBound()) {
                    return false;
                }
            } else {
                // (lower_bound, ...
                if (numericVal <= range.getLowerBound()) {
                    return false;
                }
            }
        }

        // 上界检查
        if (!range.getIsUpperUnbounded()) {
            if (range.getIncludeUpperBound()) {
                // ..., upper_bound]
                if (numericVal > range.getUpperBound()) {
                    return false;
                }
            } else {
                // ..., upper_bound)
                if (numericVal >= range.getUpperBound()) {
                    return false;
                }
            }
        }

        // 如果都通过检查，表示 numericVal 在此区间内
        return true;
    }

    public static Pair<ReturnCode, Integer> countScoreFactors(
        List<DoctorScoreFactorMetaPB> metas,
        List<DoctorScoreFactorPB> factors,
        StatusCode unexpectedFactorStatusCode,
        List<String> statusCodeMsgList
    ) {
        int cnt = 0;
        for (DoctorScoreFactorPB factor : factors) {
            String code = factor.getCode();
            DoctorScoreFactorMetaPB factorMeta = metas.stream()
                .filter(meta -> meta.getCode().equals(code))
                .findFirst()
                .orElse(null);
            if (factorMeta == null) {
                log.error("No ValueMetaPB found for Caprini code: {}", code);
                return new Pair<>(ReturnCodeUtils.getReturnCode(statusCodeMsgList, unexpectedFactorStatusCode, code), null);
            }
            if (ValueMetaUtils.convertToBool(factor.getValue(), factorMeta.getValueMeta())) {
                cnt++;
            }
        }
        return new Pair<>(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK), cnt);
    }
}