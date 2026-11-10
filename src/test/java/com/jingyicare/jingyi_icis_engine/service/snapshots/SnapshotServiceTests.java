package com.jingyicare.jingyi_icis_engine.service.snapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.UnknownFieldSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.NursingRecord;
import com.jingyicare.jingyi_icis_engine.entity.snapshots.PatientSnapshot;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.NursingRecordPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.ScoreGroupPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSnapshot.PatientSnapshotPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.NursingRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.snapshots.PatientSnapshotRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.shifts.StatisticsDayWindow;
import com.jingyicare.jingyi_icis_engine.service.shifts.StatisticsDayWindowResolver;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

class SnapshotServiceTests extends TestsBase {
    @BeforeEach
    void prepare() {
        cleanRecords();
        // 不设置入科时间，快照查询只要求患者存在且科室有效。
        jdbcTemplate.update("INSERT INTO patient_records (id, dept_id) VALUES (?, ?)", PID, DEPT);
    }

    @AfterEach
    void cleanUp() {
        cleanRecords();
    }

    @Test
    void rejectsInvalidArgumentsAndReturnsEmptyForNoDates() {
        assertCode(service.get(null, List.of(DAY), false), StatusCode.INVALID_PARAM_VALUE);
        assertCode(service.get(0L, List.of(DAY), false), StatusCode.INVALID_PARAM_VALUE);
        assertCode(service.get(-1L, List.of(DAY), false), StatusCode.INVALID_PARAM_VALUE);
        assertCode(service.get(PID, null, false), StatusCode.INVALID_PARAM_VALUE);
        assertCode(service.get(PID, Arrays.asList(DAY, null), false),
            StatusCode.INVALID_PARAM_VALUE);
        assertCode(service.get(Long.MAX_VALUE, List.of(DAY), false),
            StatusCode.PATIENT_NOT_FOUND);

        var empty = service.get(PID, List.of(), false);
        assertCode(empty, StatusCode.OK);
        assertThat(empty.getSecond()).isEmpty();
        assertThat(snapshotCount()).isZero();
        assertThat(lockCount()).isZero();
    }

    @Test
    void rejectsPatientWithoutDepartment() {
        jdbcTemplate.update("UPDATE patient_records SET dept_id = NULL WHERE id = ?", PID);

        assertCode(service.get(PID, List.of(DAY), false), StatusCode.PATIENT_NOT_FOUND);

        assertThat(snapshotCount()).isZero();
    }

    @Test
    void createsSortedUniqueSnapshotsUsingMonitoringHalfOpenWindowsAndOneTimestamp() {
        StatisticsDayWindow window = window(DAY);
        saveRecord(window.monitoringStartUtc().minusNanos(1000), "之前", false);
        NursingRecord first = saveRecord(window.monitoringStartUtc(), "起点", false);
        NursingRecord sameTime = saveRecord(window.monitoringStartUtc(), "相同时刻", false);
        NursingRecord last = saveRecord(window.monitoringEndUtc().minusNanos(1000), "末尾", false);
        NursingRecord next = saveRecord(window.monitoringEndUtc(), "次日起点", false);
        saveRecord(window.monitoringStartUtc().plusHours(1), "已删除", true);

        List<PatientSnapshotPB> result = getAt(NOW, List.of(DAY.plusDays(1), DAY, DAY), false);

        assertThat(result).extracting(PatientSnapshotPB::getStatisticsDate)
            .containsExactly(DAY.toString(), DAY.plusDays(1).toString());
        assertThat(result.get(0).getNursingRecordsList()).extracting(NursingRecordPB::getId)
            .containsExactly(first.getId(), sameTime.getId(), last.getId());
        assertThat(result.get(1).getNursingRecordsList()).extracting(NursingRecordPB::getId)
            .containsExactly(next.getId());
        for (PatientSnapshotPB snapshot : result) {
            assertThat(snapshot.getPid()).isEqualTo(PID);
            assertThat(snapshot.getNursingRecordSaIso8601()).isEqualTo(iso(NOW));
            assertThat(snapshot.getNursingRecordLmaIso8601()).isEmpty();
            PatientSnapshot stored = stored(LocalDate.parse(snapshot.getStatisticsDate()));
            assertThat(stored.getSnapshotAt()).isEqualTo(NOW);
            assertThat(stored.getLastModifiedAt()).isNull();
            assertThat(ProtoUtils.decodePatientSnapshot(stored.getPayload())).isEqualTo(snapshot);
        }
        assertThat(snapshotCount()).isEqualTo(2);
        assertThat(lockCount()).isEqualTo(2);
    }

    @Test
    void cacheHitDoesNotReadChangedRecordsOrWriteSnapshotRow() {
        NursingRecord record = saveRecord(window(DAY).monitoringStartUtc(), "缓存内容", false);
        PatientSnapshotPB first = getAt(NOW, List.of(DAY), false).get(0);
        String originalVersion = rowVersion(DAY);
        jdbcTemplate.update("UPDATE nursing_records SET content = ? WHERE id = ?",
            "未失效的新内容", record.getId());

        PatientSnapshotPB cached = getAt(NOW.plusSeconds(5), List.of(DAY), false).get(0);

        assertThat(cached).isEqualTo(first);
        assertThat(rowVersion(DAY)).isEqualTo(originalVersion);
        assertThat(stored(DAY).getSnapshotAt()).isEqualTo(NOW);
    }

    @Test
    void forceRefreshWritesTimestampEvenWhenDataIsUnchanged() {
        saveRecord(window(DAY).monitoringStartUtc(), "内容", false);
        PatientSnapshotPB first = getAt(NOW, List.of(DAY), false).get(0);
        String originalVersion = rowVersion(DAY);

        PatientSnapshotPB refreshed = getAt(NOW.plusSeconds(1), List.of(DAY), true).get(0);

        assertThat(refreshed.getNursingRecordsList()).isEqualTo(first.getNursingRecordsList());
        assertThat(refreshed.getNursingRecordSaIso8601()).isEqualTo(iso(NOW.plusSeconds(1)));
        assertThat(rowVersion(DAY)).isNotEqualTo(originalVersion);
        assertThat(stored(DAY).getSnapshotAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void emptyRefreshClearsRecordsAndPreservesModificationAndOtherModuleFields() {
        NursingRecord record = saveRecord(window(DAY).monitoringStartUtc(), "旧记录", false);
        PatientSnapshotPB initial = getAt(NOW, List.of(DAY), false).get(0);
        UnknownFieldSet unknownFields = UnknownFieldSet.newBuilder().addField(900,
            UnknownFieldSet.Field.newBuilder().addVarint(71).build()).build();
        ScoreGroupPB score = ScoreGroupPB.newBuilder().setCode("gcs")
            .setGroupScoreText("15分").build();
        String modifiedAt = iso(NOW.plusSeconds(1));
        PatientSnapshotPB payload = initial.toBuilder().addScoreGroups(score)
            .setScoreGroupLmaIso8601(iso(NOW.minusSeconds(2)))
            .setScoreGroupSaIso8601(iso(NOW.minusSeconds(1)))
            .setNursingRecordLmaIso8601(modifiedAt)
            .setUnknownFields(unknownFields).build();
        savePayload(DAY, payload, NOW.plusSeconds(1));
        jdbcTemplate.update("UPDATE nursing_records SET is_deleted = TRUE WHERE id = ?",
            record.getId());

        PatientSnapshotPB refreshed = getAt(NOW.plusSeconds(2), List.of(DAY), false).get(0);

        assertThat(refreshed.getNursingRecordsList()).isEmpty();
        assertThat(refreshed.getNursingRecordSaIso8601()).isEqualTo(iso(NOW.plusSeconds(2)));
        assertThat(refreshed.getNursingRecordLmaIso8601()).isEqualTo(modifiedAt);
        assertThat(refreshed.getScoreGroupsList()).containsExactly(score);
        assertThat(refreshed.getScoreGroupLmaIso8601()).isEqualTo(iso(NOW.minusSeconds(2)));
        assertThat(refreshed.getScoreGroupSaIso8601()).isEqualTo(iso(NOW.minusSeconds(1)));
        assertThat(refreshed.getUnknownFields()).isEqualTo(unknownFields);
        assertThat(stored(DAY).getLastModifiedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-sa", "invalid-sa", "equal-lma", "newer-lma", "invalid-lma"})
    void refreshesForMissingInvalidOrStaleModuleTimestamps(String condition) {
        PatientSnapshotPB.Builder payload = getAt(NOW, List.of(DAY), false).get(0).toBuilder();
        switch (condition) {
            case "missing-sa" -> payload.clearNursingRecordSaIso8601();
            case "invalid-sa" -> payload.setNursingRecordSaIso8601("bad-time");
            case "equal-lma" -> payload.setNursingRecordLmaIso8601(iso(NOW));
            case "newer-lma" -> payload.setNursingRecordLmaIso8601(iso(NOW.plusSeconds(1)));
            case "invalid-lma" -> payload.setNursingRecordLmaIso8601("bad-time");
            default -> throw new IllegalArgumentException(condition);
        }
        savePayload(DAY, payload.build(), null);
        NursingRecord added = saveRecord(window(DAY).monitoringStartUtc(), "新增内容", false);

        PatientSnapshotPB refreshed = getAt(NOW.plusSeconds(2), List.of(DAY), false).get(0);

        assertThat(refreshed.getNursingRecordsList()).extracting(NursingRecordPB::getId)
            .containsExactly(added.getId());
        assertThat(refreshed.getNursingRecordSaIso8601()).isEqualTo(iso(NOW.plusSeconds(2)));
        assertThat(refreshed.getNursingRecordLmaIso8601())
            .isEqualTo(payload.getNursingRecordLmaIso8601());
    }

    @Test
    void olderModificationTimestampAllowsCacheHit() {
        PatientSnapshotPB payload = getAt(NOW, List.of(DAY), false).get(0).toBuilder()
            .setNursingRecordLmaIso8601(iso(NOW.minusSeconds(1))).build();
        savePayload(DAY, payload, NOW.minusSeconds(1));
        String originalVersion = rowVersion(DAY);

        assertThat(getAt(NOW.plusSeconds(1), List.of(DAY), false)).containsExactly(payload);
        assertThat(rowVersion(DAY)).isEqualTo(originalVersion);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null-payload", "bad-base64", "bad-proto", "null-snapshot-at",
        "wrong-pid", "wrong-date"})
    void rebuildsUninitializedCorruptedOrMismatchedRows(String condition) {
        PatientSnapshotPB initial = getAt(NOW, List.of(DAY), false).get(0);
        PatientSnapshotPB foreign = initial.toBuilder()
            .addScoreGroups(ScoreGroupPB.newBuilder().setCode("foreign-score"))
            .setScoreGroupLmaIso8601("foreign-modified-time")
            .setScoreGroupSaIso8601("foreign-snapshot-time")
            .setUnknownFields(UnknownFieldSet.newBuilder().addField(900,
                UnknownFieldSet.Field.newBuilder().addVarint(71).build()).build()).build();
        switch (condition) {
            case "null-payload" -> jdbcTemplate.update(
                "UPDATE patient_snapshots SET payload = NULL WHERE pid = ?", PID);
            case "bad-base64" -> jdbcTemplate.update(
                "UPDATE patient_snapshots SET payload = ? WHERE pid = ?", "%%%", PID);
            case "bad-proto" -> jdbcTemplate.update(
                "UPDATE patient_snapshots SET payload = ? WHERE pid = ?", "/w==", PID);
            case "null-snapshot-at" -> jdbcTemplate.update(
                "UPDATE patient_snapshots SET snapshot_at = NULL WHERE pid = ?", PID);
            case "wrong-pid" -> savePayload(DAY, foreign.toBuilder().setPid(PID + 1).build(), null);
            case "wrong-date" -> savePayload(DAY,
                foreign.toBuilder().setStatisticsDate(DAY.minusDays(1).toString()).build(), null);
            default -> throw new IllegalArgumentException(condition);
        }
        NursingRecord added = saveRecord(window(DAY).monitoringStartUtc(), "恢复内容", false);

        PatientSnapshotPB refreshed = getAt(NOW.plusSeconds(1), List.of(DAY), false).get(0);

        assertThat(refreshed.getPid()).isEqualTo(PID);
        assertThat(refreshed.getStatisticsDate()).isEqualTo(DAY.toString());
        assertThat(refreshed.getNursingRecordsList()).extracting(NursingRecordPB::getId)
            .containsExactly(added.getId());
        assertThat(refreshed.getNursingRecordSaIso8601()).isEqualTo(iso(NOW.plusSeconds(1)));
        if (condition.equals("wrong-pid") || condition.equals("wrong-date")) {
            assertThat(refreshed.getScoreGroupsList()).isEmpty();
            assertThat(refreshed.getScoreGroupLmaIso8601()).isEmpty();
            assertThat(refreshed.getScoreGroupSaIso8601()).isEqualTo(iso(NOW.plusSeconds(1)));
            assertThat(refreshed.getUnknownFields())
                .isEqualTo(UnknownFieldSet.getDefaultInstance());
        }
        assertThat(snapshotCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"monitoring-start", "monitoring-end", "balance-start", "balance-end"})
    void mismatchedWindowForcesOnlyTheAffectedDay(String endpoint) {
        List<PatientSnapshotPB> initial = getAt(NOW, List.of(DAY, DAY.plusDays(1)), false);
        PatientSnapshotPB.Builder changed = initial.get(0).toBuilder();
        String incorrect = iso(window(DAY).monitoringStartUtc().minusDays(3));
        switch (endpoint) {
            case "monitoring-start" -> changed.setMonitoringStartIso8601(incorrect);
            case "monitoring-end" -> changed.setMonitoringEndIso8601(incorrect);
            case "balance-start" -> changed.setBalanceStartIso8601(incorrect);
            case "balance-end" -> changed.setBalanceEndIso8601(incorrect);
            default -> throw new IllegalArgumentException(endpoint);
        }
        savePayload(DAY, changed.build(), null);
        String nextDayVersion = rowVersion(DAY.plusDays(1));
        NursingRecord added = saveRecord(window(DAY).monitoringStartUtc(), "窗口重建", false);

        List<PatientSnapshotPB> refreshed = getAt(NOW.plusSeconds(1),
            List.of(DAY, DAY.plusDays(1)), false);

        assertThat(refreshed.get(0).getNursingRecordsList()).extracting(NursingRecordPB::getId)
            .containsExactly(added.getId());
        assertThat(refreshed.get(0).getMonitoringStartIso8601())
            .isEqualTo(initial.get(0).getMonitoringStartIso8601());
        assertThat(refreshed.get(0).getMonitoringEndIso8601())
            .isEqualTo(initial.get(0).getMonitoringEndIso8601());
        assertThat(refreshed.get(0).getBalanceStartIso8601())
            .isEqualTo(initial.get(0).getBalanceStartIso8601());
        assertThat(refreshed.get(0).getBalanceEndIso8601())
            .isEqualTo(initial.get(0).getBalanceEndIso8601());
        assertThat(refreshed.get(1)).isEqualTo(initial.get(1));
        assertThat(rowVersion(DAY.plusDays(1))).isEqualTo(nextDayVersion);
    }

    @Test
    void equivalentWindowOffsetsAllowCacheHit() {
        PatientSnapshotPB.Builder payload = getAt(NOW, List.of(DAY), false).get(0).toBuilder();
        StatisticsDayWindow current = window(DAY);
        payload.setMonitoringStartIso8601(offsetIso(current.monitoringStartUtc()));
        payload.setMonitoringEndIso8601(offsetIso(current.monitoringEndUtc()));
        payload.setBalanceStartIso8601(offsetIso(current.balanceStartUtc()));
        payload.setBalanceEndIso8601(offsetIso(current.balanceEndUtc()));
        savePayload(DAY, payload.build(), null);
        String originalVersion = rowVersion(DAY);

        assertThat(getAt(NOW.plusSeconds(1), List.of(DAY), false))
            .containsExactly(payload.build());
        assertThat(rowVersion(DAY)).isEqualTo(originalVersion);
    }

    @Test
    void databaseFailureRollsBackAllDatesAndReturnsNoPartialResults() {
        saveRecord(window(DAY).monitoringStartUtc(), "原内容", false);
        PatientSnapshotPB original = getAt(NOW, List.of(DAY), false).get(0);
        String originalVersion = rowVersion(DAY);
        jdbcTemplate.update("UPDATE nursing_records SET content = ? WHERE patient_id = ?",
            "准备刷新内容", PID);
        jdbcTemplate.execute("ALTER TABLE patient_snapshots ADD CONSTRAINT " + FAIL_CONSTRAINT
            + " CHECK (pid <> " + PID + " OR statistics_date <> DATE '" + DAY.plusDays(1) + "')");

        var result = service.get(PID, List.of(DAY, DAY.plusDays(1)), true);

        assertCode(result, StatusCode.INTERNAL_EXCEPTION);
        assertThat(result.getSecond()).isEmpty();
        assertThat(snapshotCount()).isEqualTo(1);
        assertThat(ProtoUtils.decodePatientSnapshot(stored(DAY).getPayload())).isEqualTo(original);
        assertThat(rowVersion(DAY)).isEqualTo(originalVersion);
        assertThat(stored(DAY).getSnapshotAt()).isEqualTo(NOW);
        assertThat(lockCount()).isEqualTo(1);
    }

    @Test
    void concurrentGetsSerializeAndCreateOnlyOneSnapshotRow() throws Exception {
        saveRecord(window(DAY).monitoringStartUtc(), "并发内容", false);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(
                List.of(LockKeys.patientSnapshot(PID, DAY)), () -> {
                    locked.countDown();
                    try {
                        assertTrue(release.await(20, TimeUnit.SECONDS), "释放快照锁超时");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return null;
                }));
            assertTrue(locked.await(10, TimeUnit.SECONDS), "未取得快照锁");
            Future<Pair<ReturnCode, List<PatientSnapshotPB>>> first = pool.submit(
                () -> service.get(PID, List.of(DAY), false));
            Future<Pair<ReturnCode, List<PatientSnapshotPB>>> second = pool.submit(
                () -> service.get(PID, List.of(DAY), false));
            assertTrue(waitForBlockedGets(), "两个读取请求应等待同一个快照锁");
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            var firstResult = first.get(15, TimeUnit.SECONDS);
            var secondResult = second.get(15, TimeUnit.SECONDS);

            assertCode(firstResult, StatusCode.OK);
            assertCode(secondResult, StatusCode.OK);
            assertThat(firstResult.getSecond()).isEqualTo(secondResult.getSecond());
            assertThat(firstResult.getSecond()).hasSize(1);
            assertThat(firstResult.getSecond().get(0).getNursingRecordsList()).hasSize(1);
            assertThat(snapshotCount()).isEqualTo(1);
            assertThat(lockCount()).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private List<PatientSnapshotPB> getAt(
        LocalDateTime now, List<LocalDate> days, boolean forceRefresh
    ) {
        try (var clock = mockStatic(TimeUtils.class, CALLS_REAL_METHODS)) {
            clock.when(TimeUtils::getNowUtc).thenReturn(now);
            var result = service.get(PID, days, forceRefresh);
            assertCode(result, StatusCode.OK);
            return result.getSecond();
        }
    }

    private void assertCode(Pair<ReturnCode, List<PatientSnapshotPB>> result, StatusCode code) {
        assertThat(result.getFirst().getCode()).isEqualTo(code.getNumber());
        if (code != StatusCode.OK) assertThat(result.getSecond()).isEmpty();
    }

    private StatisticsDayWindow window(LocalDate day) {
        String zone = protoService.getZoneId();
        return resolver.resolve(TimeUtils.getDayMidnightUtc(day, ZoneId.of(zone)), zone, DEPT);
    }

    private NursingRecord saveRecord(LocalDateTime effectiveTime, String content, boolean deleted) {
        return records.save(NursingRecord.builder().patientId(PID).effectiveTime(effectiveTime)
            .content(content).isDeleted(deleted).build());
    }

    private void savePayload(LocalDate day, PatientSnapshotPB payload, LocalDateTime modifiedAt) {
        PatientSnapshot row = stored(day);
        row.setPayload(ProtoUtils.encodePatientSnapshot(payload));
        row.setLastModifiedAt(modifiedAt);
        snapshots.save(row);
    }

    private PatientSnapshot stored(LocalDate day) {
        return snapshots.findByPidAndStatisticsDateIn(PID, List.of(day)).get(0);
    }

    private String iso(LocalDateTime utc) {
        return utc.withNano(0).atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
    }

    private String offsetIso(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).withOffsetSameInstant(ZoneOffset.ofHours(8))
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private String rowVersion(LocalDate day) {
        return jdbcTemplate.queryForObject(
            "SELECT xmin::text FROM patient_snapshots WHERE pid = ? AND statistics_date = ?",
            String.class, PID, day);
    }

    private int snapshotCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM patient_snapshots WHERE pid = ?", Integer.class, PID);
    }

    private int lockCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_transaction_locks WHERE resource_key LIKE ?",
            Integer.class, "patient_snapshot:" + PID + ":%");
    }

    private boolean waitForBlockedGets() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock'
                  AND query LIKE '%app_transaction_locks%'
                """, Integer.class);
            if (waiting != null && waiting == 2) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private void cleanRecords() {
        jdbcTemplate.execute("ALTER TABLE patient_snapshots DROP CONSTRAINT IF EXISTS "
            + FAIL_CONSTRAINT);
        jdbcTemplate.update("DELETE FROM nursing_records WHERE patient_id = ?", PID);
        jdbcTemplate.update("DELETE FROM patient_snapshots WHERE pid = ?", PID);
        jdbcTemplate.update("DELETE FROM patient_records WHERE id = ?", PID);
        jdbcTemplate.update("DELETE FROM app_transaction_locks WHERE resource_key LIKE ?",
            "patient_snapshot:" + PID + ":%");
    }

    private static final long PID = 9_310_041L;
    private static final String DEPT = "snapshot-service-tests";
    private static final String FAIL_CONSTRAINT = "snapshot_service_tests_reject_date";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-04T12:01:02.123456");

    @Autowired private SnapshotService service;
    @Autowired private PatientSnapshotRepository snapshots;
    @Autowired private NursingRecordRepository records;
    @Autowired private ConfigProtoService protoService;
    @Autowired private StatisticsDayWindowResolver resolver;
    @Autowired private LockExecutor executor;
    @Autowired private JdbcTemplate jdbcTemplate;
}
