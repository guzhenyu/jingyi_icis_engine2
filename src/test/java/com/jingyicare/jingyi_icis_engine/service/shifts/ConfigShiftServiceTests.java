package com.jingyicare.jingyi_icis_engine.service.shifts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.entity.settings.DeptSystemSettings;
import com.jingyicare.jingyi_icis_engine.entity.settings.DeptSystemSettingsId;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDeptShiftReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDeptShiftResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.UpdateDeptShiftReq;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.SystemSettingFunctionId;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.ShiftSettingsPB;
import com.jingyicare.jingyi_icis_engine.repository.settings.DeptSystemSettingsRepository;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.WebApiService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.settings.ShiftDayResolver;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

public class ConfigShiftServiceTests extends TestsBase {
    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("DELETE FROM balance_stats_shifts WHERE dept_id = ?", DEPT_ID);
        deleteDepartmentSettings(DEPT_ID);
        jdbcTemplate.update("DELETE FROM accounts WHERE account_id = ?", ACCOUNT_ID);
        jdbcTemplate.update("DELETE FROM rbac_accounts WHERE account_id = ?", ACCOUNT_ID);
    }

    @Test
    void shiftApiCreatesAndUpdatesSettingsWithExistingStorageKeyAndAuditFields() {
        insertDepartment(DEPT_ID);
        String accountAutoId = authenticate();

        assertThat(getShift(DEPT_ID)).isEqualTo(shiftDefaults());
        ShiftSettingsPB custom = customShift();
        assertThat(webApiService.updateDeptShift(updateRequest(DEPT_ID, custom)).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        assertThat(getShift(DEPT_ID)).isEqualTo(custom);

        DeptSystemSettings stored = settingsRepo.findById(SETTING_ID).orElseThrow();
        assertThat(stored.getSettingsPb()).isEqualTo(ProtoUtils.encodeShiftSettings(custom));
        assertThat(stored.getModifiedAt()).isNotNull();
        assertThat(stored.getModifiedBy()).isEqualTo(accountAutoId);

        stored.setModifiedAt(LEGACY_MODIFIED_AT);
        stored.setModifiedBy("previous-user");
        settingsRepo.save(stored);
        ShiftSettingsPB updated = custom.toBuilder()
            .setFunctionName("更新后的科室班次").build();
        assertThat(webApiService.updateDeptShift(updateRequest(DEPT_ID, updated)).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);

        DeptSystemSettings updatedRow = settingsRepo.findById(SETTING_ID).orElseThrow();
        assertThat(getShift(DEPT_ID)).isEqualTo(updated);
        assertThat(updatedRow.getSettingsPb()).isEqualTo(ProtoUtils.encodeShiftSettings(updated));
        assertThat(updatedRow.getModifiedAt()).isAfter(LEGACY_MODIFIED_AT);
        assertThat(updatedRow.getModifiedBy()).isEqualTo(accountAutoId);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_transaction_locks WHERE resource_key = ?", Integer.class,
            LOCK_KEY)).isEqualTo(1);
    }

    @Test
    void shiftApiFallsBackForUnreadableSettingsAndKeepsReadableLegacySettings() {
        insertDepartment(DEPT_ID);
        assertThat(getShift(DEPT_ID)).isEqualTo(shiftDefaults());
        assertThat(settingsRepo.existsById(SETTING_ID)).isFalse();

        saveLegacyShift("broken!");
        assertThat(getShift(DEPT_ID)).isEqualTo(shiftDefaults());
        assertThat(settingsRepo.findById(SETTING_ID).orElseThrow().getSettingsPb())
            .isEqualTo("broken!");

        ShiftSettingsPB empty = customShift().toBuilder().clearShift().build();
        saveLegacyShift(ProtoUtils.encodeShiftSettings(empty));
        assertThat(getShift(DEPT_ID)).isEqualTo(empty);
    }

    @Test
    void balanceHistoryUsesSystemDefaultAndEffectiveBalanceShifts() {
        insertDepartment(DEPT_ID);
        int defaultHour = shiftDefaults().getShift(0).getBalanceTimeRange().getStartHour();
        ShiftSettingsPB.Builder deptShift = customShift().toBuilder();
        deptShift.getShiftBuilder(0).getBalanceTimeRangeBuilder()
            .setStartHour((defaultHour + 1) % 24);
        saveLegacyShift(ProtoUtils.encodeShiftSettings(deptShift.build()));

        String zoneId = protoConfig.getConfig().getZoneId();
        LocalDate effectiveDate = LocalDate.of(2025, 6, 1);
        int balanceHour = (defaultHour + 2) % 24;
        jdbcTemplate.update("""
            INSERT INTO balance_stats_shifts
                (dept_id, start_hour, mon_start_hour, effective_time, modified_at, is_deleted)
            VALUES (?, ?, ?, ?, ?, FALSE)
            """, DEPT_ID, balanceHour, balanceHour,
            TimeUtils.getUtcFromLocalDateTime(effectiveDate.atStartOfDay(), zoneId),
            LEGACY_MODIFIED_AT);

        assertThat(shiftUtils.getBalanceStatTimeUtcHistory(DEPT_ID)).containsExactly(
            TimeUtils.getUtcFromLocalDateTime(TimeUtils.getMin().withHour(defaultHour), zoneId),
            TimeUtils.getUtcFromLocalDateTime(effectiveDate.atTime(balanceHour, 0), zoneId));
    }

    @ParameterizedTest
    @MethodSource("historicalDepartmentIds")
    void shiftApiAcceptsDepartmentIdsSupportedByTheLegacyStorage(String deptId) {
        insertDepartment(deptId);
        try {
            authenticate();
            ShiftSettingsPB custom = customShift();
            String requestJson = updateRequest(deptId, custom);
            assertThat(webApiService.updateDeptShift(requestJson).getRt().getCode())
                .isEqualTo(StatusCode.OK_VALUE);
            assertThat(settingsRepo.existsById(new DeptSystemSettingsId(deptId, FUNCTION_ID)))
                .isTrue();
            assertThat(getShift(deptId)).isEqualTo(custom);
        } finally {
            deleteDepartmentSettings(deptId);
        }
    }

    @Test
    void shiftApiPreservesValidationCodes() {
        SecurityContextHolder.clearContext();
        assertThat(webApiService.getDeptShift("{").getRt().getCode())
            .isEqualTo(StatusCode.PARSE_JSON_FAILED_VALUE);
        assertThat(webApiService.updateDeptShift("{").getRt().getCode())
            .isEqualTo(StatusCode.PARSE_JSON_FAILED_VALUE);
        String noShifts = updateRequest(DEPT_ID, ShiftSettingsPB.getDefaultInstance());
        assertThat(webApiService.updateDeptShift(noShifts).getRt().getCode())
            .isEqualTo(StatusCode.NO_SHIFT_SETTING_VALUE);
        String validShift = updateRequest(DEPT_ID, customShift());
        assertThat(webApiService.updateDeptShift(validShift).getRt().getCode())
            .isEqualTo(StatusCode.ACCOUNT_NOT_FOUND_VALUE);
        assertThat(settingsRepo.existsById(SETTING_ID)).isFalse();
    }

    @Test
    void resolvesLocalShiftDayStarts() {
        ShiftSettingsPB shiftSettings = shiftDefaults();
        String zoneId = protoConfig.getConfig().getZoneId();
        assertThat(ShiftDayResolver.getShiftDayStartLocal(
            shiftSettings, TimeUtils.getLocalTime(2024, 12, 12, 0, 0), zoneId))
            .isEqualTo(TimeUtils.getLocalTime(2024, 12, 12, 8, 0));

        assertThat(ShiftDayResolver.getShiftDayStartLocal(
            shiftSettings, TimeUtils.getLocalTime(2024, 12, 11, 23, 59), zoneId))
            .isEqualTo(TimeUtils.getLocalTime(2024, 12, 11, 8, 0));

        List<LocalDateTime> shiftDayStarts = ShiftDayResolver.listShiftDayStartsLocal(
            shiftSettings,
            TimeUtils.getLocalTime(2024, 12, 12, 5, 0),
            TimeUtils.getLocalTime(2024, 12, 15, 10, 0),
            zoneId
        );
        assertThat(shiftDayStarts).containsExactly(
            TimeUtils.getLocalTime(2024, 12, 12, 8, 0),
            TimeUtils.getLocalTime(2024, 12, 13, 8, 0),
            TimeUtils.getLocalTime(2024, 12, 14, 8, 0),
            TimeUtils.getLocalTime(2024, 12, 15, 8, 0)
        );
    }

    private ShiftSettingsPB shiftDefaults() {
        return protoConfig.getConfig().getShift().getDefaultSetting();
    }

    private ShiftSettingsPB getShift(String deptId) {
        GetDeptShiftReq req = GetDeptShiftReq.newBuilder().setDeptId(deptId).build();
        GetDeptShiftResp resp = webApiService.getDeptShift(ProtoUtils.protoToJson(req));
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        return resp.getShift();
    }

    private ShiftSettingsPB customShift() {
        ShiftSettingsPB.TimeRange range = ShiftSettingsPB.TimeRange.newBuilder()
            .setStartHour(6).setHours(24).build();
        return ShiftSettingsPB.newBuilder()
            .setFunctionId(SystemSettingFunctionId.GET_SHIFT)
            .setFunctionName("科室班次")
            .addShift(ShiftSettingsPB.Shift.newBuilder().setName("全天班")
                .setDefaultShift(range).setMonitoringTimeRange(range).setBalanceTimeRange(range))
            .build();
    }

    private String updateRequest(String deptId, ShiftSettingsPB settings) {
        return ProtoUtils.protoToJson(UpdateDeptShiftReq.newBuilder()
            .setDeptId(deptId).setShift(settings).build());
    }

    private static List<String> historicalDepartmentIds() {
        return List.of(" ", "科".repeat(255));
    }

    private void insertDepartment(String deptId) {
        jdbcTemplate.update("INSERT INTO rbac_departments(dept_id, dept_name) VALUES (?, ?)",
            deptId, "班次设置测试科室");
    }

    private void deleteDepartmentSettings(String deptId) {
        settingsRepo.deleteById(new DeptSystemSettingsId(deptId, FUNCTION_ID));
        jdbcTemplate.update("DELETE FROM app_transaction_locks WHERE resource_key = ?",
            LockKeys.deptShiftSettings(deptId));
        jdbcTemplate.update("DELETE FROM rbac_departments WHERE dept_id = ?", deptId);
    }

    private String authenticate() {
        jdbcTemplate.update("INSERT INTO rbac_accounts(account_id, account_name) VALUES (?, ?)",
            ACCOUNT_ID, ACCOUNT_ID);
        Long accountAutoId = jdbcTemplate.queryForObject("""
            INSERT INTO accounts (account_id, name) VALUES (?, ?) RETURNING id
            """, Long.class, ACCOUNT_ID, ACCOUNT_ID);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(ACCOUNT_ID, null, List.of()));
        return accountAutoId.toString();
    }

    private void saveLegacyShift(String settingsPb) {
        settingsRepo.save(DeptSystemSettings.builder()
            .id(SETTING_ID)
            .settingsPb(settingsPb)
            .modifiedAt(LEGACY_MODIFIED_AT)
            .modifiedBy("previous-user")
            .build());
    }

    private static final String DEPT_ID = "shift-settings-tests";
    private static final String ACCOUNT_ID = "shift-settings-user";
    private static final int FUNCTION_ID = SystemSettingFunctionId.GET_SHIFT_VALUE;
    private static final DeptSystemSettingsId SETTING_ID =
        new DeptSystemSettingsId(DEPT_ID, FUNCTION_ID);
    private static final String LOCK_KEY = LockKeys.deptSystemSettings(DEPT_ID, FUNCTION_ID);
    private static final LocalDateTime LEGACY_MODIFIED_AT = LocalDateTime.of(2020, 1, 1, 0, 0);

    @Autowired private ConfigShiftUtils shiftUtils;
    @Autowired private ProtoConfig protoConfig;
    @Autowired private WebApiService webApiService;
    @Autowired private DeptSystemSettingsRepository settingsRepo;
    @Autowired private JdbcTemplate jdbcTemplate;
}
