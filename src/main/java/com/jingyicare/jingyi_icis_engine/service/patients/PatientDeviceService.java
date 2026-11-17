package com.jingyicare.jingyi_icis_engine.service.patients;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.certs.*;
import com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceMapper;
import com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceService;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class PatientDeviceService {
    public static class UsageHistory<T> {
        public UsageHistory() {
            this.usageRecords = new ArrayList<>();
            this.endTime = null;
        }
        public T getCurUsageId() {
            if (usageRecords == null || usageRecords.isEmpty()) return null;
            return usageRecords.get(usageRecords.size() - 1).getFirst();
        }
        public List<Pair<T, LocalDateTime>> usageRecords;
        public LocalDateTime endTime;  // null表示正在使用中
    }

    public PatientDeviceService(
        @Autowired ProtoConfig protoConfig,
        @Autowired PatientConfig patientConfig,
        @Autowired UserQuery userQuery,
        @Autowired CertificateService certificateService,
        @Autowired PatientRecordRepository patientRepo,
        @Autowired BedConfigRepository bedConfigRepo,
        @Autowired BedCountRepository bedCountRepo,
        @Autowired PatientBedHistoryRepository bedHistoryRepo,
        @Autowired DeviceService deviceService,
        @Autowired PatientDeviceRepository patientDevRepo
    ) {
        Config config = protoConfig.getConfig();
        this.statusCodeMsgList = config.getText().getStatusCodeMsgList();
        this.ZONE_ID = config.getZoneId();

        this.patientConfig = patientConfig;
        this.userQuery = userQuery;
        this.certificateService = certificateService;
        this.patientRepo = patientRepo;
        this.bedConfigRepo = bedConfigRepo;
        this.bedCountRepo = bedCountRepo;
        this.bedHistoryRepo = bedHistoryRepo;
        this.deviceService = deviceService;
        this.patientDevRepo = patientDevRepo;
    }

    @Transactional
    public GetBedConfigResp getBedConfig(String getBedConfigReqJson) {
        final GetBedConfigReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getBedConfigReqJson, GetBedConfigReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GetBedConfigResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }
        final String deptId = req.getDeptId();

        // 查找在线病人
        Map<String/*his_bed_number*/, PatientRecord> patientMap = new HashMap<>();
        for (PatientRecord patient : patientRepo.findByDeptIdAndAdmissionStatus(
            deptId, PatientEnums.ADMISSION_STATUS_ADMITTED)) {
            if (patientMap.containsKey(patient.getHisBedNumber())) {
                log.error("Duplicate his bed number found for patients: {}, {}",
                    patientMap.get(patient.getHisBedNumber()).getId(), patient.getId()
                );
            }
            patientMap.put(patient.getHisBedNumber(), patient);
        }

        // 查找床位配置
        List<BedConfig> bedConfigs = StrUtils.isBlank(deptId) ?
            bedConfigRepo.findAllByIsDeletedFalse() : bedConfigRepo.findByDepartmentIdAndIsDeletedFalse(deptId);
        bedConfigs = bedConfigs.stream()
            .sorted((a, b) -> a.getHisBedNumber().compareTo(b.getHisBedNumber()))
            .collect(Collectors.toList());
        
        // 构建返回
        GetBedConfigResp.Builder respBuilder = GetBedConfigResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK));
        for (BedConfig bedConfig : bedConfigs) {
            final PatientRecord patient = patientMap.get(bedConfig.getHisBedNumber());
            BedConfigPB bedConfigPB = patientConfig.getBedConfigPB(bedConfig, patient);
            respBuilder.addBedConfig(bedConfigPB);
        }
        return respBuilder.build();
    }

    @Transactional
    public AddBedConfigResp addBedConfig(String addBedConfigReqJson) {
        final AddBedConfigReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addBedConfigReqJson, AddBedConfigReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return AddBedConfigResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }
        final String deptId = req.getBedConfig().getDepartmentId();
        final String hisBedNumber = req.getBedConfig().getHisBedNumber();

        // 获取当前用户信息
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("Account not found");
            return AddBedConfigResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        // 查找床位配置
        List<BedConfig> bedConfigs = bedConfigRepo.findByDepartmentIdAndIsDeletedFalse(deptId)
            .stream()
            .filter(b -> b.getHisBedNumber().equals(hisBedNumber))
            .collect(Collectors.toList());
        if (!bedConfigs.isEmpty()) {
            log.error("BedConfig already exists: {}", bedConfigs.get(0));
            return AddBedConfigResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.BED_CONFIG_ALREADY_EXISTS))
                .build();
        }
        BedConfig bedConfig = bedConfigRepo.findByDepartmentIdAndDisplayBedNumberAndIsDeletedFalse(
            deptId, req.getBedConfig().getDisplayBedNumber()
        ).orElse(null);
        if (bedConfig != null) {
            log.error("BedConfig already exists: {}", bedConfig);
            return AddBedConfigResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.BED_CONFIG_DISPLAY_NAME_ALREADY_EXISTS))
                .build();
        }
        bedConfig = bedConfigRepo.findByDepartmentIdAndDeviceBedNumberAndIsDeletedFalse(
            deptId, req.getBedConfig().getDeviceBedNumber()
        ).orElse(null);
        if (bedConfig != null) {
            log.error("BedConfig already exists: {}", bedConfig);
            return AddBedConfigResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.BED_CONFIG_DEVICE_NAME_ALREADY_EXISTS))
                .build();
        }

        // 新增或恢复已删除床位时，只占用其对应床位类型的一份授权
        if (!certificateService.checkBedAvailable(deptId, req.getBedConfig().getBedType(), 1)) {
            return AddBedConfigResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.BED_CONFIG_BED_NUMBER_LIMIT_EXCEEDED))
                .build();
        }

        // 新增床位配置
        bedConfig = getBedConfig(req.getBedConfig(), account.autoAcctId(), account.acctName());
        bedConfig = bedConfigRepo.save(bedConfig);
        log.info("BedConfig added: {}, by {}", bedConfig, account.acctName());

        return AddBedConfigResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setId(bedConfig.getId())
            .build();
    }

    @Transactional
    public GenericResp updateBedConfig(String updateBedConfigReqJson) {
        final AddBedConfigReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updateBedConfigReqJson, AddBedConfigReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("Account not found");
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        // 检查床位是否存在
        BedConfig bedConfig = bedConfigRepo
            .findByIdAndIsDeletedFalse(req.getBedConfig().getId()).orElse(null);
        if (bedConfig == null) {
            log.error("BedConfig not found: {}", req.getBedConfig().getId());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.BED_CONFIG_NOT_EXISTS))
                .build();
        }

        // 检查床位是否重名
        BedConfig bedConfigWithSameName = bedConfigRepo.findByDepartmentIdAndDisplayBedNumberAndIsDeletedFalse(
            req.getBedConfig().getDepartmentId(), req.getBedConfig().getDisplayBedNumber()
        ).orElse(null);
        if (bedConfigWithSameName != null && bedConfigWithSameName.getId() != bedConfig.getId()) {
            log.error("BedConfig already exists: {}", bedConfigWithSameName);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.BED_CONFIG_DISPLAY_NAME_ALREADY_EXISTS))
                .build();
        }
        bedConfigWithSameName = bedConfigRepo.findByDepartmentIdAndDeviceBedNumberAndIsDeletedFalse(
            req.getBedConfig().getDepartmentId(), req.getBedConfig().getDeviceBedNumber()
        ).orElse(null);
        if (bedConfigWithSameName != null && bedConfigWithSameName.getId() != bedConfig.getId()) {
            log.error("BedConfig already exists: {}", bedConfigWithSameName);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.BED_CONFIG_DEVICE_NAME_ALREADY_EXISTS))
                .build();
        }

        final String targetDeptId = req.getBedConfig().getDepartmentId();
        final Integer targetBedType = req.getBedConfig().getBedType();
        if ((!Objects.equals(bedConfig.getDepartmentId(), targetDeptId)
            || !Objects.equals(bedConfig.getBedType(), targetBedType))
            && !certificateService.checkBedAvailable(targetDeptId, targetBedType, 1)) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.BED_CONFIG_BED_NUMBER_LIMIT_EXCEEDED))
                .build();
        }

        // 更新床位配置
        bedConfig = getBedConfig(req.getBedConfig(), account.autoAcctId(), account.acctName());
        bedConfig = bedConfigRepo.save(bedConfig);
        log.info("BedConfig updated: {}, by {}", bedConfig, account.acctName());

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteBedConfig(String deleteBedConfigReqJson) {
        final DeleteBedConfigReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteBedConfigReqJson, DeleteBedConfigReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        // 检查床位是否存在
        BedConfig bedConfig = bedConfigRepo
            .findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (bedConfig == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                .build();
        }

        // 删除床位配置
        bedConfig.setIsDeleted(true);
        bedConfig.setDeletedBy(account.autoAcctId());
        bedConfig.setDeletedAt(TimeUtils.getNowUtc());
        bedConfigRepo.save(bedConfig);
        log.info("BedConfig deleted: {}, by {}", bedConfig, account.acctName());

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public GetBedCountsResp getBedCounts(String getBedCountsReqJson) {
        final GetBedCountsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getBedCountsReqJson, GetBedCountsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GetBedCountsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String deptId = req.getDeptId();
        List<BedCount> bedCounts = null;
        if (StrUtils.isBlank(deptId)) {
            bedCounts = bedCountRepo.findByIsDeletedFalseOrderByDeptIdAscEffectiveTimeDesc();
        } else {
            bedCounts = bedCountRepo.findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(deptId);
        }
        List<BedCountPB> bedCountPbs = bedCounts.stream()
            .map(bc -> BedCountPB.newBuilder()
                .setId(bc.getId())
                .setDeptId(bc.getDeptId())
                .setDateIso8601(TimeUtils.toIso8601String(bc.getEffectiveTime(), ZONE_ID))
                .setCount(bc.getBedCount())
                .build()
            )
            .toList();

        return GetBedCountsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllBedCount(bedCountPbs)
            .build();
    }

    @Transactional
    public GenericResp addBedCount(String addBedCountReqJson) {
        final AddBedCountReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addBedCountReqJson, AddBedCountReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("Account not found");
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        // 解析关键参数
        String deptId = req.getDeptId();
        BedCountPB bedCountPB = req.getBedCount();
        LocalDateTime effectiveTime = TimeUtils.fromIso8601String(bedCountPB.getDateIso8601(), "UTC");
        if (effectiveTime == null) {
            log.error("Invalid date format: {}", bedCountPB.getDateIso8601());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.INVALID_TIME_FORMAT))
                .build();
        }
        Integer count = bedCountPB.getCount();

        // 检查是否已存在相同日期的记录
        Optional<BedCount> existingBedCount = bedCountRepo.findByDeptIdAndEffectiveTimeAndIsDeletedFalse(
            deptId, effectiveTime);
        if (existingBedCount.isPresent()) {
            log.error("BedCount already exists for date: {}", bedCountPB.getDateIso8601());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.BED_COUNT_ALREADY_EXISTS))
                .build();
        }

        // 创建新的床位数量记录
        BedCount bedCount = BedCount.builder()
            .deptId(deptId)
            .bedCount(count)
            .effectiveTime(effectiveTime)
            .isDeleted(false)
            .modifiedBy(account.autoAcctId())
            .modifiedAt(TimeUtils.getNowUtc())
            .build();

        bedCount = bedCountRepo.save(bedCount);
        log.info("BedCount added: {}, by {}", bedCount, account.acctName());

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteBedCount(String deleteBedCountReqJson) {
        final DeleteBedCountReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteBedCountReqJson, DeleteBedCountReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        // 检查床位数量记录是否存在
        BedCount bedCount = bedCountRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (bedCount == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                .build();
        }

        // 删除床位数量记录
        bedCount.setIsDeleted(true);
        bedCount.setDeletedBy(account.autoAcctId());
        bedCount.setDeletedAt(TimeUtils.getNowUtc());
        bedCountRepo.save(bedCount);
        log.info("BedCount deleted: {}, by {}", bedCount, account.acctName());

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public GetDeviceInfoResp getDeviceInfo(String getDeviceInfoReqJson) {
        final GetDeviceInfoReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getDeviceInfoReqJson, GetDeviceInfoReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GetDeviceInfoResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        Pair<StatusCode, Map<Integer/*device_id*/, DeviceBindingPB>> bindingResult =
            patientConfig.getBindedDevices();
        if (bindingResult.getFirst() != StatusCode.OK) {
            return GetDeviceInfoResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, bindingResult.getFirst()))
                .build();
        }
        Map<Integer/*device_id*/, DeviceBindingPB> bindingMap = bindingResult.getSecond();

        List<DeviceInfoWithBindingPB> inlineDevList = new ArrayList<>();
        for (DeviceInfo dev : deviceService.searchActiveDevices(req)) {
            DeviceBindingPB binding = bindingMap.get(dev.getId());
            DeviceInfoWithBindingPB.Builder builder = DeviceInfoWithBindingPB.newBuilder()
                .setDevice(DeviceMapper.toDeviceInfoPB(dev));
            if (binding != null) builder.setIsBinding(true).addBinding(binding);
            inlineDevList.add(builder.build());
        }
        // 移动设备在前，同组中空闲设备在前，最后按设备 ID 排序。
        inlineDevList.sort(Comparator
            .comparing((DeviceInfoWithBindingPB d) ->
                !StrUtils.isBlank(d.getDevice().getDeviceBedNumber()))
            .thenComparing(DeviceInfoWithBindingPB::getIsBinding)
            .thenComparingInt(d -> d.getDevice().getId()));

        return GetDeviceInfoResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllInlineDev(inlineDevList)
            .build();
    }

    @Transactional
    public GetDeviceBindingHistoryResp getDeviceBindingHistory(String getDeviceBindingHistoryReqJson) {
        final GetDeviceBindingHistoryReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getDeviceBindingHistoryReqJson, GetDeviceBindingHistoryReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GetDeviceBindingHistoryResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }
        
        LocalDateTime from = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime to = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        Pair<StatusCode, Pair<List<DeviceBindingPB>, Boolean>> bindingResult = patientConfig.getDeviceBindingHistory(
            req.getDeviceId(), from, to);
        if (bindingResult.getFirst() != StatusCode.OK) {
            return GetDeviceBindingHistoryResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, bindingResult.getFirst()))
                .build();
        }
        Pair<List<DeviceBindingPB>, Boolean> bindingInfo = bindingResult.getSecond();
        return GetDeviceBindingHistoryResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setIsBinding(bindingInfo.getSecond())
            .addAllBinding(bindingInfo.getFirst())
            .build();
    }

    @Transactional
    public GetPatientDeviceBindingHistoryResp getPatientDeviceBindingHistory(String getPatientDeviceBindingHistoryReqJson) {
        final GetPatientDeviceBindingHistoryReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getPatientDeviceBindingHistoryReqJson, GetPatientDeviceBindingHistoryReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GetPatientDeviceBindingHistoryResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        PatientRecord patient = patientRepo.findById(req.getPid()).orElse(null);
        if (patient == null) {
            return GetPatientDeviceBindingHistoryResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        LocalDateTime from = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime to = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        return GetPatientDeviceBindingHistoryResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllBinding(patientConfig.getPatientDeviceBindingHistory(
                patient, req.getDeviceType(), from, to
            ))
            .build();
    }

    @Transactional
    public BindPatientDeviceResp bindPatientDevice(String bindPatientDeviceReqJson) {
        final BindPatientDeviceReq req;
        try {
            req = ProtoUtils.parseJsonToProto(bindPatientDeviceReqJson, BindPatientDeviceReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return BindPatientDeviceResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return BindPatientDeviceResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final Integer deviceId = req.getDeviceId();

        if (!StrUtils.isBlank(req.getBindingTimeIso8601())) {
            // 绑定设备
            LocalDateTime bindingTime = TimeUtils.fromIso8601String(req.getBindingTimeIso8601(), "UTC");
            if (bindingTime == null) bindingTime = TimeUtils.getNowUtc();

            DeviceInfo deviceInfo = deviceService.lockDeviceById(deviceId).orElse(null);
            if (deviceInfo == null || Boolean.TRUE.equals(deviceInfo.getIsDeleted())) {
                log.error("Device not found: {}", deviceId);
                return BindPatientDeviceResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.DEVICE_INFO_NOT_EXISTS))
                    .build();
            }

            List<PatientDevice> patientDevs = patientDevRepo.findByDeviceIdAndUnbindingTimeIsNullAndIsDeletedFalse(deviceId);
            PatientDevice patientDev = null;
            if (patientDevs.size() > 1) {
                log.error("Device already binded: {}", deviceId);
                return BindPatientDeviceResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.DEVICE_HAS_MULTIPLE_BINDINGS))
                    .build();
            } else if (patientDevs.size() == 1) {
                patientDev = patientDevs.get(0);
                if (patientDev.getPatientId() == req.getPid()) {
                    // 设备已经绑定到当前病人
                    return BindPatientDeviceResp.newBuilder()
                        .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                        .build();
                }
                if (patientDev.getBindingTime().isAfter(bindingTime)) {
                    log.error("Binding time is earlier than the current binding time: {}",
                        bindingTime);
                    return BindPatientDeviceResp.newBuilder()
                        .setRt(ReturnCodeUtils.getReturnCode(
                            statusCodeMsgList, StatusCode.BINDING_TIME_CONFLICT))
                        .build();
                }
            }

            // 检查病人是否绑定了其他同类型的设备
            List<DeviceInfo> deviceWithSameType = patientConfig.getCurrentBindedDevices(req.getPid(), deviceInfo.getDeviceType());
            if (!deviceWithSameType.isEmpty()) {
                log.error("Patient already binded to a device with the same type: {}", deviceWithSameType.get(0));
                return BindPatientDeviceResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.PATIENT_ALREADY_BINDED_TO_SAME_TYPE_DEVICE))
                    .build();
            }

            // 查找病人针对同类型设备的绑定时间
            LocalDateTime latestUnbindedTime = patientConfig.getDeviceLatestUnbindedTime(req.getPid(), deviceInfo.getDeviceType());
            if (latestUnbindedTime != null && latestUnbindedTime.isAfter(bindingTime)) {
                log.error("Binding time is earlier than the latest unbinding time: {}", bindingTime);
                return BindPatientDeviceResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList,
                        StatusCode.BINDING_TIME_BEFORE_UNBINDING_TIME_FOR_SAME_TYPE_DEVICE))
                    .build();
            }

            // 转绑校验通过后再结束原绑定，确保失败时患者仍保留原设备。
            if (patientDev != null) {
                patientDev.setUnbindingTime(bindingTime);
                patientDev.setModifiedBy(account.autoAcctId());
                patientDev.setModifiedByAccountName(account.acctName());
                patientDev.setModifiedAt(TimeUtils.getNowUtc());
                patientDevRepo.save(patientDev);
            }

            // 绑定到当前病人
            patientDev = PatientDevice.builder()
                .patientId(req.getPid())
                .deviceId(deviceId)
                .bindingTime(bindingTime)
                .isDeleted(false)
                .modifiedBy(account.autoAcctId())
                .modifiedByAccountName(account.acctName())
                .modifiedAt(TimeUtils.getNowUtc())
                .build();
            patientDev = patientDevRepo.save(patientDev);
            log.info("PatientDevice binded: {}, by {}", patientDev, account.acctName());

            return BindPatientDeviceResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                .setId(patientDev.getId())
                .build();
        } else if (!StrUtils.isBlank(req.getUnbindingTimeIso8601())) {
            // 解绑设备
            LocalDateTime unbindingTime = TimeUtils.fromIso8601String(req.getUnbindingTimeIso8601(), "UTC");
            if (unbindingTime == null) unbindingTime = TimeUtils.getNowUtc();

            // 检查设备是否是固定设备
            DeviceInfo deviceInfo = deviceService.lockDeviceById(deviceId).orElse(null);
            if (deviceInfo == null) {
                log.error("Device not found: {}", req.getDeviceId());
                return BindPatientDeviceResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.DEVICE_INFO_NOT_EXISTS))
                    .build();
            }
            if (!Boolean.TRUE.equals(deviceInfo.getIsDeleted())
                && !StrUtils.isBlank(deviceInfo.getDeviceBedNumber())) {
                // 处理固定设备的逻辑
                log.error("Cannot unbind a fixed device: {}", deviceInfo);
                return BindPatientDeviceResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.CANNOT_UNBIND_FIXED_DEVICE))
                    .build();
            }

            // 查找设备是否已经被绑定
            PatientDevice patientDev = patientDevRepo.findByPatientIdAndDeviceIdAndUnbindingTimeIsNullAndIsDeletedFalse(
                req.getPid(), req.getDeviceId()
            ).orElse(null);
            if (patientDev == null) {
                return BindPatientDeviceResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                    .build();
            }
            StatusCode statusCode = PatientDevUtils.unbindDevice(
                patientDev, unbindingTime,
                account.autoAcctId(), account.acctName(), TimeUtils.getNowUtc()
            );
            if (statusCode != StatusCode.OK) {
                log.error("Failed to unbind device: {}", statusCode);
                return BindPatientDeviceResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, statusCode)).build();
            }
            patientDevRepo.save(patientDev);
            log.info("PatientDevice unbinded: {}, by {}", patientDev, account.acctName());
        }

        return BindPatientDeviceResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp switchBed(SwitchPatientBedReq req) {
        // 提取关键参数
        Long pid = req.getPid();
        String toHisBedNumber = req.getToHisBedNumber();
        LocalDateTime switchTime = TimeUtils.fromIso8601String(req.getSwitchTimeIso8601(), "UTC");
        if (switchTime == null) switchTime = TimeUtils.getNowUtc();

        // 获取病人信息
        PatientRecord patient = patientRepo.findById(pid).orElse(null);
        if (patient == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        if (patient.getAdmissionStatus() != PatientEnums.ADMISSION_STATUS_ADMITTED) {
            log.error("Patient not in ICU: {}", patient);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PATIENT_NOT_IN_ICU))
                .build();
        }

        // 判断是否需要切换床位
        String fromHisBedNumber = patient.getHisBedNumber();
        if (toHisBedNumber.equals(fromHisBedNumber)) {
            log.info("Bed switch not needed: {}", fromHisBedNumber);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                .build();
        }

        StatusCode statusCode = switchBedImpl(patient, toHisBedNumber, switchTime, false, "his", "his");
        if (statusCode != StatusCode.OK) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, statusCode))
                .build();
        }

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    // 如果是普通换床
    // - 增加一条床位历史记录
    //   (bed = fromHisBedNumber,
    //       switchTime = switchTime,
    //       switchType = PatientEnums.SWITCH_TYPE_NORMAL)
    //
    // 如果是病人重返，则
    // - 需要满足 patient.getAdmissionStatus() 为 '已出科'
    // - 增加两条床位历史记录
    //   1. (bed = patient.getHisBedNumber(),
    //       switchTime = patient.getDischargeTime(),
    //       switchType = PatientEnums.SWITCH_TYPE_READMISSION_DISCHARGE)
    //   2. (bed = toHisBedNumber,
    //       switchTime = switchTime,
    //       switchType = PatientEnums.SWITCH_TYPE_READMISSION_ADMIT)
    @Transactional
    public StatusCode switchBedImpl(
        PatientRecord patient, String toHisBedNumber, LocalDateTime switchTime,
        boolean readmission, String modifiedBy, String modifiedByName
    ) {
        // 获取床位历史，校验合法性
        UsageHistory<BedName> usageHistory = getBedHistory(patient);
        if (usageHistory.usageRecords.isEmpty()) {
            log.error("No bed history found for patient: {}", patient.getId());
            return StatusCode.BED_HISTORY_NOT_FOUND;
        }
        Pair<BedName, LocalDateTime> latestBedUsage = usageHistory.usageRecords.get(
            usageHistory.usageRecords.size() - 1
        );
        BedName latestBedName = latestBedUsage.getFirst();
        LocalDateTime latestBedStartTime = latestBedUsage.getSecond();
        LocalDateTime latestBedEndTime = usageHistory.endTime;
        if (StrUtils.isBlank(latestBedName.hisBedNumber) ||
            StrUtils.isBlank(latestBedName.deviceBedNumber)
        ) {
            log.error("Invalid latest bed name: {}", latestBedName);
            return StatusCode.INVALID_BED_NAME;
        }
        if (!latestBedName.hisBedNumber.equals(patient.getHisBedNumber())) {
            log.error("Latest bed does not match patient's current bed: {}, expected: {}",
                latestBedName.hisBedNumber, patient.getHisBedNumber());
            return StatusCode.BED_HISTORY_MISMATCH;
        }
        // 获取目标床位信息，校验合法性
        BedConfigPB toBedConfigPB = patientConfig.findBedConfigByHisBedNumber(
            patient.getDeptId(), toHisBedNumber);
        if (toBedConfigPB == null) {
            log.error("BedConfig not found: {}", toHisBedNumber);
            return StatusCode.BED_CONFIG_NOT_EXISTS;
        }
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 换床
        if (!readmission) {  // 普通换床
            // 检查合法性
            if (!patient.getAdmissionStatus().equals(PatientEnums.ADMISSION_STATUS_PRE_ADMISSION) &&
                !patient.getAdmissionStatus().equals(PatientEnums.ADMISSION_STATUS_ADMITTED)
            ) {
                log.error("Invalid admission status for bed switch: {}, pid {}",
                    patient.getAdmissionStatus(), patient.getId()
                );
                return StatusCode.INVALID_ADMISSION_STATUS_FOR_BED_SWITCH;
            }
            long toBedPid = toBedConfigPB.getPid();
            if (toBedPid != 0) {
                if (toBedPid == patient.getId() &&
                    toBedConfigPB.getHisBedNumber().equals(patient.getHisBedNumber())
                ) return StatusCode.OK;

                log.error("Bed already occupied: hisBedNumber {}[by {}], pid {}, curBed {}",
                    toHisBedNumber, toBedPid, patient.getId(), patient.getHisBedNumber());
                return StatusCode.BED_ALREADY_OCCUPIED;
            }
            if (!switchTime.isAfter(latestBedStartTime)) {
                log.error("Switch time is earlier than the current bed starting time: {}", switchTime);
                return StatusCode.BED_SWITCH_TIME_OUTDATED;
            }
            if (latestBedEndTime != null) {
                log.error("The bed usage end time, for patients in icu [pid {}], should be open/null", patient.getId());
                return StatusCode.BED_USAGE_END_TIME_NOT_NULL;
            }

            // 换床 * 1
            PatientBedHistory bedHistoryRecord = PatientBedHistory.builder()
                .patientId(patient.getId())
                .hisBedNumber(latestBedName.hisBedNumber)
                .deviceBedNumber(latestBedName.deviceBedNumber)
                .displayBedNumber(latestBedName.displayBedNumber)
                .switchTime(switchTime)
                .switchType(PatientEnums.SWITCH_TYPE_NORMAL)
                .modifiedBy(modifiedBy)
                .modifiedByAccountName(modifiedByName)
                .modifiedAt(nowUtc)
                .build();
            bedHistoryRepo.save(bedHistoryRecord);
            log.info("Bed switched:patient {}, {} -> {}",
                patient.getIcuName(), latestBedName.hisBedNumber, toBedConfigPB.getHisBedNumber()
            );
        } else {  // 病人重返
            // 检查合法性
            if (!patient.getAdmissionStatus().equals(PatientEnums.ADMISSION_STATUS_DISCHARGED)) {
                log.error("Invalid admission status for bed switch: {}, pid {}",
                    patient.getAdmissionStatus(), patient.getId()
                );
                return StatusCode.INVALID_ADMISSION_STATUS_FOR_BED_SWITCH;
            }
            if (!toBedConfigPB.getHisMrn().equals(patient.getHisMrn())) {
                log.error("MRN mismatch for bed switch, expected: {}, actual: {}",
                    patient.getHisMrn(), toBedConfigPB.getHisMrn()
                );
                return StatusCode.BED_SWITCH_READMISSION_MRN_MISMATCH;
            }
            if (latestBedEndTime == null || !switchTime.isAfter(latestBedEndTime)) {
                log.error("Invalid bed end time for readmission: {}, pid {}, switch time {}",
                    latestBedEndTime, patient.getId(), switchTime
                );
                return StatusCode.BED_SWITCH_READMISSION_END_TIME_INVALID;
            }

            // 插入重返记录 * 2
            PatientBedHistory dischargedBedHistory = PatientBedHistory.builder()
                .patientId(patient.getId())
                .hisBedNumber(latestBedName.hisBedNumber)
                .deviceBedNumber(latestBedName.deviceBedNumber)
                .displayBedNumber(latestBedName.displayBedNumber)
                .switchTime(latestBedEndTime)
                .switchType(PatientEnums.SWITCH_TYPE_READMISSION_DISCHARGE)
                .modifiedBy(modifiedBy)
                .modifiedByAccountName(modifiedByName)
                .modifiedAt(nowUtc)
                .build();
            bedHistoryRepo.save(dischargedBedHistory);
            PatientBedHistory readmitBedHistory = PatientBedHistory.builder()
                .patientId(patient.getId())
                .hisBedNumber(toHisBedNumber)
                .deviceBedNumber(toBedConfigPB.getDeviceBedNumber())
                .displayBedNumber(toBedConfigPB.getDisplayBedNumber())
                .switchTime(switchTime)
                .switchType(PatientEnums.SWITCH_TYPE_READMISSION_ADMIT)
                .modifiedBy(modifiedBy)
                .modifiedByAccountName(modifiedByName)
                .modifiedAt(nowUtc)
                .build();
            bedHistoryRepo.save(readmitBedHistory);
        }

        patient.setHisBedNumber(toHisBedNumber);
        if (readmission) {
            patient.setAdmissionStatus(PatientEnums.ADMISSION_STATUS_ADMITTED);
            patientConfig.clearDischargeFields(patient);
        }
        patientRepo.save(patient);

        return StatusCode.OK;
    }

    @AllArgsConstructor
    public static class BedName {
        public String hisBedNumber;
        public String deviceBedNumber;
        public String displayBedNumber;
        public Integer switchType;
    }

    public UsageHistory<BedName> getBedHistory(PatientRecord patient) {
        UsageHistory<BedName> usageHistory = new UsageHistory<>();

        // 获取当前设备床号
        final String curHisBedNumber = patient.getHisBedNumber();
        BedConfigPB curBedConfigPB = patientConfig.findBedConfigByHisBedNumber(
            patient.getDeptId(), curHisBedNumber);
        BedName curBedName = new BedName(
            curHisBedNumber,
            curBedConfigPB == null ? "" : curBedConfigPB.getDeviceBedNumber(),
            curBedConfigPB == null ? "" : curBedConfigPB.getDisplayBedNumber(),
            PatientEnums.SWITCH_TYPE_NORMAL
        );

        // 获取床位历史
        List<PatientBedHistory> bedHistoryList = bedHistoryRepo
            .findByPatientIdAndSwitchTimeAfter(patient.getId(), patient.getAdmissionTime())
            .stream()
            .sorted((a, b) -> a.getSwitchTime().compareTo(b.getSwitchTime()))
            .toList();

        LocalDateTime startTime = patient.getAdmissionTime();
        LocalDateTime endTime = null;

        for (PatientBedHistory bedHistory : bedHistoryList) {
            if (bedHistory.getSwitchTime().isAfter(startTime)) {
                usageHistory.usageRecords.add(new Pair<>(
                    new BedName(
                        bedHistory.getHisBedNumber(), bedHistory.getDeviceBedNumber(),
                        bedHistory.getDisplayBedNumber(), bedHistory.getSwitchType()
                    ),
                    startTime
                ));
                startTime = bedHistory.getSwitchTime();
            }
        }
        usageHistory.usageRecords.add(new Pair<>(curBedName, startTime));
        if (patient.getDischargeTime() != null) {
            usageHistory.endTime = patient.getDischargeTime();
        }

        return usageHistory;
    }

    private BedConfig getBedConfig(BedConfigPB bedConfigPB, String accountId, String accountName) {
        BedConfig bedConfig = BedConfig.builder()
            .departmentId(bedConfigPB.getDepartmentId())
            .hisBedNumber(bedConfigPB.getHisBedNumber())
            .deviceBedNumber(bedConfigPB.getDeviceBedNumber())
            .displayBedNumber(bedConfigPB.getDisplayBedNumber())
            .bedType(bedConfigPB.getBedType())
            .note(bedConfigPB.getNote())
            .isDeleted(false)
            .modifiedBy(accountId)
            .modifiedByAccountName(accountName)
            .modifiedAt(TimeUtils.getNowUtc())
            .build();
        if (bedConfigPB.getId() > 0) {
            bedConfig.setId(bedConfigPB.getId());
        }
        return bedConfig;
    }

    private final String ZONE_ID;

    private final List<String> statusCodeMsgList;
    private final PatientConfig patientConfig;
    private final UserQuery userQuery;
    private final CertificateService certificateService;
    private final PatientRecordRepository patientRepo;
    private final BedConfigRepository bedConfigRepo;
    private final BedCountRepository bedCountRepo;
    private final PatientBedHistoryRepository bedHistoryRepo;
    private final DeviceService deviceService;
    private final PatientDeviceRepository patientDevRepo;
}
