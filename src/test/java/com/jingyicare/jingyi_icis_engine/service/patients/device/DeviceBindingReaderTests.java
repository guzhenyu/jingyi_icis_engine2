package com.jingyicare.jingyi_icis_engine.service.patients.device;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeviceWithBindingsPB;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceBindingRecordPB;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientReader;
import com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceBindingReader.BindingHistory;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

class DeviceBindingReaderTests extends TestsBase {
    @BeforeEach
    void captureSql() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        previousStatisticsEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        sqlLogger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        previousSqlLevel = sqlLogger.getLevel();
        sqlAppender = new ListAppender<>();
        sqlAppender.start();
        sqlLogger.addAppender(sqlAppender);
        sqlLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void cleanUp() {
        sqlLogger.detachAppender(sqlAppender);
        sqlLogger.setLevel(previousSqlLevel);
        sqlAppender.stop();
        statistics.setStatisticsEnabled(previousStatisticsEnabled);
        jdbc.update("""
            DELETE FROM patient_devices WHERE patient_id IN (
                SELECT id FROM patient_records WHERE dept_id IN (?, ?))
            """, DEPT, OTHER_DEPT);
        jdbc.update("DELETE FROM device_infos WHERE department_id IN (?, ?)", DEPT, OTHER_DEPT);
        jdbc.update("DELETE FROM bed_configs WHERE department_id IN (?, ?)", DEPT, OTHER_DEPT);
        jdbc.update("DELETE FROM patient_records WHERE dept_id IN (?, ?)", DEPT, OTHER_DEPT);
    }

    @Test
    void aggregateQueriesAcceptOptionalTypesAndKeepDeletedDeviceHistory() {
        long patientId = insertPatient(DEPT, "01", START);
        int monitor = insertDevice(DEPT, "当前监护仪", null, MONITOR, false);
        int deletedMonitor = insertDevice(DEPT, "历史监护仪", null, MONITOR, true);
        int ventilator = insertDevice(DEPT, "历史呼吸机", null, VENTILATOR, false);
        insertBinding(patientId, monitor, START, START.plusHours(1), false);
        insertBinding(patientId, deletedMonitor, START, START.plusHours(2), false);
        insertBinding(patientId, ventilator, START, START.plusHours(3), false);
        insertBinding(patientId, ventilator, START.plusHours(4), START.plusHours(5), true);
        insertBinding(patientId, monitor, START.plusHours(6), null, false);

        for (Integer type : Arrays.asList(null, 0, -1)) {
            assertSingleAggregate(() -> assertThat(reader.findLatestUnbindingTime(patientId, type))
                .isEqualTo(START.plusHours(3)), "max(");
        }
        assertSingleAggregate(() -> assertThat(reader.findLatestUnbindingTime(patientId, MONITOR))
            .isEqualTo(START.plusHours(2)), "max(");
        assertSingleAggregate(() -> assertThat(
            reader.findLatestUnbindingTime(patientId, VENTILATOR))
                .isEqualTo(START.plusHours(3)), "max(");
        assertSingleAggregate(() -> assertThat(reader.findLatestUnbindingTime(patientId, 999))
            .isNull(), "max(");
        for (Integer type : Arrays.asList(null, 0, -1, MONITOR)) {
            assertSingleAggregate(() -> assertThat(reader.hasCurrentDeviceOfType(patientId, type))
                .isTrue(), "count(");
        }
    }

    @Test
    void currentDevicesIgnoreDeletedRowsAndIncludeThePatientsFixedDevice() {
        long patientId = insertPatient(DEPT, "01", START);
        int deletedDevice = insertDevice(DEPT, "已删除设备", null, MONITOR, true);
        int deletedBindingDevice = insertDevice(DEPT, "已删除绑定", null, MONITOR, false);
        insertBinding(patientId, deletedDevice, START, null, false);
        insertBinding(patientId, deletedBindingDevice, START, null, true);
        assertThat(reader.hasCurrentDeviceOfType(patientId, MONITOR)).isFalse();
        assertThat(reader.hasCurrentDeviceOfType(patientId, null)).isFalse();

        insertBed(DEPT, "01", "dev-01", "一床");
        insertDevice(DEPT, "固定呼吸机", "dev-01", VENTILATOR, false);
        assertThat(reader.hasCurrentDeviceOfType(patientId, VENTILATOR)).isTrue();
        assertThat(reader.hasCurrentDeviceOfType(patientId, null)).isTrue();
        assertThat(reader.hasCurrentDeviceOfType(patientId, 0)).isTrue();
        assertThat(reader.hasCurrentDeviceOfType(patientId, MONITOR)).isFalse();
    }

    @Test
    void currentBindingsBatchOnlySelectedDevicesAndResolveBedsWithinTheirDepartment() {
        insertBed(DEPT, "01", "dev-a", "甲科一床");
        insertBed(OTHER_DEPT, "01", "dev-b", "乙科一床");
        List<Integer> deviceIds = new ArrayList<>();
        List<Long> patientIds = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            String department = index % 2 == 0 ? DEPT : OTHER_DEPT;
            long patientId = insertPatient(department, "01", START);
            int deviceId = insertDevice(department, "设备" + index, null, MONITOR, false);
            patientIds.add(patientId);
            deviceIds.add(deviceId);
            insertBinding(patientId, deviceId, START, null, false);
        }
        int unselected = insertDevice(DEPT, "未查询设备", null, MONITOR, false);
        insertBinding(patientIds.get(0), unselected, START, null, false);
        insertBinding(patientIds.get(1), unselected, START, null, false);

        long statementsBefore = statistics.getPrepareStatementCount();
        int sqlBefore = sqlAppender.list.size();
        Pair<StatusCode, Map<Integer, DeviceBindingRecordPB>> result =
            reader.findCurrentBindings(deviceIds);
        assertThat(result.getFirst()).isEqualTo(StatusCode.OK);
        assertThat(result.getSecond()).containsOnlyKeys(deviceIds);
        assertThat(result.getSecond().get(deviceIds.get(0)).getDisplayBedNumber())
            .isEqualTo("甲科一床");
        assertThat(result.getSecond().get(deviceIds.get(1)).getDisplayBedNumber())
            .isEqualTo("乙科一床");
        assertThat(statistics.getPrepareStatementCount() - statementsBefore).isEqualTo(3);
        assertThat(sqlSince(sqlBefore)).filteredOn(sql -> sql.contains("from patient_devices"))
            .singleElement().satisfies(sql -> assertThat(sql).contains("device_id in"));

        statementsBefore = statistics.getPrepareStatementCount();
        assertThat(reader.findCurrentBindings(List.of()).getSecond()).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(statementsBefore);
    }

    @Test
    void historyPreservesDeletedBindingsAndDevicesAndTheOriginalWindowBoundaries() {
        long patientId = insertPatient(DEPT, "01", START);
        int historyDevice = insertDevice(DEPT, "历史监护仪", null, MONITOR, true);
        insertBinding(patientId, historyDevice, START, START.plusHours(1), false);
        long closed = insertBinding(patientId, historyDevice,
            START.plusHours(1), START.plusHours(2), true);
        long open = insertBinding(patientId, historyDevice, START.plusHours(2), null, true);
        insertBinding(patientId, historyDevice, START.plusHours(3), null, false);
        insertBed(DEPT, "01", "dev-01", "一床");
        int fixedDevice = insertDevice(DEPT, "固定呼吸机", "dev-01", VENTILATOR, false);

        Pair<StatusCode, BindingHistory> history = reader.findBindingHistory(
            historyDevice, START.plusHours(1), START.plusHours(3));
        assertThat(history.getFirst()).isEqualTo(StatusCode.OK);
        assertThat(history.getSecond().isBinding()).isTrue();
        assertThat(history.getSecond().records()).extracting(DeviceBindingRecordPB::getId)
            .containsExactly(closed, open);

        PatientRecord patient = patientReader.findById(patientId).orElseThrow();
        List<DeviceWithBindingsPB> patientHistory = reader.findBindingHistory(
            patient, null, START.plusHours(1), START.plusHours(3));
        assertThat(patientHistory).extracting(value -> value.getDevice().getId())
            .containsExactly(historyDevice, fixedDevice);
        assertThat(patientHistory.get(0).getBindingList())
            .extracting(DeviceBindingRecordPB::getId).containsExactly(closed, open);
        assertThat(patientHistory.get(1).getBindingList()).singleElement().satisfies(binding -> {
            assertThat(binding.getId()).isZero();
            assertThat(TimeUtils.fromIso8601String(binding.getBindingTimeIso8601(), "UTC"))
                .isEqualTo(START);
        });
    }

    @Test
    void missingAdmissionTimeKeepsActualHistoryWithoutSynthesizingAFixedBinding() {
        long patientId = insertPatient(DEPT, "01", null);
        int mobileDevice = insertDevice(DEPT, "移动监护仪", null, MONITOR, false);
        long bindingId = insertBinding(patientId, mobileDevice, START, null, false);
        insertBed(DEPT, "01", "dev-01", "一床");
        insertDevice(DEPT, "固定呼吸机", "dev-01", VENTILATOR, false);

        PatientRecord patient = patientReader.findById(patientId).orElseThrow();
        List<DeviceWithBindingsPB> history = reader.findBindingHistory(patient, null, null, null);
        assertThat(history).singleElement().satisfies(device -> {
            assertThat(device.getDevice().getId()).isEqualTo(mobileDevice);
            assertThat(device.getBindingList()).extracting(DeviceBindingRecordPB::getId)
                .containsExactly(bindingId);
        });
        assertThat(reader.hasCurrentDeviceOfType(patientId, VENTILATOR)).isTrue();
    }

    private void assertSingleAggregate(Runnable assertion, String function) {
        long statementsBefore = statistics.getPrepareStatementCount();
        long entitiesBefore = statistics.getEntityLoadCount();
        int sqlBefore = sqlAppender.list.size();
        assertion.run();
        assertThat(statistics.getPrepareStatementCount() - statementsBefore).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isEqualTo(entitiesBefore);
        assertThat(sqlSince(sqlBefore)).singleElement().satisfies(sql ->
            assertThat(sql).contains(function, "patient_devices", "device_infos"));
    }

    private List<String> sqlSince(int start) {
        return sqlAppender.list.subList(start, sqlAppender.list.size()).stream()
            .map(event -> event.getFormattedMessage().toLowerCase(Locale.ROOT)).toList();
    }

    private long insertPatient(String department, String bed, LocalDateTime admissionTime) {
        return jdbc.queryForObject("""
            INSERT INTO patient_records (his_mrn, icu_name, dept_id, his_bed_number,
                admission_time, admission_status)
            VALUES ('binding-reader-patient', '患者', ?, ?, ?, 1) RETURNING id
            """, Long.class, department, bed, admissionTime);
    }

    private int insertDevice(
        String department, String name, String bed, int type, boolean deleted
    ) {
        return jdbc.queryForObject("""
            INSERT INTO device_infos (department_id, device_name, device_bed_number, device_type,
                is_deleted, device_sn, source_mode, source_topology, upstream_device_id, pds_ip_seq)
            VALUES (?, ?, ?, ?, ?, '', 1, 1, 0, 0) RETURNING id
            """, Integer.class, department, name, bed, type, deleted);
    }

    private long insertBinding(
        long patientId, int deviceId, LocalDateTime fromUtc, LocalDateTime toUtc, boolean deleted
    ) {
        return jdbc.queryForObject("""
            INSERT INTO patient_devices (patient_id, device_id, binding_time, unbinding_time,
                is_deleted) VALUES (?, ?, ?, ?, ?) RETURNING id
            """, Long.class, patientId, deviceId, fromUtc, toUtc, deleted);
    }

    private void insertBed(String department, String hisBed, String deviceBed, String displayBed) {
        jdbc.update("""
            INSERT INTO bed_configs (department_id, his_bed_number, device_bed_number,
                display_bed_number, bed_type, is_deleted) VALUES (?, ?, ?, ?, 1, FALSE)
            """, department, hisBed, deviceBed, displayBed);
    }

    private static final String DEPT = "test-binding-reader-a";
    private static final String OTHER_DEPT = "test-binding-reader-b";
    private static final int MONITOR = DeviceEnums.DEVICE_TYPE_MONITOR;
    private static final int VENTILATOR = DeviceEnums.DEVICE_TYPE_VENTILATOR;
    private static final LocalDateTime START = LocalDateTime.of(2025, 3, 9, 0, 0);

    @Autowired private DeviceBindingReader reader;
    @Autowired private PatientReader patientReader;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;
    private boolean previousStatisticsEnabled;
    private Logger sqlLogger;
    private Level previousSqlLevel;
    private ListAppender<ILoggingEvent> sqlAppender;
}
