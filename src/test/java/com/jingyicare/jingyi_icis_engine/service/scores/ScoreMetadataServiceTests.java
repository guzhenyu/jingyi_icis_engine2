package com.jingyicare.jingyi_icis_engine.service.scores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringRecord;
import com.jingyicare.jingyi_icis_engine.entity.scores.PatientScore;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSnapshot.PatientSnapshotPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.GenericValuePB;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.scores.PatientScoreRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.service.reports.common.*;
import com.jingyicare.jingyi_icis_engine.service.shifts.StatisticsDayWindow;
import com.jingyicare.jingyi_icis_engine.service.shifts.StatisticsDayWindowResolver;
import com.jingyicare.jingyi_icis_engine.service.snapshots.SnapshotService;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.*;

/** 通过真实 PostgreSQL 事务验证统一评分写入、观察联写和快照刷新，不套测试外层事务。 */
class ScoreMetadataServiceTests extends TestsBase {
    @BeforeEach
    void prepare() {
        clean();
        jdbc.update("""
            INSERT INTO patient_records (id, dept_id, admission_status, admission_time)
            VALUES (?, ?, 1, ?)
            """, PID, DEPT, TIME.minusDays(3));
        // 测试库由 JPA 建表，补上生产库中已有的两个活动记录部分唯一索引。
        jdbc.execute("""
            CREATE UNIQUE INDEX score_tests_score_unique ON patient_scores
            (pid, score_group_code, effective_time) WHERE is_deleted = FALSE AND pid = 9620041
            """);
        jdbc.execute("""
            CREATE UNIQUE INDEX score_tests_monitor_unique ON patient_monitoring_records
            (pid, monitoring_param_code, effective_time)
            WHERE is_deleted = FALSE AND pid = 9620041
            """);
        users = mock(UserService.class);
        when(users.getAccountWithAutoId()).thenReturn(new Pair<>("123", "当前用户"));
        calculator = mock(ScoreCalculator.class);
        when(calculator.validateScoreGroup(any(), any())).thenReturn(StatusCode.OK);
        when(calculator.printGroupScore(any(), any(), any())).thenAnswer(invocation -> {
            ((StringBuilder) invocation.getArgument(2)).append("15分");
            return StatusCode.OK;
        });
        invalidation = mock(PatientNursingReportInvalidationService.class,
            delegatesTo(realInvalidation));
        service = createService(jdbc);
    }

    private ScoreService createService(JdbcTemplate scoreJdbc) {
        ConfigProtoService config = mock(ConfigProtoService.class, delegatesTo(protoService));
        when(config.getConfig()).thenReturn(Config.newBuilder().setZoneId("Asia/Shanghai")
            .setMonitoring(MonitoringPB.newBuilder().addParamModalOption(
                MonitoringParamModalOptionsPB.newBuilder().setParamCode(PARAM)
                    .addOption(MonitoringModalOptionPB.newBuilder()
                        .setUiModalCode("gcs_modal").setScoreGroupCode(CODE)))).build());
        ScoreMetaService metadata = mock(ScoreMetaService.class);
        when(metadata.getConfiguredScoreGroupMeta(anyString())).thenAnswer(invocation ->
            ScoreGroupMetaPB.newBuilder().setCode(invocation.getArgument(0)).build());
        MonitoringParamRepository params = mock(MonitoringParamRepository.class);
        when(params.findByCode(PARAM)).thenReturn(java.util.Optional.of(
            MonitoringParam.builder().code(PARAM).uiModalCode("gcs_modal").build()));
        checkedPatients = mock(PatientService.class, delegatesTo(patients));
        return new ScoreService(config, metadata, calculator, users, checkedPatients, invalidation,
            scores, params, monitoring, reader, snapshots, executor, scoreJdbc);
    }

    @AfterEach
    void cleanUp() { clean(); }

    @ParameterizedTest
    @ValueSource(strings = {"ADD", "UPDATE", "UPSERT"})
    void allModesUseCurrentRecorderAndServerTextEvenWhenClientSuppliesRecorder(String mode) {
        ScoreGroupPB request = request(TIME).toBuilder().setRecordedBy("321").build();
        if (mode.equals("UPDATE")) request = request.toBuilder().setId(seed(TIME, CODE).getId())
            .build();

        assertThat(write(mode, request)).isEqualTo(StatusCode.OK_VALUE);

        PatientScore stored = active(TIME);
        assertThat(stored.getModifiedBy()).isEqualTo("123");
        assertThat(stored.getRawModifiedByAccountName()).isEqualTo("当前用户");
        assertThat(stored.getScoreStr()).isEqualTo("15分");
        assertThat(stored.getNote()).isEqualTo("列备注");
        ScoreGroupPB payload = ProtoUtils.decodeScoreGroupPB(stored.getScore());
        assertThat(payload.getGroupScoreText()).isEqualTo("15分");
        assertThat(payload.getId()).isZero();
        assertThat(payload.getPid()).isZero();
        assertThat(payload.getRecordedBy()).isEmpty();
        assertThat(payload.getRecordedByAccountName()).isEmpty();
        assertThat(payload.getRecordedAtIso8601()).isEmpty();
        assertThat(payload.getNote()).isEmpty();
        assertThat(payload.getMeasureList()).isEqualTo(request.getMeasureList());
        PatientMonitoringRecord linked = linked(TIME);
        assertThat(linked.getParamValueStr()).isEqualTo("15分");
        assertThat(linked.getModifiedBy()).isEqualTo("123");
        assertThat(linked.getNote()).isEqualTo("列备注");
        assertThat(linked.getModifiedAt()).isEqualTo(stored.getModifiedAt());
        verify(users, never()).getNameByAutoId(anyString());
        assertMarked(TIME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADD", "UPDATE", "UPSERT"})
    void allModesUseCurrentUserWhenRecorderIsMissing(String mode) {
        ScoreGroupPB request = request(TIME);
        if (mode.equals("UPDATE")) request = request.toBuilder().setId(seed(TIME, CODE).getId())
            .build();
        assertThat(write(mode, request)).isEqualTo(StatusCode.OK_VALUE);
        assertThat(active(TIME).getModifiedBy()).isEqualTo("123");
        assertThat(active(TIME).getRawModifiedByAccountName()).isEqualTo("当前用户");
        assertThat(linked(TIME).getModifiedBy()).isEqualTo("123");
    }

    @Test
    void addRejectsDuplicateAndUpsertIgnoresClientIdAndReusesExistingId() {
        assertThat(write("ADD", request(TIME))).isEqualTo(StatusCode.OK_VALUE);
        long id = active(TIME).getId();
        assertThat(write("ADD", request(TIME)))
            .isEqualTo(StatusCode.PATIENT_SCORE_ALREADY_EXISTS_VALUE);
        assertThat(write("UPSERT", request(TIME).toBuilder().setId(Long.MAX_VALUE)
            .setNote("更新备注").build())).isEqualTo(StatusCode.OK_VALUE);
        assertThat(active(TIME).getId()).isEqualTo(id);
        assertThat(linked(TIME).getNote()).isEqualTo("更新备注");
        assertThat(monitoring.findByPidAndIsDeletedTrue(PID)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void saveReturnsServerTextForBothNewAndExistingRecords(boolean exists) {
        Long existingId = exists ? seed(TIME, CODE).getId() : null;
        SavePatientScoreResp response = service.savePatientScore(ProtoUtils.protoToJson(
            SavePatientScoreReq.newBuilder().setRecord(request(TIME).toBuilder()
                .setId(Long.MAX_VALUE).setRecordedBy("321")).build()));
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(response.getGroupScoreText()).isEqualTo("15分");
        PatientScore stored = active(TIME);
        if (exists) assertThat(stored.getId()).isEqualTo(existingId);
        assertThat(stored.getModifiedBy()).isEqualTo("123");
        assertThat(linked(TIME).getParamValueStr()).isEqualTo(response.getGroupScoreText());
        assertThat(count("patient_scores")).isEqualTo(1);
    }

    @Test
    void updateClearsNoteAndRejectsMissingTimeMalformedTimeAndIdentityChanges() {
        assertThat(write("ADD", request(TIME))).isEqualTo(StatusCode.OK_VALUE);
        long id = active(TIME).getId();
        ScoreGroupPB update = request(TIME).toBuilder().setId(id).clearNote().build();
        assertThat(write("UPDATE", update)).isEqualTo(StatusCode.OK_VALUE);
        assertThat(active(TIME).getNote()).isEmpty();
        assertThat(linked(TIME).getNote()).isEmpty();
        assertThat(write("UPDATE", update.toBuilder().clearRecordedAtIso8601().build()))
            .isEqualTo(StatusCode.INVALID_SCORE_RECORD_TIME_VALUE);
        assertThat(write("UPDATE", update.toBuilder().setRecordedAtIso8601("bad").build()))
            .isEqualTo(StatusCode.INVALID_SCORE_RECORD_TIME_VALUE);
        assertThat(write("UPDATE", update.toBuilder().setPid(PID + 1).build()))
            .isEqualTo(StatusCode.PATIENT_SCORE_ID_NOT_MATCH_VALUE);
        assertThat(write("UPDATE", update.toBuilder().setCode("other").build()))
            .isEqualTo(StatusCode.PATIENT_SCORE_ID_NOT_MATCH_VALUE);
        assertThat(write("UPDATE", update.toBuilder().clearId().build()))
            .isEqualTo(StatusCode.INVALID_PARAM_VALUE_VALUE);
        assertThat(write("UPDATE", update.toBuilder().setId(Long.MAX_VALUE).build()))
            .isEqualTo(StatusCode.PATIENT_SCORE_NOT_FOUND_VALUE);
    }

    @Test
    void updateReturnsIdentityErrorWhenRecordChangesAfterPrecheck() {
        PatientScore original = seed(TIME, CODE);
        AtomicInteger reads = new AtomicInteger();
        JdbcTemplate changing = mock(JdbcTemplate.class, delegatesTo(jdbc));
        doAnswer(invocation -> {
            List<PatientScore> result = jdbc.query(invocation.<String>getArgument(0),
                invocation.<PreparedStatementSetter>getArgument(1),
                invocation.<RowMapper<PatientScore>>getArgument(2));
            // 预校验读到原值后模拟类型变化，覆盖锁后的一致性检查。
            if (reads.incrementAndGet() == 1) {
                jdbc.update("UPDATE patient_scores SET score_group_code = ? WHERE id = ?",
                    "other", original.getId());
            }
            return result;
        }).when(changing).query(anyString(), any(PreparedStatementSetter.class),
            any(RowMapper.class));
        service = createService(changing);

        GenericResp response = service.updatePatientScore(ProtoUtils.protoToJson(
            UpdatePatientScoreReq.newBuilder()
                .setRecord(request(TIME).toBuilder().setId(original.getId())).build()));

        assertThat(response.getRt().getCode())
            .isEqualTo(StatusCode.PATIENT_SCORE_ID_NOT_MATCH_VALUE);
        assertThat(response.getRt().getMsg()).isEqualTo("患者或评分类型与原评分记录不一致");
        PatientScore stored = scores.findById(original.getId()).orElseThrow();
        assertThat(stored.getScoreGroupCode()).isEqualTo("other");
        assertThat(stored.getScoreStr()).isEqualTo(original.getScoreStr());
        assertThat(count("patient_monitoring_records")).isZero();
        assertThat(count("patient_snapshots")).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void admissionStatusSelectsTheApplicableTimeBoundary(int status) {
        jdbc.update("UPDATE patient_records SET admission_status = ?, discharge_time = ? "
            + "WHERE id = ?",
            status, TIME.plusDays(1), PID);
        for (String mode : List.of("ADD", "UPDATE", "UPSERT")) {
            long id = mode.equals("UPDATE") ? seed(TIME, CODE).getId() : 0;
            LocalDateTime rejectedTime = status < 2 ? TIME.minusDays(4) : TIME.plusDays(2);
            int error = status < 2 ? StatusCode.SCORE_RECORD_TIME_IS_BEFORE_ADMISSION_TIME_VALUE
                : StatusCode.SCORE_RECORD_TIME_IS_AFTER_DISCHARGE_TIME_VALUE;
            assertThat(write(mode, request(rejectedTime).toBuilder().setId(id).build()))
                .isEqualTo(error);
        }
        assertThat(monitoring.findByPidAndIsDeletedFalse(PID)).isEmpty();
        assertThat(count("patient_snapshots")).isZero();
        verifyNoInteractions(calculator);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM app_transaction_locks WHERE resource_key LIKE ?
            """, Integer.class, "%:" + PID + ":%")).isZero();
        LocalDateTime ignoredBoundary = status < 2 ? TIME.plusDays(2) : TIME.minusDays(4);
        assertThat(write("ADD", request(ignoredBoundary))).isEqualTo(StatusCode.OK_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADD", "UPDATE", "UPSERT"})
    void checksPatientOnceBeforeEnteringLockTransaction(String mode) {
        ScoreGroupPB record = request(TIME);
        if (mode.equals("UPDATE")) {
            record = record.toBuilder().setId(seed(TIME, CODE).getId()).build();
        }
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return patients.getPatientRecord(invocation.getArgument(0));
        }).when(checkedPatients).getPatientRecord(PID);
        assertThat(write(mode, record)).isEqualTo(StatusCode.OK_VALUE);
        verify(checkedPatients, times(1)).getPatientRecord(PID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADD", "UPDATE", "UPSERT"})
    void missingPatientReturnsBeforeScoreCalculationOrLocking(String mode) {
        ScoreGroupPB record = request(TIME);
        if (mode.equals("UPDATE")) {
            record = record.toBuilder().setId(seed(TIME, CODE).getId()).build();
        }
        jdbc.update("DELETE FROM patient_records WHERE id = ?", PID);
        assertThat(write(mode, record)).isEqualTo(StatusCode.PATIENT_NOT_FOUND_VALUE);
        verifyNoInteractions(calculator);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM app_transaction_locks WHERE resource_key LIKE ?
            """, Integer.class, "%:" + PID + ":%")).isZero();
    }

    @Test
    void updateRejectsOccupiedTargetWithoutChangingEitherScoreOrMonitoring() {
        assertThat(write("ADD", request(TIME))).isEqualTo(StatusCode.OK_VALUE);
        assertThat(write("ADD", request(TIME.plusDays(1)))).isEqualTo(StatusCode.OK_VALUE);
        long id = active(TIME).getId();
        assertThat(write("UPDATE", request(TIME.plusDays(1)).toBuilder().setId(id).build()))
            .isEqualTo(StatusCode.PATIENT_SCORE_ALREADY_EXISTS_VALUE);
        assertThat(active(TIME).getId()).isEqualTo(id);
        assertThat(monitoring.findByPidAndIsDeletedFalse(PID)).hasSize(2);
    }

    @Test
    void movingScoreMigratesMonitoringAndInvalidatesOldAndNewSnapshotDays() {
        assertThat(write("ADD", request(TIME))).isEqualTo(StatusCode.OK_VALUE);
        long id = active(TIME).getId();
        List<LocalDate> oldDates = dates(TIME);
        assertThat(snapshots.get(PID, oldDates, false).getFirst().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        LocalDateTime moved = TIME.plusDays(2);
        assertThat(write("UPDATE", request(moved).toBuilder().setId(id).build()))
            .isEqualTo(StatusCode.OK_VALUE);
        assertThat(scores.findByPidAndScoreGroupCodeAndEffectiveTimeAndIsDeletedFalse(
            PID, CODE, TIME)).isEmpty();
        assertThat(monitoring.findByPidAndMonitoringParamCodeAndEffectiveTimeAndIsDeletedFalse(
            PID, PARAM, TIME)).isEmpty();
        assertThat(active(moved).getId()).isEqualTo(id);
        assertThat(linked(moved).getParamValueStr()).isEqualTo("15分");
        assertThat(jdbc.queryForList("""
            SELECT resource_key FROM app_transaction_locks WHERE resource_key LIKE ?
            """, String.class, "%:" + PID + ":%"))
            .contains(LockKeys.patientScore(PID, CODE, TIME),
                LockKeys.patientScore(PID, CODE, moved),
                LockKeys.patientMonitoringRecord(PID, PARAM, TIME),
                LockKeys.patientMonitoringRecord(PID, PARAM, moved));
        assertMarked(TIME);
        assertMarked(moved);
        assertThat(snapshots.get(PID, oldDates, false).getSecond())
            .allSatisfy(snapshot -> assertThat(snapshot.getScoreGroupsList()).isEmpty());
        assertThat(snapshots.get(PID, dates(moved), false).getSecond())
            .anySatisfy(snapshot -> assertThat(snapshot.getScoreGroupsList())
                .extracting(ScoreGroupPB::getId).contains(id));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deletionIsIdempotentMarksSnapshotAndPreservesMonitoringFlag(boolean byMonitoring) {
        assertThat(write("ADD", request(TIME))).isEqualTo(StatusCode.OK_VALUE);
        long id = active(TIME).getId();
        assertThat(delete(id, byMonitoring)).isEqualTo(StatusCode.OK_VALUE);
        String version = snapshotVersion();
        assertThat(delete(id, byMonitoring)).isEqualTo(StatusCode.OK_VALUE);
        assertThat(snapshotVersion()).isEqualTo(version);
        assertThat(scores.findById(id).orElseThrow().getIsDeleted()).isTrue();
        assertThat(monitoring.findByPidAndIsDeletedFalse(PID)).hasSize(byMonitoring ? 0 : 1);
        assertThat(write("UPDATE", request(TIME).toBuilder().setId(id).build()))
            .isEqualTo(StatusCode.PATIENT_SCORE_NOT_FOUND_VALUE);
        assertThat(snapshots.get(PID, dates(TIME), false).getSecond())
            .allSatisfy(snapshot -> assertThat(snapshot.getScoreGroupsList()).isEmpty());
    }

    @Test
    void deleteKeepsParseAccountPatientAndMissingRecordResponses() {
        PatientScore original = seed(TIME, CODE);
        assertThat(service.deletePatientScore("{").getRt().getCode())
            .isEqualTo(StatusCode.PARSE_JSON_FAILED_VALUE);
        when(users.getAccountWithAutoId()).thenReturn(null);
        assertThat(delete(original.getId(), true)).isEqualTo(StatusCode.ACCOUNT_NOT_FOUND_VALUE);
        when(users.getAccountWithAutoId()).thenReturn(new Pair<>("123", "当前用户"));
        assertThat(delete(Long.MAX_VALUE, true)).isEqualTo(StatusCode.OK_VALUE);
        jdbc.update("DELETE FROM patient_records WHERE id = ?", PID);
        assertThat(delete(original.getId(), true)).isEqualTo(StatusCode.PATIENT_NOT_FOUND_VALUE);
        assertThat(scores.findById(original.getId()).orElseThrow().getIsDeleted()).isFalse();
        assertThat(count("patient_monitoring_records")).isZero();
        assertThat(count("patient_snapshots")).isZero();
        verifyNoInteractions(calculator);
    }

    @Test
    void deleteAllowsHistoricalRecordWithCorruptPayload() {
        PatientScore original = seed(TIME.minusDays(30), CODE);
        jdbc.update("UPDATE patient_scores SET score = '%%%' WHERE id = ?", original.getId());
        assertThat(delete(original.getId(), true)).isEqualTo(StatusCode.OK_VALUE);
        PatientScore deleted = scores.findById(original.getId()).orElseThrow();
        assertThat(deleted.getIsDeleted()).isTrue();
        assertThat(deleted.getScore()).isEqualTo("%%%");
        assertThat(deleted.getEffectiveTime()).isEqualTo(original.getEffectiveTime());
        assertMarked(original.getEffectiveTime());
        verifyNoInteractions(calculator);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentDeletesRemainIdempotentAndInvalidateOnce(boolean byMonitoring) throws Exception {
        assertThat(write("ADD", request(TIME))).isEqualTo(StatusCode.OK_VALUE);
        long id = active(TIME).getId();
        clearInvocations(invalidation);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(
                List.of(LockKeys.patientScore(PID, CODE, TIME)), () -> {
                    locked.countDown();
                    await(release);
                    return null;
                }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<Integer> first = pool.submit(() -> delete(id, byMonitoring));
            Future<Integer> second = pool.submit(() -> delete(id, byMonitoring));
            assertTrue(waitForBlocked(2));
            assertThat(active(TIME).getIsDeleted()).isFalse();
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                .containsExactly(StatusCode.OK_VALUE, StatusCode.OK_VALUE);
            PatientScore deleted = scores.findById(id).orElseThrow();
            assertThat(deleted.getIsDeleted()).isTrue();
            assertThat(deleted.getDeletedBy()).isEqualTo("123");
            assertThat(monitoring.findByPidAndIsDeletedFalse(PID)).hasSize(byMonitoring ? 0 : 1);
            assertThat(count("patient_monitoring_records")).isEqualTo(1);
            verify(invalidation, times(1))
                .updateLatestDataTime(any(), any(), any(), any(), any());
            assertMarked(TIME);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 20L, 86420L})
    void deleteUsesCurrentContentAndReplansChangedTime(long offsetSeconds) {
        assertThat(write("ADD", request(TIME))).isEqualTo(StatusCode.OK_VALUE);
        PatientScore original = active(TIME);
        long monitoringId = linked(TIME).getId();
        LocalDateTime moved = TIME.plusSeconds(offsetSeconds);
        AtomicInteger reads = new AtomicInteger();
        JdbcTemplate changing = mock(JdbcTemplate.class, delegatesTo(jdbc));
        doAnswer(invocation -> {
            List<PatientScore> result = jdbc.query(invocation.<String>getArgument(0),
                invocation.<PreparedStatementSetter>getArgument(1),
                invocation.<RowMapper<PatientScore>>getArgument(2));
            if (reads.incrementAndGet() == 2) {
                jdbc.update("UPDATE patient_scores SET effective_time = ?, note = ? WHERE id = ?",
                    moved, "并发修改后的备注", original.getId());
                jdbc.update("UPDATE patient_monitoring_records SET effective_time = ? WHERE id = ?",
                    moved, monitoringId);
            }
            return result;
        }).when(changing).query(anyString(), any(PreparedStatementSetter.class),
            any(RowMapper.class));
        service = createService(changing);

        assertThat(delete(original.getId(), true)).isEqualTo(StatusCode.OK_VALUE);
        assertThat(reads.get()).isEqualTo(offsetSeconds == 0 ? 3 : 5);
        PatientScore deleted = scores.findById(original.getId()).orElseThrow();
        PatientMonitoringRecord linked = monitoring.findById(monitoringId).orElseThrow();
        assertThat(deleted.getIsDeleted()).isTrue();
        assertThat(deleted.getEffectiveTime()).isEqualTo(moved);
        assertThat(deleted.getNote()).isEqualTo("并发修改后的备注");
        assertThat(deleted.getDeletedBy()).isEqualTo("123");
        assertThat(linked.getIsDeleted()).isTrue();
        assertThat(linked.getEffectiveTime()).isEqualTo(moved);
        assertThat(linked.getDeletedBy()).isEqualTo(deleted.getDeletedBy());
        assertThat(linked.getDeletedAt()).isEqualTo(deleted.getDeletedAt());
        assertThat(jdbc.queryForList("""
            SELECT resource_key FROM app_transaction_locks WHERE resource_key LIKE ?
            """, String.class, "%:" + PID + ":%"))
            .contains(LockKeys.patientScore(PID, CODE, moved.truncatedTo(ChronoUnit.MINUTES)),
                LockKeys.patientMonitoringRecord(PID, PARAM, moved));
        assertMarked(moved);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deleteReplansSnapshotDatesAfterWaitingForLock(boolean departmentChanged) throws Exception {
        String targetDept = departmentChanged ? DEPT + "-moved" : DEPT;
        jdbc.update("""
            INSERT INTO balance_stats_shifts
                (dept_id, start_hour, mon_start_hour, effective_time, is_deleted, modified_at)
            VALUES (?, 8, 8, ?, FALSE, ?)
            """, DEPT, TIME.minusDays(3), TIME);
        assertThat(write("ADD", request(TIME))).isEqualTo(StatusCode.OK_VALUE);
        long id = active(TIME).getId();
        LocalDate oldDate = TIME.toLocalDate();
        LocalDate newDate = oldDate.minusDays(1);
        String oldVersion = snapshotVersion();
        clearInvocations(invalidation);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(
                List.of(LockKeys.patientSnapshot(PID, oldDate)), () -> {
                    locked.countDown();
                    await(release);
                    return null;
                }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<Integer> deletion = pool.submit(() -> delete(id, true));
            assertTrue(waitForBlocked(1));
            assertThat(active(TIME).getIsDeleted()).isFalse();
            assertThat(linked(TIME).getIsDeleted()).isFalse();
            if (departmentChanged) {
                jdbc.update("""
                    INSERT INTO balance_stats_shifts
                        (dept_id, start_hour, mon_start_hour, effective_time,
                            is_deleted, modified_at)
                    VALUES (?, 10, 10, ?, FALSE, ?)
                    """, targetDept, TIME.minusDays(3), TIME);
                jdbc.update("UPDATE patient_records SET dept_id = ? WHERE id = ?", targetDept, PID);
            } else {
                jdbc.update("""
                    UPDATE balance_stats_shifts SET start_hour = 10, mon_start_hour = 10
                    WHERE dept_id = ?
                    """, DEPT);
            }
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            assertThat(deletion.get(15, TimeUnit.SECONDS)).isEqualTo(StatusCode.OK_VALUE);
            assertThat(scores.findById(id).orElseThrow().getIsDeleted()).isTrue();
            assertThat(monitoring.findByPidAndIsDeletedFalse(PID)).isEmpty();
            assertThat(jdbc.queryForObject("""
                SELECT xmin::text FROM patient_snapshots WHERE pid = ? AND statistics_date = ?
                """, String.class, PID, oldDate)).isEqualTo(oldVersion);
            String payload = jdbc.queryForObject("""
                SELECT payload FROM patient_snapshots WHERE pid = ? AND statistics_date = ?
                """, String.class, PID, newDate);
            assertThat(ProtoUtils.decodePatientSnapshot(payload).getScoreGroupLmaIso8601())
                .isNotEmpty();
            assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM app_transaction_locks WHERE resource_key = ?
                """, Integer.class, LockKeys.patientSnapshot(PID, newDate))).isEqualTo(1);
            verify(invalidation).updateLatestDataTime(
                eq(PID), eq(targetDept), eq(TIME), eq(TIME), any());
            assertThat(snapshots.get(PID, List.of(newDate), false).getSecond())
                .allSatisfy(snapshot -> assertThat(snapshot.getScoreGroupsList()).isEmpty());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADD", "UPDATE", "UPSERT", "DELETE"})
    void lateFailureRollsBackScoresMonitoringSnapshotsAndReports(String mode) {
        if (!mode.equals("ADD")) {
            assertThat(write("ADD", request(TIME))).isEqualTo(StatusCode.OK_VALUE);
        }
        List<PatientScore> beforeScores = scores.findByPidAndIsDeletedFalse(PID);
        List<PatientMonitoringRecord> beforeMonitoring = monitoring.findByPidAndIsDeletedFalse(PID);
        String beforeSnapshot = snapshotVersion();
        String beforeReport = reportVersions();
        doAnswer(invocation -> {
            realInvalidation.updateLatestDataTime(invocation.getArgument(0),
                invocation.getArgument(1), invocation.getArgument(2),
                invocation.getArgument(3), invocation.getArgument(4));
            throw new IllegalStateException("测试联写后失败");
        }).when(invalidation).updateLatestDataTime(any(), any(), any(), any(), any());
        int result;
        if (mode.equals("DELETE")) {
            result = delete(beforeScores.get(0).getId(), true);
        } else {
            ScoreGroupPB request = request(TIME).toBuilder().setNote("写入后失败").build();
            if (mode.equals("UPDATE")) request = request.toBuilder()
                .setId(beforeScores.get(0).getId()).setRecordedAtIso8601(iso(TIME.plusDays(2)))
                .build();
            result = write(mode, request);
        }
        assertThat(result).isEqualTo(StatusCode.INTERNAL_EXCEPTION_VALUE);
        verify(invalidation, atLeastOnce()).updateLatestDataTime(any(), any(), any(), any(), any());
        assertThat(scores.findByPidAndIsDeletedFalse(PID)).isEqualTo(beforeScores);
        assertThat(monitoring.findByPidAndIsDeletedFalse(PID)).isEqualTo(beforeMonitoring);
        assertThat(snapshotVersion()).isEqualTo(beforeSnapshot);
        assertThat(reportVersions()).isEqualTo(beforeReport);
        assertThat(monitoring.findByPidAndIsDeletedTrue(PID)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADD", "UPDATE", "UPSERT"})
    void snapshotFailureAfterLinkedWritesRollsBackEverything(String mode) {
        ScoreGroupPB record = request(TIME);
        if (mode.equals("UPDATE")) {
            record = record.toBuilder().setId(seed(TIME, CODE).getId()).build();
        }
        jdbc.execute("""
            ALTER TABLE patient_snapshots ADD CONSTRAINT score_tests_snapshot_fail
            CHECK (pid <> 9620041)
            """);
        assertThat(write(mode, record)).isEqualTo(StatusCode.INTERNAL_EXCEPTION_VALUE);
        assertThat(count("patient_scores")).isEqualTo(mode.equals("UPDATE") ? 1 : 0);
        if (mode.equals("UPDATE")) assertThat(active(TIME).getScoreStr()).isEqualTo("历史15分");
        assertThat(count("patient_monitoring_records")).isZero();
        assertThat(count("patient_nursing_reports")).isZero();
        assertThat(count("patient_snapshots")).isZero();
    }

    @Test
    void unchangedUpsertKeepsScoreAuditAndSnapshotWhileRefreshingMonitoring() {
        assertThat(write("ADD", request(TIME))).isEqualTo(StatusCode.OK_VALUE);
        PatientScore before = active(TIME);
        String version = snapshotVersion();
        long monitoringId = linked(TIME).getId();
        assertThat(write("UPSERT", request(TIME))).isEqualTo(StatusCode.OK_VALUE);
        assertThat(active(TIME)).isEqualTo(before);
        assertThat(snapshotVersion()).isEqualTo(version);
        assertThat(linked(TIME).getId()).isNotEqualTo(monitoringId);
        assertThat(count("patient_monitoring_records")).isEqualTo(2);
        assertThat(monitoring.findByPidAndIsDeletedTrue(PID)).hasSize(1);
        verify(invalidation, times(2)).updateLatestDataTime(any(), any(), any(), any(), any());
    }

    @Test
    void unchangedScoreStillRepairsMissingMonitoringWithoutInvalidatingScoreSnapshot() {
        assertThat(write("ADD", request(TIME))).isEqualTo(StatusCode.OK_VALUE);
        String version = snapshotVersion();
        jdbc.update("DELETE FROM patient_monitoring_records WHERE pid = ?", PID);
        assertThat(write("UPSERT", request(TIME))).isEqualTo(StatusCode.OK_VALUE);
        assertThat(linked(TIME).getParamValueStr()).isEqualTo("15分");
        assertThat(snapshotVersion()).isEqualTo(version);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADD", "UPDATE", "UPSERT"})
    void unmappedScoreWritesWithoutCreatingMonitoringRecord(String mode) {
        ScoreGroupPB record = request(TIME).toBuilder().setCode("unmapped").build();
        if (mode.equals("UPDATE")) {
            record = record.toBuilder().setId(seed(TIME, "unmapped").getId()).build();
        }
        assertThat(write(mode, record)).isEqualTo(StatusCode.OK_VALUE);
        assertThat(count("patient_scores")).isEqualTo(1);
        assertThat(count("patient_monitoring_records")).isZero();
        assertMarked(TIME);
    }

    @Test
    void validationFailuresDoNotWriteSourceOrSnapshotRows() {
        when(users.getAccountWithAutoId()).thenReturn(null);
        assertThat(write("ADD", request(TIME))).isEqualTo(StatusCode.ACCOUNT_NOT_FOUND_VALUE);
        when(users.getAccountWithAutoId()).thenReturn(new Pair<>("123", "当前用户"));
        assertThat(write("ADD", request(TIME).toBuilder().clearRecordedAtIso8601().build()))
            .isEqualTo(StatusCode.INVALID_SCORE_RECORD_TIME_VALUE);
        when(calculator.validateScoreGroup(any(), any()))
            .thenReturn(StatusCode.INVALID_PARAM_VALUE);
        assertThat(write("ADD", request(TIME))).isEqualTo(StatusCode.INVALID_PARAM_VALUE_VALUE);
        assertThat(count("patient_scores")).isZero();
        assertThat(count("patient_monitoring_records")).isZero();
        assertThat(count("patient_snapshots")).isZero();
    }

    @Test
    void addAndSaveRequirePatientIdInsideRecord() {
        AddPatientScoreResp add = service.addPatientScore(ProtoUtils.protoToJson(
            AddPatientScoreReq.newBuilder()
            .setRecord(request(TIME).toBuilder().clearPid()).build()));
        SavePatientScoreResp save = service.savePatientScore(ProtoUtils.protoToJson(
            SavePatientScoreReq.newBuilder()
            .setRecord(request(TIME).toBuilder().clearPid()).build()));
        assertThat(add.getRt().getCode()).isEqualTo(StatusCode.PATIENT_NOT_FOUND_VALUE);
        assertThat(save.getRt().getCode()).isEqualTo(StatusCode.PATIENT_NOT_FOUND_VALUE);
        assertThat(count("patient_scores")).isZero();
        assertThat(count("patient_monitoring_records")).isZero();
        assertThat(count("patient_snapshots")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADD", "UPDATE", "UPSERT"})
    void allModesRequireExplicitPatientCodeAndTime(String mode) {
        ScoreGroupPB record = request(TIME);
        if (mode.equals("UPDATE")) {
            record = record.toBuilder().setId(seed(TIME, CODE).getId()).build();
        }
        assertThat(write(mode, record.toBuilder().clearPid().build()))
            .isEqualTo(StatusCode.PATIENT_NOT_FOUND_VALUE);
        assertThat(write(mode, record.toBuilder().clearCode().build()))
            .isEqualTo(StatusCode.SCORE_GROUP_NOT_FOUND_VALUE);
        assertThat(write(mode, record.toBuilder().setCode(" ").build()))
            .isEqualTo(StatusCode.SCORE_GROUP_NOT_FOUND_VALUE);
        assertThat(write(mode, record.toBuilder().clearRecordedAtIso8601().build()))
            .isEqualTo(StatusCode.INVALID_SCORE_RECORD_TIME_VALUE);
        assertThat(write(mode, record.toBuilder().setRecordedAtIso8601(" ").build()))
            .isEqualTo(StatusCode.INVALID_SCORE_RECORD_TIME_VALUE);
        verifyNoInteractions(calculator);
        assertThat(count("patient_monitoring_records")).isZero();
        assertThat(count("patient_snapshots")).isZero();
    }

    @Test
    void ordinaryQueriesKeepDatabaseMetadataAndHistoricalText() {
        PatientScore stored = seed(TIME, CODE);
        stored.setModifiedBy("321");
        stored.setModifiedByAccountName(null);
        stored.setNote(null);
        scores.save(stored);
        GetPatientScoreResp single = service.getPatientScore(ProtoUtils.protoToJson(
            GetPatientScoreReq.newBuilder()
            .setPid(PID).setMonitoringParamCode(PARAM).setEffectiveTimeIso8601(iso(TIME)).build()));
        GetPatientScoresResp list = service.getPatientScores(ProtoUtils.protoToJson(
            GetPatientScoresReq.newBuilder()
            .setPid(PID).setScoreGroupCode(CODE).setQueryStartIso8601(iso(TIME.minusHours(1)))
            .setQueryEndIso8601(iso(TIME)).build()));
        assertThat(single.getFound()).isTrue();
        assertThat(list.getRecordList()).containsExactly(single.getRecord());
        assertThat(single.getRecord().getId()).isEqualTo(stored.getId());
        assertThat(single.getRecord().getRecordedBy()).isEqualTo("321");
        assertThat(single.getRecord().getRecordedByAccountName()).isEmpty();
        assertThat(single.getRecord().getNote()).isEmpty();
        assertThat(single.getRecord().getGroupScoreText()).isEqualTo("历史15分");
    }

    @Test
    void readerAndSnapshotUseHalfOpenWindowAllCodesAndDoNotRecalculateHistoricalText() {
        LocalDate day = dates(TIME).get(0);
        StatisticsDayWindow window = resolver.resolve(
            TimeUtils.getDayMidnightUtc(day, ZoneId.of("Asia/Shanghai")),
            "Asia/Shanghai", DEPT);
        PatientScore first = seed(window.monitoringStartUtc(), CODE);
        PatientScore retired = seed(window.monitoringStartUtc().plusHours(1), "retired");
        PatientScore next = seed(window.monitoringEndUtc(), CODE);
        List<ScoreGroupPB> read = reader.getRecords(PID, window.monitoringStartUtc(),
            window.monitoringEndUtc());
        assertThat(read).extracting(ScoreGroupPB::getId)
            .containsExactly(first.getId(), retired.getId());
        Pair<ReturnCode, List<PatientSnapshotPB>> result = snapshots.get(PID, List.of(day), true);
        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(result.getSecond().get(0).getScoreGroupsList()).isEqualTo(read);
        assertThat(read).allSatisfy(score -> assertThat(score.getGroupScoreText())
            .isEqualTo("历史15分"));
        assertThat(result.getSecond().get(0).getScoreGroupSaIso8601()).isNotEmpty();
        assertThat(reader.getRecords(PID, window.monitoringEndUtc(),
            window.monitoringEndUtc().plusDays(1))).extracting(ScoreGroupPB::getId)
            .containsExactly(next.getId());
    }

    @Test
    void corruptedSourceFailsSnapshotWithoutAdvancingSnapshotTime() {
        PatientScore stored = seed(TIME, CODE);
        jdbc.update("UPDATE patient_scores SET score = '%%%' WHERE id = ?", stored.getId());
        assertThat(snapshots.get(PID, dates(TIME), true).getFirst().getCode())
            .isEqualTo(StatusCode.INTERNAL_EXCEPTION_VALUE);
        assertThat(count("patient_snapshots")).isZero();
    }

    @Test
    void emptySnapshotStillRecordsSuccessfulRefresh() {
        Pair<ReturnCode, List<PatientSnapshotPB>> result = snapshots.get(PID, dates(TIME), false);
        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(result.getSecond()).allSatisfy(snapshot -> {
            assertThat(snapshot.getScoreGroupsList()).isEmpty();
            assertThat(snapshot.getScoreGroupSaIso8601()).isNotEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void updateReplansWhenEffectiveTimeChangesAfterPreRead(int days) {
        PatientScore original = seed(TIME, CODE);
        LocalDateTime moved = TIME.plusDays(days).plusSeconds(20);
        LocalDateTime target = TIME.plusHours(2);
        AtomicInteger reads = new AtomicInteger();
        JdbcTemplate replanning = mock(JdbcTemplate.class, delegatesTo(jdbc));
        doAnswer(invocation -> {
            List<PatientScore> result = jdbc.query(invocation.<String>getArgument(0),
                invocation.<PreparedStatementSetter>getArgument(1),
                invocation.<RowMapper<PatientScore>>getArgument(2));
            if (reads.incrementAndGet() == 2) {
                jdbc.update("UPDATE patient_scores SET effective_time = ? WHERE id = ?",
                    moved, original.getId());
            }
            return result;
        }).when(replanning).query(anyString(), any(PreparedStatementSetter.class),
            any(RowMapper.class));
        service = createService(replanning);
        assertThat(write("UPDATE", request(target).toBuilder().setId(original.getId()).build()))
            .isEqualTo(StatusCode.OK_VALUE);
        assertThat(reads.get()).isGreaterThanOrEqualTo(5);
        assertThat(active(target).getId()).isEqualTo(original.getId());
        assertThat(linked(target).getParamValueStr()).isEqualTo("15分");
        assertMarked(target);
        assertMarked(moved);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADD", "UPSERT"})
    void concurrentWritesSerializeOnScoreKeyAndKeepOneActiveRecord(String mode) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(
                List.of(LockKeys.patientScore(PID, CODE, TIME)), () -> {
                    locked.countDown();
                    await(release);
                    return null;
                }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<Integer> first = pool.submit(() -> write(mode, request(TIME)));
            Future<Integer> second = pool.submit(() -> write(mode, request(TIME)));
            assertTrue(waitForBlocked(2));
            assertThat(count("patient_scores")).isZero();
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            List<Integer> results = List.of(first.get(15, TimeUnit.SECONDS),
                second.get(15, TimeUnit.SECONDS));
            if (mode.equals("ADD")) {
                assertThat(results).containsExactlyInAnyOrder(StatusCode.OK_VALUE,
                    StatusCode.PATIENT_SCORE_ALREADY_EXISTS_VALUE);
            } else {
                assertThat(results).containsExactly(StatusCode.OK_VALUE, StatusCode.OK_VALUE);
            }
            assertThat(count("patient_scores")).isEqualTo(1);
            assertThat(monitoring.findByPidAndIsDeletedFalse(PID)).hasSize(1);
            assertThat(count("patient_monitoring_records")).isEqualTo(mode.equals("ADD") ? 1 : 2);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void updateUsesContentReadAfterLockToDecideWhetherScoreChanged() throws Exception {
        assertThat(write("ADD", request(TIME))).isEqualTo(StatusCode.OK_VALUE);
        long id = active(TIME).getId();
        clearInvocations(checkedPatients);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(List.of(
                LockKeys.patientScore(PID, CODE, TIME),
                LockKeys.patientMonitoringRecord(PID, PARAM, TIME)), () -> {
                    locked.countDown();
                    await(release);
                    jdbc.update("UPDATE patient_scores SET note = ? WHERE id = ?", "并发修改", id);
                    return null;
                }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<Integer> update = pool.submit(() -> write("UPDATE",
                request(TIME).toBuilder().setId(id).build()));
            assertTrue(waitForBlocked(1));
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            assertThat(update.get(15, TimeUnit.SECONDS)).isEqualTo(StatusCode.OK_VALUE);
            assertThat(active(TIME).getNote()).isEqualTo("列备注");
            assertThat(linked(TIME).getNote()).isEqualTo("列备注");
            verify(checkedPatients, times(1)).getPatientRecord(PID);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void writerWaitsForSnapshotLockBeforeAnySourceWrite() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(
                List.of(LockKeys.patientSnapshot(PID, dates(TIME).get(0))), () -> {
                    locked.countDown();
                    await(release);
                    return null;
                }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<Integer> write = pool.submit(() -> write("ADD", request(TIME)));
            assertTrue(waitForBlocked(1));
            assertThat(count("patient_scores")).isZero();
            assertThat(count("patient_monitoring_records")).isZero();
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            assertThat(write.get(15, TimeUnit.SECONDS)).isEqualTo(StatusCode.OK_VALUE);
            assertMarked(TIME);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @CsvSource({"ADD,false", "ADD,true", "UPDATE,false", "UPDATE,true",
        "UPSERT,false", "UPSERT,true"})
    void writesReplanSnapshotDatesAfterWaitingForLock(String mode, boolean departmentChanged)
        throws Exception {
        String targetDept = departmentChanged ? DEPT + "-moved" : DEPT;
        jdbc.update("""
            INSERT INTO balance_stats_shifts
                (dept_id, start_hour, mon_start_hour, effective_time, is_deleted, modified_at)
            VALUES (?, 8, 8, ?, FALSE, ?)
            """, DEPT, TIME.minusDays(3), TIME);
        LocalDate oldDate = TIME.toLocalDate();
        LocalDate newDate = oldDate.minusDays(1);
        assertThat(dates(TIME)).containsExactly(oldDate);
        ScoreGroupPB record = request(TIME);
        if (mode.equals("UPDATE")) {
            record = record.toBuilder().setId(seed(TIME.minusDays(1), CODE).getId()).build();
        }
        ScoreGroupPB pendingRecord = record;
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(
                List.of(LockKeys.patientSnapshot(PID, oldDate)), () -> {
                    locked.countDown();
                    await(release);
                    return null;
                }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<Integer> write = pool.submit(() -> write(mode, pendingRecord));
            assertTrue(waitForBlocked(1));
            assertThat(count("patient_scores")).isEqualTo(mode.equals("UPDATE") ? 1 : 0);
            assertThat(count("patient_monitoring_records")).isZero();
            // 旧日期计划已经生成，等待锁期间改变统计日配置或患者科室。
            if (departmentChanged) {
                jdbc.update("""
                    INSERT INTO balance_stats_shifts
                        (dept_id, start_hour, mon_start_hour, effective_time,
                            is_deleted, modified_at)
                    VALUES (?, 10, 10, ?, FALSE, ?)
                    """, targetDept, TIME.minusDays(3), TIME);
                jdbc.update("UPDATE patient_records SET dept_id = ? WHERE id = ?", targetDept, PID);
            } else {
                jdbc.update("""
                    UPDATE balance_stats_shifts SET start_hour = 10, mon_start_hour = 10
                    WHERE dept_id = ?
                    """, DEPT);
            }
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            assertThat(write.get(15, TimeUnit.SECONDS)).isEqualTo(StatusCode.OK_VALUE);
            List<LocalDate> expectedDates = mode.equals("UPDATE")
                ? List.of(newDate.minusDays(1), newDate) : List.of(newDate);
            assertThat(jdbc.queryForList(
                "SELECT statistics_date FROM patient_snapshots WHERE pid = ?",
                LocalDate.class, PID))
                .containsExactlyInAnyOrderElementsOf(expectedDates);
            assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM app_transaction_locks WHERE resource_key = ?
                """, Integer.class, LockKeys.patientSnapshot(PID, newDate))).isEqualTo(1);
            PatientScore stored = active(TIME);
            PatientMonitoringRecord linked = linked(TIME);
            assertThat(stored.getScoreStr()).isEqualTo("15分");
            assertThat(stored.getModifiedBy()).isEqualTo("123");
            assertThat(linked.getParamValueStr()).isEqualTo(stored.getScoreStr());
            assertThat(linked.getModifiedAt()).isEqualTo(stored.getModifiedAt());
            assertThat(linked.getDeptId()).isEqualTo(targetDept);
            verify(checkedPatients, times(1)).getPatientRecord(PID);
            verify(calculator, times(1)).validateScoreGroup(any(), any());
            verify(calculator, times(1)).printGroupScore(
                any(), eq(request(TIME).getGroupScore()), any());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-03T01:02:03.123456499", "2026-11-03T23:59:59.999999999"})
    void addAndUpsertUseSameMinuteForStorageDuplicateChecksAndLinkedRecords(String input) {
        LocalDateTime time = LocalDateTime.parse(input);
        LocalDateTime minute = time.truncatedTo(ChronoUnit.MINUTES);
        assertThat(write("ADD", request(time))).isEqualTo(StatusCode.OK_VALUE);
        long id = active(minute).getId();
        assertThat(active(minute).getEffectiveTime()).isEqualTo(minute);
        assertThat(linked(minute).getEffectiveTime()).isEqualTo(minute);
        assertThat(jdbc.queryForObject("""
            SELECT effective_time = date_trunc('minute', effective_time)
            FROM patient_scores WHERE id = ?
            """, Boolean.class, id)).isTrue();
        assertThat(write("ADD", request(minute.plusSeconds(40))))
            .isEqualTo(StatusCode.PATIENT_SCORE_ALREADY_EXISTS_VALUE);
        assertThat(write("UPSERT", request(minute.plusSeconds(55)).toBuilder()
            .setNote("同分钟更新").build())).isEqualTo(StatusCode.OK_VALUE);
        assertThat(active(minute).getId()).isEqualTo(id);
        assertThat(linked(minute).getNote()).isEqualTo("同分钟更新");
        assertThat(count("patient_scores")).isEqualTo(1);
        GetPatientScoreResp single = service.getPatientScore(ProtoUtils.protoToJson(
            GetPatientScoreReq.newBuilder()
            .setPid(PID).setMonitoringParamCode(PARAM).setEffectiveTimeIso8601(iso(time)).build()));
        assertThat(single.getFound()).isTrue();
        assertThat(single.getRecord().getId()).isEqualTo(id);
        assertThat(jdbc.queryForList("""
            SELECT resource_key FROM app_transaction_locks WHERE resource_key LIKE ?
            """, String.class, "patient_score:" + PID + ":%"))
            .containsExactly(LockKeys.patientScore(PID, CODE, minute));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADD", "UPDATE", "UPSERT"})
    void allModesDiscardSecondsAndFractionsBeforeWriting(String mode) {
        ScoreGroupPB record = request(TIME.plusSeconds(59).plusNanos(999999999));
        if (mode.equals("UPDATE")) {
            record = record.toBuilder().setId(seed(TIME.minusMinutes(1), CODE).getId()).build();
        }
        assertThat(write(mode, record)).isEqualTo(StatusCode.OK_VALUE);
        assertThat(active(TIME).getEffectiveTime()).isEqualTo(TIME);
        assertThat(linked(TIME).getEffectiveTime()).isEqualTo(TIME);
        assertThat(count("patient_scores")).isEqualTo(1);
    }

    @Test
    void updateWithExplicitTimeTruncatesLegacyTimeAndMigratesItsExactMonitoringRecord() {
        LocalDateTime oldTime = TIME.plusSeconds(35).plusNanos(123456000);
        PatientScore original = seed(oldTime, CODE);
        PatientMonitoringRecord previous = monitoring.save(PatientMonitoringRecord.builder()
            .pid(PID).deptId(DEPT).monitoringParamCode(PARAM).effectiveTime(oldTime)
            .paramValue("历史观察值").paramValueStr("历史15分").source("")
            .modifiedBy("123").modifiedAt(TIME).isDeleted(false).build());
        assertThat(write("UPDATE", request(oldTime).toBuilder().setId(original.getId()).build()))
            .isEqualTo(StatusCode.OK_VALUE);
        assertThat(active(TIME).getId()).isEqualTo(original.getId());
        assertThat(monitoring.findById(previous.getId()).orElseThrow().getIsDeleted()).isTrue();
        assertThat(linked(TIME).getParamValueStr()).isEqualTo("15分");
        assertThat(jdbc.queryForList("""
            SELECT resource_key FROM app_transaction_locks WHERE resource_key LIKE ?
            """, String.class, "%:" + PID + ":%"))
            .contains(LockKeys.patientScore(PID, CODE, TIME),
                LockKeys.patientMonitoringRecord(PID, PARAM, oldTime),
                LockKeys.patientMonitoringRecord(PID, PARAM, TIME))
            .doesNotContain(LockKeys.patientScore(PID, CODE, oldTime));
    }

    @Test
    void deleteKeepsLegacyTimeForLinkedMonitoringAndDoesNotPlanMinuteMigration() {
        LocalDateTime oldTime = TIME.plusSeconds(35);
        PatientScore original = seed(oldTime, CODE);
        PatientMonitoringRecord previous = monitoring.save(PatientMonitoringRecord.builder()
            .pid(PID).deptId(DEPT).monitoringParamCode(PARAM).effectiveTime(oldTime)
            .paramValue("历史观察值").source("").modifiedAt(TIME).isDeleted(false).build());
        assertThat(delete(original.getId(), true)).isEqualTo(StatusCode.OK_VALUE);
        assertThat(scores.findById(original.getId()).orElseThrow().getIsDeleted()).isTrue();
        assertThat(monitoring.findById(previous.getId()).orElseThrow().getIsDeleted()).isTrue();
        assertThat(jdbc.queryForList("""
            SELECT resource_key FROM app_transaction_locks WHERE resource_key LIKE ?
            """, String.class, "%:" + PID + ":%"))
            .contains(LockKeys.patientScore(PID, CODE, TIME),
                LockKeys.patientMonitoringRecord(PID, PARAM, oldTime))
            .doesNotContain(LockKeys.patientMonitoringRecord(PID, PARAM, TIME));
    }

    private int write(String mode, ScoreGroupPB record) {
        return switch (mode) {
            case "ADD" -> service.addPatientScore(ProtoUtils.protoToJson(AddPatientScoreReq
                .newBuilder().setRecord(record).build())).getRt().getCode();
            case "UPDATE" -> service.updatePatientScore(ProtoUtils.protoToJson(UpdatePatientScoreReq
                .newBuilder().setRecord(record).build())).getRt().getCode();
            default -> service.savePatientScore(ProtoUtils.protoToJson(SavePatientScoreReq
                .newBuilder().setRecord(record).build())).getRt().getCode();
        };
    }

    private int delete(long id, boolean byMonitoring) {
        return service.deletePatientScore(ProtoUtils.protoToJson(DeletePatientScoreReq.newBuilder()
            .setId(id).setByMonitoring(byMonitoring).build())).getRt().getCode();
    }

    private ScoreGroupPB request(LocalDateTime time) {
        return ScoreGroupPB.newBuilder().setPid(PID).setCode(CODE).setRecordedAtIso8601(iso(time))
            .setGroupScore(GenericValuePB.newBuilder().setInt32Val(15))
            .setGroupScoreText("客户端文本").setRecordedByAccountName("客户端姓名").setNote("列备注")
            .addMeasure(ScoreGroupMeasurePB.newBuilder().setName("护理措施").setApply(true)).build();
    }

    private PatientScore seed(LocalDateTime time, String code) {
        return scores.save(PatientScore.builder().pid(PID).scoreGroupCode(code).effectiveTime(time)
            .score(ProtoUtils.encodeScoreGroupPB(request(time).toBuilder().setCode(code).build()))
            .scoreStr("历史15分").modifiedAt(TIME).isDeleted(false).build());
    }

    private PatientScore active(LocalDateTime time) {
        return scores.findByPidAndScoreGroupCodeAndEffectiveTimeAndIsDeletedFalse(PID, CODE, time)
            .orElseThrow();
    }

    private PatientMonitoringRecord linked(LocalDateTime time) {
        return monitoring.findByPidAndMonitoringParamCodeAndEffectiveTimeAndIsDeletedFalse(
            PID, PARAM, time).orElseThrow();
    }

    private List<LocalDate> dates(LocalDateTime time) {
        return resolver.resolveStatisticDates(time, "Asia/Shanghai", DEPT);
    }

    private void assertMarked(LocalDateTime time) {
        for (LocalDate date : dates(time)) {
            String payload = jdbc.queryForObject("""
                SELECT payload FROM patient_snapshots WHERE pid = ? AND statistics_date = ?
                """, String.class, PID, date);
            assertThat(ProtoUtils.decodePatientSnapshot(payload).getScoreGroupLmaIso8601())
                .isNotEmpty();
        }
    }

    private String snapshotVersion() {
        return String.join(",", jdbc.queryForList("""
            SELECT xmin::text FROM patient_snapshots WHERE pid = ? ORDER BY statistics_date
            """, String.class, PID));
    }

    private String reportVersions() {
        return String.join(",", jdbc.queryForList("""
            SELECT xmin::text FROM patient_nursing_reports WHERE pid = ? ORDER BY id
            """, String.class, PID));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE pid = ?",
            Integer.class, PID);
    }

    private boolean waitForBlocked(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database()
                    AND wait_event_type = 'Lock' AND query LIKE '%app_transaction_locks%'
                """, Integer.class);
            if (count != null && count >= expected) return true;
            Thread.sleep(25);
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String iso(LocalDateTime time) { return time.atOffset(ZoneOffset.UTC).toString(); }

    private void clean() {
        jdbc.update("DELETE FROM balance_stats_shifts WHERE dept_id IN (?, ?)",
            DEPT, DEPT + "-moved");
        jdbc.execute("ALTER TABLE patient_snapshots DROP CONSTRAINT IF EXISTS "
            + "score_tests_snapshot_fail");
        for (String table : List.of("patient_scores", "patient_monitoring_records",
            "patient_snapshots", "patient_nursing_reports", "patient_records")) {
            jdbc.update("DELETE FROM " + table + " WHERE "
                + (table.equals("patient_records") ? "id" : "pid") + " = ?", PID);
        }
        jdbc.execute("DROP INDEX IF EXISTS score_tests_score_unique");
        jdbc.execute("DROP INDEX IF EXISTS score_tests_monitor_unique");
        jdbc.update("DELETE FROM app_transaction_locks WHERE resource_key LIKE ?",
            "%:" + PID + ":%");
    }

    private static final long PID = 9_620_041L;
    private static final String DEPT = "score-write-tests";
    private static final String CODE = "gcs";
    private static final String PARAM = "score_test_gcs";
    private static final LocalDateTime TIME = LocalDateTime.parse("2026-11-03T01:02:00");
    private UserService users;
    private ScoreCalculator calculator;
    private PatientService checkedPatients;
    private PatientNursingReportInvalidationService invalidation;
    private ScoreService service;
    @Autowired private ConfigProtoService protoService;
    @Autowired private PatientService patients;
    @Autowired private PatientScoreRepository scores;
    @Autowired private PatientMonitoringRecordRepository monitoring;
    @Autowired private PatientNursingReportInvalidationService realInvalidation;
    @Autowired private ScoreReader reader;
    @Autowired private SnapshotService snapshots;
    @Autowired private StatisticsDayWindowResolver resolver;
    @Autowired private LockExecutor executor;
    @Autowired private JdbcTemplate jdbc;
}
