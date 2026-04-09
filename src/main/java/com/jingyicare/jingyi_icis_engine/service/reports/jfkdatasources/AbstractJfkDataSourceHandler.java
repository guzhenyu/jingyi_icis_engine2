package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.time.LocalDateTime;
import java.util.List;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ReturnCodeUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

public abstract class AbstractJfkDataSourceHandler implements JfkDataSourceHandler {
    protected AbstractJfkDataSourceHandler(JfkDataSourceSupport support) {
        this.support = support;
    }

    protected ReturnCode returnCode(StatusCode statusCode, String... args) {
        return args.length == 0
            ? ReturnCodeUtils.getReturnCode(support.getStatusMsgList(), statusCode)
            : ReturnCodeUtils.getReturnCode(support.getStatusMsgList(), statusCode, String.join(", ", args));
    }

    protected Pair<ReturnCode, JfkDataSourcePB> error(StatusCode statusCode, String... args) {
        return new Pair<>(returnCode(statusCode, args), null);
    }

    protected JfkDataSourcePB.Builder newOutputBuilder(JfkDataSourcePB input) {
        return support.newOutputBuilder(input);
    }

    protected Long getInt64Input(JfkDataSourcePB input, String fieldId) {
        for (JfkFieldDataPB fieldData : input.getInputDataList()) {
            if (fieldId.equals(fieldData.getId())) {
                return fieldData.getVal().getInt64Val();
            }
        }
        return null;
    }

    protected String getStringInput(JfkDataSourcePB input, String fieldId) {
        for (JfkFieldDataPB fieldData : input.getInputDataList()) {
            if (fieldId.equals(fieldData.getId())) {
                return fieldData.getVal().getStrVal();
            }
        }
        return null;
    }

    protected String getDeptIdInput(JfkDataSourcePB input, String fieldId) {
        for (JfkFieldDataPB fieldData : input.getInputDataList()) {
            if (!fieldId.equals(fieldData.getId())) continue;
            String strVal = fieldData.getVal().getStrVal();
            return !StrUtils.isBlank(strVal) ? strVal : String.valueOf(fieldData.getVal().getInt64Val());
        }
        return null;
    }

    protected LocalDateTime getUtcDateTimeInput(JfkDataSourcePB input, String fieldId) {
        String timeIso8601 = getStringInput(input, fieldId);
        if (StrUtils.isBlank(timeIso8601)) return null;
        return TimeUtils.fromIso8601String(timeIso8601, "UTC");
    }

    protected String joinMissingFields(List<String> fieldNames) {
        String joined = String.join(" ", fieldNames);
        return joined.isEmpty() ? joined : joined + " ";
    }

    protected final JfkDataSourceSupport support;
}
