package com.jingyicare.jingyi_icis_engine.service.monitorings.meta;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddMonitoringParamReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetMonitoringParamReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetMonitoringParamResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetMonitoringParamsReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetMonitoringParamsResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringModalOptionPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamModalOptionsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.MonitoringParamHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.MonitoringParamRepository;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonEnums;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonMapper;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.ReturnCodeUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
@Slf4j
public class MonMetaService {
    public MonMetaService(
        Config config,
        UserQuery userQuery,
        MonMetaReader monMetaReader,
        DeptMonMetaReader deptMonMetaReader,
        MonitoringParamRepository monitoringParamRepository,
        MonitoringParamHistoryRepository monitoringParamHistoryRepository,
        LockExecutor lockExecutor
    ) {
        this.userQuery = userQuery;
        this.monMetaReader = monMetaReader;
        this.deptMonMetaReader = deptMonMetaReader;
        this.monitoringParamRepository = monitoringParamRepository;
        this.monitoringParamHistoryRepository = monitoringParamHistoryRepository;
        this.lockExecutor = lockExecutor;
        this.statusCodeMsgList = config.getText().getStatusCodeMsgList();
        this.monitoringPb = config.getMonitoring();
        this.paramModalOptionsByCode = monitoringPb.getParamModalOptionList().stream()
            .collect(Collectors.toMap(
                MonitoringParamModalOptionsPB::getParamCode,
                MonitoringParamModalOptionsPB::getOptionList));
    }

    public GetMonitoringParamsResp getMonitoringParams(String requestJson) {
        final GetMonitoringParamsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(requestJson, GetMonitoringParamsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse get monitoring params request", e);
            return GetMonitoringParamsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        List<MonitoringParam> globalParams = monMetaReader.listGlobalParams();
        String deptId = req.getDepartmentId();
        List<MonitoringParamPB> params;
        if (deptId.isBlank()) {
            params = globalParams.stream()
                .sorted(Comparator.comparing(MonitoringParam::getDisplayOrder))
                .map(param -> MonMapper.toMonitoringParamPB(param, null).toBuilder()
                    .clearNameEn()
                    .clearCategory()
                    .clearUiModalCode()
                    .clearChartSign()
                    .clearDisplayOrder()
                    .build())
                .toList();
        } else {
            Map<String, DeptMonitoringParam> overrides = deptMonMetaReader
                .listParamOverridesByDept(deptId).stream()
                .collect(Collectors.toMap(
                    override -> override.getId().getCode(), override -> override));
            params = globalParams.stream().map(param -> {
                DeptMonitoringParam override = overrides.get(param.getCode());
                ValueMetaPB valueMeta = override == null ? null : Objects.requireNonNullElse(
                    ProtoUtils.decodeValueMeta(override.getTypePb()),
                    ValueMetaPB.getDefaultInstance());
                return MonMapper.toMonitoringParamPB(param, valueMeta);
            }).toList();
        }

        return GetMonitoringParamsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllParams(params)
            .build();
    }

    public GetMonitoringParamResp getMonitoringParam(String requestJson) {
        final GetMonitoringParamReq req;
        try {
            req = ProtoUtils.parseJsonToProto(requestJson, GetMonitoringParamReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse get monitoring param request", e);
            return GetMonitoringParamResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String code = req.getParamCode();
        MonitoringParam param = monMetaReader.findGlobalParam(code).orElse(null);
        if (param == null) {
            return GetMonitoringParamResp.newBuilder().setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.MONITORING_PARAM_CODE_NOT_EXIST)).build();
        }

        return GetMonitoringParamResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setParam(MonMapper.toMonitoringParamPB(param, null).toBuilder()
                .clearDisplayOrder()
                .setUiModalCode(Objects.requireNonNullElse(param.getUiModalCode(), "")))
            .addAllDeptParam(deptMonMetaReader.listParamOverridesByCode(code).stream()
                .map(override -> MonMapper.toDeptMonitoringParamPB(param, override)).toList())
            .addAllModalOption(paramModalOptionsByCode.getOrDefault(code, List.of()))
            .build();
    }

    public void initMonitoringParams() {
        // 整批持有全局锁，保留已有参数顺序并按配置追加缺失参数。
        lockExecutor.execute(List.of(LockKeys.monitoringParam()), () -> {
            List<MonitoringParam> existingParams = monMetaReader.listGlobalParams().stream()
                .sorted(Comparator.comparing(MonitoringParam::getDisplayOrder)
                    .thenComparing(MonitoringParam::getCode))
                .toList();
            Set<String> existingCodes = existingParams.stream()
                .map(MonitoringParam::getCode).collect(Collectors.toSet());
            List<MonitoringParam> paramsToSave = new ArrayList<>();
            for (int i = 0; i < existingParams.size(); i++) {
                MonitoringParam param = existingParams.get(i);
                int displayOrder = i + 1;
                if (param.getDisplayOrder() != displayOrder) {
                    param.setDisplayOrder(displayOrder);
                    paramsToSave.add(param);
                }
            }
            int nextDisplayOrder = existingParams.size() + 1;
            for (MonitoringParamPB paramPb : monitoringPb.getMonitoringParamList()) {
                if (existingCodes.add(paramPb.getCode())) {
                    paramsToSave.add(
                        MonMapper.toMonitoringParamEntity(
                        paramPb.toBuilder().setDisplayOrder(nextDisplayOrder++).build()));
                }
            }
            if (!paramsToSave.isEmpty()) {
                monitoringParamRepository.saveAll(paramsToSave);
                log.info("Saved {} monitoring params", paramsToSave.size());
            }
            return null;
        });
    }

    public GenericResp addMonitoringParam(String requestJson) {
        final AddMonitoringParamReq req;
        try {
            req = ProtoUtils.parseJsonToProto(requestJson, AddMonitoringParamReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse add monitoring param request", e);
            return ReturnCodeUtils.genericResponse(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED);
        }
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return ReturnCodeUtils.genericResponse(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND);
        }

        MonitoringParamPB paramPb = req.getParam();
        StatusCode status = validateRequestParam(paramPb);
        if (status != StatusCode.OK) {
            return ReturnCodeUtils.genericResponse(statusCodeMsgList, status);
        }
        String code = paramPb.getCode();

        try {
            StatusCode result = lockExecutor.execute(List.of(LockKeys.monitoringParam()), () -> {
                if (monMetaReader.findGlobalParam(code).isPresent()) {
                    return StatusCode.MONITORING_PARAM_CODE_EXIST;
                }
                int displayOrder = paramPb.getDisplayOrder() > 0
                    ? paramPb.getDisplayOrder() : nextDisplayOrder();
                MonitoringParam param = monitoringParamRepository.save(
                    MonMapper.toMonitoringParamEntity(
                        paramPb.toBuilder().setDisplayOrder(displayOrder).build()));
                monitoringParamHistoryRepository.save(MonMapper.toMonitoringParamHistoryEntity(
                    param, account.autoAcctId(), true, TimeUtils.getNowUtc()));
                log.info("Added monitoring param {} by {}", code, account.autoAcctId());
                return StatusCode.OK;
            });
            return ReturnCodeUtils.genericResponse(statusCodeMsgList, result);
        } catch (Exception e) {
            log.error("Failed to add monitoring param {} by {}", code, account.autoAcctId(), e);
            return ReturnCodeUtils.genericResponse(
                statusCodeMsgList, StatusCode.INTERNAL_EXCEPTION);
        }
    }

    public GenericResp updateMonitoringParam(String requestJson) {
        final AddMonitoringParamReq req;
        try {
            req = ProtoUtils.parseJsonToProto(requestJson, AddMonitoringParamReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse update monitoring param request", e);
            return ReturnCodeUtils.genericResponse(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED);
        }
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return ReturnCodeUtils.genericResponse(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND);
        }

        MonitoringParamPB paramPb = req.getParam();
        StatusCode status = validateRequestParam(paramPb);
        if (status != StatusCode.OK) {
            return ReturnCodeUtils.genericResponse(statusCodeMsgList, status);
        }
        String code = paramPb.getCode();

        try {
            StatusCode result = lockExecutor.execute(List.of(LockKeys.monitoringParam()), () -> {
                MonitoringParam param = monMetaReader.findGlobalParam(code).orElse(null);
                if (param == null) return StatusCode.MONITORING_PARAM_CODE_NOT_EXIST;

                int displayOrder = paramPb.getDisplayOrder() > 0
                    ? paramPb.getDisplayOrder() : nextDisplayOrder();
                LocalDateTime now = TimeUtils.getNowUtc();
                monitoringParamHistoryRepository.save(MonMapper.toMonitoringParamHistoryEntity(
                    param, account.autoAcctId(), true, now));
                MonitoringParam updated = monitoringParamRepository.save(
                    MonMapper.toMonitoringParamEntity(
                        paramPb.toBuilder().setDisplayOrder(displayOrder).build()));
                monitoringParamHistoryRepository.save(MonMapper.toMonitoringParamHistoryEntity(
                    updated, account.autoAcctId(), false, now));
                log.info("Updated monitoring param {} by {}", code, account.autoAcctId());
                return StatusCode.OK;
            });
            return ReturnCodeUtils.genericResponse(statusCodeMsgList, result);
        } catch (Exception e) {
            log.error("Failed to update monitoring param {} by {}", code, account.autoAcctId(), e);
            return ReturnCodeUtils.genericResponse(
                statusCodeMsgList, StatusCode.INTERNAL_EXCEPTION);
        }
    }

    public StatusCode deleteMonitoringParam(String paramCode) {
        if (StrUtils.isBlank(paramCode)) return StatusCode.MONITORING_PARAM_CODE_EMPTY;

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return StatusCode.ACCOUNT_NOT_FOUND;

        try {
            return lockExecutor.execute(List.of(LockKeys.monitoringParam()), () -> {
                MonitoringParam param = monMetaReader.findGlobalParam(paramCode).orElse(null);
                if (param == null) return StatusCode.OK;

                if (deptMonMetaReader.hasDeptOverride(paramCode)) {
                    return StatusCode.MONITORING_PARAM_HAS_DEPT_CONFIG;
                }
                monitoringParamHistoryRepository.save(MonMapper.toMonitoringParamHistoryEntity(
                    param, account.autoAcctId(), true, TimeUtils.getNowUtc()));
                monitoringParamRepository.delete(param);
                log.info("Deleted monitoring param {} by {}", paramCode, account.autoAcctId());
                return StatusCode.OK;
            });
        } catch (Exception e) {
            log.error("Failed to delete monitoring param {} by {}",
                paramCode, account.autoAcctId(), e);
            return StatusCode.INTERNAL_EXCEPTION;
        }
    }

    // 挂起管道事务，由 LockExecutor 在独立事务内取得字典锁。
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void upsertDrainageParam(String paramCode, String name) {
        String nameEn = StrUtils.toPinyin(name);
        lockExecutor.execute(List.of(LockKeys.monitoringParam()), () -> {
            MonitoringParam param = monMetaReader.findGlobalParam(paramCode).orElse(null);
            if (param != null) {
                param.setName(name);
                param.setNameEn(nameEn);
                monitoringParamRepository.save(param);
            } else {
                MonitoringParamPB paramPb = monitoringPb.getDefaultDrainageParam().toBuilder()
                    .setCode(paramCode)
                    .setName(name)
                    .setNameEn(nameEn)
                    .setDisplayOrder(nextDisplayOrder())
                    .build();
                monitoringParamRepository.save(MonMapper.toMonitoringParamEntity(paramPb));
            }
            return null;
        });
    }

    public StatusCode validateParamValueMeta(int balanceType, ValueMetaPB valueMeta) {
        if (balanceType != MonEnums.BALANCE_TYPE_IN && balanceType != MonEnums.BALANCE_TYPE_OUT) {
            return StatusCode.OK;
        }
        if (valueMeta.getIsMultipleSelection()) {
            return StatusCode.MONITORING_PARAM_MULTI_SELECTION_NOT_ALLOWED;
        }
        if (!monitoringPb.getBalanceParamTypeList().contains(valueMeta.getValueType())) {
            return StatusCode.MONITORING_PARAM_BALANCE_TYPE_VALUE_TYPE_NOT_MATCH;
        }
        return StatusCode.OK;
    }

    private StatusCode validateRequestParam(MonitoringParamPB paramPb) {
        if (paramPb.getCode().isBlank()) return StatusCode.MONITORING_PARAM_CODE_EMPTY;
        return validateParamValueMeta(paramPb.getBalanceType(), paramPb.getValueMeta());
    }

    private int nextDisplayOrder() {
        return monitoringParamRepository.findTopByOrderByDisplayOrderDesc()
            .map(param -> param.getDisplayOrder() + 1).orElse(1);
    }

    private final UserQuery userQuery;
    private final MonMetaReader monMetaReader;
    private final DeptMonMetaReader deptMonMetaReader;
    private final MonitoringParamRepository monitoringParamRepository;
    private final MonitoringParamHistoryRepository monitoringParamHistoryRepository;
    private final LockExecutor lockExecutor;
    private final List<String> statusCodeMsgList;
    private final MonitoringPB monitoringPb;
    private final Map<String, List<MonitoringModalOptionPB>> paramModalOptionsByCode;
}
