package com.jingyicare.jingyi_icis_engine.service.settings.stats;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddDeptBalanceStatsShiftReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddDeptBalanceStatsShiftResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.BalanceStatsShiftPB;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeleteDeptBalanceStatsShiftReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDeptBalanceStatsShiftsReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDeptBalanceStatsShiftsResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftJdbc;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.ReturnCodeUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
@Slf4j
public class StatsDayConfigService {
    public StatsDayConfigService(
        ProtoConfig protoConfig,
        StatsDayConfigReader reader,
        UserQuery userQuery,
        BalanceStatsShiftRepository repository,
        JdbcTemplate jdbcTemplate,
        LockExecutor lockExecutor
    ) {
        this.zoneId = protoConfig.getConfig().getZoneId();
        this.statusMessages = protoConfig.getConfig().getText().getStatusCodeMsgList();
        this.reader = reader;
        this.userQuery = userQuery;
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.lockExecutor = lockExecutor;
    }

    public GetDeptBalanceStatsShiftsResp list(String requestJson) {
        final GetDeptBalanceStatsShiftsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                requestJson, GetDeptBalanceStatsShiftsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse stats day config list request", e);
            return GetDeptBalanceStatsShiftsResp.newBuilder()
                .setRt(returnCode(StatusCode.PARSE_JSON_FAILED)).build();
        }

        List<BalanceStatsShiftPB> shifts = reader.listActiveByDept(req.getDeptId()).stream()
            .map(this::toShiftPB).toList();
        return GetDeptBalanceStatsShiftsResp.newBuilder()
            .setRt(returnCode(StatusCode.OK)).addAllShift(shifts).build();
    }

    public AddDeptBalanceStatsShiftResp add(String requestJson) {
        AddDeptBalanceStatsShiftResp.Builder response = AddDeptBalanceStatsShiftResp.newBuilder();
        final AddDeptBalanceStatsShiftReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                requestJson, AddDeptBalanceStatsShiftReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse stats day config add request", e);
            return response.setRt(returnCode(StatusCode.PARSE_JSON_FAILED)).build();
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return response.setRt(returnCode(StatusCode.ACCOUNT_NOT_FOUND)).build();
        }

        BalanceStatsShiftPB input = req.getShift();
        // 新增的生效时间按医院当地日期归到午夜。
        LocalDateTime effectiveLocal = TimeUtils.fromIso8601String(
            input.getEffectiveTimeIso8601(), zoneId);
        LocalDateTime effectiveTimeUtc = TimeUtils.getUtcFromLocalDateTime(
            effectiveLocal.toLocalDate().atStartOfDay(), zoneId);
        Integer halfDayHours = readHalfDayHours(input);
        if (halfDayHours != null && !isValidHalfDayHours(halfDayHours)) {
            return response.setRt(returnCode(StatusCode.INVALID_PARAM_VALUE)).build();
        }

        String deptId = input.getDeptId();
        return lockExecutor.execute(List.of(LockKeys.statsDayConfig(deptId)), () -> {
            if (existsAt(deptId, effectiveTimeUtc)) {
                return response.setRt(returnCode(StatusCode.BALANCE_STATS_SHIFT_EXISTS)).build();
            }
            BalanceStatsShift shift = BalanceStatsShift.builder()
                .deptId(deptId)
                .isDeleted(false)
                .build();
            applyConfig(input, effectiveTimeUtc, halfDayHours, account, shift);
            long id = repository.save(shift).getId();
            return response.setRt(returnCode(StatusCode.OK)).setId(id).build();
        });
    }

    public GenericResp update(String requestJson) {
        final AddDeptBalanceStatsShiftReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                requestJson, AddDeptBalanceStatsShiftReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse stats day config update request", e);
            return ReturnCodeUtils.genericResponse(returnCode(StatusCode.PARSE_JSON_FAILED));
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return ReturnCodeUtils.genericResponse(returnCode(StatusCode.ACCOUNT_NOT_FOUND));
        }

        BalanceStatsShiftPB input = req.getShift();
        LocalDateTime effectiveTimeUtc = TimeUtils.fromIso8601String(
            input.getEffectiveTimeIso8601(), "UTC");
        if (effectiveTimeUtc == null) {
            return ReturnCodeUtils.genericResponse(returnCode(StatusCode.INVALID_TIME_FORMAT));
        }
        Integer halfDayHours = readHalfDayHours(input);
        if (halfDayHours != null && !isValidHalfDayHours(halfDayHours)) {
            return ReturnCodeUtils.genericResponse(returnCode(StatusCode.INVALID_PARAM_VALUE));
        }

        long id = input.getId();
        String deptId = input.getDeptId();
        return lockExecutor.execute(List.of(LockKeys.statsDayConfig(deptId)), () -> {
            BalanceStatsShift shift = repository.findByIdAndDeptIdAndIsDeletedFalse(id, deptId)
                .orElse(null);
            if (shift == null) {
                return ReturnCodeUtils.genericResponse(
                    returnCode(StatusCode.BALANCE_STATS_SHIFT_NOT_FOUND));
            }
            if (!shift.getEffectiveTime().equals(effectiveTimeUtc)
                && existsAt(deptId, effectiveTimeUtc)) {
                return ReturnCodeUtils.genericResponse(
                    returnCode(StatusCode.BALANCE_STATS_SHIFT_EXISTS));
            }

            applyConfig(input, effectiveTimeUtc, halfDayHours, account, shift);
            repository.save(shift);
            log.info("Stats day config updated by {}: id={}, deptId={}, effectiveTimeUtc={}",
                account.autoAcctId(), id, deptId, effectiveTimeUtc);
            return ReturnCodeUtils.genericResponse(returnCode(StatusCode.OK));
        });
    }

    public GenericResp delete(String requestJson) {
        final DeleteDeptBalanceStatsShiftReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                requestJson, DeleteDeptBalanceStatsShiftReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse stats day config delete request", e);
            return ReturnCodeUtils.genericResponse(returnCode(StatusCode.PARSE_JSON_FAILED));
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return ReturnCodeUtils.genericResponse(returnCode(StatusCode.ACCOUNT_NOT_FOUND));
        }

        long id = req.getId();
        GenericResp ok = ReturnCodeUtils.genericResponse(returnCode(StatusCode.OK));
        Supplier<String> planLock = () -> {
            String deptId = BalanceStatsShiftJdbc.findActiveDeptId(jdbcTemplate, id);
            return deptId == null ? null : LockKeys.statsDayConfig(deptId);
        };
        return lockExecutor.executePlannedIfPresent(planLock, List::of,
            key -> key.equals(planLock.get()), key -> {
                BalanceStatsShift shift = repository.findByIdAndIsDeletedFalse(id).orElse(null);
                if (shift == null) return ok;

                shift.setIsDeleted(true);
                shift.setDeletedAt(TimeUtils.getNowUtc());
                shift.setDeletedBy(account.autoAcctId());
                repository.save(shift);
                return ok;
            }, ok);
    }

    private boolean existsAt(String deptId, LocalDateTime effectiveTimeUtc) {
        return repository.findByDeptIdAndEffectiveTimeAndIsDeletedFalse(deptId, effectiveTimeUtc)
            .isPresent();
    }

    private void applyConfig(
        BalanceStatsShiftPB input, LocalDateTime effectiveTimeUtc, Integer halfDayHours,
        CurrentAccount account, /* OUT */ BalanceStatsShift shift
    ) {
        shift.setStartHour(input.getStartHour());
        shift.setMonStartHour(
            input.hasMonStartHour() ? input.getMonStartHour() : input.getStartHour());
        shift.setHalfDayShiftHours(halfDayHours);
        shift.setEffectiveTime(effectiveTimeUtc);
        shift.setModifiedAt(TimeUtils.getNowUtc());
        shift.setModifiedBy(account.autoAcctId());
        shift.setModifiedByAccountName(account.acctName());
    }

    private BalanceStatsShiftPB toShiftPB(BalanceStatsShift shift) {
        Integer monStartHour = shift.getMonStartHour();
        BalanceStatsShiftPB.Builder builder = BalanceStatsShiftPB.newBuilder()
            .setId(shift.getId())
            .setDeptId(shift.getDeptId())
            .setStartHour(shift.getStartHour())
            .setMonStartHour(monStartHour == null ? shift.getStartHour() : monStartHour)
            .setEffectiveTimeIso8601(TimeUtils.toIso8601String(shift.getEffectiveTime(), zoneId));
        Integer halfDayHours = shift.getHalfDayShiftHours();
        if (halfDayHours != null && isValidHalfDayHours(halfDayHours)) {
            builder.setHalfDayShiftHours(halfDayHours);
        }
        return builder.build();
    }

    private static Integer readHalfDayHours(BalanceStatsShiftPB input) {
        return input.hasHalfDayShiftHours() ? input.getHalfDayShiftHours() : null;
    }

    private static boolean isValidHalfDayHours(int hours) {
        return hours > 0 && hours < 24;
    }

    private ReturnCode returnCode(StatusCode statusCode) {
        return ReturnCodeUtils.getReturnCode(statusMessages, statusCode);
    }

    private final String zoneId;
    private final List<String> statusMessages;
    private final StatsDayConfigReader reader;
    private final UserQuery userQuery;
    private final BalanceStatsShiftRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final LockExecutor lockExecutor;
}
