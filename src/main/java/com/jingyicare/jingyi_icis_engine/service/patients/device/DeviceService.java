package com.jingyicare.jingyi_icis_engine.service.patients.device;

import static com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceEnums.*;
import static com.jingyicare.jingyi_icis_engine.utils.ReturnCodeUtils.genericResponse;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.DeviceInfo;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddDeviceInfoReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddDeviceInfoResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeleteDeviceInfoReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDeviceInfoReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DevicePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.repository.patients.DeviceInfoJdbc;
import com.jingyicare.jingyi_icis_engine.repository.patients.DeviceInfoJdbc.DeviceKey;
import com.jingyicare.jingyi_icis_engine.repository.patients.DeviceInfoRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.DeviceInfoSpecification;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientDeviceRepository;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.ReturnCodeUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
@Slf4j
public class DeviceService {
    private final List<String> statusCodeMsgList;
    private final UserQuery userQuery;
    private final DeviceInfoRepository deviceRepo;
    private final PatientDeviceRepository patientDevRepo;
    private final JdbcTemplate jdbcTemplate;
    private final LockExecutor lockExecutor;

    public DeviceService(
        ProtoConfig protoConfig,
        UserQuery userQuery,
        DeviceInfoRepository deviceRepo,
        PatientDeviceRepository patientDevRepo,
        JdbcTemplate jdbcTemplate,
        LockExecutor lockExecutor
    ) {
        this.statusCodeMsgList = protoConfig.getConfig().getText().getStatusCodeMsgList();
        this.userQuery = userQuery;
        this.deviceRepo = deviceRepo;
        this.patientDevRepo = patientDevRepo;
        this.jdbcTemplate = jdbcTemplate;
        this.lockExecutor = lockExecutor;
    }

    public List<DeviceInfo> searchActiveDevices(GetDeviceInfoReq filter) {
        return deviceRepo.findAll(DeviceInfoSpecification.isActive()
            .and(DeviceInfoSpecification.hasDepartmentId(filter.getDeptId()))
            .and(DeviceInfoSpecification.hasDeviceId(filter.getDeviceId()))
            .and(DeviceInfoSpecification.hasDeviceBedNumber(filter.getDeviceBedNum()))
            .and(DeviceInfoSpecification.hasDeviceType(filter.getDeviceType()))
            .and(DeviceInfoSpecification.hasDeviceName(filter.getDeviceName())));
    }

    public List<DeviceInfo> findDevicesAtBed(String deptId, String deviceBedNumber) {
        if (StrUtils.isBlank(deviceBedNumber)) return List.of();
        return deviceRepo.findByDepartmentIdAndDeviceBedNumberAndIsDeletedFalse(
            deptId, deviceBedNumber);
    }

    // 历史绑定需要读取已软删除设备的信息。
    public List<DeviceInfo> findDevicesByIds(Collection<Integer> deviceIds) {
        if (deviceIds.isEmpty()) return List.of();
        return deviceRepo.findAllById(deviceIds);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<DeviceInfo> lockDeviceById(int deviceId) {
        if (!DeviceInfoJdbc.lockById(jdbcTemplate, deviceId)) return Optional.empty();
        return deviceRepo.findById(deviceId);
    }

    public AddDeviceInfoResp addDeviceInfo(String json) {
        final AddDeviceInfoReq req;
        try {
            req = ProtoUtils.parseJsonToProto(json, AddDeviceInfoReq.newBuilder());
        } catch (Exception e) {
            log.error("解析新增设备请求失败", e);
            return addError(StatusCode.PARSE_JSON_FAILED);
        }
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return addError(StatusCode.ACCOUNT_NOT_FOUND);

        DevicePB device = normalizeDeviceInfo(req.getDeviceInfo());
        Integer upstreamId = upstreamIdToLock(device);
        Supplier<Map<Integer, DeviceKey>> loadKeys = () -> upstreamId == null
            ? Map.of() : DeviceInfoJdbc.findActiveKeys(jdbcTemplate, null, upstreamId);
        return lockExecutor.executePlanned(loadKeys, plan -> deviceLockKeys(device, plan.values()),
            plan -> plan.equals(loadKeys.get()), plan -> {
                if (deviceRepo.findByDepartmentIdAndDeviceNameAndIsDeletedFalse(
                    device.getDepartmentId(), device.getDeviceName()).isPresent()) {
                    return addError(StatusCode.DEVICE_INFO_ALREADY_EXISTS);
                }
                if (deviceRepo.existsByDeviceIpAndIsDeletedFalse(device.getDeviceIp())) {
                    return addError(StatusCode.DEVICE_IP_ALREADY_USED);
                }
                StatusCode sourceStatus = validateDeviceSourceConfig(device, null, plan);
                if (sourceStatus != StatusCode.OK) return addError(sourceStatus);

                DeviceInfo entity = deviceRepo.save(toModifiedDeviceEntity(device, account));
                log.info("新增设备 id={}，操作人={}", entity.getId(), account.acctName());
                return AddDeviceInfoResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                    .setId(entity.getId()).build();
            });
    }

    public GenericResp updateDeviceInfo(String json) {
        final AddDeviceInfoReq req;
        try {
            req = ProtoUtils.parseJsonToProto(json, AddDeviceInfoReq.newBuilder());
        } catch (Exception e) {
            log.error("解析更新设备请求失败", e);
            return genericResponse(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED);
        }
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return genericResponse(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND);
        }

        DevicePB device = normalizeDeviceInfo(req.getDeviceInfo());
        int id = device.getId();
        Integer upstreamId = upstreamIdToLock(device);
        Supplier<Map<Integer, DeviceKey>> loadKeys =
            () -> DeviceInfoJdbc.findActiveKeys(jdbcTemplate, id, upstreamId);
        return lockExecutor.executePlanned(loadKeys, plan -> deviceLockKeys(device, plan.values()),
            plan -> plan.equals(loadKeys.get()), plan -> {
                if (!plan.containsKey(id)) {
                    return genericResponse(statusCodeMsgList, StatusCode.DEVICE_INFO_NOT_EXISTS);
                }

                DeviceInfo sameName = deviceRepo.findByDepartmentIdAndDeviceNameAndIsDeletedFalse(
                    device.getDepartmentId(), device.getDeviceName()).orElse(null);
                if (sameName != null && sameName.getId() != id) {
                    return genericResponse(statusCodeMsgList, StatusCode.DEVICE_INFO_ALREADY_EXISTS);
                }
                String ip = device.getDeviceIp();
                if (deviceRepo.existsByDeviceIpAndIsDeletedFalseAndIdNot(ip, id)) {
                    return genericResponse(statusCodeMsgList, StatusCode.DEVICE_IP_ALREADY_USED);
                }
                StatusCode sourceStatus = validateDeviceSourceConfig(device, id, plan);
                if (sourceStatus != StatusCode.OK) {
                    return genericResponse(statusCodeMsgList, sourceStatus);
                }

                DeviceInfo entity = toModifiedDeviceEntity(device, account);
                entity.setId(id);
                deviceRepo.save(entity);
                log.info("更新设备 id={}，操作人={}", id, account.acctName());
                return genericResponse(statusCodeMsgList, StatusCode.OK);
            });
    }

    private static Integer upstreamIdToLock(DevicePB device) {
        return device.getSourceMode() == SOURCE_MODE_NON_DIRECT_CONNECT
            && device.getUpstreamDeviceId() > 0 ? device.getUpstreamDeviceId() : null;
    }

    private static List<String> deviceLockKeys(DevicePB device, Collection<DeviceKey> keys) {
        List<String> lockKeys = new ArrayList<>();
        lockKeys.add(LockKeys.deviceInfo(device.getDepartmentId(), device.getDeviceName()));
        // IP 按全院查重；没有 IP 的设备落库为 NULL，可同时存在。
        if (!device.getDeviceIp().isEmpty()) lockKeys.add(LockKeys.deviceIp(device.getDeviceIp()));
        for (DeviceKey key : keys) {
            lockKeys.add(LockKeys.deviceInfo(key.deptId(), key.deviceName()));
        }
        return lockKeys;
    }

    private static DeviceInfo toModifiedDeviceEntity(DevicePB device, CurrentAccount account) {
        DeviceInfo entity = DeviceMapper.toDeviceInfoEntity(device);
        entity.setModifiedBy(account.autoAcctId());
        entity.setModifiedByAccountName(account.acctName());
        entity.setModifiedAt(TimeUtils.getNowUtc());
        return entity;
    }

    public GenericResp deleteDeviceInfo(String json) {
        final DeleteDeviceInfoReq req;
        try {
            req = ProtoUtils.parseJsonToProto(json, DeleteDeviceInfoReq.newBuilder());
        } catch (Exception e) {
            log.error("解析设备删除请求失败", e);
            return genericResponse(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED);
        }
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return genericResponse(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND);
        }

        int id = req.getId();
        Supplier<DeviceKey> loadKey = () -> DeviceInfoJdbc.findActiveKeys(jdbcTemplate, id, null)
            .get(id);
        StatusCode status = lockExecutor.executePlannedIfPresent(
            loadKey,
            key -> List.of(LockKeys.deviceInfo(key.deptId(), key.deviceName())),
            key -> key.equals(loadKey.get()),
            key -> {
                // 与患者绑定共用设备行锁，检查当前绑定和软删除必须在同一事务内完成。
                DeviceInfo device = lockDeviceById(id).orElse(null);
                if (device == null || Boolean.TRUE.equals(device.getIsDeleted())) {
                    return StatusCode.OK;
                }
                if (patientDevRepo
                    .existsByDeviceIdAndUnbindingTimeIsNullAndIsDeletedFalse(id)) {
                    return StatusCode.DEVICE_ALREADY_BINDED;
                }
                LocalDateTime now = TimeUtils.getNowUtc();
                device.setIsDeleted(true);
                device.setDeletedBy(account.autoAcctId());
                device.setDeletedAt(now);
                device.setModifiedBy(account.autoAcctId());
                device.setModifiedByAccountName(account.acctName());
                device.setModifiedAt(now);
                deviceRepo.save(device);
                log.info("删除设备 id={}，操作人={}", id, account.acctName());
                return StatusCode.OK;
            },
            StatusCode.OK
        );
        return genericResponse(statusCodeMsgList, status);
    }

    private static DevicePB normalizeDeviceInfo(DevicePB device) {
        if (device.getSourceMode() == SOURCE_MODE_INBOUND_SERVER) {
            return device.toBuilder().clearDevicePort().build();
        }
        if (device.getSourceMode() == SOURCE_MODE_NON_DIRECT_CONNECT) {
            return device.toBuilder().clearDevicePort()
                .setSourceTopology(SOURCE_TOPOLOGY_CENTRAL_STATION_FANOUT).build();
        }
        return device;
    }

    private StatusCode validateDeviceSourceConfig(
        DevicePB device, Integer id, Map<Integer, DeviceKey> plan
    ) {
        int mode = device.getSourceMode();
        int topology = device.getSourceTopology();
        int upstreamId = device.getUpstreamDeviceId();
        if (mode == SOURCE_MODE_INBOUND_SERVER || mode == SOURCE_MODE_OUTBOUND_CLIENT) {
            if (upstreamId != 0 || (topology != SOURCE_TOPOLOGY_SINGLE_DEVICE
                && topology != SOURCE_TOPOLOGY_CENTRAL_STATION_FANOUT)) {
                return invalidSourceConfig(device);
            }
            if (mode == SOURCE_MODE_OUTBOUND_CLIENT && StrUtils.isBlank(device.getDevicePort())) {
                return invalidSourceConfig(device);
            }
            return StatusCode.OK;
        }

        // source_mode == SOURCE_MODE_NON_DIRECT_CONNECT
        // 非直连设备必须以同科室的直连中央站为上游。
        if (mode != SOURCE_MODE_NON_DIRECT_CONNECT || Objects.equals(id, upstreamId)
            || !plan.containsKey(upstreamId)) return invalidSourceConfig(device);

        DeviceInfo upstream = deviceRepo.findById(upstreamId).orElse(null);
        if (upstream == null || Boolean.TRUE.equals(upstream.getIsDeleted())
            || !device.getDepartmentId().equals(upstream.getDepartmentId())
            || !Objects.equals(upstream.getDeviceType(), DEVICE_TYPE_CENTRAL_STATION)
            || (!Objects.equals(upstream.getSourceMode(), SOURCE_MODE_INBOUND_SERVER)
                && !Objects.equals(upstream.getSourceMode(), SOURCE_MODE_OUTBOUND_CLIENT))) {
            return invalidSourceConfig(device);
        }
        return StatusCode.OK;
    }

    private static StatusCode invalidSourceConfig(DevicePB device) {
        log.warn("设备来源配置无效：科室={}，设备={}，来源模式={}，拓扑={}，上游设备={}",
            device.getDepartmentId(), device.getDeviceName(), device.getSourceMode(),
            device.getSourceTopology(), device.getUpstreamDeviceId());
        return StatusCode.DEVICE_SOURCE_CONFIG_INVALID;
    }

    private AddDeviceInfoResp addError(StatusCode code) {
        return AddDeviceInfoResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, code)).build();
    }
}
