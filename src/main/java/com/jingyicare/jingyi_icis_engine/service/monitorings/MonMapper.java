package com.jingyicare.jingyi_icis_engine.service.monitorings;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroupParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParamId;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParamHistory;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringGroupParam;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeptMonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupBetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupParamPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

@Slf4j
public final class MonMapper {
    private MonMapper() {}

    public static MonitoringGroupParamPB toMonitoringGroupParamPB(DeptMonitoringGroupParam param) {
        if (param == null) return MonitoringGroupParamPB.getDefaultInstance();
        return toMonitoringGroupParamPB(
            param.getId(), param.getMonitoringParamCode(), param.getDisplayOrder());
    }

    public static MonitoringGroupParamPB toMonitoringGroupParamPB(
        PatientMonitoringGroupParam param
    ) {
        if (param == null) return MonitoringGroupParamPB.getDefaultInstance();
        return toMonitoringGroupParamPB(
            param.getId(), param.getMonitoringParamCode(), param.getDisplayOrder());
    }

    private static MonitoringGroupParamPB toMonitoringGroupParamPB(
        Integer id, String code, Integer displayOrder
    ) {
        return MonitoringGroupParamPB.newBuilder()
            .setId(Objects.requireNonNullElse(id, 0))
            .setCode(Objects.requireNonNullElse(code, ""))
            .setDisplayOrder(Objects.requireNonNullElse(displayOrder, 0))
            .build();
    }

    public static MonitoringGroupPB toMonitoringGroupPB(
        DeptMonitoringGroup group, List<MonitoringGroupParamPB> params
    ) {
        if (group == null) return MonitoringGroupPB.getDefaultInstance();

        MonitoringGroupPB.Builder builder = MonitoringGroupPB.newBuilder()
            .setId(Objects.requireNonNullElse(group.getId(), 0))
            .setName(Objects.requireNonNullElse(group.getName(), ""))
            .setGroupType(Objects.requireNonNullElse(group.getGroupType(), 0))
            .setDisplayOrder(Objects.requireNonNullElse(group.getDisplayOrder(), 0))
            .addAllMonitoringParam(params);
        Integer balanceRole = group.getBalanceRole();
        if (Objects.equals(group.getGroupType(), MonEnums.GROUP_TYPE_BALANCE)
            && balanceRole != null) {
            builder.setBalanceRole(balanceRole);
        }
        ValueMetaPB sumValueMeta = ProtoUtils.decodeValueMeta(group.getSumTypePb());
        if (sumValueMeta != null) builder.setSumValueMeta(sumValueMeta);
        return builder.build();
    }

    public static MonitoringGroupBetaPB toMonitoringGroupBetaPB(
        DeptMonitoringGroup group, List<MonitoringParamPB> params
    ) {
        if (group == null) return MonitoringGroupBetaPB.getDefaultInstance();

        MonitoringGroupBetaPB.Builder builder = MonitoringGroupBetaPB.newBuilder()
            .setId(Objects.requireNonNullElse(group.getId(), 0))
            .setName(Objects.requireNonNullElse(group.getName(), ""))
            .setGroupType(Objects.requireNonNullElse(group.getGroupType(), 0))
            .setDisplayOrder(Objects.requireNonNullElse(group.getDisplayOrder(), 0))
            .addAllParam(params);
        Integer balanceRole = group.getBalanceRole();
        if (Objects.equals(group.getGroupType(), MonEnums.GROUP_TYPE_BALANCE)
            && balanceRole != null) {
            builder.setBalanceRole(balanceRole);
        }
        ValueMetaPB sumValueMeta = ProtoUtils.decodeValueMeta(group.getSumTypePb());
        if (sumValueMeta != null) builder.setSumValueMeta(sumValueMeta);
        return builder.build();
    }

    public static List<MonitoringGroupBetaPB> toMonitoringGroupBetaPBs(
        List<DeptMonitoringGroup> groups,
        Map<Integer, List<MonitoringGroupParamPB>> paramsByDeptGroupId,
        Map<String, MonitoringParam> paramsByCode,
        List<String> tubeParamCodes,
        List<MonitoringParamPB> tubeDetailTemplates
    ) {
        List<MonitoringGroupBetaPB> monitoringGroups = new ArrayList<>();
        List<DeptMonitoringGroup> orderedGroups = groups.stream()
            .sorted(Comparator.comparingInt(DeptMonitoringGroup::getDisplayOrder)
                .thenComparingInt(DeptMonitoringGroup::getId))
            .toList();
        for (DeptMonitoringGroup group : orderedGroups) {
            List<MonitoringGroupParamPB> groupParams = paramsByDeptGroupId
                .getOrDefault(group.getId(), List.of()).stream()
                .sorted(Comparator.comparingInt(MonitoringGroupParamPB::getDisplayOrder)
                    .thenComparingInt(MonitoringGroupParamPB::getId))
                .toList();
            monitoringGroups.add(buildMonitoringGroupBetaPB(group.getDeptId(),
                toMonitoringGroupPB(group, groupParams), paramsByCode, tubeParamCodes,
                tubeDetailTemplates));
        }
        return monitoringGroups;
    }

    public static List<MonitoringGroupBetaPB> toMonitoringGroupBetaPBs(
        String deptId, List<MonitoringGroupPB> groups, Map<String, MonitoringParam> paramsByCode,
        List<String> tubeParamCodes, List<MonitoringParamPB> tubeDetailTemplates
    ) {
        List<MonitoringGroupBetaPB> result = new ArrayList<>();
        for (MonitoringGroupPB group : groups) {
            result.add(buildMonitoringGroupBetaPB(
                deptId, group, paramsByCode, tubeParamCodes, tubeDetailTemplates));
        }
        return result;
    }

    private static MonitoringGroupBetaPB buildMonitoringGroupBetaPB(
        String deptId, MonitoringGroupPB group, Map<String, MonitoringParam> paramsByCode,
        List<String> tubeParamCodes, List<MonitoringParamPB> tubeDetailTemplates
    ) {
        List<MonitoringParamPB> paramPbs = new ArrayList<>();
        int nextDisplayOrder = 1;
        for (MonitoringGroupParamPB groupParam : group.getMonitoringParamList()) {
            String code = groupParam.getCode();
            MonitoringParam param = paramsByCode.get(code);
            if (param == null) {
                log.error("MonitoringParam not found for code: {}, deptId {}", code, deptId);
                continue;
            }
            ValueMetaPB valueMeta = ProtoUtils.decodeValueMeta(param.getTypePb());
            if (valueMeta == null) {
                log.error("ValueMetaPB not found for code: {}, deptId {}", code, deptId);
                continue;
            }

            int displayOrder = groupParam.getDisplayOrder();
            paramPbs.add(toMonitoringParamPB(param, valueMeta).toBuilder()
                .setId(groupParam.getId()).setDisplayOrder(displayOrder).build());
            if (displayOrder >= nextDisplayOrder) nextDisplayOrder = displayOrder + 1;
        }

        boolean isBalanceOutGroup = group.getGroupType() == MonEnums.GROUP_TYPE_BALANCE
            && group.getBalanceRole() == MonEnums.BALANCE_TYPE_OUT;
        if (isBalanceOutGroup && tubeParamCodes != null) {
            for (String code : tubeParamCodes) {
                MonitoringParam param = paramsByCode.get(code);
                if (param == null) {
                    log.error("MonitoringParam not found for balance out tube code: {}, deptId {}",
                        code, deptId);
                    continue;
                }
                ValueMetaPB valueMeta = ProtoUtils.decodeValueMeta(param.getTypePb());
                if (valueMeta == null) {
                    log.error("ValueMetaPB not found for balance out tube code: {}, deptId {}",
                        code, deptId);
                    continue;
                }
                paramPbs.add(toMonitoringParamPB(param, valueMeta).toBuilder()
                    .setDisplayOrder(nextDisplayOrder++).build());
                for (MonitoringParamPB template : tubeDetailTemplates) {
                    paramPbs.add(template.toBuilder().setCode(code + "_" + template.getCode())
                        .build());
                }
            }
        }

        MonitoringGroupBetaPB.Builder builder = MonitoringGroupBetaPB.newBuilder()
            .setId(group.getId())
            .setName(group.getName())
            .setGroupType(group.getGroupType())
            .setDisplayOrder(group.getDisplayOrder())
            .addAllParam(paramPbs);
        if (group.hasBalanceRole()) builder.setBalanceRole(group.getBalanceRole());
        if (group.hasSumValueMeta()) builder.setSumValueMeta(group.getSumValueMeta());
        return builder.build();
    }

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
