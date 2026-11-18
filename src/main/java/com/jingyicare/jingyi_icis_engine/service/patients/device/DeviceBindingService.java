package com.jingyicare.jingyi_icis_engine.service.patients.device;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.DeviceInfo;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientDevice;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.BindPatientDeviceReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.BindPatientDeviceResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeviceWithBindingsPB;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDeviceBindingHistoryReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDeviceBindingHistoryResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDeviceInfoReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDeviceInfoResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetPatientDeviceBindingHistoryReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetPatientDeviceBindingHistoryResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceBindingRecordPB;
import com.jingyicare.jingyi_icis_engine.repository.patients.DeviceBindingJdbc;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientDeviceRepository;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientReader;
import com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceBindingReader.BindingHistory;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.ReturnCodeUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
@Slf4j
public class DeviceBindingService {
    public DeviceBindingService(
        ProtoConfig protoConfig,
        DeviceBindingReader bindingReader,
        PatientDeviceRepository bindingRepo,
        DeviceService deviceService,
        PatientReader patientReader,
        UserQuery userQuery,
        LockExecutor lockExecutor,
        JdbcTemplate jdbcTemplate
    ) {
        this.statusCodeMsgList = protoConfig.getConfig().getText().getStatusCodeMsgList();
        this.bindingReader = bindingReader;
        this.bindingRepo = bindingRepo;
        this.deviceService = deviceService;
        this.patientReader = patientReader;
        this.userQuery = userQuery;
        this.lockExecutor = lockExecutor;
        this.jdbcTemplate = jdbcTemplate;
    }

    public GetDeviceInfoResp getDeviceInfo(String json) {
        final GetDeviceInfoReq req;
        try {
            req = ProtoUtils.parseJsonToProto(json, GetDeviceInfoReq.newBuilder());
        } catch (Exception e) {
            log.error("解析设备列表请求失败", e);
            return GetDeviceInfoResp.newBuilder().setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.PARSE_JSON_FAILED)).build();
        }

        List<DeviceInfo> devices = deviceService.searchActiveDevices(req);
        Pair<StatusCode, Map<Integer, DeviceBindingRecordPB>> result =
            bindingReader.findCurrentBindings(devices.stream().map(DeviceInfo::getId).toList());
        if (result.getFirst() != StatusCode.OK) {
            return GetDeviceInfoResp.newBuilder().setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, result.getFirst())).build();
        }
        Map<Integer, DeviceBindingRecordPB> bindings = result.getSecond();
        List<DeviceWithBindingsPB> inlineDevices = new ArrayList<>();
        for (DeviceInfo device : devices) {
            DeviceBindingRecordPB binding = bindings.get(device.getId());
            List<DeviceBindingRecordPB> records = binding == null ? List.of() : List.of(binding);
            inlineDevices.add(DeviceMapper.toDeviceWithBindingsPB(
                device, binding != null, records));
        }
        // 移动设备在前，同组中空闲设备在前，最后按设备 ID 排序。
        inlineDevices.sort(Comparator
            .comparing((DeviceWithBindingsPB device) ->
                !StrUtils.isBlank(device.getDevice().getDeviceBedNumber()))
            .thenComparing(DeviceWithBindingsPB::getIsBinding)
            .thenComparingInt(device -> device.getDevice().getId()));
        return GetDeviceInfoResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllInlineDev(inlineDevices).build();
    }

    public GetDeviceBindingHistoryResp getDeviceBindingHistory(String json) {
        final GetDeviceBindingHistoryReq req;
        try {
            req = ProtoUtils.parseJsonToProto(json, GetDeviceBindingHistoryReq.newBuilder());
        } catch (Exception e) {
            log.error("解析设备绑定历史请求失败", e);
            return GetDeviceBindingHistoryResp.newBuilder().setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.PARSE_JSON_FAILED)).build();
        }

        LocalDateTime from = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime to = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        Pair<StatusCode, BindingHistory> result = bindingReader.findBindingHistory(
            req.getDeviceId(), from, to);
        GetDeviceBindingHistoryResp.Builder response = GetDeviceBindingHistoryResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, result.getFirst()));
        if (result.getFirst() != StatusCode.OK) return response.build();
        return response.setIsBinding(result.getSecond().isBinding())
            .addAllBinding(result.getSecond().records()).build();
    }

    public GetPatientDeviceBindingHistoryResp getPatientDeviceBindingHistory(String json) {
        final GetPatientDeviceBindingHistoryReq req;
        try {
            req = ProtoUtils.parseJsonToProto(json, GetPatientDeviceBindingHistoryReq.newBuilder());
        } catch (Exception e) {
            log.error("解析患者设备绑定历史请求失败", e);
            return GetPatientDeviceBindingHistoryResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED)).build();
        }

        PatientRecord patient = patientReader.findById(req.getPid()).orElse(null);
        if (patient == null) {
            return GetPatientDeviceBindingHistoryResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PATIENT_NOT_FOUND)).build();
        }
        LocalDateTime from = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime to = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        return GetPatientDeviceBindingHistoryResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllBinding(bindingReader.findBindingHistory(
                patient, req.getDeviceType(), from, to)).build();
    }

    public BindPatientDeviceResp bindPatientDevice(String json) {
        final BindPatientDeviceReq req;
        try {
            req = ProtoUtils.parseJsonToProto(json, BindPatientDeviceReq.newBuilder());
        } catch (Exception e) {
            log.error("解析患者设备绑定请求失败", e);
            return bindingResponse(StatusCode.PARSE_JSON_FAILED);
        }
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return bindingResponse(StatusCode.ACCOUNT_NOT_FOUND);
        int deviceId = req.getDeviceId();
        long patientId = req.getPid();

        if (!StrUtils.isBlank(req.getBindingTimeIso8601())) {
            LocalDateTime requestedTime = TimeUtils.fromIso8601String(
                req.getBindingTimeIso8601(), "UTC");
            LocalDateTime bindingTime = requestedTime == null
                ? TimeUtils.getNowUtc() : requestedTime;
            Supplier<Set<Long>> loadPatientIds = () ->
                DeviceBindingJdbc.findActivePatientIdsByDeviceId(jdbcTemplate, deviceId);
            return lockExecutor.executePlanned(loadPatientIds, patientIds -> {
                List<String> keys = new ArrayList<>();
                keys.add(LockKeys.deviceBinding(deviceId));
                keys.add(LockKeys.patientDeviceBindings(patientId));
                patientIds.forEach(id -> keys.add(LockKeys.patientDeviceBindings(id)));
                return keys;
            }, patientIds -> patientIds.equals(loadPatientIds.get()), patientIds -> {
                // 与设备删除共用行锁，读取设备后再校验绑定状态。
                DeviceInfo device = deviceService.lockDeviceById(deviceId).orElse(null);
                if (device == null || Boolean.TRUE.equals(device.getIsDeleted())) {
                    return bindingResponse(StatusCode.DEVICE_INFO_NOT_EXISTS);
                }
                // 出科与绑定共用患者锁，必须在锁内确认目标患者尚未出科。
                PatientRecord patient = patientReader.findById(patientId).orElse(null);
                if (patient == null) return bindingResponse(StatusCode.PATIENT_NOT_FOUND);
                if (PatientEnums.ADMISSION_STATUS_DISCHARGED.equals(patient.getAdmissionStatus())) {
                    return bindingResponse(StatusCode.PATIENT_NOT_IN_ICU);
                }
                List<PatientDevice> bindings = bindingReader.findActiveBindingsByDeviceId(deviceId);
                if (bindings.size() > 1) {
                    return bindingResponse(StatusCode.DEVICE_HAS_MULTIPLE_BINDINGS);
                }
                PatientDevice previous = bindings.isEmpty() ? null : bindings.getFirst();
                if (previous != null) {
                    if (previous.getPatientId() == patientId) return bindingResponse(StatusCode.OK);
                    if (previous.getBindingTime().isAfter(bindingTime)) {
                        return bindingResponse(StatusCode.BINDING_TIME_CONFLICT);
                    }
                }
                if (bindingReader.hasCurrentDeviceOfType(patientId, device.getDeviceType())) {
                    return bindingResponse(StatusCode.PATIENT_ALREADY_BINDED_TO_SAME_TYPE_DEVICE);
                }
                LocalDateTime latestUnbindingTime = bindingReader.findLatestUnbindingTime(
                    patientId, device.getDeviceType());
                if (latestUnbindingTime != null && latestUnbindingTime.isAfter(bindingTime)) {
                    return bindingResponse(
                        StatusCode.BINDING_TIME_BEFORE_UNBINDING_TIME_FOR_SAME_TYPE_DEVICE);
                }

                // 校验全部通过后再结束旧绑定，转绑允许两个时间点相等。
                LocalDateTime modifiedAt = TimeUtils.getNowUtc();
                if (previous != null) {
                    closeBinding(previous, bindingTime, account, modifiedAt);
                    bindingRepo.save(previous);
                }
                PatientDevice binding = bindingRepo.save(PatientDevice.builder()
                    .patientId(patientId).deviceId(deviceId).bindingTime(bindingTime)
                    .isDeleted(false).modifiedBy(account.autoAcctId())
                    .modifiedByAccountName(account.acctName()).modifiedAt(modifiedAt).build());
                log.info("患者绑定设备，患者={}，设备={}，操作人={}",
                    patientId, deviceId, account.acctName());
                return bindingResponse(StatusCode.OK).toBuilder().setId(binding.getId()).build();
            });
        }
        if (!StrUtils.isBlank(req.getUnbindingTimeIso8601())) {
            LocalDateTime requestedTime = TimeUtils.fromIso8601String(
                req.getUnbindingTimeIso8601(), "UTC");
            LocalDateTime unbindingTime = requestedTime == null
                ? TimeUtils.getNowUtc() : requestedTime;
            return lockExecutor.execute(List.of(LockKeys.deviceBinding(deviceId),
                LockKeys.patientDeviceBindings(patientId)), () -> {
                    DeviceInfo device = deviceService.lockDeviceById(deviceId).orElse(null);
                    if (device == null) return bindingResponse(StatusCode.DEVICE_INFO_NOT_EXISTS);
                    if (!Boolean.TRUE.equals(device.getIsDeleted())
                        && !StrUtils.isBlank(device.getDeviceBedNumber())) {
                        return bindingResponse(StatusCode.CANNOT_UNBIND_FIXED_DEVICE);
                    }
                    PatientDevice binding = bindingReader.findActiveBinding(patientId, deviceId)
                        .orElse(null);
                    if (binding == null) return bindingResponse(StatusCode.OK);
                    StatusCode status = validateUnbinding(binding, unbindingTime);
                    if (status != StatusCode.OK) return bindingResponse(status);
                    closeBinding(binding, unbindingTime, account, TimeUtils.getNowUtc());
                    bindingRepo.save(binding);
                    log.info("患者解绑设备，患者={}，设备={}，操作人={}",
                        patientId, deviceId, account.acctName());
                    return bindingResponse(StatusCode.OK);
                });
        }
        return bindingResponse(StatusCode.OK);
    }

    // 调用方已通过 LockExecutor 取得患者绑定锁；出科与全部解绑在同一事务中提交。
    @Transactional(propagation = Propagation.MANDATORY)
    public StatusCode unbindAllForDischargeInTransaction(
        long patientId, LocalDateTime unbindingTime,
        CurrentAccount account, LocalDateTime modifiedAt
    ) {
        List<PatientDevice> bindings = bindingReader.findActiveBindingsByPatientId(patientId);
        for (PatientDevice binding : bindings) {
            StatusCode status = validateUnbinding(binding, unbindingTime);
            if (status != StatusCode.OK) return status;
        }
        for (PatientDevice binding : bindings) {
            closeBinding(binding, unbindingTime, account, modifiedAt);
        }
        bindingRepo.saveAll(bindings);
        return StatusCode.OK;
    }

    private static StatusCode validateUnbinding(
        PatientDevice binding, LocalDateTime unbindingTime
    ) {
        if (binding == null || unbindingTime == null) return StatusCode.INTERNAL_EXCEPTION;
        if (!binding.getBindingTime().isBefore(unbindingTime)) {
            return StatusCode.UNBINDING_TIME_OUTDATED;
        }
        return StatusCode.OK;
    }

    private static void closeBinding(
        PatientDevice binding, LocalDateTime unbindingTime,
        CurrentAccount account, LocalDateTime modifiedAt
    ) {
        binding.setUnbindingTime(unbindingTime);
        binding.setModifiedBy(account.autoAcctId());
        binding.setModifiedByAccountName(account.acctName());
        binding.setModifiedAt(modifiedAt);
    }

    private BindPatientDeviceResp bindingResponse(StatusCode status) {
        return BindPatientDeviceResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, status)).build();
    }

    private final List<String> statusCodeMsgList;
    private final DeviceBindingReader bindingReader;
    private final PatientDeviceRepository bindingRepo;
    private final DeviceService deviceService;
    private final PatientReader patientReader;
    private final UserQuery userQuery;
    private final LockExecutor lockExecutor;
    private final JdbcTemplate jdbcTemplate;
}
