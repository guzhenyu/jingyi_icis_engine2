package com.jingyicare.jingyi_icis_engine.service.patients.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.jingyicare.jingyi_icis_engine.entity.patients.DeviceInfo;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddDeviceInfoReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddDeviceInfoResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.BindPatientDeviceReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeleteDeviceInfoReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDeviceInfoReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DevicePB;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientDeviceService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class DeviceServiceTests extends TestsBase {
    private static final String DEPT = "test-device-service-a";
    private static final String OTHER_DEPT = "test-device-service-b";

    @Autowired private DeviceService service;
    @Autowired private LockExecutor executor;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM device_infos WHERE department_id IN (?, ?)", DEPT, OTHER_DEPT);
    }

    @Test
    void concurrentAddsUnderSameDepartmentAndNameReturnNameConflict() throws Exception {
        DevicePB first = device(DEPT, "重名设备", "192.0.2.201");
        DevicePB second = first.toBuilder().setDeviceIp("192.0.2.202").build();
        assertCompetingAdds(first, second, "device_infos:" + DEPT + ":重名设备",
            StatusCode.DEVICE_INFO_ALREADY_EXISTS);
    }

    @Test
    void concurrentAddsInDifferentDepartmentsWithSameIpReturnIpConflict() throws Exception {
        DevicePB first = device(DEPT, "设备甲", "192.0.2.203");
        DevicePB second = device(OTHER_DEPT, "设备乙", first.getDeviceIp());
        assertCompetingAdds(first, second, "device_ip:192.0.2.203",
            StatusCode.DEVICE_IP_ALREADY_USED);
    }

    @Test
    void managementSearchUsesPartialMatchesWhileBedLookupMatchesTheWholeBedInItsDepartment() {
        int monitor = DeviceEnums.DEVICE_TYPE_MONITOR;
        int ventilator = DeviceEnums.DEVICE_TYPE_VENTILATOR;
        int active = insertDevice(DEPT, "床旁设备甲", "A-01", monitor, false);
        int deleted = insertDevice(DEPT, "床旁设备乙", "A-01", ventilator, true);
        int adjacent = insertDevice(DEPT, "床旁设备丙", "A-010", ventilator, false);
        int differentName = insertDevice(DEPT, "心电图机", "A-01", monitor, false);
        insertDevice(OTHER_DEPT, "床旁设备丁", "A-01", monitor, false);
        insertDevice(DEPT, "无固定床号", null, monitor, false);
        insertDevice(DEPT, "旧数据空床号", "", monitor, false);

        GetDeviceInfoReq search = GetDeviceInfoReq.newBuilder()
            .setDeptId(DEPT).setDeviceBedNum("01").setDeviceName("设备").build();
        assertThat(service.searchActiveDevices(GetDeviceInfoReq.getDefaultInstance()))
            .extracting(DeviceInfo::getId).contains(active, adjacent).doesNotContain(deleted);
        assertThat(service.searchActiveDevices(search)).extracting(DeviceInfo::getId)
            .containsExactlyInAnyOrder(active, adjacent);
        GetDeviceInfoReq typeSearch = search.toBuilder().setDeviceType(ventilator).build();
        assertThat(service.searchActiveDevices(typeSearch))
            .extracting(DeviceInfo::getId).containsExactly(adjacent);
        assertThat(service.searchActiveDevices(search.toBuilder().setDeviceId(active).build()))
            .extracting(DeviceInfo::getId).containsExactly(active);
        assertThat(service.searchActiveDevices(search.toBuilder().setDeviceId(deleted).build()))
            .isEmpty();

        assertThat(service.findDevicesAtBed(DEPT, "A-01")).extracting(DeviceInfo::getId)
            .containsExactlyInAnyOrder(active, differentName);
        assertThat(service.findDevicesAtBed(DEPT, null)).isEmpty();
        assertThat(service.findDevicesAtBed(DEPT, "")).isEmpty();
    }

    @Test
    void historicalLookupReturnsDevicesByIdIncludingSoftDeletedDevices() {
        int active = insertDevice(DEPT, "在用设备", null, DeviceEnums.DEVICE_TYPE_MONITOR, false);
        int deleted = insertDevice(OTHER_DEPT, "历史设备", null, DeviceEnums.DEVICE_TYPE_MONITOR, true);

        assertThat(service.findDevicesByIds(List.of(deleted, active))).extracting(DeviceInfo::getId)
            .containsExactlyInAnyOrder(active, deleted);
        assertThat(service.findDevicesByIds(List.of())).isEmpty();
    }

    @Test
    void lockingADeviceRequiresAnExistingTransaction() {
        assertThatThrownBy(() -> service.lockDeviceById(1))
            .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @Transactional
    void lockingAMissingDeviceReturnsEmpty() {
        assertThat(service.lockDeviceById(-1)).isEmpty();
    }

    @Test
    void addAssignsANewIdWhileUpdateRequiresAnExistingId() {
        signIn();
        int originalId = insertDevice(
            DEPT, "原设备", null, DeviceEnums.DEVICE_TYPE_MONITOR, false);
        DevicePB device = device(DEPT, "新设备", "192.0.2.204").toBuilder()
            .setId(originalId).build();
        AddDeviceInfoResp added = service.addDeviceInfo(request(device));
        assertThat(added.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(added.getId()).isNotEqualTo(originalId);

        DevicePB missing = device.toBuilder().clearId().build();
        assertThat(service.updateDeviceInfo(request(missing)).getRt().getCode())
            .isEqualTo(StatusCode.DEVICE_INFO_NOT_EXISTS_VALUE);
        DevicePB updated = device.toBuilder()
            .setId(added.getId()).setDeviceSn("SN-204").build();
        assertThat(service.updateDeviceInfo(request(updated)).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        assertThat(service.findDevicesByIds(List.of(added.getId())))
            .extracting(DeviceInfo::getDeviceSn).containsExactly("SN-204");
    }

    @Test
    void renameAndSoftDeleteReleaseNamesAndAllowMultipleDevicesWithoutIp() {
        signIn();
        DevicePB original = device(DEPT, "原名", "");
        AddDeviceInfoResp created = service.addDeviceInfo(request(original));
        assertThat(created.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        DevicePB moved = device(OTHER_DEPT, "新名", "").toBuilder()
            .setId(created.getId()).build();
        assertThat(service.updateDeviceInfo(request(moved)).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        assertThat(service.addDeviceInfo(request(original)).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);

        DevicePB replacement = moved.toBuilder().clearId().build();
        assertThat(service.addDeviceInfo(request(replacement)).getRt().getCode())
            .isEqualTo(StatusCode.DEVICE_INFO_ALREADY_EXISTS_VALUE);
        String deleteRequest = ProtoUtils.protoToJson(
            DeleteDeviceInfoReq.newBuilder().setId(created.getId()).build());
        assertThat(service.deleteDeviceInfo(deleteRequest).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        AddDeviceInfoResp recreated = service.addDeviceInfo(request(replacement));
        assertThat(recreated.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(recreated.getId()).isNotEqualTo(created.getId());
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM device_infos WHERE id = ?",
            Boolean.class, created.getId())).isTrue();
    }

    @Test
    void deleteReplansWhenNameChangesWhileWaitingForItsLock() throws Exception {
        signIn();
        DevicePB original = device(DEPT, "等待删除的设备", "");
        AddDeviceInfoResp created = service.addDeviceInfo(request(original));
        assertThat(created.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        String newName = "并发改名-" + UUID.randomUUID();
        String oldKey = LockKeys.deviceInfo(DEPT, original.getDeviceName());
        String newKey = LockKeys.deviceInfo(DEPT, newName);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<Integer> deletion = executor.execute(List.of(oldKey), () -> {
                Future<Integer> call = pool.submit(() -> deleteAsAdmin(created.getId()));
                awaitLockWaiters(1);
                // 删除请求已经选中旧名称，在释放锁前改变数据库中的名称。
                assertThat(jdbc.update("UPDATE device_infos SET device_name = ? WHERE id = ?",
                    newName, created.getId())).isEqualTo(1);
                return call;
            });
            assertThat(deletion.get(10, TimeUnit.SECONDS)).isEqualTo(StatusCode.OK_VALUE);
        }
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM app_transaction_locks WHERE resource_key = ?",
            Integer.class, newKey)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM device_infos WHERE id = ?",
            Boolean.class, created.getId())).isTrue();
    }

    @Test
    void concurrentDeletePreservesTheFirstDeletionAudit() throws Exception {
        String name = "等待重复删除的设备";
        int id = insertDevice(DEPT, name, null, DeviceEnums.DEVICE_TYPE_MONITOR, false);
        LocalDateTime deletedAt = LocalDateTime.of(2025, 3, 9, 9, 0);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<Integer> deletion = executor.execute(List.of(LockKeys.deviceInfo(DEPT, name)),
                () -> {
                    Future<Integer> call = pool.submit(() -> deleteAsAdmin(id));
                    awaitLockWaiters(1);
                    assertThat(jdbc.update("""
                        UPDATE device_infos SET is_deleted = true, deleted_by = ?, deleted_at = ?
                        WHERE id = ?
                        """, "first-operator", deletedAt, id)).isEqualTo(1);
                    return call;
                });
            assertThat(deletion.get(10, TimeUnit.SECONDS)).isEqualTo(StatusCode.OK_VALUE);
        }
        DeviceInfo deleted = service.findDevicesByIds(List.of(id)).getFirst();
        assertThat(deleted.getIsDeleted()).isTrue();
        assertThat(deleted.getDeletedBy()).isEqualTo("first-operator");
        assertThat(deleted.getDeletedAt()).isEqualTo(deletedAt);
    }

    @Test
    void deletionWaitsForBindingAndKeepsTheBoundDevice(
        @Autowired PatientDeviceService bindingService,
        @Autowired PlatformTransactionManager transactionManager
    ) throws Exception {
        signIn();
        DevicePB device = device(DEPT, "正在绑定的设备", "");
        AddDeviceInfoResp added = service.addDeviceInfo(request(device));
        assertThat(added.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        int id = added.getId();
        long pid = jdbc.queryForObject("""
            INSERT INTO patient_records(his_mrn, dept_id, his_bed_number)
            VALUES (?, ?, ?) RETURNING id
            """, Long.class, "test-device-service-binding", DEPT, "01");
        BindPatientDeviceReq bind = BindPatientDeviceReq.newBuilder()
            .setPid(pid).setDeviceId(id).setBindingTimeIso8601("2025-03-09T09:00+08:00").build();
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            Future<Integer> deletion = transaction.execute(status -> {
                assertThat(bindingService.bindPatientDevice(ProtoUtils.protoToJson(bind))
                    .getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
                Future<Integer> call = pool.submit(() -> deleteAsAdmin(id));
                awaitLockWaiters(1);
                return call;
            });
            assertThat(deletion.get(10, TimeUnit.SECONDS))
                .isEqualTo(StatusCode.DEVICE_ALREADY_BINDED_VALUE);
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM device_infos d JOIN patient_devices b ON b.device_id = d.id
                WHERE d.id = ? AND b.patient_id = ? AND NOT d.is_deleted AND NOT b.is_deleted
                  AND b.unbinding_time IS NULL
                """, Integer.class, id, pid)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM patient_devices WHERE patient_id = ?", pid);
            jdbc.update("DELETE FROM patient_records WHERE id = ?", pid);
        }
    }

    private void assertCompetingAdds(
        DevicePB first, DevicePB second, String key, StatusCode conflict
    ) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> calls = executor.execute(List.of(key), () -> {
                List<Future<Integer>> requests = List.of(
                    pool.submit(() -> addAsAdmin(first)), pool.submit(() -> addAsAdmin(second)));
                awaitLockWaiters(2);
                return requests;
            });
            assertThat(List.of(calls.get(0).get(10, TimeUnit.SECONDS),
                calls.get(1).get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(StatusCode.OK_VALUE, conflict.getNumber());
        }
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM device_infos WHERE department_id IN (?, ?) AND NOT is_deleted
            """, Integer.class, DEPT, OTHER_DEPT)).isEqualTo(1);
    }

    private void awaitLockWaiters(int count) {
        Integer holder = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND ? = ANY(pg_blocking_pids(pid))
                """, Integer.class, holder)).isEqualTo(count));
    }

    private int addAsAdmin(DevicePB device) {
        signIn();
        try {
            return service.addDeviceInfo(request(device)).getRt().getCode();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private int deleteAsAdmin(int id) {
        signIn();
        try {
            DeleteDeviceInfoReq req = DeleteDeviceInfoReq.newBuilder().setId(id).build();
            return service.deleteDeviceInfo(ProtoUtils.protoToJson(req)).getRt().getCode();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private int insertDevice(String dept, String name, String bed, int type, boolean deleted) {
        return jdbc.queryForObject("""
            INSERT INTO device_infos (department_id, device_name, device_bed_number, device_type,
                is_deleted, device_sn, source_mode, source_topology, upstream_device_id, pds_ip_seq)
            VALUES (?, ?, ?, ?, ?, '', ?, ?, 0, 0) RETURNING id
            """, Integer.class, dept, name, bed, type, deleted,
            DeviceEnums.SOURCE_MODE_INBOUND_SERVER, DeviceEnums.SOURCE_TOPOLOGY_SINGLE_DEVICE);
    }

    private void signIn() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("admin", null,
                AuthorityUtils.createAuthorityList("ROLE_1")));
    }

    private String request(DevicePB device) {
        return ProtoUtils.protoToJson(AddDeviceInfoReq.newBuilder().setDeviceInfo(device).build());
    }

    private DevicePB device(String dept, String name, String ip) {
        return DevicePB.newBuilder().setDepartmentId(dept).setDeviceName(name).setDeviceIp(ip)
            .setDeviceType(DeviceEnums.DEVICE_TYPE_MONITOR)
            .setSourceMode(DeviceEnums.SOURCE_MODE_INBOUND_SERVER)
            .setSourceTopology(DeviceEnums.SOURCE_TOPOLOGY_SINGLE_DEVICE)
            .build();
    }
}
