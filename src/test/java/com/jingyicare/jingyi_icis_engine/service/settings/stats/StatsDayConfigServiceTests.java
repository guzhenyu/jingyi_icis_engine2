package com.jingyicare.jingyi_icis_engine.service.settings.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddDeptBalanceStatsShiftReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddDeptBalanceStatsShiftResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.BalanceStatsShiftPB;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeleteDeptBalanceStatsShiftReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDeptBalanceStatsShiftsReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDeptBalanceStatsShiftsResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.service.WebApiService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

class StatsDayConfigServiceTests extends TestsBase {
    @BeforeEach
    void authenticate() {
        jdbcTemplate.update("INSERT INTO rbac_accounts(account_id, account_name) VALUES (?, ?)",
            ACCOUNT_ID, ACCOUNT_NAME);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(ACCOUNT_ID, null, List.of()));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("DELETE FROM balance_stats_shifts WHERE dept_id IN (?, ?)",
            DEPT_ID, REQUEST_DEPT_ID);
        jdbcTemplate.update("DELETE FROM app_transaction_locks WHERE resource_key IN (?, ?)",
            LockKeys.statsDayConfig(DEPT_ID), LockKeys.statsDayConfig(REQUEST_DEPT_ID));
        jdbcTemplate.update("DELETE FROM rbac_accounts WHERE account_id = ?", ACCOUNT_ID);
    }

    @Test
    void preservesCrudContractsAndAuditThroughExistingWebApi() {
        BalanceStatsShiftPB shift = BalanceStatsShiftPB.newBuilder().setDeptId(DEPT_ID)
            .setEffectiveTimeIso8601("2026-10-23T19:23:45.123456+08:00")
            .setStartHour(6)
            .build();
        AddDeptBalanceStatsShiftResp created = webApi.addDeptBalanceStatsShift(writeRequest(shift));
        assertThat(created.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        long id = created.getId();
        BalanceStatsShift stored = repository.findById(id).orElseThrow();
        assertThat(stored.getEffectiveTime()).isEqualTo(ANCHOR);
        assertThat(stored.getModifiedBy()).isEqualTo(ACCOUNT_ID);
        assertThat(stored.getModifiedByAccountName()).isEqualTo(ACCOUNT_NAME);

        AddDeptBalanceStatsShiftResp duplicate = webApi.addDeptBalanceStatsShift(writeRequest(
            shift.toBuilder().setEffectiveTimeIso8601("2026-10-23T08:59:00+08:00").build()));
        assertThat(duplicate.getRt().getCode())
            .isEqualTo(StatusCode.BALANCE_STATS_SHIFT_EXISTS_VALUE);

        long laterId = seedShift(DEPT_ID, ANCHOR.plusDays(1));
        List<BalanceStatsShiftPB> beforeUpdate = listActive(DEPT_ID);
        assertThat(beforeUpdate).extracting(BalanceStatsShiftPB::getId)
            .containsExactly(laterId, id);
        assertThat(beforeUpdate.get(1).getMonStartHour()).isEqualTo(6);
        assertThat(beforeUpdate.get(1).hasHalfDayShiftHours()).isFalse();

        BalanceStatsShiftPB update = shift.toBuilder().setId(id)
            .setEffectiveTimeIso8601("2026-10-23T19:23:45.123456+08:00")
            .setMonStartHour(7).setHalfDayShiftHours(11).build();
        assertThat(webApi.updateDeptBalanceStatsShift(writeRequest(update)).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        assertThat(repository.findById(id).orElseThrow().getEffectiveTime())
            .isEqualTo(ANCHOR.plusHours(19).plusMinutes(23).plusSeconds(45).plusNanos(123456000));
        BalanceStatsShiftPB updated = listActive(DEPT_ID).get(1);
        assertThat(updated.getEffectiveTimeIso8601()).isEqualTo("2026-10-23T19:23+08:00");
        assertThat(updated.getMonStartHour()).isEqualTo(7);
        assertThat(updated.getHalfDayShiftHours()).isEqualTo(11);

        String deleteJson = ProtoUtils.protoToJson(
            DeleteDeptBalanceStatsShiftReq.newBuilder().setId(id).build());
        assertThat(webApi.deleteDeptBalanceStatsShift(deleteJson).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        BalanceStatsShift deleted = repository.findById(id).orElseThrow();
        assertThat(deleted.getIsDeleted()).isTrue();
        assertThat(deleted.getDeletedBy()).isEqualTo(ACCOUNT_ID);
        assertThat(deleted.getDeletedAt()).isNotNull();
        assertThat(webApi.deleteDeptBalanceStatsShift(deleteJson).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        assertThat(repository.findById(id).orElseThrow().getDeletedAt())
            .isEqualTo(deleted.getDeletedAt());
        assertThat(webApi.updateDeptBalanceStatsShift(writeRequest(update)).getRt().getCode())
            .isEqualTo(StatusCode.BALANCE_STATS_SHIFT_NOT_FOUND_VALUE);
        assertThat(listActive(DEPT_ID)).extracting(BalanceStatsShiftPB::getId)
            .containsExactly(laterId);
        assertThat(testLocks()).containsExactly(LockKeys.statsDayConfig(DEPT_ID));
    }

    @Test
    void changedTimeChecksForDuplicatesInMatchingDepartment() {
        long id = seedShift(DEPT_ID, ANCHOR);
        LocalDateTime newTime = ANCHOR.plusHours(1);
        long conflictingId = seedShift(DEPT_ID, newTime);
        BalanceStatsShiftPB update = updateRequest(id, DEPT_ID, newTime);

        assertThat(webApi.updateDeptBalanceStatsShift(writeRequest(update)).getRt().getCode())
            .isEqualTo(StatusCode.BALANCE_STATS_SHIFT_EXISTS_VALUE);
        assertThat(testLocks()).containsExactly(LockKeys.statsDayConfig(DEPT_ID));
        assertThat(repository.findById(id).orElseThrow().getEffectiveTime()).isEqualTo(ANCHOR);

        repository.deleteById(conflictingId);
        assertThat(webApi.updateDeptBalanceStatsShift(writeRequest(update)).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        BalanceStatsShift edited = repository.findById(id).orElseThrow();
        assertThat(edited.getDeptId()).isEqualTo(DEPT_ID);
        assertThat(edited.getEffectiveTime()).isEqualTo(newTime);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void mismatchedDepartmentReturnsNotFoundAndLeavesShiftUnchanged(int offsetHours) {
        long id = seedShift(DEPT_ID, ANCHOR);
        BalanceStatsShift original = repository.findById(id).orElseThrow();
        LocalDateTime requestedTime = ANCHOR.plusHours(offsetHours);
        seedShift(REQUEST_DEPT_ID, requestedTime);
        BalanceStatsShiftPB update = updateRequest(id, REQUEST_DEPT_ID, requestedTime);

        assertThat(webApi.updateDeptBalanceStatsShift(writeRequest(update)).getRt().getCode())
            .isEqualTo(StatusCode.BALANCE_STATS_SHIFT_NOT_FOUND_VALUE);
        assertThat(testLocks()).containsExactly(LockKeys.statsDayConfig(REQUEST_DEPT_ID));
        assertThat(repository.findById(id).orElseThrow()).isEqualTo(original);
    }

    private long seedShift(String deptId, LocalDateTime effectiveTime) {
        return repository.save(BalanceStatsShift.builder().deptId(deptId)
            .effectiveTime(effectiveTime).startHour(8).monStartHour(8).isDeleted(false)
            .modifiedAt(ANCHOR).build()).getId();
    }

    private BalanceStatsShiftPB updateRequest(long id, String deptId, LocalDateTime effectiveTime) {
        return BalanceStatsShiftPB.newBuilder().setId(id).setDeptId(deptId).setStartHour(9)
            .setEffectiveTimeIso8601(TimeUtils.toIso8601String(effectiveTime, "UTC")).build();
    }

    private String writeRequest(BalanceStatsShiftPB shift) {
        return ProtoUtils.protoToJson(AddDeptBalanceStatsShiftReq.newBuilder().setShift(shift));
    }

    private List<BalanceStatsShiftPB> listActive(String deptId) {
        GetDeptBalanceStatsShiftsResp result = webApi.getDeptBalanceStatsShifts(ProtoUtils
            .protoToJson(GetDeptBalanceStatsShiftsReq.newBuilder().setDeptId(deptId)));
        assertThat(result.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        return result.getShiftList();
    }

    private List<String> testLocks() {
        return jdbcTemplate.queryForList("""
            SELECT resource_key FROM app_transaction_locks WHERE resource_key IN (?, ?)
            """, String.class, LockKeys.statsDayConfig(DEPT_ID),
            LockKeys.statsDayConfig(REQUEST_DEPT_ID));
    }

    private static final LocalDateTime ANCHOR = LocalDateTime.of(2026, 10, 22, 16, 0);
    private static final String ACCOUNT_ID = "stats-day-config-api-tests";
    private static final String ACCOUNT_NAME = "统计日配置测试";
    private static final String DEPT_ID = "stats-day-config-actual-dept";
    private static final String REQUEST_DEPT_ID = "stats-day-config-request-dept";

    @Autowired private BalanceStatsShiftRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private WebApiService webApi;
}
