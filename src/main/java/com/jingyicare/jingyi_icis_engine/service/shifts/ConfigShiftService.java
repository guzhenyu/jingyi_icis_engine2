package com.jingyicare.jingyi_icis_engine.service.shifts;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.settings.*;
import com.jingyicare.jingyi_icis_engine.entity.shifts.*;
import com.jingyicare.jingyi_icis_engine.repository.settings.*;
import com.jingyicare.jingyi_icis_engine.repository.shifts.*;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class ConfigShiftService {
    public ConfigShiftService(
        @Autowired ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired UserService userService,
        @Autowired ConfigShiftUtils configShiftUtils,
        @Autowired DeptSystemSettingsRepository deptSystemSettingsRepo,
        @Autowired BalanceStatsShiftRepository balanceStatsShiftRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.statusMsgList = protoService.getConfig().getText().getStatusCodeMsgList();

        this.context = context;
        this.protoService = protoService;
        this.userService = userService;
        this.configShiftUtils = configShiftUtils;

        this.defaultShiftSettings = protoService.getConfig().getShift().getDefaultSetting();
        this.deptSystemSettingsRepo = deptSystemSettingsRepo;
        this.balanceStatsShiftRepo = balanceStatsShiftRepo;
    }

    public void checkIntegrity() {
        if (defaultShiftSettings.getShiftCount() <= 0) {
            log.error("Shift is not set, count(shift) == 0");
            LogUtils.flushAndQuit(context);
        }

        // todo(guzhenyu): 校验班次配置的合法性
    }

    @Transactional
    public GetDeptShiftResp getDeptShift(String getDeptShiftReqJson) {
        final GetDeptShiftReq req;
        try {
            GetDeptShiftReq.Builder builder = GetDeptShiftReq.newBuilder();
            JsonFormat.parser().merge(getDeptShiftReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetDeptShiftResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取班次配置，如果没有特殊设置，返回系统配置
        ShiftSettingsPB shiftSettings = configShiftUtils.getShiftByDeptId(req.getDeptId());
        if (shiftSettings == null) {
            log.error("ShiftSettings not found: ", req.getDeptId());
            return GetDeptShiftResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NO_SHIFT_SETTING))
                .build();
        }

        return GetDeptShiftResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setShift(shiftSettings)
            .build();
    }

    @Transactional
    public GenericResp updateDeptShift(String updateDeptShiftReqJson) {
        final UpdateDeptShiftReq req;
        try {
            UpdateDeptShiftReq.Builder builder = UpdateDeptShiftReq.newBuilder();
            JsonFormat.parser().merge(updateDeptShiftReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        if (req.getShift().getShiftCount() <= 0) {
            log.error("Shift is not set, count(shift) == 0");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NO_SHIFT_SETTING))
                .build();
        }

        // 获取当前用户
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 更新班次配置
        DeptSystemSettingsId deptSettingsId = DeptSystemSettingsId.builder()
            .deptId(req.getDeptId())
            .functionId(SystemSettingFunctionId.GET_SHIFT.getNumber())
            .build();
        DeptSystemSettings deptSettings = deptSystemSettingsRepo.findById(deptSettingsId).orElse(null);
        LocalDateTime modifiedAt = TimeUtils.getNowUtc();
        if (deptSettings == null) {
            deptSettings = DeptSystemSettings.builder()
                .id(deptSettingsId)
                .settingsPb(ProtoUtils.encodeShiftSettings(req.getShift()))
                .modifiedAt(modifiedAt)
                .modifiedBy(accountId)
                .build();
        } else {
            deptSettings.setSettingsPb(ProtoUtils.encodeShiftSettings(req.getShift()));
            deptSettings.setModifiedAt(modifiedAt);
            deptSettings.setModifiedBy(accountId);
        }
        deptSystemSettingsRepo.save(deptSettings);

        log.info("ShiftSettings updated (by {}): deptId = {}, shift = {}",
            accountId, req.getDeptId(), ProtoUtils.protoToTxt(req.getShift()));
        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GetDeptBalanceStatsShiftsResp getDeptBalanceStatsShifts(String getDeptBalanceStatsShiftsReqJson) {
        final GetDeptBalanceStatsShiftsReq req;
        try {
            GetDeptBalanceStatsShiftsReq.Builder builder = GetDeptBalanceStatsShiftsReq.newBuilder();
            JsonFormat.parser().merge(getDeptBalanceStatsShiftsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetDeptBalanceStatsShiftsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        List<BalanceStatsShiftPB> balanceStatsShiftPBs = balanceStatsShiftRepo
            .findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(req.getDeptId())
            .stream()
            .map(shift -> toBalanceStatsShiftPB(shift))
            .toList();

        return GetDeptBalanceStatsShiftsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllShift(balanceStatsShiftPBs)
            .build();
    }

    private BalanceStatsShiftPB toBalanceStatsShiftPB(BalanceStatsShift balanceStatsShift) {
        return BalanceStatsShiftPB.newBuilder()
            .setId(balanceStatsShift.getId())
            .setDeptId(balanceStatsShift.getDeptId())
            .setStartHour(balanceStatsShift.getStartHour())
            .setEffectiveTimeIso8601(TimeUtils.toIso8601String(balanceStatsShift.getEffectiveTime(), ZONE_ID))
            .build();
    }

    @Transactional
    public AddDeptBalanceStatsShiftResp addDeptBalanceStatsShift(String addDeptBalanceStatsShiftReqJson) {
        final AddDeptBalanceStatsShiftReq req;
        try {
            AddDeptBalanceStatsShiftReq.Builder builder = AddDeptBalanceStatsShiftReq.newBuilder();
            JsonFormat.parser().merge(addDeptBalanceStatsShiftReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return AddDeptBalanceStatsShiftResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return AddDeptBalanceStatsShiftResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        LocalDateTime effectiveTimeLocal = TimeUtils.fromIso8601String(
            req.getShift().getEffectiveTimeIso8601(), ZONE_ID);
        effectiveTimeLocal = effectiveTimeLocal.withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime effectiveTime = TimeUtils.getUtcFromLocalDateTime(effectiveTimeLocal, ZONE_ID);
        if (effectiveTime == null) {
            log.error("Failed to parse effective time: ", req.getShift().getEffectiveTimeIso8601());
            return AddDeptBalanceStatsShiftResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_FORMAT))
                .build();
        }
        BalanceStatsShift balanceStatsShift = balanceStatsShiftRepo
            .findByDeptIdAndEffectiveTimeAndIsDeletedFalse(req.getShift().getDeptId(), effectiveTime)
            .orElse(null);
        if (balanceStatsShift != null) {
            log.error("BalanceStatsShift already exists: ", req.getShift().getDeptId(), effectiveTime);
            return AddDeptBalanceStatsShiftResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.BALANCE_STATS_SHIFT_EXISTS))
                .build();
        }

        // 创建新的 BalanceStatsShift 实体
        balanceStatsShift = BalanceStatsShift.builder()
            .deptId(req.getShift().getDeptId())
            .startHour(req.getShift().getStartHour())
            .effectiveTime(effectiveTime)
            .isDeleted(false)
            .modifiedAt(TimeUtils.getNowUtc())
            .modifiedBy(accountId)
            .modifiedByAccountName(accountName)
            .build();
        balanceStatsShift =  balanceStatsShiftRepo.save(balanceStatsShift);

        return AddDeptBalanceStatsShiftResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(balanceStatsShift.getId())
            .build();
    }

    @Transactional
    public GenericResp updateDeptBalanceStatsShift(String updateDeptBalanceStatsShiftReqJson) {
        final AddDeptBalanceStatsShiftReq req;
        try {
            AddDeptBalanceStatsShiftReq.Builder builder = AddDeptBalanceStatsShiftReq.newBuilder();
            JsonFormat.parser().merge(updateDeptBalanceStatsShiftReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        LocalDateTime effectiveTime = TimeUtils.fromIso8601String(
            req.getShift().getEffectiveTimeIso8601(), "UTC");
        if (effectiveTime == null) {
            log.error("Failed to parse effective time: ", req.getShift().getEffectiveTimeIso8601());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_FORMAT))
                .build();
        }

        // 查找现有的 BalanceStatsShift 实体
        BalanceStatsShift balanceStatsShift = balanceStatsShiftRepo
            .findByIdAndIsDeletedFalse(req.getShift().getId())
            .orElse(null);
        if (balanceStatsShift == null) {
            log.error("BalanceStatsShift not found: ", req.getShift().getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.BALANCE_STATS_SHIFT_NOT_FOUND))
                .build();
        }
        if (!balanceStatsShift.getEffectiveTime().equals(effectiveTime)) {
            BalanceStatsShift duplicateShift = balanceStatsShiftRepo
                .findByDeptIdAndEffectiveTimeAndIsDeletedFalse(req.getShift().getDeptId(), effectiveTime)
                .orElse(null);
            if (duplicateShift != null) {
                log.error("BalanceStatsShift already exists: ", req.getShift().getDeptId(), effectiveTime);
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.BALANCE_STATS_SHIFT_EXISTS))
                    .build();
            }
        }
        // 更新 BalanceStatsShift 实体
        balanceStatsShift.setStartHour(req.getShift().getStartHour());
        balanceStatsShift.setEffectiveTime(effectiveTime);
        balanceStatsShift.setModifiedAt(TimeUtils.getNowUtc());
        balanceStatsShift.setModifiedBy(accountId);
        balanceStatsShift.setModifiedByAccountName(accountName);
        balanceStatsShiftRepo.save(balanceStatsShift);
        log.info("BalanceStatsShift updated (by {}): id = {}, deptId = {}, effectiveTime = {}",
            accountId, req.getShift().getId(), req.getShift().getDeptId(), effectiveTime);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteDeptBalanceStatsShift(String deleteDeptBalanceStatsShiftReqJson) {
        final DeleteDeptBalanceStatsShiftReq req;
        try {
            DeleteDeptBalanceStatsShiftReq.Builder builder = DeleteDeptBalanceStatsShiftReq.newBuilder();
            JsonFormat.parser().merge(deleteDeptBalanceStatsShiftReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 查找现有的 BalanceStatsShift 实体
        BalanceStatsShift balanceStatsShift = balanceStatsShiftRepo
            .findByIdAndIsDeletedFalse(req.getId())
            .orElse(null);
        if (balanceStatsShift == null) {
            log.error("BalanceStatsShift not found: ", req.getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        // 删除 BalanceStatsShift 实体
        balanceStatsShift.setIsDeleted(true);
        balanceStatsShift.setDeletedAt(TimeUtils.getNowUtc());
        balanceStatsShift.setDeletedBy(accountId);
        balanceStatsShiftRepo.save(balanceStatsShift);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    private final String ZONE_ID;
    private final List<String> statusMsgList;

    private final ConfigurableApplicationContext context;
    private final ConfigProtoService protoService;
    private final UserService userService;
    private final ConfigShiftUtils configShiftUtils;

    private final ShiftSettingsPB defaultShiftSettings;
    private final DeptSystemSettingsRepository deptSystemSettingsRepo;
    private final BalanceStatsShiftRepository balanceStatsShiftRepo;
}