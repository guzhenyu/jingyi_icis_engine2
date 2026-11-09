package com.jingyicare.jingyi_icis_engine.service.monitorings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.MonitoringParamHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.MonitoringParamRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

@Service
@Slf4j
public class MonitoringMetaService {
    public MonitoringMetaService(
        ConfigProtoService protoService,
        UserService userService,
        MonitoringParamRepository monitoringParamRepository,
        DeptMonitoringParamRepository deptMonitoringParamRepository,
        MonitoringParamHistoryRepository monitoringParamHistoryRepository,
        LockExecutor lockExecutor
    ) {
        this.protoService = protoService;
        this.userService = userService;
        this.monitoringParamRepository = monitoringParamRepository;
        this.deptMonitoringParamRepository = deptMonitoringParamRepository;
        this.monitoringParamHistoryRepository = monitoringParamHistoryRepository;
        this.lockExecutor = lockExecutor;
        this.monitoringPb = protoService.getConfig().getMonitoring();
    }

    public void initMonitoringParams() {
        // 整批初始化只取得一次列表锁，并在同一事务中提交或回滚。
        lockExecutor.execute(List.of(LockKeys.monitoringParam()), () -> {
            List<MonitoringParam> existingParams = monitoringParamRepository.findAll().stream()
                .sorted(Comparator.comparing(MonitoringParam::getDisplayOrder)
                    .thenComparing(MonitoringParam::getCode))
                .toList();
            Set<String> existingCodes = existingParams.stream()
                .map(MonitoringParam::getCode).collect(Collectors.toSet());
            List<MonitoringParam> paramsToSave = new ArrayList<>();
            // 保留已有参数的相对次序，仅将序号校正为连续值。
            for (int i = 0; i < existingParams.size(); i++) {
                MonitoringParam param = existingParams.get(i);
                int displayOrder = i + 1;
                if (param.getDisplayOrder() != displayOrder) {
                    param.setDisplayOrder(displayOrder);
                    paramsToSave.add(param);
                }
            }
            // 缺失参数按配置顺序追加，并去重配置中的重复编码。
            int nextDisplayOrder = existingParams.size() + 1;
            for (MonitoringParamPB paramPb : monitoringPb.getMonitoringParamList()) {
                if (!existingCodes.add(paramPb.getCode())) continue;
                paramsToSave.add(
                    MonitoringMapper.toMonitoringParamEntity(paramPb, nextDisplayOrder++));
            }
            if (!paramsToSave.isEmpty()) {
                monitoringParamRepository.saveAll(paramsToSave);
                log.info("Saved {} MonitoringParams", paramsToSave.size());
            }
            return null;
        });
    }

    public GenericResp addMonitoringParam(String addMonitoringParamReqJson) {
        final AddMonitoringParamReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                addMonitoringParamReqJson, AddMonitoringParamReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return response(StatusCode.ACCOUNT_NOT_FOUND);
        }
        final String accountId = accountWithAutoId.getFirst();

        // 验证请求中的 MonitoringParamPB 对象。
        MonitoringParamPB paramPb = req.getParam();
        if (paramPb == null || paramPb.getCode().isBlank()) {
            log.warn("Invalid or missing MonitoringParamPB in request. User: {}", accountId);
            return response(StatusCode.MONITORING_PARAM_CODE_EMPTY);
        }
        StatusCode statusCode = MonitoringMapper.isValidParamPb(paramPb, monitoringPb);
        if (statusCode != StatusCode.OK) {
            log.warn("Invalid MonitoringParamPB {} in request. User: {}", paramPb, accountId);
            return response(statusCode);
        }
        final String code = paramPb.getCode();

        try {
            return lockExecutor.execute(List.of(LockKeys.monitoringParam()), () -> {
                // 初始化和接口新增均在取得列表锁后检查编码并分配显示顺序。
                if (monitoringParamRepository.findByCode(code).isPresent()) {
                    log.warn("MonitoringParam with code '{}' already exists. User: {}",
                        code, accountId);
                    return response(StatusCode.MONITORING_PARAM_CODE_EXIST);
                }
                MonitoringParam param = monitoringParamRepository.save(
                    MonitoringMapper.toMonitoringParamEntity(paramPb, nextDisplayOrder()));
                monitoringParamHistoryRepository.save(
                    MonitoringMapper.toMonitoringParamHistoryEntity(param, accountId));
                log.info("Successfully added MonitoringParam with code: {} by user: {}",
                    code, accountId);
                return response(StatusCode.OK);
            });
        } catch (Exception e) {
            // 在执行器完成回滚后转换错误响应，保证参数与历史记录同时提交或回滚。
            log.error("Unexpected error saving MonitoringParam with code: {} by user: {}",
                code, accountId, e);
            return response(StatusCode.INTERNAL_EXCEPTION);
        }
    }

    public GenericResp updateMonitoringParam(String updateMonitoringParamReqJson) {
        final AddMonitoringParamReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                updateMonitoringParamReqJson, AddMonitoringParamReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }

        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return response(StatusCode.ACCOUNT_NOT_FOUND);
        }
        final String accountId = accountWithAutoId.getFirst();

        // 验证请求中的 MonitoringParamPB 对象。
        MonitoringParamPB paramPb = req.getParam();
        if (paramPb == null || paramPb.getCode().isBlank()) {
            log.warn("Invalid or missing MonitoringParamPB in request. User: {}", accountId);
            return response(StatusCode.MONITORING_PARAM_CODE_EMPTY);
        }
        StatusCode statusCode = MonitoringMapper.isValidParamPb(paramPb, monitoringPb);
        if (statusCode != StatusCode.OK) {
            log.warn("Invalid MonitoringParamPB {} in request. User: {}", paramPb, accountId);
            return response(statusCode);
        }
        final String code = paramPb.getCode();

        try {
            return lockExecutor.execute(List.of(LockKeys.monitoringParam()), () -> {
                MonitoringParam param = monitoringParamRepository.findByCode(code).orElse(null);
                if (param == null) {
                    log.warn("MonitoringParam with code '{}' not found. User: {}", code, accountId);
                    return response(StatusCode.MONITORING_PARAM_CODE_NOT_EXIST);
                }
                monitoringParamHistoryRepository.save(
                    MonitoringMapper.toMonitoringParamHistoryEntity(param, accountId));
                MonitoringMapper.toMonitoringParamEntity(paramPb, param, -1);
                monitoringParamRepository.save(param);
                monitoringParamHistoryRepository.save(
                    MonitoringMapper.toMonitoringParamHistoryEntity(param, accountId, false));
                log.info("Successfully updated MonitoringParam with code: {} by user: {}",
                    code, accountId);
                return response(StatusCode.OK);
            });
        } catch (Exception e) {
            // 在执行器完成回滚后转换错误响应，避免参数或历史记录部分提交。
            log.error("Unexpected error updating MonitoringParam with code: {} by user: {}",
                code, accountId, e);
            return response(StatusCode.INTERNAL_EXCEPTION);
        }
    }

    public GenericResp deleteMonitoringParam(String deleteMonitoringParamReqJson) {
        final DeleteMonitoringParamReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                deleteMonitoringParamReqJson, DeleteMonitoringParamReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }
        final String departmentId = req.getDepartmentId();
        final String code = req.getCode();
        if (code.isBlank()) {
            log.warn("Invalid request: code is empty.");
            return response(StatusCode.MONITORING_PARAM_CODE_EMPTY);
        }
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return response(StatusCode.ACCOUNT_NOT_FOUND);
        }
        final String accountId = accountWithAutoId.getFirst();

        try {
            List<String> lockKeys = departmentId.isEmpty()
                ? List.of(LockKeys.monitoringParam())
                : List.of(LockKeys.monitoringParam(),
                    LockKeys.deptMonitoringParams(departmentId, code));
            return lockExecutor.execute(lockKeys, () -> {
                if (!departmentId.isEmpty()) {
                    Optional<DeptMonitoringParam> optionalDeptParam =
                        deptMonitoringParamRepository.findByIdDeptIdAndIdCode(departmentId, code);
                    if (optionalDeptParam.isEmpty()) {
                        log.info("DeptMonitoringParam not found: {}, dept: {}. User: {}",
                            code, departmentId, accountId);
                        return response(StatusCode.OK);
                    }
                    DeptMonitoringParam deptParam = optionalDeptParam.get();
                    // monitoringParamHistoryRepository.save(
                    //     MonitoringMapper.toMonitoringParamHistoryEntity(
                    //         deptParam, accountId, true));
                    deptMonitoringParamRepository.delete(deptParam);
                    log.info("Deleted DeptMonitoringParam with code: {} for dept: {}. User: {}",
                        code, departmentId, accountId);
                    return response(StatusCode.OK);
                }

                Optional<MonitoringParam> optionalParam =
                    monitoringParamRepository.findByCode(code);
                if (optionalParam.isEmpty()) {
                    log.info("MonitoringParam not found for code: {}. User: {}", code, accountId);
                    return response(StatusCode.OK);
                }
                List<DeptMonitoringParam> deptParams =
                    deptMonitoringParamRepository.findByIdCode(code);
                if (deptParams == null || !deptParams.isEmpty()) {
                    log.warn("Cannot delete MonitoringParam with dept config: {}. User: {}",
                        code, accountId);
                    return response(StatusCode.MONITORING_PARAM_HAS_DEPT_CONFIG);
                }
                MonitoringParam param = optionalParam.get();
                monitoringParamHistoryRepository.save(
                    MonitoringMapper.toMonitoringParamHistoryEntity(param, accountId));
                monitoringParamRepository.delete(param);
                log.info("Deleted MonitoringParam with code: {}. User: {}", code, accountId);
                return response(StatusCode.OK);
            });
        } catch (Exception e) {
            // 删除和历史记录必须在同一事务中提交或回滚。
            log.error("Failed to delete monitoring parameter for code: {} and dept: {}. User: {}",
                code, departmentId, accountId, e);
            return response(StatusCode.INTERNAL_EXCEPTION);
        }
    }

    private int nextDisplayOrder() {
        return monitoringParamRepository.findTopByOrderByDisplayOrderDesc()
            .map(param -> param.getDisplayOrder() + 1).orElse(1);
    }

    private GenericResp response(StatusCode statusCode) {
        return GenericResp.newBuilder().setRt(protoService.getReturnCode(statusCode)).build();
    }

    private final ConfigProtoService protoService;
    private final UserService userService;
    private final MonitoringParamRepository monitoringParamRepository;
    private final DeptMonitoringParamRepository deptMonitoringParamRepository;
    private final MonitoringParamHistoryRepository monitoringParamHistoryRepository;
    private final LockExecutor lockExecutor;
    private final MonitoringPB monitoringPb;
}
