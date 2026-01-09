package com.jingyicare.jingyi_icis_engine.service.patients;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.grpc.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.service.certs.*;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
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
        @Autowired ConfigProtoService protoService,
        @Autowired PatientConfig patientConfig,
        @Autowired UserService userService,
        @Autowired CertificateService certificateService,
        @Autowired PatientRecordRepository patientRepo,
        @Autowired BedConfigRepository bedConfigRepo,
        @Autowired BedCountRepository bedCountRepo,
        @Autowired PatientBedHistoryRepository bedHistoryRepo,
        @Autowired DeviceInfoRepository devRepo,
        @Autowired PatientDeviceRepository patientDevRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.IN_ICU_VAL = protoService.getConfig().getPatient().getEnumsV2()
            .getAdmissionStatusList().stream()
            .filter(e -> e.getName().equals(protoService.getConfig().getPatient().getInIcuName()))
            .findFirst()
            .get()
            .getId();
        DeviceEnums devEnums = protoService.getConfig().getDevice().getEnums();
        this.SWITCH_TYPE_NORMAL = devEnums.getSwitchTypeNormal().getId();
        this.SWITCH_TYPE_READMISSION_DISCHARGE = devEnums.getSwitchTypeReadmissionDischarge().getId();
        this.SWITCH_TYPE_READMISSION_ADMIT = devEnums.getSwitchTypeReadmissionAdmit().getId();

        this.protoService = protoService;
        this.patientConfig = patientConfig;
        this.userService = userService;
        this.certificateService = certificateService;
        this.patientRepo = patientRepo;
        this.bedConfigRepo = bedConfigRepo;
        this.bedCountRepo = bedCountRepo;
        this.bedHistoryRepo = bedHistoryRepo;
        this.devRepo = devRepo;
        this.patientDevRepo = patientDevRepo;
    }

    public Integer getSwitchTypeNormalId() {
        return SWITCH_TYPE_NORMAL;
    }

    public Integer getSwitchTypeReadmissionDischargeId() {
        return SWITCH_TYPE_READMISSION_DISCHARGE;
    }

    public Integer getSwitchTypeReadmissionAdmitId() {
        return SWITCH_TYPE_READMISSION_ADMIT;
    }

    @Transactional
    public GetBedConfigResp getBedConfig(String getBedConfigReqJson) {
        final GetBedConfigReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getBedConfigReqJson, GetBedConfigReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GetBedConfigResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }
        final String deptId = req.getDeptId();

        // 查找在线病人
        final Map<String/*his_bed_number*/, PatientRecord> patientMap = patientRepo
            .findByDeptIdAndAdmissionStatus(deptId, IN_ICU_VAL)
            .stream()
            .collect(Collectors.toMap(PatientRecord::getHisBedNumber, Function.identity()));

        // 查找床位配置
        List<BedConfig> bedConfigs = StrUtils.isBlank(deptId) ?
            bedConfigRepo.findAllByIsDeletedFalse() : bedConfigRepo.findByDepartmentIdAndIsDeletedFalse(deptId);
        bedConfigs = bedConfigs.stream()
            .sorted((a, b) -> a.getHisBedNumber().compareTo(b.getHisBedNumber()))
            .collect(Collectors.toList());
        
        // 构建返回
        GetBedConfigResp.Builder respBuilder = GetBedConfigResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK));
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
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }
        final String deptId = req.getBedConfig().getDepartmentId();
        final String hisBedNumber = req.getBedConfig().getHisBedNumber();

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            log.error("Account not found");
            return AddBedConfigResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 查找床位配置
        List<BedConfig> bedConfigs = bedConfigRepo.findByDepartmentIdAndIsDeletedFalse(deptId)
            .stream()
            .filter(b -> b.getHisBedNumber().equals(hisBedNumber))
            .collect(Collectors.toList());
        if (!bedConfigs.isEmpty()) {
            log.error("BedConfig already exists: {}", bedConfigs.get(0));
            return AddBedConfigResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.BED_CONFIG_ALREADY_EXISTS))
                .build();
        }
        BedConfig bedConfig = bedConfigRepo.findByDepartmentIdAndDisplayBedNumberAndIsDeletedFalse(
            deptId, req.getBedConfig().getDisplayBedNumber()
        ).orElse(null);
        if (bedConfig != null) {
            log.error("BedConfig already exists: {}", bedConfig);
            return AddBedConfigResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.BED_CONFIG_DISPLAY_NAME_ALREADY_EXISTS))
                .build();
        }
        bedConfig = bedConfigRepo.findByDepartmentIdAndDeviceBedNumberAndIsDeletedFalse(
            deptId, req.getBedConfig().getDeviceBedNumber()
        ).orElse(null);
        if (bedConfig != null) {
            log.error("BedConfig already exists: {}", bedConfig);
            return AddBedConfigResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.BED_CONFIG_DEVICE_NAME_ALREADY_EXISTS))
                .build();
        }

        if (!certificateService.checkBedAvailable(deptId, 1)) {
            return AddBedConfigResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.BED_CONFIG_BED_NUMBER_LIMIT_EXCEEDED))
                .build();
        }

        // 新增床位配置
        bedConfig = getBedConfig(req.getBedConfig(), accountId, accountName);
        bedConfig = bedConfigRepo.save(bedConfig);
        log.info("BedConfig added: {}, by {}", bedConfig, accountName);

        return AddBedConfigResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
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
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            log.error("Account not found");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 检查床位是否存在
        BedConfig bedConfig = bedConfigRepo
            .findByIdAndIsDeletedFalse(req.getBedConfig().getId()).orElse(null);
        if (bedConfig == null) {
            log.error("BedConfig not found: {}", req.getBedConfig().getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.BED_CONFIG_NOT_EXISTS))
                .build();
        }

        // 检查床位是否重名
        BedConfig bedConfigWithSameName = bedConfigRepo.findByDepartmentIdAndDisplayBedNumberAndIsDeletedFalse(
            req.getBedConfig().getDepartmentId(), req.getBedConfig().getDisplayBedNumber()
        ).orElse(null);
        if (bedConfigWithSameName != null && bedConfigWithSameName.getId() != bedConfig.getId()) {
            log.error("BedConfig already exists: {}", bedConfigWithSameName);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.BED_CONFIG_DISPLAY_NAME_ALREADY_EXISTS))
                .build();
        }
        bedConfigWithSameName = bedConfigRepo.findByDepartmentIdAndDeviceBedNumberAndIsDeletedFalse(
            req.getBedConfig().getDepartmentId(), req.getBedConfig().getDeviceBedNumber()
        ).orElse(null);
        if (bedConfigWithSameName != null && bedConfigWithSameName.getId() != bedConfig.getId()) {
            log.error("BedConfig already exists: {}", bedConfigWithSameName);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.BED_CONFIG_DEVICE_NAME_ALREADY_EXISTS))
                .build();
        }

        // 更新床位配置
        bedConfig = getBedConfig(req.getBedConfig(), accountId, accountName);
        bedConfig = bedConfigRepo.save(bedConfig);
        log.info("BedConfig updated: {}, by {}", bedConfig, accountName);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
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
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 检查床位是否存在
        BedConfig bedConfig = bedConfigRepo
            .findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (bedConfig == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        // 删除床位配置
        bedConfig.setIsDeleted(true);
        bedConfig.setDeletedBy(accountId);
        bedConfig.setDeletedAt(TimeUtils.getNowUtc());
        bedConfigRepo.save(bedConfig);
        log.info("BedConfig deleted: {}, by {}", bedConfig, accountName);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
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
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
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
            .setRt(protoService.getReturnCode(StatusCode.OK))
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
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            log.error("Account not found");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 解析关键参数
        String deptId = req.getDeptId();
        BedCountPB bedCountPB = req.getBedCount();
        LocalDateTime effectiveTime = TimeUtils.fromIso8601String(bedCountPB.getDateIso8601(), "UTC");
        if (effectiveTime == null) {
            log.error("Invalid date format: {}", bedCountPB.getDateIso8601());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_FORMAT))
                .build();
        }
        Integer count = bedCountPB.getCount();

        // 检查是否已存在相同日期的记录
        Optional<BedCount> existingBedCount = bedCountRepo.findByDeptIdAndEffectiveTimeAndIsDeletedFalse(
            deptId, effectiveTime);
        if (existingBedCount.isPresent()) {
            log.error("BedCount already exists for date: {}", bedCountPB.getDateIso8601());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.BED_COUNT_ALREADY_EXISTS))
                .build();
        }

        // 创建新的床位数量记录
        BedCount bedCount = BedCount.builder()
            .deptId(deptId)
            .bedCount(count)
            .effectiveTime(effectiveTime)
            .isDeleted(false)
            .modifiedBy(accountId)
            .modifiedAt(TimeUtils.getNowUtc())
            .build();

        bedCount = bedCountRepo.save(bedCount);
        log.info("BedCount added: {}, by {}", bedCount, accountName);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
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
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 检查床位数量记录是否存在
        BedCount bedCount = bedCountRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (bedCount == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        // 删除床位数量记录
        bedCount.setIsDeleted(true);
        bedCount.setDeletedBy(accountId);
        bedCount.setDeletedAt(TimeUtils.getNowUtc());
        bedCountRepo.save(bedCount);
        log.info("BedCount deleted: {}, by {}", bedCount, accountName);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
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
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        List<DeviceInfoPB> deviceList = patientConfig.getDeviceInfo(
            req.getDeptId(), req.getDeviceId(), req.getDeviceBedNum(), req.getDeviceType(), req.getDeviceName()
        );
        Map<Integer/*device_id*/, DeviceBindingPB> bindingMap = patientConfig.getBindedDevices();

        List<DeviceInfoWithBindingPB> inlineDevList = new ArrayList<>();
        for (DeviceInfoPB dev : deviceList) {
            DeviceBindingPB binding = bindingMap.get(dev.getId());
            DeviceInfoWithBindingPB.Builder builder = DeviceInfoWithBindingPB.newBuilder().setDevice(dev);
            if (binding != null) builder.setIsBinding(true).addBinding(binding);
            inlineDevList.add(builder.build());
        }
        // 排序: 空闲，使用中，固定设备
        inlineDevList = inlineDevList.stream()
            .sorted((a, b) -> {
                // 移动设备在前
                boolean aIsFixed = !StrUtils.isBlank(a.getDevice().getDeviceBedNumber());
                boolean bIsFixed = !StrUtils.isBlank(b.getDevice().getDeviceBedNumber());
                if (aIsFixed != bIsFixed) return aIsFixed ? 1 : -1;

                // 空闲设备在前
                boolean aIsFree = !a.getIsBinding();
                boolean bIsFree = !b.getIsBinding();
                if (aIsFree != bIsFree) return aIsFree ? -1 : 1;

                // 最后按deviceId排序
                if (a.getDevice().getId() < b.getDevice().getId()) return -1;
                return 1;
            })
            .collect(Collectors.toList());

        return GetDeviceInfoResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
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
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }
        
        LocalDateTime from = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime to = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        Pair<List<DeviceBindingPB>, Boolean> bindingInfo = patientConfig.getDeviceBindingHistory(
            req.getDeviceId(), from, to);
        return GetDeviceBindingHistoryResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
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
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        PatientRecord patient = patientRepo.findById(req.getPid()).orElse(null);
        if (patient == null) {
            return GetPatientDeviceBindingHistoryResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        LocalDateTime from = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime to = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        return GetPatientDeviceBindingHistoryResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllBinding(patientConfig.getPatientDeviceBindingHistory(
                patient.getId(), patient.getIcuName(), req.getDeviceType(), from, to
            ))
            .build();
    }

    @Transactional
    public AddDeviceInfoResp addDeviceInfo(String addDeviceInfoReqJson) {
        final AddDeviceInfoReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addDeviceInfoReqJson, AddDeviceInfoReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return AddDeviceInfoResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return AddDeviceInfoResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 查找设备信息
        DeviceInfo devInfo = devRepo.findByDepartmentIdAndDeviceNameAndIsDeletedFalse(
            req.getDeviceInfo().getDepartmentId(), req.getDeviceInfo().getDeviceName()
        ).orElse(null);
        if (devInfo != null) {
            log.error("DeviceInfo already exists: {}", devInfo);
            return AddDeviceInfoResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEVICE_INFO_ALREADY_EXISTS))
                .build();
        }

        // 确认IP是否被其它设备占用
        List<DeviceInfo> devInfoList = devRepo.findByDeviceIpAndIsDeletedFalse(req.getDeviceInfo().getDeviceIp());
        if (!devInfoList.isEmpty()) {
            log.error("DeviceInfo already exists: {}", devInfoList.get(0));
            return AddDeviceInfoResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEVICE_IP_ALREADY_USED))
                .build();
        }

        // 新增设备信息
        devInfo = patientConfig.toDeviceInfo(req.getDeviceInfo());
        devInfo.setModifiedBy(accountId);
        devInfo.setModifiedByAccountName(accountName);
        devInfo.setModifiedAt(TimeUtils.getNowUtc());
        devInfo = devRepo.save(devInfo);
        log.info("DeviceInfo added: {}, by {}", devInfo, accountName);

        return AddDeviceInfoResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(devInfo.getId())
            .build();
    }

    @Transactional
    public GenericResp updateDeviceInfo(String updateDeviceInfoReqJson) {
        final AddDeviceInfoReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updateDeviceInfoReqJson, AddDeviceInfoReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 查找设备是否存在
        DeviceInfo devInfo = devRepo.findById(req.getDeviceInfo().getId()).orElse(null);
        if (devInfo == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEVICE_INFO_NOT_EXISTS))
                .build();
        }

        // 查找设备和其他设备是否重名
        DeviceInfo devInfoWithSameName = devRepo.findByDepartmentIdAndDeviceNameAndIsDeletedFalse(
            req.getDeviceInfo().getDepartmentId(), req.getDeviceInfo().getDeviceName()
        ).orElse(null);
        if (devInfoWithSameName != null && devInfoWithSameName.getId() != devInfo.getId()) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEVICE_INFO_ALREADY_EXISTS))
                .build();
        }

        // 更新设备信息
        devInfo = patientConfig.toDeviceInfo(req.getDeviceInfo());
        devInfo.setId(req.getDeviceInfo().getId());
        devInfo.setModifiedBy(accountId);
        devInfo.setModifiedByAccountName(accountName);
        devInfo.setModifiedAt(TimeUtils.getNowUtc());
        devInfo = devRepo.save(devInfo);
        log.info("DeviceInfo updated: {}, by {}", devInfo, accountName);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteDeviceInfo(String deleteDeviceInfoReqJson) {
        final DeleteDeviceInfoReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteDeviceInfoReqJson, DeleteDeviceInfoReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 查找设备是否存在
        DeviceInfo devInfo = devRepo.findById(req.getId()).orElse(null);
        if (devInfo == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        // 查看是否有病人绑定了该设备
        List<PatientDevice> patientDevList = patientDevRepo.findByDeviceIdAndUnbindingTimeIsNullAndIsDeletedFalse(req.getId());
        if (!patientDevList.isEmpty()) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEVICE_ALREADY_BINDED))
                .build();
        }

        // 删除设备信息
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        devInfo.setIsDeleted(true);
        devInfo.setDeletedBy(accountId);
        devInfo.setDeletedAt(nowUtc);
        devInfo.setModifiedBy(accountId);
        devInfo.setModifiedByAccountName(accountName);
        devInfo.setModifiedAt(nowUtc);
        devRepo.save(devInfo);
        log.info("DeviceInfo deleted: {}, by {}", devInfo, accountName);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
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
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return BindPatientDeviceResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();
        final Integer deviceId = req.getDeviceId();

        if (!StrUtils.isBlank(req.getBindingTimeIso8601())) {
            // 绑定设备
            LocalDateTime bindingTime = TimeUtils.fromIso8601String(req.getBindingTimeIso8601(), "UTC");
            if (bindingTime == null) bindingTime = TimeUtils.getNowUtc();

            List<PatientDevice> patientDevs = patientDevRepo.findByDeviceIdAndUnbindingTimeIsNullAndIsDeletedFalse(deviceId);
            PatientDevice patientDev = null;
            if (patientDevs.size() > 1) {
                log.error("Device already binded: {}", deviceId);
                return BindPatientDeviceResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.DEVICE_HAS_MULTIPLE_BINDINGS))
                    .build();
            } else if (patientDevs.size() == 1) {
                patientDev = patientDevs.get(0);
                if (patientDev.getPatientId() == req.getPid()) {
                    // 设备已经绑定到当前病人
                    return BindPatientDeviceResp.newBuilder()
                        .setRt(protoService.getReturnCode(StatusCode.OK))
                        .build();
                } else {
                    // 设备已经绑定到其他病人，解绑
                    if (patientDev.getBindingTime().isAfter(bindingTime)) {
                        log.error("Binding time is earlier than the current binding time: {}", bindingTime);
                        return BindPatientDeviceResp.newBuilder()
                            .setRt(protoService.getReturnCode(StatusCode.BINDING_TIME_CONFLICT))
                            .build();
                    }
                    patientDev.setUnbindingTime(bindingTime);
                    patientDev.setModifiedBy(accountId);
                    patientDev.setModifiedByAccountName(accountName);
                    patientDev.setModifiedAt(TimeUtils.getNowUtc());
                    patientDevRepo.save(patientDev);
                }
            }

            // 检查病人是否绑定了其他同类型的设备
            DeviceInfo deviceInfo = devRepo.findById(deviceId).orElse(null);
            if (deviceInfo == null) {
                log.error("Device not found: {}", deviceId);
                return BindPatientDeviceResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.DEVICE_INFO_NOT_EXISTS))
                    .build();
            }
            List<DeviceInfo> deviceWithSameType = patientConfig.getCurrentBindedDevices(req.getPid(), deviceInfo.getDeviceType());
            if (!deviceWithSameType.isEmpty()) {
                log.error("Patient already binded to a device with the same type: {}", deviceWithSameType.get(0));
                return BindPatientDeviceResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PATIENT_ALREADY_BINDED_TO_SAME_TYPE_DEVICE))
                    .build();
            }

            // 查找病人针对同类型设备的绑定时间
            LocalDateTime latestUnbindedTime = patientConfig.getDeviceLatestUnbindedTime(req.getPid(), deviceInfo.getDeviceType());
            if (latestUnbindedTime != null && latestUnbindedTime.isAfter(bindingTime)) {
                log.error("Binding time is earlier than the latest unbinding time: {}", bindingTime);
                return BindPatientDeviceResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.BINDING_TIME_BEFORE_UNBINDING_TIME_FOR_SAME_TYPE_DEVICE))
                    .build();
            }

            // 绑定到当前病人
            patientDev = PatientDevice.builder()
                .patientId(req.getPid())
                .deviceId(deviceId)
                .bindingTime(bindingTime)
                .isDeleted(false)
                .modifiedBy(accountId)
                .modifiedByAccountName(accountName)
                .modifiedAt(TimeUtils.getNowUtc())
                .build();
            patientDev = patientDevRepo.save(patientDev);
            log.info("PatientDevice binded: {}, by {}", patientDev, accountName);

            return BindPatientDeviceResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .setId(patientDev.getId())
                .build();
        } else if (!StrUtils.isBlank(req.getUnbindingTimeIso8601())) {
            // 解绑设备
            LocalDateTime unbindingTime = TimeUtils.fromIso8601String(req.getUnbindingTimeIso8601(), "UTC");
            if (unbindingTime == null) unbindingTime = TimeUtils.getNowUtc();

            // 检查设备是否是固定设备
            DeviceInfo deviceInfo = devRepo.findById(req.getDeviceId()).orElse(null);
            if (deviceInfo == null) {
                log.error("Device not found: {}", req.getDeviceId());
                return BindPatientDeviceResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.DEVICE_INFO_NOT_EXISTS))
                    .build();
            }
            if (!StrUtils.isBlank(deviceInfo.getDeviceBedNumber())) {
                // 处理固定设备的逻辑
                log.error("Cannot unbind a fixed device: {}", deviceInfo);
                return BindPatientDeviceResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.CANNOT_UNBIND_FIXED_DEVICE))
                    .build();
            }

            // 查找设备是否已经被绑定
            PatientDevice patientDev = patientDevRepo.findByPatientIdAndDeviceIdAndUnbindingTimeIsNullAndIsDeletedFalse(
                req.getPid(), req.getDeviceId()
            ).orElse(null);
            if (patientDev == null) {
                return BindPatientDeviceResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.OK))
                    .build();
            }
            StatusCode statusCode = PatientDevUtils.unbindDevice(
                patientDev, unbindingTime, accountId, accountName, TimeUtils.getNowUtc()
            );
            if (statusCode != StatusCode.OK) {
                log.error("Failed to unbind device: {}", statusCode);
                return BindPatientDeviceResp.newBuilder().setRt(protoService.getReturnCode(statusCode)).build();
            }
            patientDevRepo.save(patientDev);
            log.info("PatientDevice unbinded: {}, by {}", patientDev, accountName);
        }

        return BindPatientDeviceResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
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
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        if (patient.getAdmissionStatus() != IN_ICU_VAL) {
            log.error("Patient not in ICU: {}", patient);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_IN_ICU))
                .build();
        }

        // 判断是否需要切换床位
        String fromHisBedNumber = patient.getHisBedNumber();
        if (toHisBedNumber.equals(fromHisBedNumber)) {
            log.info("Bed switch not needed: {}", fromHisBedNumber);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        StatusCode statusCode = switchBedImpl(patient, toHisBedNumber, switchTime, false, "his", "his");
        if (statusCode != StatusCode.OK) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(statusCode))
                .build();
        }

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    // 如果是普通换床
    // - 增加一条床位历史记录
    //   (bed = fromHisBedNumber,
    //       switchTime = switchTime,
    //       switchType = SWITCH_TYPE_NORMAL)
    //
    // 如果是病人重返，则
    // - 需要满足 patient.getAdmissionStatus() 为 '已出科'
    // - 增加两条床位历史记录
    //   1. (bed = patient.getHisBedNumber(),
    //       switchTime = patient.getDischargeTime(),
    //       switchType = SWITCH_TYPE_READMISSION_DISCHARGE)
    //   2. (bed = toHisBedNumber,
    //       switchTime = switchTime,
    //       switchType = SWITCH_TYPE_READMISSION_ADMIT)
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
            if (!patient.getAdmissionStatus().equals(patientConfig.getPendingAdmissionId()) &&
                !patient.getAdmissionStatus().equals(patientConfig.getInIcuId())
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
                .switchType(SWITCH_TYPE_NORMAL)
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
            if (!patient.getAdmissionStatus().equals(patientConfig.getDischargedId())) {
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
                .switchType(SWITCH_TYPE_READMISSION_DISCHARGE)
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
                .switchType(SWITCH_TYPE_READMISSION_ADMIT)
                .modifiedBy(modifiedBy)
                .modifiedByAccountName(modifiedByName)
                .modifiedAt(nowUtc)
                .build();
            bedHistoryRepo.save(readmitBedHistory);
        }

        patient.setHisBedNumber(toHisBedNumber);
        if (readmission) {
            patient.setAdmissionStatus(patientConfig.getInIcuId());
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
            SWITCH_TYPE_NORMAL
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
    private final Integer IN_ICU_VAL;
    private final Integer SWITCH_TYPE_NORMAL;
    private final Integer SWITCH_TYPE_READMISSION_DISCHARGE;
    private final Integer SWITCH_TYPE_READMISSION_ADMIT;

    private final ConfigProtoService protoService;
    private final PatientConfig patientConfig;
    private final UserService userService;
    private final CertificateService certificateService;
    private final PatientRecordRepository patientRepo;
    private final BedConfigRepository bedConfigRepo;
    private final BedCountRepository bedCountRepo;
    private final PatientBedHistoryRepository bedHistoryRepo;
    private final DeviceInfoRepository devRepo;
    private final PatientDeviceRepository patientDevRepo;
}