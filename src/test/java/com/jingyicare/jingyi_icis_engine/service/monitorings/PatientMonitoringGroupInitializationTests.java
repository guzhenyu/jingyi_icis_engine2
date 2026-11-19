package com.jingyicare.jingyi_icis_engine.service.monitorings;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroupParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringGroupParam;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetPatientMonitoringGroupsReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetPatientMonitoringGroupsResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.SyncPatientMonitoringGroupsFromDeptReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.UpdatePatientMonitoringGroupReq;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupParamPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.TypeEnumPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class PatientMonitoringGroupInitializationTests extends TestsBase {
    @BeforeEach
    void prepare() {
        authenticate();
        groupType = config.getConfig().getMonitoring().getEnums().getGroupTypeMonitoring().getId();
        int balanceType = config.getConfig().getMonitoring().getEnums()
            .getGroupTypeBalance().getId();
        String meta = ProtoUtils.encodeValueMeta(ValueMetaPB.newBuilder()
            .setValueType(TypeEnumPB.FLOAT).build());
        for (String code : List.of(DEFAULT_CODE, CUSTOM_CODE)) {
            params.save(MonitoringParam.builder().code(code).name(code).typePb(meta)
                .balanceType(0).displayOrder(1).build());
        }
        monitoringGroup = saveGroup(DEPT, "观察项", groupType, meta);
        balanceGroup = saveGroup(DEPT, "测试出入量", balanceType, meta);
        otherGroup = saveGroup(OTHER_DEPT, "其他科室", groupType, meta);
        PatientRecord patient = new PatientRecord();
        patient.setDeptId(DEPT);
        patient.setIcuName("患者分组初始化测试");
        patient.setAdmissionStatus(1);
        patient.setAdmissionTime(NOW);
        patient.setCreatedAt(NOW);
        pid = patients.save(patient).getId();
        assertThat(patientGroups.findByPidAndIsDeletedFalse(pid)).isEmpty();
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        if (pid != null) {
            jdbc.update("""
                DELETE FROM patient_monitoring_group_params WHERE patient_monitoring_group_id IN
                (SELECT id FROM patient_monitoring_groups WHERE pid = ?)
                """, pid);
            jdbc.update("DELETE FROM patient_monitoring_groups WHERE pid = ?", pid);
            jdbc.update("DELETE FROM patient_records WHERE id = ?", pid);
            jdbc.update("DELETE FROM app_transaction_locks WHERE resource_key = ?",
                LockKeys.patientMonitoringGroups(pid));
        }
        jdbc.update("""
            DELETE FROM dept_monitoring_group_params WHERE dept_monitoring_group_id IN
            (SELECT id FROM dept_monitoring_groups WHERE dept_id IN (?, ?))
            """, DEPT, OTHER_DEPT);
        jdbc.update("DELETE FROM dept_monitoring_groups WHERE dept_id IN (?, ?)", DEPT, OTHER_DEPT);
        jdbc.update("DELETE FROM monitoring_params WHERE code IN (?, ?)",
            DEFAULT_CODE, CUSTOM_CODE);
    }

    @Test
    void firstUpdateInitializesAllGroupTypesAndPersistsCustomParams() {
        assertThat(service.updatePatientMonitoringGroup(updateRequest(pid, monitoringGroup.getId()))
            .getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertCurrentBindingsAndCustomParam();

        // 重复编辑不能重复建立患者分组或自定义参数。
        assertThat(service.updatePatientMonitoringGroup(updateRequest(pid, monitoringGroup.getId()))
            .getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertCurrentBindingsAndCustomParam();
        GetPatientMonitoringGroupsResp response =
            service.getPatientMonitoringGroups(queryRequest(DEPT));
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(response.getMonitoringGroupList()).hasSize(1);
        assertThat(response.getMonitoringGroup(0).getParamList())
            .extracting(MonitoringParamPB::getCode).containsExactly(CUSTOM_CODE);
        assertThat(response.getMonitoringGroup(0).getParam(0).getDisplayOrder()).isEqualTo(7);
    }

    @Test
    void firstUpdateRejectsAnotherDepartmentWithoutCreatingPatientGroups() {
        assertThat(service.updatePatientMonitoringGroup(updateRequest(pid, otherGroup.getId()))
            .getRt().getCode()).isEqualTo(StatusCode.PATIENT_DEPT_MISMATCH_VALUE);
        assertThat(patientGroups.findByPid(pid)).isEmpty();
        assertThat(patientParams.findByPidAndIsDeleted(pid, false)).isEmpty();
    }

    @Test
    void firstUpdateRejectsMissingPatientWithoutCreatingOrphanGroups() {
        long missingPid = Long.MAX_VALUE;
        assertThat(patients.findById(missingPid)).isEmpty();
        try {
            assertThat(service.updatePatientMonitoringGroup(
                updateRequest(missingPid, monitoringGroup.getId())).getRt().getCode())
                .isEqualTo(StatusCode.PATIENT_NOT_FOUND_VALUE);
            assertThat(patientGroups.findByPid(missingPid)).isEmpty();
        } finally {
            jdbc.update("DELETE FROM app_transaction_locks WHERE resource_key = ?",
                LockKeys.patientMonitoringGroups(missingPid));
        }
    }

    @Test
    void firstRecordsQueryRejectsAnotherDepartmentWithoutInitializingItsGroups() {
        assertThat(recordService.getPatientMonitoringRecords(queryRequest(OTHER_DEPT))
            .getRt().getCode()).isEqualTo(StatusCode.PATIENT_DEPT_MISMATCH_VALUE);
        assertThat(patientGroups.findByPid(pid)).isEmpty();
        assertThat(patientParams.findByPidAndIsDeleted(pid, false)).isEmpty();
    }

    @Test
    void historicalGroupBindingRemainsEditableAfterDepartmentVersionChanges() {
        patientGroups.saveAll(List.of(
            PatientMonitoringGroup.builder().pid(pid)
                .deptMonitoringGroupId(monitoringGroup.getId()).isDeleted(false)
                .modifiedBy("system").modifiedAt(NOW).build(),
            PatientMonitoringGroup.builder().pid(pid)
                .deptMonitoringGroupId(balanceGroup.getId()).isDeleted(false)
                .modifiedBy("system").modifiedAt(NOW).build()));
        monitoringGroup.setIsDeleted(true);
        monitoringGroup.setDeletedAt(NOW);
        monitoringGroup.setDeletedBy("system");
        groups.saveAndFlush(monitoringGroup);
        DeptMonitoringGroup currentGroup = saveGroup(
            DEPT, monitoringGroup.getName(), groupType, monitoringGroup.getSumTypePb());

        assertThat(service.updatePatientMonitoringGroup(updateRequest(pid, monitoringGroup.getId()))
            .getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertCurrentBindingsAndCustomParam();
        assertThat(patientGroups.findByDeptMonitoringGroupIdAndPidAndIsDeletedFalse(
            currentGroup.getId(), pid)).isEmpty();
        GetPatientMonitoringGroupsResp response =
            service.getPatientMonitoringGroups(queryRequest(DEPT));
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(response.getMonitoringGroupList()).hasSize(1);
        assertThat(response.getMonitoringGroup(0).getId()).isEqualTo(monitoringGroup.getId());
    }

    @Test
    void departmentSyncReplacesCustomizedBindingsOnceAndThenRemainsIdempotent() {
        assertThat(service.updatePatientMonitoringGroup(updateRequest(pid, monitoringGroup.getId()))
            .getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        List<Integer> oldIds = patientGroups.findByPidAndIsDeletedFalse(pid).stream()
            .map(PatientMonitoringGroup::getId).toList();
        String request = ProtoUtils.protoToJson(SyncPatientMonitoringGroupsFromDeptReq.newBuilder()
            .setDeptId(DEPT).build());

        assertThat(service.syncPatientMonitoringGroupsFromDept(request).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        List<PatientMonitoringGroup> active = patientGroups.findByPidAndIsDeletedFalse(pid);
        assertThat(active).extracting(PatientMonitoringGroup::getDeptMonitoringGroupId)
            .containsExactlyInAnyOrder(monitoringGroup.getId(), balanceGroup.getId());
        List<Integer> newIds = active.stream().map(PatientMonitoringGroup::getId).toList();
        assertThat(newIds).doesNotContainAnyElementsOf(oldIds);
        assertThat(patientGroups.findByPid(pid)).hasSize(4);
        assertThat(patientParams.findByPatientMonitoringGroupIdInAndIsDeletedFalse(newIds))
            .isEmpty();
        GetPatientMonitoringGroupsResp response =
            service.getPatientMonitoringGroups(queryRequest(DEPT));
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(response.getMonitoringGroup(0).getParamList())
            .extracting(MonitoringParamPB::getCode).containsExactly(DEFAULT_CODE);

        assertThat(service.syncPatientMonitoringGroupsFromDept(request).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        assertThat(patientGroups.findByPidAndIsDeletedFalse(pid))
            .extracting(PatientMonitoringGroup::getId).containsExactlyInAnyOrderElementsOf(newIds);
        assertThat(patientGroups.findByPid(pid)).hasSize(4);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void patientMonitoringApisWaitForTheSameMetadataLock(Operation operation) throws Exception {
        // 先建组并设置自定义参数。读取已有分组也要等待；同步需要实际替换分组。
        String updateRequest = updateRequest(pid, monitoringGroup.getId());
        assertThat(service.updatePatientMonitoringGroup(updateRequest).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        List<PatientMonitoringGroup> previousGroups = patientGroups.findByPidAndIsDeletedFalse(pid);

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger blockerPid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> blocker = pool.submit(() -> lockExecutor.execute(
                List.of(LockKeys.patientMonitoringGroups(pid)), () -> {
                    blockerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    held.countDown();
                    await(release);
                    return null;
                }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> operationResult = pool.submit(() -> {
                authenticate();
                try {
                    return switch (operation) {
                        case GET_GROUPS -> service.getPatientMonitoringGroups(queryRequest(DEPT))
                            .getRt().getCode();
                        case GET_RECORDS -> recordService
                            .getPatientMonitoringRecords(queryRequest(DEPT)).getRt().getCode();
                        case UPDATE -> service.updatePatientMonitoringGroup(updateRequest)
                            .getRt().getCode();
                        case SYNC_FROM_DEPT -> service.syncPatientMonitoringGroupsFromDept(
                            ProtoUtils.protoToJson(SyncPatientMonitoringGroupsFromDeptReq
                                .newBuilder().setDeptId(DEPT).build())).getRt().getCode();
                    };
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });

            // 从独立连接确认接口在数据库中等待该患者的元数据业务锁。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waitingForLock = false;
            while (System.nanoTime() < deadline) {
                Integer waiters = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity a
                    WHERE a.datname = current_database() AND a.wait_event_type = 'Lock'
                      AND ? = ANY(pg_blocking_pids(a.pid))
                      AND lower(a.query) LIKE '%app_transaction_locks%'
                    """, Integer.class, blockerPid.get());
                if (waiters != null && waiters > 0) {
                    waitingForLock = true;
                    break;
                }
                Thread.sleep(20);
            }
            assertThat(waitingForLock).as("%s 等待患者分组业务锁", operation).isTrue();
            assertThat(operationResult.isDone()).isFalse();
            assertThat(patientGroups.findByPidAndIsDeletedFalse(pid))
                .containsExactlyInAnyOrderElementsOf(previousGroups);

            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThat(operationResult.get(10, TimeUnit.SECONDS)).isEqualTo(StatusCode.OK_VALUE);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private enum Operation {
        GET_GROUPS, GET_RECORDS, UPDATE, SYNC_FROM_DEPT
    }

    @Test
    void concurrentFirstQueryAndUpdateCreateOneBindingPerDepartmentGroup() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<GetPatientMonitoringGroupsResp> query = pool.submit(() -> {
                authenticate();
                ready.countDown();
                try {
                    await(start);
                    return service.getPatientMonitoringGroups(queryRequest(DEPT));
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            Future<GenericResp> update = pool.submit(() -> {
                authenticate();
                ready.countDown();
                try {
                    await(start);
                    return service.updatePatientMonitoringGroup(
                        updateRequest(pid, monitoringGroup.getId()));
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            GetPatientMonitoringGroupsResp queried = query.get(10, TimeUnit.SECONDS);
            assertThat(queried.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
            assertThat(queried.getMonitoringGroupList()).hasSize(1);
            assertThat(queried.getMonitoringGroup(0).getId()).isEqualTo(monitoringGroup.getId());
            assertThat(update.get(10, TimeUnit.SECONDS).getRt().getCode())
                .isEqualTo(StatusCode.OK_VALUE);
            assertCurrentBindingsAndCustomParam();
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertCurrentBindingsAndCustomParam() {
        assertThat(patientGroups.findByPidAndIsDeletedFalse(pid))
            .extracting(PatientMonitoringGroup::getDeptMonitoringGroupId)
            .containsExactlyInAnyOrder(monitoringGroup.getId(), balanceGroup.getId());
        List<PatientMonitoringGroupParam> customized =
            patientParams.findByPidAndIsDeleted(pid, false);
        assertThat(customized).hasSize(1);
        assertThat(customized.getFirst().getMonitoringParamCode()).isEqualTo(CUSTOM_CODE);
        assertThat(customized.getFirst().getDisplayOrder()).isEqualTo(7);
    }

    private DeptMonitoringGroup saveGroup(String deptId, String name, int type, String meta) {
        DeptMonitoringGroup group = groups.save(DeptMonitoringGroup.builder().deptId(deptId)
            .name(name).groupType(type).displayOrder(1).sumTypePb(meta).isDeleted(false)
            .modifiedBy("system").modifiedAt(NOW).build());
        groupParams.save(DeptMonitoringGroupParam.builder().deptMonitoringGroupId(group.getId())
            .monitoringParamCode(DEFAULT_CODE).displayOrder(1).isDeleted(false)
            .modifiedBy("system").modifiedAt(NOW).build());
        return group;
    }

    private String updateRequest(long patientId, int groupId) {
        return ProtoUtils.protoToJson(UpdatePatientMonitoringGroupReq.newBuilder()
            .setPid(patientId).setMonitoringGroupId(groupId)
            .addParams(MonitoringGroupParamPB.newBuilder().setCode(CUSTOM_CODE).setDisplayOrder(7))
            .build());
    }

    private String queryRequest(String deptId) {
        return ProtoUtils.protoToJson(GetPatientMonitoringGroupsReq.newBuilder().setPid(pid)
            .setDeptId(deptId).setGroupType(groupType)
            .setQueryStartIso8601("2026-11-19T00:00:00Z")
            .setQueryEndIso8601("2026-11-19T01:00:00Z").build());
    }

    private static void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("admin", null,
                AuthorityUtils.createAuthorityList("ROLE_1")));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待患者分组并发测试超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("患者分组并发测试被中断", e);
        }
    }

    @Autowired private ConfigProtoService config;
    @Autowired private MonitoringService service;
    @Autowired private PatientMonitoringService recordService;
    @Autowired private MonitoringParamRepository params;
    @Autowired private DeptMonitoringGroupRepository groups;
    @Autowired private DeptMonitoringGroupParamRepository groupParams;
    @Autowired private PatientMonitoringGroupRepository patientGroups;
    @Autowired private PatientMonitoringGroupParamRepository patientParams;
    @Autowired private PatientRecordRepository patients;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LockExecutor lockExecutor;

    private Long pid;
    private int groupType;
    private DeptMonitoringGroup monitoringGroup;
    private DeptMonitoringGroup balanceGroup;
    private DeptMonitoringGroup otherGroup;

    private static final String DEPT = "test-patient-monitoring-init";
    private static final String OTHER_DEPT = "test-patient-monitoring-init-other";
    private static final String DEFAULT_CODE = "test_patient_mon_init_default";
    private static final String CUSTOM_CODE = "test_patient_mon_init_custom";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 0, 0);
}
