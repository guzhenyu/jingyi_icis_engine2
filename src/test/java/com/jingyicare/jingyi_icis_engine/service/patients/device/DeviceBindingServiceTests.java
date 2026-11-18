package com.jingyicare.jingyi_icis_engine.service.patients.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.BindPatientDeviceReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

class DeviceBindingServiceTests extends TestsBase {
    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        jdbc.update("""
            DELETE FROM patient_devices
            WHERE device_id IN (SELECT id FROM device_infos WHERE department_id = ?)
            """, DEPT);
        jdbc.update("DELETE FROM device_infos WHERE department_id = ?", DEPT);
        jdbc.update("DELETE FROM patient_records WHERE dept_id = ?", DEPT);
    }

    @Test
    void dischargedPatientCannotBindOrTakeOverDevice() {
        long dischargedPatientId = insertPatient("已出科患者");
        long originalPatientId = insertPatient("设备原患者");
        int freeDeviceId = insertDevice("出科后空闲设备");
        int boundDeviceId = insertDevice("出科后转绑设备");
        long originalBindingId = insertBinding(
            originalPatientId, boundDeviceId, BINDING_TIME, null);
        Map<String, Object> originalBinding = jdbc.queryForMap(
            "SELECT * FROM patient_devices WHERE id = ?", originalBindingId);
        jdbc.update("UPDATE patient_records SET admission_status = ? WHERE id = ?",
            PatientEnums.ADMISSION_STATUS_DISCHARGED, dischargedPatientId);

        for (int deviceId : List.of(freeDeviceId, boundDeviceId)) {
            assertThat(bindAsAdmin(dischargedPatientId, deviceId, BINDING_TIME.plusHours(1)))
                .isEqualTo(StatusCode.PATIENT_NOT_IN_ICU_VALUE);
        }

        assertThat(activeBindingCount(dischargedPatientId)).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM patient_devices WHERE id = ?",
            originalBindingId)).isEqualTo(originalBinding);
    }

    @Test
    void bindingWaitingForDischargeRejectsAfterPatientStatusCommits() throws Exception {
        long patientId = insertPatient("等待出科提交患者");
        int deviceId = insertDevice("等待出科提交设备");
        jdbc.update("UPDATE patient_records SET admission_status = ? WHERE id = ?",
            PatientEnums.ADMISSION_STATUS_PRE_DISCHARGE, patientId);

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<Integer> call = executor.execute(
                List.of(LockKeys.patientDeviceBindings(patientId)), () -> {
                    Future<Integer> request = pool.submit(() ->
                        bindAsAdmin(patientId, deviceId, BINDING_TIME.plusHours(1)));
                    awaitLockWaiters(1);
                    // 模拟出科事务持有患者锁，在绑定请求等待期间提交出科状态。
                    jdbc.update("UPDATE patient_records SET admission_status = ? WHERE id = ?",
                        PatientEnums.ADMISSION_STATUS_DISCHARGED, patientId);
                    return request;
                });
            assertThat(call.get(10, TimeUnit.SECONDS))
                .isEqualTo(StatusCode.PATIENT_NOT_IN_ICU_VALUE);
        }
        assertThat(activeBindingCount(patientId)).isZero();
    }

    @Test
    void missingPatientCannotBindDevice() {
        int deviceId = insertDevice("患者不存在设备");

        assertThat(bindAsAdmin(Long.MAX_VALUE, deviceId, BINDING_TIME))
            .isEqualTo(StatusCode.PATIENT_NOT_FOUND_VALUE);
        assertThat(activeBindingCount(Long.MAX_VALUE)).isZero();
    }

    @Test
    void concurrentBindingsOfTheSameTypeForOnePatientAllowOnlyOneDevice() throws Exception {
        long patientId = insertPatient("同类设备并发");
        int firstDeviceId = insertDevice("同类设备甲");
        int secondDeviceId = insertDevice("同类设备乙");
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> calls = executor.execute(
                List.of(LockKeys.patientDeviceBindings(patientId)), () -> {
                    List<Future<Integer>> requests = List.of(
                        pool.submit(() -> bindAsAdmin(patientId, firstDeviceId, BINDING_TIME)),
                        pool.submit(() -> bindAsAdmin(patientId, secondDeviceId, BINDING_TIME)));
                    awaitLockWaiters(2);
                    return requests;
                });
            assertThat(List.of(calls.get(0).get(10, TimeUnit.SECONDS),
                calls.get(1).get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(
                    StatusCode.OK_VALUE,
                    StatusCode.PATIENT_ALREADY_BINDED_TO_SAME_TYPE_DEVICE_VALUE);
        }
        assertThat(activeBindingCount(patientId)).isEqualTo(1);
    }

    @Test
    void concurrentPatientsBindingOneDeviceLeaveOneCurrentBinding() throws Exception {
        long firstPatientId = insertPatient("设备竞争甲");
        long secondPatientId = insertPatient("设备竞争乙");
        int deviceId = insertDevice("共享移动设备");
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> calls = executor.execute(
                List.of(LockKeys.deviceBinding(deviceId)), () -> {
                    List<Future<Integer>> requests = List.of(
                        pool.submit(() -> bindAsAdmin(firstPatientId, deviceId, BINDING_TIME)),
                        pool.submit(() -> bindAsAdmin(secondPatientId, deviceId, BINDING_TIME)));
                    awaitLockWaiters(2);
                    return requests;
                });
            assertThat(calls.get(0).get(10, TimeUnit.SECONDS)).isEqualTo(StatusCode.OK_VALUE);
            assertThat(calls.get(1).get(10, TimeUnit.SECONDS)).isEqualTo(StatusCode.OK_VALUE);
        }
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM patient_devices
            WHERE device_id = ? AND NOT is_deleted AND unbinding_time IS NULL
            """, Integer.class, deviceId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM patient_devices WHERE device_id = ? AND NOT is_deleted
            """, Integer.class, deviceId)).isEqualTo(2);
    }

    @Test
    void transferWaitsForThePreviousPatientAndReplansAfterDischarge() throws Exception {
        long originalPatientId = insertPatient("待出科患者");
        long nextPatientId = insertPatient("接收设备患者");
        int deviceId = insertDevice("出科转绑设备");
        long bindingId = insertBinding(originalPatientId, deviceId, BINDING_TIME, null);
        LocalDateTime dischargeTime = BINDING_TIME.plusHours(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<Integer> call = executor.execute(
                List.of(LockKeys.patientDeviceBindings(originalPatientId)), () -> {
                    Future<Integer> request = pool.submit(() -> bindAsAdmin(
                        nextPatientId, deviceId, dischargeTime.plusHours(1)));
                    awaitLockWaiters(1);
                    assertThat(service.unbindAllForDischargeInTransaction(
                        originalPatientId, dischargeTime, ACCOUNT, dischargeTime))
                        .isEqualTo(StatusCode.OK);
                    return request;
                });
            assertThat(call.get(10, TimeUnit.SECONDS)).isEqualTo(StatusCode.OK_VALUE);
        }
        assertThat(jdbc.queryForObject("""
            SELECT unbinding_time FROM patient_devices WHERE id = ?
            """, LocalDateTime.class, bindingId)).isEqualTo(dischargeTime);
        assertThat(activeBindingCount(originalPatientId)).isZero();
        assertThat(activeBindingCount(nextPatientId)).isEqualTo(1);
    }

    @Test
    void failedTransferInsertRollsBackThePreviousBinding() {
        long originalPatientId = insertPatient("转绑回滚原患者");
        long nextPatientId = insertPatient("转绑回滚目标患者");
        int deviceId = insertDevice("转绑回滚设备");
        long originalBindingId = insertBinding(originalPatientId, deviceId, BINDING_TIME, null);
        LocalDateTime transferTime = BINDING_TIME.plusHours(1);
        // 已有同一患者、设备和开始时间的历史记录，使转绑插入触发唯一约束。
        insertBinding(nextPatientId, deviceId, transferTime, transferTime);

        // Hibernate 测试建表缺少生产部分唯一索引，仅为本测试设备补齐约束。
        String indexName = "test_device_binding_unique_" + deviceId;
        jdbc.execute("CREATE UNIQUE INDEX " + indexName
            + " ON patient_devices (patient_id, device_id, binding_time)"
            + " WHERE is_deleted = FALSE AND device_id = " + deviceId);
        try {
            assertThatThrownBy(() -> bindAsAdmin(nextPatientId, deviceId, transferTime))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("""
                SELECT unbinding_time FROM patient_devices WHERE id = ?
                """, LocalDateTime.class, originalBindingId)).isNull();
            assertThat(activeBindingCount(originalPatientId)).isEqualTo(1);
            assertThat(activeBindingCount(nextPatientId)).isZero();
        } finally {
            jdbc.execute("DROP INDEX " + indexName);
        }
    }

    @Test
    void batchUnbindingValidatesEveryRecordBeforeChangingAnyRecord() {
        long patientId = insertPatient("批量解绑失败患者");
        int firstDeviceId = insertDevice("批量解绑设备甲");
        int secondDeviceId = insertDevice("批量解绑设备乙");
        insertBinding(patientId, firstDeviceId, BINDING_TIME, null);
        insertBinding(patientId, secondDeviceId, BINDING_TIME.plusHours(2), null);
        LocalDateTime dischargeTime = BINDING_TIME.plusHours(1);

        StatusCode status = executor.execute(List.of(LockKeys.patientDeviceBindings(patientId)),
            () -> service.unbindAllForDischargeInTransaction(
                patientId, dischargeTime, ACCOUNT, dischargeTime));

        assertThat(status).isEqualTo(StatusCode.UNBINDING_TIME_OUTDATED);
        assertThat(activeBindingCount(patientId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM patient_devices
            WHERE patient_id = ? AND modified_by = ?
            """, Integer.class, patientId, ACCOUNT.autoAcctId())).isZero();
    }

    private int bindAsAdmin(long patientId, int deviceId, LocalDateTime bindingTime) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("admin", null,
                AuthorityUtils.createAuthorityList("ROLE_1")));
        try {
            BindPatientDeviceReq req = BindPatientDeviceReq.newBuilder()
                .setPid(patientId).setDeviceId(deviceId)
                .setBindingTimeIso8601(TimeUtils.toIso8601String(bindingTime, "UTC")).build();
            return service.bindPatientDevice(ProtoUtils.protoToJson(req)).getRt().getCode();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void awaitLockWaiters(int count) {
        Integer holder = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND ? = ANY(pg_blocking_pids(pid))
                """, Integer.class, holder)).isEqualTo(count));
    }

    private long insertPatient(String name) {
        return jdbc.queryForObject("""
            INSERT INTO patient_records (his_mrn, icu_name, dept_id, his_bed_number)
            VALUES (?, ?, ?, '') RETURNING id
            """, Long.class, name, name, DEPT);
    }

    private int insertDevice(String name) {
        return jdbc.queryForObject("""
            INSERT INTO device_infos (department_id, device_name, device_type, is_deleted,
                device_sn, source_mode, source_topology, upstream_device_id, pds_ip_seq)
            VALUES (?, ?, ?, FALSE, '', ?, ?, 0, 0) RETURNING id
            """, Integer.class, DEPT, name, DeviceEnums.DEVICE_TYPE_MONITOR,
            DeviceEnums.SOURCE_MODE_INBOUND_SERVER, DeviceEnums.SOURCE_TOPOLOGY_SINGLE_DEVICE);
    }

    private long insertBinding(
        long patientId, int deviceId, LocalDateTime bindingTime, LocalDateTime unbindingTime
    ) {
        return jdbc.queryForObject("""
            INSERT INTO patient_devices
                (patient_id, device_id, binding_time, unbinding_time, is_deleted)
            VALUES (?, ?, ?, ?, FALSE) RETURNING id
            """, Long.class, patientId, deviceId, bindingTime, unbindingTime);
    }

    private int activeBindingCount(long patientId) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM patient_devices
            WHERE patient_id = ? AND NOT is_deleted AND unbinding_time IS NULL
            """, Integer.class, patientId);
    }

    private static final String DEPT = "test-device-binding-service";
    private static final LocalDateTime BINDING_TIME = LocalDateTime.of(2025, 1, 1, 0, 0);
    private static final CurrentAccount ACCOUNT = new CurrentAccount("test", "test", "测试操作者");

    @Autowired private DeviceBindingService service;
    @Autowired private LockExecutor executor;
    @Autowired private JdbcTemplate jdbc;
}
