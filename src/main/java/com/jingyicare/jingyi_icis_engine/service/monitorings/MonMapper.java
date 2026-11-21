package com.jingyicare.jingyi_icis_engine.service.monitorings;

import java.time.LocalDateTime;
import java.util.Objects;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParamId;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParamHistory;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeptMonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

public final class MonMapper {
    private MonMapper() {}

    public static MonitoringParamPB toMonitoringParamPB(
        MonitoringParam param, ValueMetaPB valueMetaOverride
    ) {
        if (param == null) return MonitoringParamPB.getDefaultInstance();

        ValueMetaPB valueMeta = valueMetaOverride == null
            ? ProtoUtils.decodeValueMeta(param.getTypePb()) : valueMetaOverride;
        MonitoringParamPB.Builder builder = MonitoringParamPB.newBuilder()
            .setCode(Objects.requireNonNullElse(param.getCode(), ""))
            .setName(Objects.requireNonNullElse(param.getName(), ""))
            .setNameEn(param.getNameEn())
            .setValueMeta(Objects.requireNonNullElse(valueMeta, ValueMetaPB.getDefaultInstance()))
            .setBalanceType(Objects.requireNonNullElse(param.getBalanceType(), 0))
            .setCategory(param.getCategory())
            .setChartSign(param.getChartSign())
            .setDisplayOrder(Objects.requireNonNullElse(param.getDisplayOrder(), 0));
        if (!StrUtils.isBlank(param.getUiModalCode())) {
            builder.setUiModalCode(param.getUiModalCode());
        }
        return builder.build();
    }

    public static DeptMonitoringParamPB toDeptMonitoringParamPB(
        MonitoringParam globalParam, DeptMonitoringParam deptParam
    ) {
        if (globalParam == null || deptParam == null) {
            return DeptMonitoringParamPB.getDefaultInstance();
        }

        DeptMonitoringParamId id = deptParam.getId();
        String deptId = id == null ? "" : Objects.requireNonNullElse(id.getDeptId(), "");
        ValueMetaPB valueMeta = Objects.requireNonNullElse(
            ProtoUtils.decodeValueMeta(deptParam.getTypePb()), ValueMetaPB.getDefaultInstance());
        return DeptMonitoringParamPB.newBuilder()
            .setDeptId(deptId)
            .setParam(toMonitoringParamPB(globalParam, valueMeta).toBuilder()
                .clearNameEn()
                .clearDisplayOrder()
                .setUiModalCode(Objects.requireNonNullElse(globalParam.getUiModalCode(), "")))
            .build();
    }

    public static MonitoringParam toMonitoringParamEntity(MonitoringParamPB pb) {
        if (pb == null) return null;

        return MonitoringParam.builder()
            .code(pb.getCode())
            .name(pb.getName())
            .nameEn(pb.getNameEn())
            .typePb(ProtoUtils.encodeValueMeta(pb.getValueMeta()))
            .balanceType(pb.getBalanceType())
            .category(pb.getCategory())
            .uiModalCode(pb.getUiModalCode())
            .chartSign(pb.getChartSign())
            .displayOrder(pb.getDisplayOrder())
            .build();
    }

    public static MonitoringParamHistory toMonitoringParamHistoryEntity(
        MonitoringParam param, String accountId, boolean isDeleted, LocalDateTime occurredAt
    ) {
        if (param == null) return null;

        return MonitoringParamHistory.builder()
            .code(param.getCode())
            .name(param.getName())
            .typePb(param.getTypePb())
            .balanceType(param.getBalanceType())
            .isDeleted(isDeleted)
            .deletedBy(isDeleted ? accountId : null)
            .deletedAt(isDeleted ? occurredAt : null)
            .modifiedBy(isDeleted ? null : accountId)
            .modifiedAt(isDeleted ? null : occurredAt)
            .build();
    }
}
