package com.jingyicare.jingyi_icis_engine.service.nursingrecords;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.NursingRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSnapshot.PatientSnapshotPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientMonitoringRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.NursingRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.lis.LisService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.medications.MedReportUtils;
import com.jingyicare.jingyi_icis_engine.service.monitorings.BalanceCalculator;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.service.monitorings.PatientMonitoringService;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.service.reports.common.*;
import com.jingyicare.jingyi_icis_engine.service.settings.SettingService;
import com.jingyicare.jingyi_icis_engine.service.shifts.ConfigShiftUtils;
import com.jingyicare.jingyi_icis_engine.service.shifts.StatisticsDayWindowResolver;
import com.jingyicare.jingyi_icis_engine.service.snapshots.SnapshotService;
import com.jingyicare.jingyi_icis_engine.service.tubes.PatientTubeImpl;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

class NursingRecordServiceLockTests extends TestsBase {
    @BeforeEach
    void prepare() {
        cleanRecords();
        jdbcTemplate.update("""
            INSERT INTO patient_records (id, his_mrn, dept_id)
            VALUES (?, ?, ?), (?, ?, ?)
            """, PID, "snapshot-test-1", DEPT_ID, PID + 1, "snapshot-test-2", DEPT_ID);
        jdbcTemplate.execute("""
            CREATE UNIQUE INDEX idx_nursing_record_lock_tests_unique
            ON nursing_records (patient_id, effective_time, patient_critical_lis_handling_id)
            WHERE is_deleted = FALSE AND patient_id = 9300031
            """);
        invalidation = mock(PatientNursingReportInvalidationService.class);
        service = createService(jdbcTemplate);
    }

    private NursingRecordService createService(JdbcTemplate readerJdbc) {
        UserService users = mock(UserService.class);
        when(users.getAccountWithAutoId()).thenReturn(new Pair<>(ACCOUNT, ACCOUNT_NAME));
        PatientService patients = mock(PatientService.class);
        PatientRecord patient = new PatientRecord();
        patient.setId(PID);
        patient.setDeptId(DEPT_ID);
        when(patients.getPatientRecord(PID)).thenReturn(patient);
        patientRepo = mock(PatientRecordRepository.class);
        when(patientRepo.findById(PID)).thenReturn(Optional.of(patient));
        settings = mock(SettingService.class);
        when(settings.getNursingRecordSettings(DEPT_ID))
            .thenReturn(NursingRecordSettingsPB.getDefaultInstance());
        return new NursingRecordService(protoService, users, patients,
            mock(MedReportUtils.class), mock(MonitoringConfig.class),
            mock(PatientMonitoringService.class), mock(BalanceCalculator.class),
            mock(ConfigShiftUtils.class), mock(PatientTubeImpl.class), mock(LisService.class),
            invalidation, settings, records, patientRepo,
            mock(PatientMonitoringRecordRepository.class), executor, readerJdbc,
            recordReader, snapshots);
    }

    @AfterEach
    void cleanUp() {
        cleanRecords();
    }

    @Test
    void getReturnsDatabaseMetadataWithConfiguredTimeZoneAndNullDefaults() {
        LocalDateTime effectiveTime = TIME.plusSeconds(59).plusNanos(123456000);
        NursingRecord full = records.save(record(effectiveTime).source("template_id=7")
            .patientCriticalLisHandlingId(7).reviewedBy("reviewer")
            .reviewedByAccountName("审核人").reviewedAt(effectiveTime.plusMinutes(1))
            .modifiedBy("modifier").modifiedByAccountName("修改人")
            .modifiedAt(effectiveTime.plusMinutes(2)).createdBy("creator")
            .createdByAccountName("创建人").createdAt(effectiveTime.minusMinutes(1)).build());
        NursingRecord nullable = records.save(NursingRecord.builder().patientId(PID)
            .content("旧记录").effectiveTime(TIME.plusHours(1)).isDeleted(false)
            .modifiedBy("legacy-modifier").build());
        var request = GetNursingRecordReq.newBuilder().setPid(PID)
            .setQueryStartIso8601(iso8601(TIME))
            .setQueryEndIso8601(iso8601(TIME.plusHours(2))).build();

        GetNursingRecordResp response = service.getNursingRecord(ProtoUtils.protoToJson(request));

        String zoneId = protoService.getZoneId();
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(response.getRecordList()).containsExactly(
            NursingRecordPB.newBuilder().setId(full.getId()).setPatientId(PID)
                .setContent(full.getContent())
                .setEffectiveTimeIso8601(TimeUtils.toIso8601String(TIME, zoneId))
                .setSource("template_id=7").setPatientCriticalLisHandlingId(7)
                .setReviewedBy("reviewer").setReviewedByAccountName("审核人")
                .setReviewedAtIso8601(TimeUtils.toIso8601String(TIME.plusMinutes(1), zoneId))
                .setModifiedBy("modifier").setModifiedByAccountName("修改人")
                .setModifiedAtIso8601(TimeUtils.toIso8601String(TIME.plusMinutes(2), zoneId))
                .setCreatedBy("creator").setCreatedByAccountName("创建人")
                .setCreatedAtIso8601(TimeUtils.toIso8601String(TIME.minusMinutes(1), zoneId))
                .build(),
            NursingRecordPB.newBuilder().setId(nullable.getId()).setPatientId(PID)
                .setContent("旧记录")
                .setEffectiveTimeIso8601(TimeUtils.toIso8601String(TIME.plusHours(1), zoneId))
                .setModifiedBy("legacy-modifier").setModifiedByAccountName("legacy-modifier")
                .build());
    }

    @Test
    void addIgnoresClientMetadataAndUsesServerAuditFields() {
        LocalDateTime now = TIME.plusHours(2).plusSeconds(59).plusNanos(123456789);
        var request = AddNursingRecordReq.newBuilder().setPid(PID)
            .setRecord(clientMetadataRecord().setId(Long.MAX_VALUE).setContent("新增记录")
                .setEffectiveTimeIso8601(iso8601(TIME.plusSeconds(59)))).build();
        AddNursingRecordResp response;
        try (var clock = mockStatic(TimeUtils.class, CALLS_REAL_METHODS)) {
            clock.when(TimeUtils::getNowUtc).thenReturn(now);
            response = service.addNursingRecord(ProtoUtils.protoToJson(request));
        }

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(response.getId()).isNotEqualTo(Long.MAX_VALUE);
        assertThat(activeRecord(TIME, null)).isEqualTo(NursingRecord.builder()
            .id(response.getId()).patientId(PID).content("新增记录").effectiveTime(TIME)
            .isDeleted(false).createdBy(ACCOUNT).createdByAccountName(ACCOUNT_NAME)
            .createdAt(TIME.plusHours(2)).modifiedBy(ACCOUNT)
            .modifiedByAccountName(ACCOUNT_NAME).modifiedAt(TIME.plusHours(2)).build());
    }

    @Test
    void concurrentAddsWithinSameMinuteOnlyInsertOneOrdinaryRecord() throws Exception {
        String key = LockKeys.nursingRecord(PID, TIME);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(List.of(key), () -> {
                locked.countDown();
                try {
                    assertTrue(release.await(30, TimeUnit.SECONDS),
                        "等待释放护理记录锁超时");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return null;
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS), "未取得护理记录锁");
            Future<AddNursingRecordResp> first = pool.submit(
                () -> add(TIME.plusSeconds(1).plusNanos(123456789), "第一条记录"));
            Future<AddNursingRecordResp> second = pool.submit(
                () -> add(TIME.plusSeconds(59).plusNanos(999999999), "第二条记录"));
            assertTrue(waitForBlockedRequests(2),
                "两个新增请求应同时等待护理记录锁");
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);

            assertThat(List.of(first.get(15, TimeUnit.SECONDS).getRt().getCode(),
                second.get(15, TimeUnit.SECONDS).getRt().getCode()))
                .containsExactlyInAnyOrder(StatusCode.OK_VALUE,
                    StatusCode.NURSING_RECORD_ALREADY_EXISTS_VALUE);
            assertThat(recordCount()).isEqualTo(1);
            NursingRecord added = activeRecord(TIME, null);
            assertThat(added.getContent()).isIn("第一条记录", "第二条记录");
            assertMinutePrecision(added.getCreatedAt());
            assertMinutePrecision(added.getModifiedAt());
            assertThat(lockCount(key)).isEqualTo(1);
            verify(invalidation, times(1)).updateLatestDataTime(
                eq(PID), eq(DEPT_ID), eq(TIME), eq(TIME), any());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void updatingToOccupiedTimeKeepsOriginalRecord() {
        NursingRecord original = saveRecord(TIME);
        NursingRecord occupied = saveRecord(TIME.plusHours(1));

        GenericResp response = update(original.getId(),
            occupied.getEffectiveTime().plusSeconds(59).plusNanos(999999999));

        assertThat(response.getRt().getCode())
            .isEqualTo(StatusCode.NURSING_RECORD_ALREADY_EXISTS_VALUE);
        assertThat(records.findById(original.getId()).orElseThrow()).isEqualTo(original);
        assertThat(records.findById(occupied.getId()).orElseThrow()).isEqualTo(occupied);
        assertThat(recordCount()).isEqualTo(2);
        verifyNoInteractions(invalidation);
    }

    @ParameterizedTest
    @CsvSource({"0, false", "1, false", "24, false", "1, true"})
    void replacementUsesCreatorSettingAndClearsReview(long hoursToMove, boolean updateCreator) {
        when(settings.getNursingRecordSettings(DEPT_ID)).thenReturn(
            NursingRecordSettingsPB.newBuilder().setEnableUpdatingCreatedBy(updateCreator).build());
        NursingRecord original = records.save(record(TIME).source("template_id=7&params=记录")
            .patientCriticalLisHandlingId(7).reviewedBy("previous-reviewer")
            .reviewedByAccountName("原审核人").reviewedAt(TIME.plusMinutes(10))
            .createdAt(TIME.minusDays(1).plusSeconds(59).plusNanos(123456000)).build());
        LocalDateTime targetTime = TIME.plusHours(hoursToMove);
        LocalDateTime now = TIME.plusDays(2);

        try (var clock = mockStatic(TimeUtils.class, CALLS_REAL_METHODS)) {
            clock.when(TimeUtils::getNowUtc).thenReturn(now.plusSeconds(59).plusNanos(123456789));
            assertOk(update(original.getId(), targetTime.plusSeconds(59).plusNanos(999999999)));
        }

        NursingRecord replacement = activeRecord(targetTime, 7);
        assertThat(replacement.getId()).isNotEqualTo(original.getId());
        assertThat(replacement.getContent()).isEqualTo("更新后的内容");
        assertThat(replacement.getSource()).isEqualTo(original.getSource());
        assertThat(replacement.getPatientCriticalLisHandlingId()).isEqualTo(7);
        assertThat(replacement.getCreatedBy())
            .isEqualTo(updateCreator ? ACCOUNT : original.getCreatedBy());
        assertThat(replacement.getCreatedByAccountName())
            .isEqualTo(updateCreator ? ACCOUNT_NAME : original.getCreatedByAccountName());
        assertThat(replacement.getCreatedAt()).isEqualTo(updateCreator ? now : TIME.minusDays(1));
        verify(settings).getNursingRecordSettings(DEPT_ID);
        assertThat(replacement.getModifiedBy()).isEqualTo(ACCOUNT);
        assertThat(replacement.getModifiedByAccountName()).isEqualTo(ACCOUNT_NAME);
        assertMinutePrecision(replacement.getModifiedAt());
        assertThat(replacement.getReviewedBy()).isNull();
        assertThat(replacement.getReviewedByAccountName()).isNull();
        assertThat(replacement.getReviewedAt()).isNull();
        NursingRecord deleted = records.findById(original.getId()).orElseThrow();
        assertThat(deleted.getIsDeleted()).isTrue();
        assertMinutePrecision(deleted.getDeletedAt());
        assertThat(recordCount()).isEqualTo(2);
        assertThat(lockCount(LockKeys.nursingRecord(PID, TIME))).isEqualTo(1);
        assertThat(lockCount(LockKeys.nursingRecord(PID, targetTime))).isEqualTo(1);
        verify(invalidation).updateLatestDataTime(
            eq(PID), eq(DEPT_ID), eq(TIME), eq(TIME), any());
        if (hoursToMove != 0) {
            verify(invalidation).updateLatestDataTime(
                eq(PID), eq(DEPT_ID), eq(targetTime), eq(targetTime), any());
        }
    }

    @Test
    void missingRecordOrPatientDisablesCreatorUpdates() {
        assertThat(service.getEnableUpdatingCreatedBy(Long.MAX_VALUE)).isFalse();

        NursingRecord original = saveRecord(TIME);
        when(patientRepo.findById(PID)).thenReturn(Optional.empty());
        assertThat(service.getEnableUpdatingCreatedBy(original.getId())).isFalse();
        verifyNoInteractions(settings);
    }

    @Test
    void unchangedContentWithoutTimeDoesNotReplaceRecordOrInvalidateReport() {
        NursingRecord original = saveRecord(TIME);
        var request = UpdateNursingRecordReq.newBuilder()
            .setRecord(NursingRecordPB.newBuilder().setId(original.getId())
                .setContent(original.getContent())).build();

        assertOk(service.updateNursingRecord(ProtoUtils.protoToJson(request)));

        assertThat(records.findById(original.getId()).orElseThrow()).isEqualTo(original);
        assertThat(recordCount()).isEqualTo(1);
        assertThat(lockCount(LockKeys.nursingRecord(PID, TIME))).isEqualTo(1);
        verifyNoInteractions(invalidation);
    }

    @Test
    void updateWithoutTimeNormalizesExistingEffectiveTime() {
        NursingRecord original = saveRecord(TIME.plusSeconds(59).plusNanos(123456000));
        var request = UpdateNursingRecordReq.newBuilder()
            .setRecord(NursingRecordPB.newBuilder().setId(original.getId())
                .setContent(original.getContent())).build();

        assertOk(service.updateNursingRecord(ProtoUtils.protoToJson(request)));

        NursingRecord replacement = activeRecord(TIME, null);
        assertThat(replacement.getId()).isNotEqualTo(original.getId());
        assertThat(replacement.getContent()).isEqualTo(original.getContent());
        assertThat(records.findById(original.getId()).orElseThrow().getIsDeleted()).isTrue();
        assertThat(recordCount()).isEqualTo(2);
    }

    @Test
    void failedInvalidationRollsBackReplacementAndNewLocks() {
        NursingRecord original = records.save(record(TIME).patientCriticalLisHandlingId(7).build());
        failInvalidation();

        assertThrows(IllegalStateException.class,
            () -> update(original.getId(), TIME.plusHours(1)));

        assertThat(records.findById(original.getId()).orElseThrow()).isEqualTo(original);
        assertThat(recordCount()).isEqualTo(1);
        assertThat(lockCount(LockKeys.nursingRecord(PID, TIME))).isZero();
        assertThat(lockCount(LockKeys.nursingRecord(PID, TIME.plusHours(1)))).isZero();
        assertThat(snapshotCount()).isZero();
    }

    @Test
    void failedInvalidationRollsBackDeletionAndNewLock() {
        NursingRecord original = saveRecord(TIME);
        failInvalidation();

        assertThrows(IllegalStateException.class, () -> delete(original.getId()));

        assertThat(records.findById(original.getId()).orElseThrow()).isEqualTo(original);
        assertThat(recordCount()).isEqualTo(1);
        assertThat(lockCount(LockKeys.nursingRecord(PID, TIME))).isZero();
        assertThat(snapshotCount()).isZero();
    }

    @Test
    void missingReviewIdLeavesEntireBatchUnmodified() {
        NursingRecord first = saveRecord(TIME);
        NursingRecord second = saveRecord(TIME.plusHours(1));

        GenericResp response = review(TIME.plusHours(2), first.getId(), Long.MAX_VALUE,
            second.getId());

        assertThat(response.getRt().getCode())
            .isEqualTo(StatusCode.NURSING_RECORD_NOT_EXISTS_VALUE);
        assertThat(records.findById(first.getId()).orElseThrow()).isEqualTo(first);
        assertThat(records.findById(second.getId()).orElseThrow()).isEqualTo(second);
    }

    @Test
    void reviewDeduplicatesIdsAndLocksEachRecordMinute() {
        NursingRecord first = saveRecord(TIME);
        NursingRecord second = saveRecord(TIME.plusHours(1).plusSeconds(59));
        LocalDateTime reviewTime = TIME.plusHours(1);

        assertOk(review(reviewTime.plusSeconds(1).plusNanos(123456789),
            first.getId(), second.getId(), first.getId()));

        for (NursingRecord original : List.of(first, second)) {
            NursingRecord reviewed = records.findById(original.getId()).orElseThrow();
            assertThat(reviewed.getReviewedBy()).isEqualTo(ACCOUNT);
            assertThat(reviewed.getReviewedByAccountName()).isEqualTo(ACCOUNT_NAME);
            assertThat(reviewed.getReviewedAt()).isEqualTo(reviewTime);
        }
        assertThat(lockCount(LockKeys.nursingRecord(PID, TIME))).isEqualTo(1);
        assertThat(lockCount(LockKeys.nursingRecord(PID, TIME.plusHours(1)))).isEqualTo(1);
        assertThat(lockCount(LockKeys.nursingRecord(PID, second.getEffectiveTime()))).isZero();
        assertThat(recordCount()).isEqualTo(2);
    }

    @Test
    void reviewReplansWhenRecordTimeChangesAfterPreRead() {
        NursingRecord original = saveRecord(TIME);
        LocalDateTime changedTime = TIME.plusHours(1);
        AtomicInteger reads = new AtomicInteger();
        JdbcTemplate replanningJdbc = mock(JdbcTemplate.class, delegatesTo(jdbcTemplate));
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            PreparedStatementSetter setter = invocation.getArgument(1);
            RowMapper<NursingRecord> mapper = invocation.getArgument(2);
            List<NursingRecord> result = jdbcTemplate.query(sql, setter, mapper);
            if (reads.incrementAndGet() == 2) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                jdbcTemplate.update("UPDATE nursing_records SET effective_time = ? WHERE id = ?",
                    changedTime, original.getId());
            }
            return result;
        }).when(replanningJdbc).query(anyString(), any(PreparedStatementSetter.class),
            any(RowMapper.class));
        service = createService(replanningJdbc);

        assertOk(review(TIME.plusHours(2), original.getId()));

        NursingRecord reviewed = records.findById(original.getId()).orElseThrow();
        assertThat(reads.get()).isGreaterThanOrEqualTo(3);
        assertThat(reviewed.getEffectiveTime()).isEqualTo(changedTime);
        assertThat(reviewed.getReviewedBy()).isEqualTo(ACCOUNT);
        assertThat(reviewed.getReviewedAt()).isEqualTo(TIME.plusHours(2));
        assertThat(lockCount(LockKeys.nursingRecord(PID, TIME))).isZero();
        assertThat(lockCount(LockKeys.nursingRecord(PID, changedTime))).isEqualTo(1);
    }

    @Test
    void reviewBeforeAnyRecordTimeLeavesEntireBatchUnmodified() {
        NursingRecord first = saveRecord(TIME);
        NursingRecord second = saveRecord(TIME.plusHours(1));

        GenericResp response = review(TIME.plusMinutes(30), first.getId(), second.getId());

        assertThat(response.getRt().getCode())
            .isEqualTo(StatusCode.NURSING_RECORD_REVIEW_TIME_BEFORE_RECORD_TIME_VALUE);
        assertThat(records.findById(first.getId()).orElseThrow()).isEqualTo(first);
        assertThat(records.findById(second.getId()).orElseThrow()).isEqualTo(second);
    }

    @Test
    void repeatedDeletionSucceedsWithoutInvalidatingAgain() {
        NursingRecord original = saveRecord(TIME);
        LocalDateTime nowUtc = TIME.plusHours(2).plusSeconds(59).plusNanos(123456789);

        try (var clock = mockStatic(TimeUtils.class, CALLS_REAL_METHODS)) {
            clock.when(TimeUtils::getNowUtc).thenReturn(nowUtc);
            assertOk(delete(original.getId()));
            assertOk(delete(original.getId()));
        }

        NursingRecord deleted = records.findById(original.getId()).orElseThrow();
        assertThat(deleted.getIsDeleted()).isTrue();
        assertThat(deleted.getDeletedAt()).isEqualTo(TIME.plusHours(2));
        assertThat(deleted.getModifiedAt()).isEqualTo(deleted.getDeletedAt());
        assertThat(recordCount()).isEqualTo(1);
        verify(invalidation, times(1)).updateLatestDataTime(
            eq(PID), eq(DEPT_ID), eq(TIME), eq(TIME), eq(nowUtc));
    }

    @Test
    void getUsesHalfOpenRangeAndRejectsInvalidArguments() {
        NursingRecord included = saveRecord(TIME);
        saveRecord(TIME.plusHours(1));
        var request = GetNursingRecordReq.newBuilder().setPid(PID)
            .setQueryStartIso8601(iso8601(TIME))
            .setQueryEndIso8601(iso8601(TIME.plusHours(1)));

        assertThat(service.getNursingRecord(ProtoUtils.protoToJson(request.build()))
            .getRecordList()).extracting(NursingRecordPB::getId).containsExactly(included.getId());
        assertThat(service.getNursingRecord(ProtoUtils.protoToJson(request.clone().setPid(0)
            .build())).getRt().getCode()).isEqualTo(StatusCode.INVALID_PARAM_VALUE_VALUE);
        assertThat(service.getNursingRecord(ProtoUtils.protoToJson(request.clone()
            .setQueryEndIso8601(iso8601(TIME)).build())).getRt().getCode())
            .isEqualTo(StatusCode.INVALID_TIME_RANGE_VALUE);
        assertThat(service.getNursingRecord(ProtoUtils.protoToJson(request.clone()
            .setQueryStartIso8601("invalid").build())).getRt().getCode())
            .isEqualTo(StatusCode.INVALID_TIME_RANGE_VALUE);
    }

    @Test
    void addMarksSnapshotWithActualServerTimeAndLeavesItUnrefreshed() {
        LocalDateTime now = TIME.plusHours(2).plusSeconds(37);
        try (var clock = mockStatic(TimeUtils.class, CALLS_REAL_METHODS)) {
            clock.when(TimeUtils::getNowUtc).thenReturn(now);
            assertThat(add(TIME, "新增护理记录").getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        }

        assertSnapshotModified(PID, TIME, now);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM patient_snapshots WHERE pid = ? AND snapshot_at IS NOT NULL
            """, Integer.class, PID)).isZero();
    }

    @Test
    void addWaitsForSnapshotLockBeforeWritingRecordAndModificationMarker() throws Exception {
        LocalDate date = statDates(TIME).get(0);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(
                List.of(LockKeys.patientSnapshot(PID, date)), () -> {
                    locked.countDown();
                    try {
                        assertTrue(release.await(30, TimeUnit.SECONDS), "等待释放快照锁超时");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return null;
                }));
            assertTrue(locked.await(10, TimeUnit.SECONDS), "未取得快照锁");
            Future<AddNursingRecordResp> addition = pool.submit(() -> add(TIME, "新增护理记录"));
            assertTrue(waitForBlockedRequests(1), "护理新增请求应等待快照锁");
            assertThat(recordCount()).isZero();
            assertThat(snapshotCount()).isZero();
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            assertThat(addition.get(15, TimeUnit.SECONDS).getRt().getCode())
                .isEqualTo(StatusCode.OK_VALUE);
            assertThat(recordCount()).isEqualTo(1);
            assertThat(snapshotCount()).isPositive();
            var refreshed = snapshots.get(PID, List.of(date), false);
            assertThat(refreshed.getFirst().getCode()).isEqualTo(StatusCode.OK_VALUE);
            assertThat(refreshed.getSecond().get(0).getNursingRecordsList())
                .extracting(NursingRecordPB::getContent).containsExactly("新增护理记录");
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void movingRecordInvalidatesBothDatesAndRefreshRemovesOldRecord() {
        NursingRecord original = saveRecord(TIME);
        LocalDateTime targetTime = TIME.plusDays(1);
        List<LocalDate> dates = Stream.concat(
            statDates(TIME).stream(), statDates(targetTime).stream()).distinct().sorted().toList();
        LocalDateTime initialSnapshotTime = TIME.plusDays(2);
        try (var clock = mockStatic(TimeUtils.class, CALLS_REAL_METHODS)) {
            clock.when(TimeUtils::getNowUtc).thenReturn(initialSnapshotTime);
            assertThat(snapshots.get(PID, dates, false).getFirst().getCode())
                .isEqualTo(StatusCode.OK_VALUE);
        }
        LocalDateTime modifiedAt = initialSnapshotTime.plusSeconds(37);
        try (var clock = mockStatic(TimeUtils.class, CALLS_REAL_METHODS)) {
            clock.when(TimeUtils::getNowUtc).thenReturn(modifiedAt);
            assertOk(update(original.getId(), targetTime));
        }
        assertSnapshotModified(PID, TIME, modifiedAt);
        assertSnapshotModified(PID, targetTime, modifiedAt);

        var result = snapshots.get(PID, dates, false);
        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(result.getSecond()).flatExtracting(PatientSnapshotPB::getNursingRecordsList)
            .extracting(NursingRecordPB::getContent).containsExactly("更新后的内容");
        assertThat(jdbcTemplate.queryForList("""
            SELECT snapshot_at FROM patient_snapshots WHERE pid = ? ORDER BY statistics_date
            """, LocalDateTime.class, PID)).doesNotContain(initialSnapshotTime);
    }

    @Test
    void unchangedUpdateAndRepeatedReviewOrDeleteDoNotAdvanceSnapshotModificationTime() {
        NursingRecord original = saveRecord(TIME);
        LocalDateTime firstChange = TIME.plusDays(1).plusSeconds(17);
        LocalDateTime laterTime = firstChange.plusSeconds(20);
        LocalDateTime reviewTime = TIME.plusHours(1);
        try (var clock = mockStatic(TimeUtils.class, CALLS_REAL_METHODS)) {
            clock.when(TimeUtils::getNowUtc).thenReturn(firstChange);
            assertOk(review(reviewTime, original.getId()));
            clock.when(TimeUtils::getNowUtc).thenReturn(laterTime);
            assertOk(review(reviewTime, original.getId()));
            assertOk(service.updateNursingRecord(ProtoUtils.protoToJson(
                UpdateNursingRecordReq.newBuilder().setRecord(NursingRecordPB.newBuilder()
                    .setId(original.getId()).setContent(original.getContent())).build())));
        }
        assertSnapshotModified(PID, TIME, firstChange);

        try (var clock = mockStatic(TimeUtils.class, CALLS_REAL_METHODS)) {
            clock.when(TimeUtils::getNowUtc).thenReturn(laterTime);
            assertOk(delete(original.getId()));
            clock.when(TimeUtils::getNowUtc).thenReturn(laterTime.plusSeconds(20));
            assertOk(delete(original.getId()));
        }
        assertSnapshotModified(PID, TIME, laterTime);
    }

    @Test
    void reviewMarksOnlyChangedRecordsAcrossPatientsUsingServerTime() {
        LocalDateTime reviewTime = TIME.plusHours(2);
        NursingRecord unchanged = records.save(record(TIME).reviewedBy(ACCOUNT)
            .reviewedByAccountName(ACCOUNT_NAME).reviewedAt(reviewTime).build());
        NursingRecord changed = records.save(record(TIME).patientId(PID + 1).build());
        LocalDateTime modifiedAt = TIME.plusDays(2).plusSeconds(41);
        try (var clock = mockStatic(TimeUtils.class, CALLS_REAL_METHODS)) {
            clock.when(TimeUtils::getNowUtc).thenReturn(modifiedAt);
            assertOk(review(reviewTime, unchanged.getId(), changed.getId()));
        }

        assertThat(snapshotCount()).isZero();
        assertSnapshotModified(PID + 1, TIME, modifiedAt);
        assertThat(records.findById(changed.getId()).orElseThrow().getReviewedAt())
            .isEqualTo(reviewTime);
    }

    @Test
    void reviewReplansWhenRecordMovesWithinSameMinute() {
        NursingRecord original = saveRecord(TIME);
        LocalDateTime changedTime = TIME.plusSeconds(20);
        AtomicInteger reads = new AtomicInteger();
        JdbcTemplate replanningJdbc = mock(JdbcTemplate.class, delegatesTo(jdbcTemplate));
        doAnswer(invocation -> {
            List<NursingRecord> result = jdbcTemplate.query(invocation.<String>getArgument(0),
                invocation.<PreparedStatementSetter>getArgument(1),
                invocation.<RowMapper<NursingRecord>>getArgument(2));
            if (reads.incrementAndGet() == 2) {
                jdbcTemplate.update("UPDATE nursing_records SET effective_time = ? WHERE id = ?",
                    changedTime, original.getId());
            }
            return result;
        }).when(replanningJdbc).query(anyString(), any(PreparedStatementSetter.class),
            any(RowMapper.class));
        service = createService(replanningJdbc);

        assertOk(review(TIME.plusHours(2), original.getId()));

        assertThat(reads.get()).isGreaterThanOrEqualTo(5);
        assertThat(records.findById(original.getId()).orElseThrow().getEffectiveTime())
            .isEqualTo(changedTime);
    }

    @Test
    void reviewReplansWhenPatientDepartmentChangesAfterPreRead() throws Exception {
        NursingRecord original = saveRecord(TIME);
        AtomicInteger reads = new AtomicInteger();
        JdbcTemplate replanningJdbc = mock(JdbcTemplate.class, delegatesTo(jdbcTemplate));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            doAnswer(invocation -> {
                List<NursingRecord> result = jdbcTemplate.query(invocation.<String>getArgument(0),
                    invocation.<PreparedStatementSetter>getArgument(1),
                    invocation.<RowMapper<NursingRecord>>getArgument(2));
                if (reads.incrementAndGet() == 3) {
                    // 在独立连接提交科室变更，保证锁计划回滚后仍能看到新科室。
                    pool.submit(() -> jdbcTemplate.update(
                        "UPDATE patient_records SET dept_id = ? WHERE id = ?",
                        DEPT_ID + "-moved", PID)).get(10, TimeUnit.SECONDS);
                }
                return result;
            }).when(replanningJdbc).query(anyString(), any(PreparedStatementSetter.class),
                any(RowMapper.class));
            service = createService(replanningJdbc);

            assertOk(review(TIME.plusHours(2), original.getId()));

            assertThat(reads.get()).isGreaterThanOrEqualTo(5);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT dept_id FROM patient_records WHERE id = ?", String.class, PID))
                .isEqualTo(DEPT_ID + "-moved");
            assertThat(records.findById(original.getId()).orElseThrow().getReviewedBy())
                .isEqualTo(ACCOUNT);
            assertThat(snapshotCount()).isPositive();
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private List<LocalDate> statDates(LocalDateTime effectiveTime) {
        return windowResolver.resolveStatisticDates(
            effectiveTime, protoService.getZoneId(), DEPT_ID);
    }

    private void assertSnapshotModified(long pid, LocalDateTime effectiveTime,
        LocalDateTime expectedTime) {
        for (LocalDate date : statDates(effectiveTime)) {
            String payload = jdbcTemplate.queryForObject("""
                SELECT payload FROM patient_snapshots WHERE pid = ? AND statistics_date = ?
                """, String.class, pid, date);
            PatientSnapshotPB snapshot = ProtoUtils.decodePatientSnapshot(payload);
            assertThat(snapshot.getNursingRecordLmaIso8601())
                .isEqualTo(TimeUtils.toIso8601StringWithSeconds(expectedTime, "UTC"));
            assertThat(jdbcTemplate.queryForObject("""
                SELECT last_modified_at FROM patient_snapshots
                WHERE pid = ? AND statistics_date = ?
                """, LocalDateTime.class, pid, date)).isEqualTo(expectedTime);
            assertThat(lockCount(LockKeys.patientSnapshot(pid, date))).isEqualTo(1);
        }
    }

    private int snapshotCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM patient_snapshots WHERE pid = ?", Integer.class, PID);
    }

    private AddNursingRecordResp add(LocalDateTime time, String content) {
        return service.addNursingRecord(ProtoUtils.protoToJson(AddNursingRecordReq.newBuilder()
            .setPid(PID).setRecord(NursingRecordPB.newBuilder().setContent(content)
                .setEffectiveTimeIso8601(iso8601(time))).build()));
    }

    private GenericResp update(long id, LocalDateTime time) {
        var request = UpdateNursingRecordReq.newBuilder()
            .setRecord(clientMetadataRecord().setId(id)
                .setContent("更新后的内容")
                .setEffectiveTimeIso8601(iso8601(time))).build();
        return service.updateNursingRecord(ProtoUtils.protoToJson(request));
    }

    private NursingRecordPB.Builder clientMetadataRecord() {
        return NursingRecordPB.newBuilder().setPatientId(PID + 1)
            .setSource("client-source").setPatientCriticalLisHandlingId(999)
            .setReviewedBy("client-reviewer").setReviewedByAccountName("客户端审核人")
            .setReviewedAtIso8601(iso8601(TIME.minusYears(1)))
            .setModifiedBy("client-modifier").setModifiedByAccountName("客户端修改人")
            .setModifiedAtIso8601(iso8601(TIME.minusYears(1)))
            .setCreatedBy("client-creator").setCreatedByAccountName("客户端创建人")
            .setCreatedAtIso8601(iso8601(TIME.minusYears(1)));
    }

    private GenericResp delete(long id) {
        var request = DeleteNursingRecordReq.newBuilder().setId(id).build();
        return service.deleteNursingRecord(ProtoUtils.protoToJson(request));
    }

    private GenericResp review(LocalDateTime reviewTime, Long... ids) {
        return service.reviewNursingRecords(ProtoUtils.protoToJson(
            ReviewNursingRecordsReq.newBuilder().addAllId(List.of(ids))
                .setReviewTimeIso8601(iso8601(reviewTime)).build()));
    }

    private String iso8601(LocalDateTime time) {
        return time.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private NursingRecord saveRecord(LocalDateTime time) {
        return records.save(record(time).build());
    }

    private NursingRecord.NursingRecordBuilder record(LocalDateTime time) {
        return NursingRecord.builder().patientId(PID).content("原始内容").effectiveTime(time)
            .isDeleted(false).createdBy("original-author").createdByAccountName("原创建人")
            .createdAt(TIME.minusDays(1)).modifiedBy("original-author")
            .modifiedByAccountName("原创建人").modifiedAt(TIME.minusDays(1));
    }

    private NursingRecord activeRecord(LocalDateTime time, Integer handlingId) {
        return records
            .findByPatientIdAndEffectiveTimeAndPatientCriticalLisHandlingIdAndIsDeletedFalse(
                PID, time, handlingId).orElseThrow();
    }

    private void failInvalidation() {
        doThrow(new IllegalStateException("护理单失效更新失败")).when(invalidation)
            .updateLatestDataTime(eq(PID), eq(DEPT_ID), any(), any(), any());
    }

    private void assertOk(GenericResp response) {
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
    }

    private void assertMinutePrecision(LocalDateTime time) {
        assertThat(time).isNotNull();
        assertThat(time.getSecond()).isZero();
        assertThat(time.getNano()).isZero();
    }

    private int recordCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM nursing_records WHERE patient_id = ?", Integer.class, PID);
    }

    private int lockCount(String key) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_transaction_locks WHERE resource_key = ?",
            Integer.class, key);
    }

    private boolean waitForBlockedRequests(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock'
                  AND query LIKE '%app_transaction_locks%'
                """, Integer.class);
            if (waiting != null && waiting == expected) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private void cleanRecords() {
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_nursing_record_lock_tests_unique");
        for (long pid : List.of(PID, PID + 1)) {
            jdbcTemplate.update("DELETE FROM nursing_records WHERE patient_id = ?", pid);
            jdbcTemplate.update("DELETE FROM patient_snapshots WHERE pid = ?", pid);
            jdbcTemplate.update("DELETE FROM patient_records WHERE id = ?", pid);
            jdbcTemplate.update("DELETE FROM app_transaction_locks WHERE resource_key LIKE ?",
                "nursing_record:" + pid + ":%");
            jdbcTemplate.update("DELETE FROM app_transaction_locks WHERE resource_key LIKE ?",
                "patient_snapshot:" + pid + ":%");
        }
    }

    private static final long PID = 9_300_031L;
    private static final String DEPT_ID = "nursing-record-lock-tests";
    private static final String ACCOUNT = "nursing-record-lock-user";
    private static final String ACCOUNT_NAME = "护理记录测试用户";
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 1, 2, 3, 4);

    private NursingRecordService service;
    private SettingService settings;
    private PatientRecordRepository patientRepo;
    private PatientNursingReportInvalidationService invalidation;

    @Autowired private ConfigProtoService protoService;
    @Autowired private NursingRecordRepository records;
    @Autowired private LockExecutor executor;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private NursingRecordReader recordReader;
    @Autowired private SnapshotService snapshots;
    @Autowired private StatisticsDayWindowResolver windowResolver;
}
