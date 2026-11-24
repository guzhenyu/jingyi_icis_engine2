package com.jingyicare.jingyi_icis_engine.service.monitorings;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.meta.MonMetaReader;
import com.jingyicare.jingyi_icis_engine.service.monitorings.meta.MonMetaService;
import com.jingyicare.jingyi_icis_engine.service.settings.SettingService;
import com.jingyicare.jingyi_icis_engine.service.shifts.*;
import com.jingyicare.jingyi_icis_engine.utils.LogUtils;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class MonitoringConfig {
    public MonitoringConfig(
        @Autowired ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired ConfigShiftUtils configShiftUtils,
        @Autowired MonMetaReader monMetaReader,
        @Autowired MonMetaService monMetaService,
        @Autowired SettingService settingService
    ) {
        this.context = context;
        this.protoService = protoService;
        this.configShiftUtils = configShiftUtils;

        this.monitoringPb = protoService.getConfig().getMonitoring();
        this.monMetaReader = monMetaReader;
        this.monMetaService = monMetaService;
        this.settingService = settingService;
    }

    ////// 系统初始化工具函数: checkIntegrity, refresh
    @Transactional
    public void checkIntegrity() {
        validateMonitoringConfig();
        checkBgaParamIntegrity();
    }

    private void validateMonitoringConfig() {
        // 观察项参数是否合法
        Map<String, MonitoringParamPB> allParamMap = new HashMap<>();
        for (MonitoringParamPB paramPb : monitoringPb.getMonitoringParamList()) {
            StatusCode statusCode = monMetaService.validateParamValueMeta(
                paramPb.getBalanceType(), paramPb.getValueMeta());
            if (statusCode != StatusCode.OK) {
                log.error("Invalid monitoring parameter code {}: {}",
                    paramPb.getCode(), statusCode);
                LogUtils.flushAndQuit(context);
            }
            allParamMap.put(paramPb.getCode(), paramPb);
        }

        Set<Integer> requiredBalanceRoles = new HashSet<>(Set.of(
            MonEnums.BALANCE_TYPE_IN, MonEnums.BALANCE_TYPE_OUT, MonEnums.BALANCE_TYPE_NET
        ));
        Set<String> existingBalanceGroupNames = new HashSet<>();
        for (MonitoringGroupPB group : monitoringPb.getBalanceGroupList()) {
            String groupName = group.getName();
            if (!existingBalanceGroupNames.add(groupName)) {
                log.error("Duplicate group name found in MonitoringPB.balance_group: {}",
                    groupName);
                LogUtils.flushAndQuit(context);
            }

            int role = group.getBalanceRole();
            if (!group.hasBalanceRole() ||
                (role != MonEnums.BALANCE_TYPE_NAN && !requiredBalanceRoles.remove(role))) {
                log.error("Missing, invalid or duplicate balance role: {}, group: {}",
                    role, groupName);
                LogUtils.flushAndQuit(context);
            }

            for (MonitoringGroupParamPB paramPb : group.getMonitoringParamList()) {
                String code = paramPb.getCode();
                MonitoringParamPB param = allParamMap.get(code);
                if (param == null) {
                    log.error("MonitoringGroupParamPB code not found in MonitoringParamPB: {}",
                        code);
                    LogUtils.flushAndQuit(context);
                }

                int balanceType = param.getBalanceType();
                if (balanceType != role && (balanceType == MonEnums.BALANCE_TYPE_IN
                    || balanceType == MonEnums.BALANCE_TYPE_OUT
                    || balanceType == MonEnums.BALANCE_TYPE_NET)) {
                    log.error("Monitoring parameter {} has balance type {} for group role {}",
                        code, balanceType, role);
                    LogUtils.flushAndQuit(context);
                }
            }
        }

        if (!requiredBalanceRoles.isEmpty()) {
            log.error("MonitoringPB.balance_group does not contain required roles: {}",
                requiredBalanceRoles);
            LogUtils.flushAndQuit(context);
        }
    }

    private void checkBgaParamIntegrity() {
        // 检查BGA参数是否存在于观察项参数中
        List<MonitoringParamPB> monitoringParamPbs = monitoringPb.getMonitoringParamList();
        Set<String> monitoringParamCodes = monitoringParamPbs.stream()
            .map(MonitoringParamPB::getCode)
            .collect(Collectors.toSet());

        for (String bgaParamCode : protoService.getConfig().getBga().getBgaParamCodeList()) {
            if (!monitoringParamCodes.contains(bgaParamCode)) {
                log.error("BGA param code not found in monitoring params: {}", bgaParamCode);
                LogUtils.flushAndQuit(context);
            }
        }
    }

    public void refresh() {
    }

    // pmr = patient monitoring record
    public Pair<LocalDateTime, LocalDateTime> normalizePmrQueryTimeRange(
        int groupType, String deptId, LocalDateTime startTime, LocalDateTime endTime
    ) {
        LocalDateTime pmrQueryStartUtc = startTime;
        LocalDateTime pmrQueryEndUtc = endTime;
        if (groupType == MonEnums.GROUP_TYPE_BALANCE) {
            List<LocalDateTime> balanceStatTimeUtcHistory = configShiftUtils.getBalanceStatTimeUtcHistory(deptId);
            pmrQueryStartUtc = configShiftUtils.getBalanceStatStartUtc(balanceStatTimeUtcHistory, startTime);
            pmrQueryEndUtc = configShiftUtils.getBalanceStatStartUtc(balanceStatTimeUtcHistory, endTime);
            if (!endTime.equals(pmrQueryEndUtc)) pmrQueryEndUtc = pmrQueryEndUtc.plusDays(1);
        } else {
            DeptMonitoringSettingsPB monitoringSettings =
                settingService.getDeptMonitoringSettings(deptId);
            int headCustomTimeGraceMinutes = monitoringSettings.getHeadCustomTimePointGraceMinutes();
            int tailCustomTimeGraceMinutes = monitoringSettings.getTailCustomTimePointGraceMinutes();
            pmrQueryStartUtc = pmrQueryStartUtc.minusMinutes(headCustomTimeGraceMinutes);
            pmrQueryEndUtc = pmrQueryEndUtc.plusMinutes(tailCustomTimeGraceMinutes);
        }

        return new Pair<>(pmrQueryStartUtc, pmrQueryEndUtc);
    }

    private final ConfigurableApplicationContext context;
    private final ConfigProtoService protoService;
    private final ConfigShiftUtils configShiftUtils;
    private final MonitoringPB monitoringPb;
    private final MonMetaReader monMetaReader;
    private final MonMetaService monMetaService;
    private final SettingService settingService;
}
