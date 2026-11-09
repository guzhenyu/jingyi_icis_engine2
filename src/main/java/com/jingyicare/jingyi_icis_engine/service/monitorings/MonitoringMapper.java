package com.jingyicare.jingyi_icis_engine.service.monitorings;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParamHistory;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Slf4j
public final class MonitoringMapper {
    private MonitoringMapper() {}

    public static MonitoringParam toMonitoringParamEntity(
        MonitoringParamPB paramPb, int displayOrder
    ) {
        return MonitoringParam.builder()
            .code(paramPb.getCode())
            .name(paramPb.getName())
            .nameEn(paramPb.getNameEn())
            .typePb(ProtoUtils.encodeValueMeta(paramPb.getValueMeta()))
            .balanceType(paramPb.getBalanceType())
            .category(paramPb.getCategory())
            .uiModalCode(paramPb.getUiModalCode())
            .displayOrder(displayOrder)
            .build();
    }

    public static MonitoringParam toMonitoringParamEntity(
        MonitoringParamPB paramPb, MonitoringParam param, int displayOrder
    ) {
        param.setName(paramPb.getName());
        param.setNameEn(paramPb.getNameEn());
        param.setTypePb(ProtoUtils.encodeValueMeta(paramPb.getValueMeta()));
        param.setBalanceType(paramPb.getBalanceType());
        param.setCategory(paramPb.getCategory());
        param.setUiModalCode(paramPb.getUiModalCode());
        param.setChartSign(paramPb.getChartSign());
        // -1 表示保留已有实体的显示顺序。
        if (displayOrder != -1) param.setDisplayOrder(displayOrder);
        return param;
    }

    public static MonitoringParamHistory toMonitoringParamHistoryEntity(
        MonitoringParam param, String accountId
    ) {
        return toMonitoringParamHistoryEntity(param, accountId, true);
    }

    public static MonitoringParamHistory toMonitoringParamHistoryEntity(
        MonitoringParam param, String accountId, boolean isDeleted
    ) {
        return MonitoringParamHistory.builder()
            .code(param.getCode())
            .name(param.getName())
            .typePb(param.getTypePb())
            .balanceType(param.getBalanceType())
            .isDeleted(isDeleted)
            .deletedBy(isDeleted ? accountId : null)
            .deletedAt(isDeleted ? TimeUtils.getNowUtc() : null)
            .modifiedBy(!isDeleted ? accountId : null)
            .modifiedAt(!isDeleted ? TimeUtils.getNowUtc() : null)
            .build();
    }

    public static MonitoringParamHistory toMonitoringParamHistoryEntity(
        DeptMonitoringParam param, String accountId, boolean isDeleted
    ) {
        return MonitoringParamHistory.builder()
            .code(param.getId().getCode())
            .deptId(param.getId().getDeptId())
            .typePb(param.getTypePb())
            .isDeleted(isDeleted)
            .deletedBy(isDeleted ? accountId : null)
            .deletedAt(isDeleted ? TimeUtils.getNowUtc() : null)
            .modifiedBy(!isDeleted ? accountId : null)
            .modifiedAt(!isDeleted ? TimeUtils.getNowUtc() : null)
            .build();
    }

    public static StatusCode isValidParamPb(MonitoringParamPB paramPb, MonitoringPB monitoringPb) {
        final String code = paramPb.getCode();
        final int balanceType = paramPb.getBalanceType();
        if (balanceType == monitoringPb.getEnums().getBalanceIn().getId()
            || balanceType == monitoringPb.getEnums().getBalanceOut().getId()
        ) {
            ValueMetaPB valueMeta = paramPb.getValueMeta();
            if (valueMeta.getIsMultipleSelection()) {
                log.error("Balance group's MonitoringParamPB with code: {} is multiple_selection.",
                    code);
                return StatusCode.MONITORING_PARAM_MULTI_SELECTION_NOT_ALLOWED;
            }
            if (!monitoringPb.getBalanceParamTypeList().contains(valueMeta.getValueType())) {
                log.error("Balance group's param code {} type is not valid.", code);
                return StatusCode.MONITORING_PARAM_BALANCE_TYPE_VALUE_TYPE_NOT_MATCH;
            }
        }
        return StatusCode.OK;
    }
}
