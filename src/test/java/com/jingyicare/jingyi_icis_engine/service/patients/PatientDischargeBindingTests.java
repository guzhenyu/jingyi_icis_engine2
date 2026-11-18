package com.jingyicare.jingyi_icis_engine.service.patients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DischargePatientReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceEnums;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

class PatientDischargeBindingTests extends TestsBase {
    @BeforeEach
    void signIn() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("admin", null,
                AuthorityUtils.createAuthorityList("ROLE_1")));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        jdbc.update("""
            DELETE FROM patient_devices
            WHERE patient_id IN (SELECT id FROM patient_records WHERE dept_id = ?)
            """, DEPT);
        jdbc.update("DELETE FROM device_infos WHERE department_id = ?", DEPT);
        jdbc.update("DELETE FROM patient_records WHERE dept_id = ?", DEPT);
    }

    @Test
    void invalidUnbindingTimePreservesEveryPatientAndBindingField() {
        long patientId = insertPatient("出科解绑校验失败");
        int firstDeviceId = insertDevice("出科校验设备甲");
        int secondDeviceId = insertDevice("出科校验设备乙");
        insertBinding(patientId, firstDeviceId, DISCHARGE_TIME.minusHours(1), null, false);
        insertBinding(patientId, secondDeviceId, DISCHARGE_TIME.plusHours(1), null, false);
        Map<String, Object> beforePatient = patientSnapshot(patientId);
        List<Map<String, Object>> beforeBindings = bindingsSnapshot(patientId);

        GenericResp response = service.dischargePatient(
            ProtoUtils.protoToJson(dischargeRequest(patientId, "内科")));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.UNBINDING_TIME_OUTDATED_VALUE);
        assertThat(patientSnapshot(patientId)).isEqualTo(beforePatient)
            .containsEntry("admission_status", PatientEnums.ADMISSION_STATUS_PRE_DISCHARGE);
        assertThat(bindingsSnapshot(patientId)).isEqualTo(beforeBindings);
    }

    @Test
    void dischargeCommitsPatientStatusAndAllCurrentDeviceUnbindingsTogether() {
        long patientId = insertPatient("出科正常解绑");
        int firstDeviceId = insertDevice("出科设备甲");
        int secondDeviceId = insertDevice("出科设备乙");
        long firstBindingId = insertBinding(
            patientId, firstDeviceId, DISCHARGE_TIME.minusHours(1), null, false);
        long secondBindingId = insertBinding(
            patientId, secondDeviceId, DISCHARGE_TIME.minusHours(2), null, false);
        long historicalId = insertBinding(patientId, firstDeviceId,
            DISCHARGE_TIME.minusDays(2), DISCHARGE_TIME.minusDays(1), false);
        long deletedId = insertBinding(
            patientId, secondDeviceId, DISCHARGE_TIME.minusDays(2), null, true);
        Map<String, Object> historicalBefore = bindingSnapshot(historicalId);
        Map<String, Object> deletedBefore = bindingSnapshot(deletedId);
        CurrentAccount account = userQuery.getCurrentAccount();
        DischargePatientReq request = dischargeRequest(patientId, "内科");

        GenericResp response = service.dischargePatient(ProtoUtils.protoToJson(request));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(patientSnapshot(patientId))
            .containsEntry("admission_status", PatientEnums.ADMISSION_STATUS_DISCHARGED)
            .containsEntry("discharge_time", Timestamp.valueOf(DISCHARGE_TIME))
            .containsEntry("discharge_type", request.getDischargeType())
            .containsEntry("to_dept_name", "内科");
        for (long id : List.of(firstBindingId, secondBindingId)) {
            Map<String, Object> binding = bindingSnapshot(id);
            assertThat(binding)
                .containsEntry("unbinding_time", Timestamp.valueOf(DISCHARGE_TIME))
                .containsEntry("modified_by", account.autoAcctId())
                .containsEntry("modified_by_account_name", account.acctName());
            assertThat(binding.get("modified_at")).isNotNull();
        }
        assertThat(bindingSnapshot(historicalId)).isEqualTo(historicalBefore);
        assertThat(bindingSnapshot(deletedId)).isEqualTo(deletedBefore);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM patient_devices
            WHERE patient_id = ? AND NOT is_deleted AND unbinding_time IS NULL
            """, Integer.class, patientId)).isZero();
    }

    @Test
    void databaseWriteFailureRollsBackPatientAndDeviceBindings() {
        long patientId = insertPatient("出科数据库失败");
        int firstDeviceId = insertDevice("出科回滚设备甲");
        int secondDeviceId = insertDevice("出科回滚设备乙");
        insertBinding(patientId, firstDeviceId, DISCHARGE_TIME.minusHours(1), null, false);
        insertBinding(patientId, secondDeviceId, DISCHARGE_TIME.minusHours(2), null, false);
        Map<String, Object> beforePatient = patientSnapshot(patientId);
        List<Map<String, Object>> beforeBindings = bindingsSnapshot(patientId);
        // 业务校验通过后，由 PostgreSQL 的 VARCHAR(255) 限制触发真实写入失败。
        DischargePatientReq request = dischargeRequest(patientId, "科".repeat(256));

        assertThatThrownBy(() -> service.dischargePatient(ProtoUtils.protoToJson(request)))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(patientSnapshot(patientId)).isEqualTo(beforePatient)
            .containsEntry("admission_status", PatientEnums.ADMISSION_STATUS_PRE_DISCHARGE);
        assertThat(bindingsSnapshot(patientId)).isEqualTo(beforeBindings);
    }

    private DischargePatientReq dischargeRequest(long patientId, String toDeptName) {
        int transferType = protoConfig.getConfig().getPatient().getEnumsV2()
            .getDischargeTypeList().stream().filter(type -> type.getName().equals("转出"))
            .findFirst().orElseThrow().getId();
        return DischargePatientReq.newBuilder().setId(patientId).setDischargeType(transferType)
            .setDischargeTime(TimeUtils.toIso8601String(DISCHARGE_TIME, "UTC"))
            .setToDeptName(toDeptName).build();
    }

    private long insertPatient(String name) {
        LocalDateTime previousTime = DISCHARGE_TIME.minusDays(1);
        return jdbc.queryForObject("""
            INSERT INTO patient_records (his_mrn, icu_name, dept_id, his_bed_number,
                admission_status, discharge_type, discharge_time, to_dept_name,
                death_time, his_discharge_time, discharge_edit_time, discharge_account_id)
            VALUES (?, ?, ?, '01', ?, 99, ?, '原去向', ?, ?, ?, '原记录人') RETURNING id
            """, Long.class, name, name, DEPT, PatientEnums.ADMISSION_STATUS_PRE_DISCHARGE,
            previousTime, previousTime, previousTime, previousTime);
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
        long patientId, int deviceId, LocalDateTime bindingTime,
        LocalDateTime unbindingTime, boolean deleted
    ) {
        return jdbc.queryForObject("""
            INSERT INTO patient_devices (patient_id, device_id, binding_time, unbinding_time,
                is_deleted, modified_by, modified_by_account_name, modified_at)
            VALUES (?, ?, ?, ?, ?, 'before', '原操作人', ?) RETURNING id
            """, Long.class, patientId, deviceId, bindingTime, unbindingTime, deleted, bindingTime);
    }

    private Map<String, Object> patientSnapshot(long patientId) {
        return jdbc.queryForMap("SELECT * FROM patient_records WHERE id = ?", patientId);
    }

    private Map<String, Object> bindingSnapshot(long bindingId) {
        return jdbc.queryForMap("SELECT * FROM patient_devices WHERE id = ?", bindingId);
    }

    private List<Map<String, Object>> bindingsSnapshot(long patientId) {
        return jdbc.queryForList(
            "SELECT * FROM patient_devices WHERE patient_id = ? ORDER BY id", patientId);
    }

    private static final String DEPT = "test-patient-discharge-bindings";
    private static final LocalDateTime DISCHARGE_TIME = LocalDateTime.of(2025, 1, 2, 0, 0);

    @Autowired private PatientService service;
    @Autowired private ProtoConfig protoConfig;
    @Autowired private UserQuery userQuery;
    @Autowired private JdbcTemplate jdbc;
}
