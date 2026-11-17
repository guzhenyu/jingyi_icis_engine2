package com.jingyicare.jingyi_icis_engine.service.patients.bed;

import static com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums.BED_TYPE_FIXED;
import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.security.test.context.support.WithMockUser;

import com.jingyicare.jingyi_icis_engine.entity.patients.BedConfig;
import com.jingyicare.jingyi_icis_engine.entity.patients.BedCount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddBedConfigReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddBedConfigResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddBedCountReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeleteBedConfigReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeleteBedCountReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetBedCountsReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.BedConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.BedCountPB;
import com.jingyicare.jingyi_icis_engine.repository.patients.BedJdbc;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

@WithMockUser(username = "admin", roles = "1")
class BedMetaServiceTests extends TestsBase {
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM bed_configs WHERE department_id IN (?, ?)", deptId, otherDeptId);
        jdbc.update("DELETE FROM bed_counts WHERE dept_id = ?", deptId);
    }

    @Test
    void countsUseUtcInstantAcrossOffsetsAndCanBeReaddedAfterDeletion() {
        String first = countRequest("2026-10-01T08:00:00.1234567+08:00", 4);
        String equivalent = countRequest("2026-10-01T00:00:00.1234567Z", 8);
        assertThat(service.addBedCount(first).getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(service.addBedCount(equivalent).getRt().getCode())
            .isEqualTo(StatusCode.BED_COUNT_ALREADY_EXISTS_VALUE);
        assertThat(service.addBedCount(countRequest("2026-10-01T00:01:00Z", 12)).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);

        List<BedCount> history = reader.findBedCountHistory(deptId);
        assertThat(history).extracting(BedCount::getBedCount).containsExactly(4, 12);
        assertThat(history.getFirst().getEffectiveTime())
            .isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0, 0, 123457000));
        int deletedId = history.getFirst().getId();
        String delete = ProtoUtils.protoToJson(DeleteBedCountReq.newBuilder().setId(deletedId));
        assertThat(service.deleteBedCount(delete).getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(service.deleteBedCount(delete).getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(service.addBedCount(equivalent).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM bed_counts WHERE id = ?",
            Boolean.class, deletedId)).isTrue();

        String list = ProtoUtils.protoToJson(GetBedCountsReq.newBuilder().setDeptId(deptId));
        assertThat(service.getBedCounts(list).getBedCountList())
            .extracting(BedCountPB::getCount).containsExactly(12, 8);
    }

    @Test
    void updatesKeepDepartmentAndRecreateDeletedBedsWithANewId() {
        BedConfigPB original = bed("HIS-1", "显示床一");
        AddBedConfigResp added = service.addBedConfig(configRequest(original));
        assertThat(added.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        BedConfigPB conflicting = bed("HIS-2", "显示床二").toBuilder()
            .setDeviceBedNumber("设备床二").build();
        assertThat(service.addBedConfig(configRequest(conflicting)).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        BedConfigPB updated = original.toBuilder()
            .setId(added.getId()).setDepartmentId(otherDeptId)
            .setDisplayBedNumber(conflicting.getDisplayBedNumber()).build();
        assertThat(service.updateBedConfig(configRequest(updated)).getRt().getCode())
            .isEqualTo(StatusCode.BED_CONFIG_DISPLAY_NAME_ALREADY_EXISTS_VALUE);
        updated = updated.toBuilder().setDisplayBedNumber("显示床一更新").build();
        assertThat(service.updateBedConfig(configRequest(updated)).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        BedConfig saved = reader.findActiveBedByHisNumber(deptId, original.getHisBedNumber())
            .orElseThrow();
        assertThat(saved.getId()).isEqualTo(added.getId());
        assertThat(saved.getDisplayBedNumber()).isEqualTo(updated.getDisplayBedNumber());
        assertThat(reader.findActiveBedsByDepartment(otherDeptId)).isEmpty();
        assertThat(BedJdbc.findConfigLocation(jdbc, added.getId()))
            .contains(new BedJdbc.ConfigLocation(deptId));

        String delete = ProtoUtils.protoToJson(
            DeleteBedConfigReq.newBuilder().setId(added.getId()));
        assertThat(service.deleteBedConfig(delete).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        assertThat(service.deleteBedConfig(delete).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        assertThat(BedJdbc.findConfigLocation(jdbc, added.getId())).isEmpty();
        assertThat(service.updateBedConfig(configRequest(updated)).getRt().getCode())
            .isEqualTo(StatusCode.BED_CONFIG_NOT_EXISTS_VALUE);
        BedConfigPB recreated = original.toBuilder().setId(added.getId()).build();
        AddBedConfigResp recreatedResult = service.addBedConfig(configRequest(recreated));
        assertThat(recreatedResult.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(recreatedResult.getId()).isNotEqualTo(added.getId());
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM bed_configs WHERE id = ?",
            Boolean.class, added.getId())).isTrue();
        assertThat(reader.findActiveBedsByDepartment(otherDeptId)).isEmpty();
        assertThat(reader.findActiveBedByHisNumber(deptId, original.getHisBedNumber()))
            .get().extracting(BedConfig::getId).isEqualTo(recreatedResult.getId());
    }

    @Test
    void newConfigsIgnoreExistingAndMissingIds() {
        BedConfigPB otherDeptBed = bed("HIS-1", "显示床一").toBuilder()
            .setDepartmentId(otherDeptId).build();
        AddBedConfigResp existing = service.addBedConfig(configRequest(otherDeptBed));
        assertThat(existing.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        BedConfigPB newBed = otherDeptBed.toBuilder()
            .setId(existing.getId()).setDepartmentId(deptId).build();
        AddBedConfigResp added = service.addBedConfig(configRequest(newBed));
        assertThat(added.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(added.getId()).isNotEqualTo(existing.getId());
        assertThat(reader.findActiveBedByHisNumber(otherDeptId, otherDeptBed.getHisBedNumber()))
            .get().extracting(BedConfig::getId).isEqualTo(existing.getId());

        BedConfigPB missing = bed("HIS-2", "显示床二").toBuilder()
            .setId(Long.MAX_VALUE).setDeviceBedNumber("设备床二").build();
        assertThat(BedJdbc.findConfigLocation(jdbc, missing.getId())).isEmpty();
        AddBedConfigResp missingResult = service.addBedConfig(configRequest(missing));
        assertThat(missingResult.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(missingResult.getId()).isNotEqualTo(missing.getId());
        assertThat(reader.findActiveBedByHisNumber(deptId, missing.getHisBedNumber()))
            .get().extracting(BedConfig::getId).isEqualTo(missingResult.getId());
        assertThat(BedJdbc.findConfigLocation(jdbc, missing.getId())).isEmpty();
    }

    @Test
    void concurrentBedsWithDifferentNamesCannotShareADeviceBedNumber() throws Exception {
        String first = configRequest(bed("HIS-1", "显示床一"));
        String second = configRequest(bed("HIS-2", "显示床二"));
        try (ExecutorService pool = new DelegatingSecurityContextExecutorService(
                Executors.newFixedThreadPool(2))) {
            List<Future<Integer>> responses = locks.execute(List.of(LockKeys.bedConfig(deptId)),
                () -> {
                    List<Future<Integer>> requests = List.of(
                        pool.submit(() -> service.addBedConfig(first).getRt().getCode()),
                        pool.submit(() -> service.addBedConfig(second).getRt().getCode()));
                    // 两个请求都等在科室锁上时，再允许它们进入查重和写入。
                    Integer holder = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                    await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                        assertThat(jdbc.queryForObject("""
                            SELECT count(*) FROM pg_stat_activity
                            WHERE datname = current_database() AND ? = ANY(pg_blocking_pids(pid))
                            """, Integer.class, holder)).isEqualTo(2));
                    return requests;
                });
            assertThat(List.of(responses.getFirst().get(10, TimeUnit.SECONDS),
                responses.getLast().get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(
                    StatusCode.OK_VALUE, StatusCode.BED_CONFIG_DEVICE_NAME_ALREADY_EXISTS_VALUE);
        }
        assertThat(reader.findActiveBedsByDepartment(deptId)).hasSize(1);
    }

    private BedConfigPB bed(String hisNumber, String displayNumber) {
        return BedConfigPB.newBuilder().setDepartmentId(deptId).setBedType(BED_TYPE_FIXED)
            .setHisBedNumber(hisNumber).setDisplayBedNumber(displayNumber)
            .setDeviceBedNumber("设备床一").build();
    }

    private static String configRequest(BedConfigPB bed) {
        return ProtoUtils.protoToJson(AddBedConfigReq.newBuilder().setBedConfig(bed));
    }

    private String countRequest(String time, int count) {
        return ProtoUtils.protoToJson(AddBedCountReq.newBuilder().setDeptId(deptId)
            .setBedCount(BedCountPB.newBuilder().setDateIso8601(time).setCount(count)));
    }

    private final String deptId = "bed-meta-" + UUID.randomUUID();
    private final String otherDeptId = deptId + "-other";

    @Autowired private BedMetaService service;
    @Autowired private BedMetaReader reader;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LockExecutor locks;
}
