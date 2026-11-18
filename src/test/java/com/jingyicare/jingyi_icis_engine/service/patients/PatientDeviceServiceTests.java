package com.jingyicare.jingyi_icis_engine.service.patients;

import static com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums.*;
import static com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.service.debug.DeviceDebugger;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.patients.bed.BedMetaService;
import com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceBindingService;
import com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceService;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class PatientDeviceServiceTests extends TestsBase {
    public PatientDeviceServiceTests(
        @Autowired PatientService patientService,
        @Autowired PatientDeviceService patientDeviceService,
        @Autowired DeviceBindingService deviceBindingService,
        @Autowired DeviceService deviceService,
        @Autowired PatientConfig patientConfig,
        @Autowired BedMetaService bedMetaService,
        @Autowired PatientRecordRepository patientRecordRepo,
        @Autowired PatientBedHistoryRepository patientBedHistoryRepo,
        @Autowired PatientDeviceRepository patientDeviceRepo
    ) {
        this.accountId = "admin";
        this.deptId = "10027";
        this.devDeptId1 = "10029";
        this.devDeptId2 = "10030";
        this.devDeptId3 = "10031";
        this.devDeptId4 = "10041";

        this.patientService = patientService;
        this.patientDeviceService = patientDeviceService;
        this.deviceBindingService = deviceBindingService;
        this.deviceService = deviceService;
        this.patientConfig = patientConfig;
        this.bedMetaService = bedMetaService;
        this.patientRecordRepo = patientRecordRepo;
        this.patientBedHistoryRepo = patientBedHistoryRepo;
        this.patientDeviceRepo = patientDeviceRepo;
    }

    @Test
    public void testBedConfigOps() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 添加床位配置
        AddBedConfigReq addBedReq = AddBedConfigReq.newBuilder()
            .setBedConfig(newBedConfigPB(deptId, 1211, BED_TYPE_FIXED)).build();
        String addBedReqJson = ProtoUtils.protoToJson(addBedReq);
        AddBedConfigResp addResp = bedMetaService.addBedConfig(addBedReqJson);
        assertThat(addResp.getRt().getCode()).isEqualTo(0);

        addBedReq = AddBedConfigReq.newBuilder()
            .setBedConfig(newBedConfigPB(deptId, 1212, BED_TYPE_FIXED)).build();
        addBedReqJson = ProtoUtils.protoToJson(addBedReq);
        addResp = bedMetaService.addBedConfig(addBedReqJson);
        assertThat(addResp.getRt().getCode()).isEqualTo(0);

        addBedReq = AddBedConfigReq.newBuilder()
            .setBedConfig(newBedConfigPB(deptId, 1213, BED_TYPE_TEMP)).build();
        addBedReqJson = ProtoUtils.protoToJson(addBedReq);
        addResp = bedMetaService.addBedConfig(addBedReqJson);
        assertThat(addResp.getRt().getCode()).isEqualTo(0);

        GetBedConfigReq getBedReq = GetBedConfigReq.newBuilder().setDeptId(deptId).build();
        String getBedReqJson = ProtoUtils.protoToJson(getBedReq);
        GetBedConfigResp getResp = bedMetaService.getBedConfig(getBedReqJson);
        assertThat(getResp.getRt().getCode()).isEqualTo(0);
        Set<String> hisBedNumbers = new HashSet<>();
        for (BedConfigPB bedConfig : getResp.getBedConfigList()) {
            hisBedNumbers.add(bedConfig.getHisBedNumber());
        }
        assertThat(hisBedNumbers).contains(
            "hisBedNumber1211", "hisBedNumber1212", "hisBedNumber1213"
        );

        // 修改床位配置
        BedConfigPB bedToUpdate = null;
        for (BedConfigPB bedConfig : getResp.getBedConfigList()) {
            if (bedConfig.getHisBedNumber().equals("hisBedNumber1212")) {
                bedToUpdate = bedConfig.toBuilder()
                    .setDisplayBedNumber("displayBedNumber1212_updated")
                    .build();
                break;
            }
        }
        AddBedConfigReq updateBedReq = AddBedConfigReq.newBuilder().setBedConfig(bedToUpdate).build();
        String updateBedReqJson = ProtoUtils.protoToJson(updateBedReq);
        GenericResp updateResp = bedMetaService.updateBedConfig(updateBedReqJson);
        assertThat(updateResp.getRt().getCode()).isEqualTo(0);

        getResp = bedMetaService.getBedConfig(getBedReqJson);
        assertThat(getResp.getRt().getCode()).isEqualTo(0);
        for (BedConfigPB bedConfig : getResp.getBedConfigList()) {
            if (bedConfig.getHisBedNumber().equals("hisBedNumber1212")) {
                assertThat(bedConfig.getDisplayBedNumber()).isEqualTo("displayBedNumber1212_updated");
                break;
            }
        }

        // 删除床位配置
        BedConfigPB bedToDelete = null;
        for (BedConfigPB bedConfig : getResp.getBedConfigList()) {
            if (bedConfig.getHisBedNumber().equals("hisBedNumber1212")) {
                bedToDelete = bedConfig;
                break;
            }
        }
        DeleteBedConfigReq deleteBedReq = DeleteBedConfigReq.newBuilder().setId(bedToDelete.getId()).build();
        String deleteBedReqJson = ProtoUtils.protoToJson(deleteBedReq);
        GenericResp deleteResp = bedMetaService.deleteBedConfig(deleteBedReqJson);
        assertThat(deleteResp.getRt().getCode()).isEqualTo(0);

        getResp = bedMetaService.getBedConfig(getBedReqJson);
        assertThat(getResp.getRt().getCode()).isEqualTo(0);
        hisBedNumbers.clear();
        for (BedConfigPB bedConfig : getResp.getBedConfigList()) {
            hisBedNumbers.add(bedConfig.getHisBedNumber());
        }
        assertThat(hisBedNumbers.contains("hisBedNumber1212")).isFalse();
    }

    @Test
    public void testSwitchBedNormal() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 添加床位配置
        for (int i = 2301; i < 2310; ++i) {
            AddBedConfigReq addBedReq = AddBedConfigReq.newBuilder()
                .setBedConfig(newBedConfigPB(devDeptId4, i, BED_TYPE_FIXED)).build();
            String addBedReqJson = ProtoUtils.protoToJson(addBedReq);
            AddBedConfigResp addResp = bedMetaService.addBedConfig(addBedReqJson);
            assertThat(addResp.getRt().getCode()).isEqualTo(0);
        }

        // 普通换床成功：status=IN_ICU
        PatientRecord patient = PatientTestUtils.newPatientRecord(
            2301L, ADMISSION_STATUS_ADMITTED, devDeptId4);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2025, 8, 15));  // 2025-08-15 8:00 上海时间
        patientConfig.clearDischargeFields(patient);
        patient = patientRecordRepo.save(patient);
        assertThat(patientDeviceService.switchBedImpl(
            patient, "hisBedNumber2302", TimeUtils.getLocalTime(2025, 8, 16), false, "123", "123"
        )).isEqualTo(StatusCode.OK);
        List<PatientBedHistory> bedHistory = patientBedHistoryRepo.findByPatientId(patient.getId());
        assertThat(bedHistory).hasSize(1);
        assertThat(bedHistory.get(0).getHisBedNumber()).isEqualTo("hisBedNumber2301");
        assertThat(bedHistory.get(0).getSwitchTime()).isEqualTo(TimeUtils.getLocalTime(2025, 8, 16));
        assertThat(bedHistory.get(0).getSwitchType()).isEqualTo(0);  // 普通换床
        patient = patientRecordRepo.findById(patient.getId()).orElse(null);
        assertThat(patient).isNotNull();
        assertThat(patient.getHisBedNumber()).isEqualTo("hisBedNumber2302");

        // 普通换床-同床 no-op，不落历史，不改 patient。
        assertThat(patientDeviceService.switchBedImpl(
            patient, "hisBedNumber2302", TimeUtils.getLocalTime(2025, 8, 17), false, "123", "123"
        )).isEqualTo(StatusCode.OK);
        bedHistory = patientBedHistoryRepo.findByPatientId(patient.getId());
        assertThat(bedHistory).hasSize(1);

        // 普通换床-目标床被他人占
        PatientRecord patient2 = PatientTestUtils.newPatientRecord(
            2305L, ADMISSION_STATUS_ADMITTED, devDeptId4);
        patient2.setAdmissionTime(TimeUtils.getLocalTime(2025, 8, 15));  // 2025-08-15 8:00 上海时间
        patientConfig.clearDischargeFields(patient2);
        patient2 = patientRecordRepo.save(patient2);
        assertThat(patientDeviceService.switchBedImpl(
            patient, "hisBedNumber2305", TimeUtils.getLocalTime(2025, 8, 17), false, "123", "123"
        )).isEqualTo(StatusCode.BED_ALREADY_OCCUPIED);

        // 时间回退不合法： 2025-8-16 => 2025-8-15。
        assertThat(patientDeviceService.switchBedImpl(
            patient, "hisBedNumber2303", TimeUtils.getLocalTime(2025, 8, 15), false, "123", "123"
        )).isEqualTo(StatusCode.BED_SWITCH_TIME_OUTDATED);

        // 普通换床-非法状态：status=DISCHARGED → INVALID_ADMISSION_STATUS_FOR_BED_SWITCH
        patient.setAdmissionStatus(PatientEnums.ADMISSION_STATUS_DISCHARGED);
        patient.setDischargeTime(TimeUtils.getLocalTime(2025, 8, 17));
        patient = patientRecordRepo.save(patient);
        assertThat(patientDeviceService.switchBedImpl(
            patient, "hisBedNumber2303", TimeUtils.getLocalTime(2025, 8, 18), false, "123", "123"
        )).isEqualTo(StatusCode.INVALID_ADMISSION_STATUS_FOR_BED_SWITCH);

        // patient重返, 2301->2306
        PatientRecord patient3 = PatientTestUtils.newPatientRecord(
            2306L, ADMISSION_STATUS_ADMITTED, devDeptId4);
        patient3.setHisMrn("mrn2305");
        patient3.setAdmissionTime(TimeUtils.getLocalTime(2025, 8, 18));  // 2025-08-18 8:00 上海时间
        patientConfig.clearDischargeFields(patient3);
        patient3 = patientRecordRepo.save(patient3);

        // 重返 - 未出科病人不能重返
        assertThat(patientDeviceService.switchBedImpl(
            patient2, "hisBedNumber2306", TimeUtils.getLocalTime(2025, 8, 18), true, "123", "123"
        )).isEqualTo(StatusCode.INVALID_ADMISSION_STATUS_FOR_BED_SWITCH);

        // 重返-MRN 不一致
        assertThat(patientDeviceService.switchBedImpl(
            patient, "hisBedNumber2306", TimeUtils.getLocalTime(2025, 8, 18), true, "123", "123"
        )).isEqualTo(StatusCode.BED_SWITCH_READMISSION_MRN_MISMATCH);

        // 重返-时间不合法
        patient3.setHisMrn("mrn2301");
        patient3.setAdmissionTime(TimeUtils.getLocalTime(2025, 8, 17));  // 2025-08-17 8:00 上海时间
        patient3 = patientRecordRepo.save(patient3);
        assertThat(patientDeviceService.switchBedImpl(
            patient, "hisBedNumber2306", TimeUtils.getLocalTime(2025, 8, 17), true, "123", "123"
        )).isEqualTo(StatusCode.BED_SWITCH_READMISSION_END_TIME_INVALID);

        // 重返成功
        patient3.setAdmissionTime(TimeUtils.getLocalTime(2025, 8, 18));
        patient3 = patientRecordRepo.save(patient3);
        assertThat(patientDeviceService.switchBedImpl(
            patient, "hisBedNumber2306", TimeUtils.getLocalTime(2025, 8, 18), true, "123", "123"
        )).isEqualTo(StatusCode.OK);

        bedHistory = patientBedHistoryRepo.findByPatientId(patient.getId())
            .stream().sorted(Comparator.comparing(PatientBedHistory::getSwitchTime)).toList();
        assertThat(bedHistory).hasSize(3);
        assertThat(bedHistory.get(0).getHisBedNumber()).isEqualTo("hisBedNumber2301");
        assertThat(bedHistory.get(0).getSwitchTime()).isEqualTo(TimeUtils.getLocalTime(2025, 8, 16));
        assertThat(bedHistory.get(0).getSwitchType()).isEqualTo(0);  // 普通换床
        assertThat(bedHistory.get(1).getHisBedNumber()).isEqualTo("hisBedNumber2302");
        assertThat(bedHistory.get(1).getSwitchTime()).isEqualTo(TimeUtils.getLocalTime(2025, 8, 17));
        assertThat(bedHistory.get(1).getSwitchType()).isEqualTo(1);  // 重返-出科
        assertThat(bedHistory.get(2).getHisBedNumber()).isEqualTo("hisBedNumber2306");
        assertThat(bedHistory.get(2).getSwitchTime()).isEqualTo(TimeUtils.getLocalTime(2025, 8, 18));
        assertThat(bedHistory.get(2).getSwitchType()).isEqualTo(2);  // 重返-入科
        patient = patientRecordRepo.findById(patient.getId()).orElse(null);
        assertThat(patient).isNotNull();
        assertThat(patient.getHisBedNumber()).isEqualTo("hisBedNumber2306");
        assertThat(patient.getAdmissionStatus()).isEqualTo(PatientEnums.ADMISSION_STATUS_ADMITTED);
    }

    @Test
    public void testDeviceInfoCRUD(@Autowired DeviceDebugger debugger) {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 添加设备信息
        DevicePB devInfoPB1 = newDevInfoPB(devDeptId1, 101, DEVICE_TYPE_MONITOR);
        DevicePB devInfoPB2 = newDevInfoPB(devDeptId1, 102, DEVICE_TYPE_MONITOR);
        devInfoPB2 = devInfoPB2.toBuilder().setDeviceBedNumber("devBedNumber1401").build();  // name to be updated
        DevicePB devInfoPB3 = newDevInfoPB(devDeptId1, 103, DEVICE_TYPE_VENTILATOR);
        DevicePB devInfoPB4 = newDevInfoPB(devDeptId2, 104, DEVICE_TYPE_MONITOR);
        DevicePB devInfoPB5 = newDevInfoPB(devDeptId2, 105, DEVICE_TYPE_MONITOR);  // 待删除

        List<Integer> devIds = new ArrayList<>();
        for (DevicePB devInfo : List.of(
            devInfoPB1, devInfoPB2, devInfoPB3, devInfoPB4, devInfoPB5)) {
            AddDeviceInfoReq addDevReq = AddDeviceInfoReq.newBuilder().setDeviceInfo(devInfo).build();
            String addDevReqJson = ProtoUtils.protoToJson(addDevReq);
            AddDeviceInfoResp addDevResp = deviceService.addDeviceInfo(addDevReqJson);
            assertThat(addDevResp.getRt().getCode()).isEqualTo(0);
            devIds.add(addDevResp.getId());
        }

        // 更新设备信息
        DevicePB devInfoToUpdate = devInfoPB2.toBuilder()
            .setId(devIds.get(1)).setDeviceName("device-name-102-updated").build();
        AddDeviceInfoReq updateDevReq = AddDeviceInfoReq.newBuilder().setDeviceInfo(devInfoToUpdate).build();
        String updateDevReqJson = ProtoUtils.protoToJson(updateDevReq);
        GenericResp updateDevResp = deviceService.updateDeviceInfo(updateDevReqJson);
        assertThat(updateDevResp.getRt().getCode()).isEqualTo(0);

        // 删除设备信息
        DeleteDeviceInfoReq deleteDevReq = DeleteDeviceInfoReq.newBuilder().setId(devIds.get(4)).build();
        String deleteDevReqJson = ProtoUtils.protoToJson(deleteDevReq);
        GenericResp deleteDevResp = deviceService.deleteDeviceInfo(deleteDevReqJson);
        assertThat(deleteDevResp.getRt().getCode()).isEqualTo(0);
        assertThat(debugger.listDevInfos())
            .contains(devInfoPB1.getDeviceName())
            .doesNotContain(devInfoPB5.getDeviceName());

        // 绑定病人设备
        PatientRecord patient = PatientTestUtils.newPatientRecord(
            1401L, ADMISSION_STATUS_ADMITTED, devDeptId1);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2025, 3, 9));  // 2025-03-09 8:00 上海时间
        patient = patientRecordRepo.save(patient);
        BindPatientDeviceReq bindDevReq = BindPatientDeviceReq.newBuilder()
            .setPid(patient.getId())
            .setDeviceId(devIds.get(0))
            .setBindingTimeIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2025, 3, 10), "UTC"))
            .build();
        String bindDevReqJson = ProtoUtils.protoToJson(bindDevReq);
        BindPatientDeviceResp bindDevResp = deviceBindingService.bindPatientDevice(bindDevReqJson);
        assertThat(bindDevResp.getRt().getCode()).isEqualTo(0);

        // 绑定病人设备 - 病人已绑定过该类型设备
        bindDevReq = bindDevReq.toBuilder()
            .setDeviceId(devIds.get(1))
            .setBindingTimeIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2025, 3, 11), "UTC"))
            .build();
        bindDevReqJson = ProtoUtils.protoToJson(bindDevReq);
        bindDevResp = deviceBindingService.bindPatientDevice(bindDevReqJson);
        assertThat(bindDevResp.getRt().getCode()).isEqualTo(StatusCode.PATIENT_ALREADY_BINDED_TO_SAME_TYPE_DEVICE.ordinal());

        // 查询设备信息 - 按部门查
        GetDeviceInfoReq getDevReq = GetDeviceInfoReq.newBuilder().setDeptId(devDeptId1).build();
        String getDevReqJson = ProtoUtils.protoToJson(getDevReq);
        GetDeviceInfoResp getDevResp = deviceBindingService.getDeviceInfo(getDevReqJson);
        assertThat(getDevResp.getRt().getCode()).isEqualTo(0);
        Map<String/*devName*/, Boolean/*isBinding*/> deviceNames = new HashMap<>();
        for (DeviceWithBindingsPB inlineDev : getDevResp.getInlineDevList()) {
            deviceNames.put(inlineDev.getDevice().getDeviceName(), inlineDev.getIsBinding());
        }
        // 验证增，改
        assertThat(deviceNames).containsEntry("device-name-101", true);
        assertThat(deviceNames).containsEntry("device-name-102-updated", false);
        assertThat(deviceNames).containsEntry("device-name-103", false);

        getDevReq = getDevReq.toBuilder().setDeptId(devDeptId2).build();
        getDevReqJson = ProtoUtils.protoToJson(getDevReq);
        getDevResp = deviceBindingService.getDeviceInfo(getDevReqJson);
        assertThat(getDevResp.getRt().getCode()).isEqualTo(0);
        assertThat(getDevResp.getInlineDevList()).hasSize(1);
        // 验证删
        assertThat(getDevResp.getInlineDevList().get(0).getDevice().getDeviceName()).isEqualTo("device-name-104");

        // 查询设备信息 - 按类型
        getDevReq = GetDeviceInfoReq.newBuilder()
            .setDeptId(devDeptId1).setDeviceType(DEVICE_TYPE_MONITOR).build();
        getDevReqJson = ProtoUtils.protoToJson(getDevReq);
        getDevResp = deviceBindingService.getDeviceInfo(getDevReqJson);
        assertThat(getDevResp.getRt().getCode()).isEqualTo(0);
        deviceNames.clear();
        for (DeviceWithBindingsPB inlineDev : getDevResp.getInlineDevList()) {
            deviceNames.put(inlineDev.getDevice().getDeviceName(), inlineDev.getIsBinding());
        }
        assertThat(deviceNames.size()).isEqualTo(2);
        assertThat(deviceNames).containsKey("device-name-101");
        assertThat(deviceNames).containsKey("device-name-102-updated");
        // assertThat(deviceNames).contains("device-name-101", "device-name-102-updated");

        // 查询设备信息 - 按床号查
        getDevReq = GetDeviceInfoReq.newBuilder().setDeviceBedNum("devBedNumber1401").build();
        getDevReqJson = ProtoUtils.protoToJson(getDevReq);
        getDevResp = deviceBindingService.getDeviceInfo(getDevReqJson);
        assertThat(getDevResp.getRt().getCode()).isEqualTo(0);
        assertThat(getDevResp.getInlineDevList()).hasSize(1);
        assertThat(getDevResp.getInlineDevList().get(0).getDevice().getDeviceName()).isEqualTo("device-name-102-updated");
    }

    @Test
    @WithMockUser(username = "admin", roles = "1")
    public void softDeletedDeviceAllowsHistoryAndUnbinding(@Autowired JdbcTemplate jdbc) {
        DevicePB device = newDevInfoPB(devDeptId3, 29411, DEVICE_TYPE_MONITOR)
            .toBuilder().setDeviceBedNumber("原固定床位").build();
        AddDeviceInfoReq add = AddDeviceInfoReq.newBuilder().setDeviceInfo(device).build();
        AddDeviceInfoResp added = deviceService.addDeviceInfo(ProtoUtils.protoToJson(add));
        assertThat(added.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        int deviceId = added.getId();
        PatientRecord patient = patientRecordRepo.save(PatientTestUtils.newPatientRecord(
            29411L, ADMISSION_STATUS_ADMITTED, devDeptId3));
        try {
            String delete = ProtoUtils.protoToJson(
                DeleteDeviceInfoReq.newBuilder().setId(deviceId).build());
            assertThat(deviceService.deleteDeviceInfo(delete).getRt().getCode())
                .isEqualTo(StatusCode.OK_VALUE);
            assertThat(deviceService.deleteDeviceInfo(delete).getRt().getCode())
                .isEqualTo(StatusCode.OK_VALUE);

            AddDeviceInfoReq update = add.toBuilder().setDeviceInfo(
                device.toBuilder().setId(deviceId).setDeviceName("更名后的监护仪")).build();
            assertThat(deviceService.updateDeviceInfo(ProtoUtils.protoToJson(update))
                .getRt().getCode()).isEqualTo(StatusCode.DEVICE_INFO_NOT_EXISTS_VALUE);
            GetDeviceInfoReq search = GetDeviceInfoReq.newBuilder().setDeviceId(deviceId).build();
            GetDeviceInfoResp results = deviceBindingService.getDeviceInfo(
                ProtoUtils.protoToJson(search));
            assertThat(results.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
            assertThat(results.getInlineDevList()).isEmpty();

            BindPatientDeviceReq bind = BindPatientDeviceReq.newBuilder()
                .setPid(patient.getId()).setDeviceId(deviceId)
                .setBindingTimeIso8601("2025-03-09T09:00+08:00").build();
            assertThat(deviceBindingService.bindPatientDevice(ProtoUtils.protoToJson(bind))
                .getRt().getCode()).isEqualTo(StatusCode.DEVICE_INFO_NOT_EXISTS_VALUE);

            // 模拟老数据中软删除设备仍有一条未结束的绑定。
            PatientDevice legacy = patientDeviceRepo.save(DeviceTestUtils.newPatientDevice(
                patient.getId(), deviceId, TimeUtils.getLocalTime(2025, 3, 9, 1, 0), null));
            assertThat(deviceBindingService.getDeviceInfo(ProtoUtils.protoToJson(search))
                .getInlineDevList()).isEmpty();
            String unbindingTime = "2025-03-09T10:00+08:00";
            BindPatientDeviceReq unbind = bind.toBuilder().clearBindingTimeIso8601()
                .setUnbindingTimeIso8601(unbindingTime).build();
            assertThat(deviceBindingService.bindPatientDevice(ProtoUtils.protoToJson(unbind))
                .getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
            assertThat(patientDeviceRepo.findById(legacy.getId()).orElseThrow()
                .getUnbindingTime()).isEqualTo(TimeUtils.fromIso8601String(unbindingTime, "UTC"));

            GetPatientDeviceBindingHistoryReq historyReq =
                GetPatientDeviceBindingHistoryReq.newBuilder().setPid(patient.getId()).build();
            GetPatientDeviceBindingHistoryResp history = deviceBindingService
                .getPatientDeviceBindingHistory(ProtoUtils.protoToJson(historyReq));
            assertThat(history.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
            assertThat(history.getBindingList()).singleElement().satisfies(entry -> {
                assertThat(entry.getDevice().getDeviceName()).isEqualTo(device.getDeviceName());
                assertThat(entry.getIsBinding()).isFalse();
                assertThat(entry.getBindingList()).singleElement().satisfies(binding -> {
                    assertThat(binding.getId()).isEqualTo(legacy.getId());
                    assertThat(binding.getUnbindingTimeIso8601()).isEqualTo(unbindingTime);
                });
            });
        } finally {
            jdbc.update("DELETE FROM patient_devices WHERE device_id = ?", deviceId);
            jdbc.update("DELETE FROM device_infos WHERE id = ?", deviceId);
            patientRecordRepo.deleteById(patient.getId());
        }
    }

    @Test
    public void testDeviceBinding() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 添加设备信息
        DevicePB devInfoPB1 = newDevInfoPB(devDeptId3, 201, DEVICE_TYPE_MONITOR);
        DevicePB devInfoPB2 = newDevInfoPB(devDeptId3, 202, DEVICE_TYPE_VENTILATOR);
        List<Integer> devIds = new ArrayList<>();
        for (DevicePB devInfo : List.of(devInfoPB1, devInfoPB2)) {
            AddDeviceInfoReq addDevReq = AddDeviceInfoReq.newBuilder().setDeviceInfo(devInfo).build();
            String addDevReqJson = ProtoUtils.protoToJson(addDevReq);
            AddDeviceInfoResp addDevResp = deviceService.addDeviceInfo(addDevReqJson);
            assertThat(addDevResp.getRt().getCode()).isEqualTo(0);
            devIds.add(addDevResp.getId());
        }

        // 新增病人
        PatientRecord patient1 = PatientTestUtils.newPatientRecord(
            1501L, ADMISSION_STATUS_ADMITTED, devDeptId3);
        patient1.setAdmissionTime(TimeUtils.getLocalTime(2025, 3, 9));  // 2025-03-09 8:00 上海时间
        patient1 = patientRecordRepo.save(patient1);
        PatientRecord patient2 = PatientTestUtils.newPatientRecord(
            1502L, ADMISSION_STATUS_ADMITTED, devDeptId3);
        patient2.setAdmissionTime(TimeUtils.getLocalTime(2025, 3, 9));  // 2025-03-09 8:00 上海时间
        patient2 = patientRecordRepo.save(patient2);

        // 设备201
        // 病人1绑定&解绑设备201，2025-03-09 9:00-10:00
        // 病人2绑定设备201，2025-03-09 11:00
        // 病人1强行绑定设备201，2025-03-09 12:00
        BindPatientDeviceReq bindDevReq = BindPatientDeviceReq.newBuilder()
            .setPid(patient1.getId())
            .setDeviceId(devIds.get(0))
            .setBindingTimeIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2025, 3, 9, 1, 0), "UTC"))
            .build();
        String bindDevReqJson = ProtoUtils.protoToJson(bindDevReq);
        BindPatientDeviceResp bindDevResp = deviceBindingService.bindPatientDevice(bindDevReqJson);
        assertThat(bindDevResp.getRt().getCode()).isEqualTo(0);

        bindDevReq = BindPatientDeviceReq.newBuilder()
            .setPid(patient1.getId())
            .setDeviceId(devIds.get(0))
            .setUnbindingTimeIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2025, 3, 9, 2, 0), "UTC"))
            .build();
        bindDevReqJson = ProtoUtils.protoToJson(bindDevReq);
        bindDevResp = deviceBindingService.bindPatientDevice(bindDevReqJson);
        assertThat(bindDevResp.getRt().getCode()).isEqualTo(0);

        bindDevReq = BindPatientDeviceReq.newBuilder()
            .setPid(patient2.getId())
            .setDeviceId(devIds.get(0))
            .setBindingTimeIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2025, 3, 9, 3, 0), "UTC"))
            .build();
        bindDevReqJson = ProtoUtils.protoToJson(bindDevReq);
        bindDevResp = deviceBindingService.bindPatientDevice(bindDevReqJson);
        assertThat(bindDevResp.getRt().getCode()).isEqualTo(0);

        bindDevReq = BindPatientDeviceReq.newBuilder()
            .setPid(patient1.getId())
            .setDeviceId(devIds.get(0))
            .setBindingTimeIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2025, 3, 9, 4, 0), "UTC"))
            .build();
        bindDevReqJson = ProtoUtils.protoToJson(bindDevReq);
        bindDevResp = deviceBindingService.bindPatientDevice(bindDevReqJson);
        assertThat(bindDevResp.getRt().getCode()).isEqualTo(0);

        // 病人1绑定设备202，2025-03-09 11:00
        bindDevReq = BindPatientDeviceReq.newBuilder()
            .setPid(patient1.getId())
            .setDeviceId(devIds.get(1))
            .setBindingTimeIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2025, 3, 9, 3, 0), "UTC"))
            .setUnbindingTimeIso8601("")
            .build();
        bindDevReqJson = ProtoUtils.protoToJson(bindDevReq);
        bindDevResp = deviceBindingService.bindPatientDevice(bindDevReqJson);
        assertThat(bindDevResp.getRt().getCode()).isEqualTo(0);

        // 查询设备的绑定历史
        GetDeviceBindingHistoryReq getDevReq = GetDeviceBindingHistoryReq.newBuilder()
            .setDeviceId(devIds.get(0))
            .build();
        String getDevReqJson = ProtoUtils.protoToJson(getDevReq);
        GetDeviceBindingHistoryResp getDevResp = deviceBindingService.getDeviceBindingHistory(getDevReqJson);
        assertThat(getDevResp.getRt().getCode()).isEqualTo(0);
        assertThat(getDevResp.getBindingList()).hasSize(3);
        assertThat(getDevResp.getBindingList().get(0).getPatientId()).isEqualTo(patient1.getId());
        assertThat(getDevResp.getBindingList().get(0).getBindingTimeIso8601()).isEqualTo("2025-03-09T09:00+08:00");
        assertThat(getDevResp.getBindingList().get(0).getUnbindingTimeIso8601()).isEqualTo("2025-03-09T10:00+08:00");
        assertThat(getDevResp.getBindingList().get(1).getPatientId()).isEqualTo(patient2.getId());
        assertThat(getDevResp.getBindingList().get(1).getBindingTimeIso8601()).isEqualTo("2025-03-09T11:00+08:00");
        assertThat(getDevResp.getBindingList().get(1).getUnbindingTimeIso8601()).isEqualTo("2025-03-09T12:00+08:00");
        assertThat(getDevResp.getBindingList().get(2).getPatientId()).isEqualTo(patient1.getId());
        assertThat(getDevResp.getBindingList().get(2).getBindingTimeIso8601()).isEqualTo("2025-03-09T12:00+08:00");
        assertThat(getDevResp.getBindingList().get(2).getUnbindingTimeIso8601()).isEqualTo("");

        // 查询病人的绑定历史
        GetPatientDeviceBindingHistoryReq getPatReq = GetPatientDeviceBindingHistoryReq.newBuilder()
            .setPid(patient1.getId())
            .build();
        String getPatReqJson = ProtoUtils.protoToJson(getPatReq);
        GetPatientDeviceBindingHistoryResp getPatResp = deviceBindingService.getPatientDeviceBindingHistory(getPatReqJson);
        assertThat(getPatResp.getRt().getCode()).isEqualTo(0);
        assertThat(getPatResp.getBindingList()).hasSize(2);
        assertThat(getPatResp.getBindingList().get(0).getDevice().getDeviceName()).isEqualTo("device-name-201");
        assertThat(getPatResp.getBindingList().get(0).getBindingList()).hasSize(2);
        assertThat(getPatResp.getBindingList().get(0).getBindingList().get(0).getBindingTimeIso8601()).isEqualTo("2025-03-09T09:00+08:00");
        assertThat(getPatResp.getBindingList().get(0).getBindingList().get(0).getUnbindingTimeIso8601()).isEqualTo("2025-03-09T10:00+08:00");
        assertThat(getPatResp.getBindingList().get(0).getBindingList().get(1).getBindingTimeIso8601()).isEqualTo("2025-03-09T12:00+08:00");
        assertThat(getPatResp.getBindingList().get(0).getBindingList().get(1).getUnbindingTimeIso8601()).isEqualTo("");

        assertThat(getPatResp.getBindingList().get(1).getDevice().getDeviceName()).isEqualTo("device-name-202");
        assertThat(getPatResp.getBindingList().get(1).getBindingList()).hasSize(1);
        assertThat(getPatResp.getBindingList().get(1).getBindingList().get(0).getBindingTimeIso8601()).isEqualTo("2025-03-09T11:00+08:00");
        assertThat(getPatResp.getBindingList().get(1).getBindingList().get(0).getUnbindingTimeIso8601()).isEqualTo("");

        // 病人2已有另一台监护仪，转绑失败时原设备仍应归病人1使用。
        AddDeviceInfoReq addMonitor = AddDeviceInfoReq.newBuilder()
            .setDeviceInfo(newDevInfoPB(devDeptId3, 203, DEVICE_TYPE_MONITOR)).build();
        AddDeviceInfoResp addedMonitor =
            deviceService.addDeviceInfo(ProtoUtils.protoToJson(addMonitor));
        assertThat(addedMonitor.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        PatientDevice otherMonitorBinding = patientDeviceRepo.save(DeviceTestUtils.newPatientDevice(
            patient2.getId(), addedMonitor.getId(), TimeUtils.getLocalTime(2025, 3, 9, 4, 30),
            null));
        try {
            BindPatientDeviceReq transfer = BindPatientDeviceReq.newBuilder()
                .setPid(patient2.getId()).setDeviceId(devIds.get(0))
                .setBindingTimeIso8601("2025-03-09T13:00+08:00").build();
            BindPatientDeviceResp rejected =
                deviceBindingService.bindPatientDevice(ProtoUtils.protoToJson(transfer));
            assertThat(rejected.getRt().getCode())
                .isEqualTo(StatusCode.PATIENT_ALREADY_BINDED_TO_SAME_TYPE_DEVICE_VALUE);
            assertThat(patientDeviceRepo.findByDeviceIdAndUnbindingTimeIsNullAndIsDeletedFalse(
                devIds.get(0))).extracting(PatientDevice::getPatientId)
                .containsExactly(patient1.getId());
        } finally {
            patientDeviceRepo.deleteById(otherMonitorBinding.getId());
            deviceService.deleteDeviceInfo(ProtoUtils.protoToJson(
                DeleteDeviceInfoReq.newBuilder().setId(addedMonitor.getId()).build()));
        }
    }

    @Test
    @Transactional
    public void bindingQueriesAllowPatientsSharingABed(
        @Autowired DeviceInfoRepository deviceRepo
    ) {
        String sharedDeptId = "shared-bed-query-dept";
        String sharedHisBedNumber = "shared-bed";
        PatientRecord inIcuPatient = PatientTestUtils.newPatientRecord(
            990001L, ADMISSION_STATUS_ADMITTED, sharedDeptId);
        inIcuPatient.setHisBedNumber(sharedHisBedNumber);
        inIcuPatient = patientRecordRepo.save(inIcuPatient);
        PatientRecord pendingPatient = PatientTestUtils.newPatientRecord(
            990002L, ADMISSION_STATUS_PRE_ADMISSION, sharedDeptId);
        pendingPatient.setHisBedNumber(sharedHisBedNumber);
        pendingPatient = patientRecordRepo.save(pendingPatient);

        DeviceInfo firstDevice = deviceRepo.save(DeviceTestUtils.newDeviceInfo(
            sharedDeptId, "", DEVICE_TYPE_MONITOR, "共床患者设备甲"));
        DeviceInfo secondDevice = deviceRepo.save(DeviceTestUtils.newDeviceInfo(
            sharedDeptId, "", DEVICE_TYPE_MONITOR, "共床患者设备乙"));
        patientDeviceRepo.save(DeviceTestUtils.newPatientDevice(
            pendingPatient.getId(), firstDevice.getId(),
            TimeUtils.getLocalTime(2025, 3, 9, 0, 0),
            TimeUtils.getLocalTime(2025, 3, 9, 1, 0)));
        patientDeviceRepo.save(DeviceTestUtils.newPatientDevice(
            inIcuPatient.getId(), firstDevice.getId(),
            TimeUtils.getLocalTime(2025, 3, 9, 1, 0), null));
        patientDeviceRepo.save(DeviceTestUtils.newPatientDevice(
            pendingPatient.getId(), secondDevice.getId(),
            TimeUtils.getLocalTime(2025, 3, 9, 1, 0), null));

        GetDeviceInfoReq deviceQuery = GetDeviceInfoReq.newBuilder()
            .setDeptId(sharedDeptId).build();
        GetDeviceInfoResp devices = deviceBindingService.getDeviceInfo(
            ProtoUtils.protoToJson(deviceQuery));
        assertThat(devices.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(devices.getInlineDevList()).hasSize(2).allSatisfy(device -> {
            assertThat(device.getIsBinding()).isTrue();
            assertThat(device.getBindingList()).singleElement().satisfies(binding ->
                assertThat(binding.getDisplayBedNumber()).isEqualTo(sharedHisBedNumber));
        });
        GetDeviceBindingHistoryReq historyQuery = GetDeviceBindingHistoryReq.newBuilder()
            .setDeviceId(firstDevice.getId()).build();
        GetDeviceBindingHistoryResp history = deviceBindingService.getDeviceBindingHistory(
            ProtoUtils.protoToJson(historyQuery));
        assertThat(history.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(history.getIsBinding()).isTrue();
        assertThat(history.getBindingList()).extracting(DeviceBindingRecordPB::getPatientId)
            .containsExactly(pendingPatient.getId(), inIcuPatient.getId());
    }

    @Test
    public void testDeviceInfoUpstreamRules() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        final String upstreamRuleDeptId = "10099";

        DevicePB directCentral = newDevInfoPB(
            upstreamRuleDeptId, 301, DEVICE_TYPE_CENTRAL_STATION).toBuilder()
            .setSourceMode(1)
            .setSourceTopology(2)
            .setUpstreamDeviceId(0)
            .build();
        AddDeviceInfoResp addResp = deviceService.addDeviceInfo(
            ProtoUtils.protoToJson(AddDeviceInfoReq.newBuilder().setDeviceInfo(directCentral).build())
        );
        assertThat(addResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        int directCentralId = addResp.getId();

        DevicePB indirectCentral = newDevInfoPB(
            upstreamRuleDeptId, 302, DEVICE_TYPE_CENTRAL_STATION).toBuilder()
            .setSourceMode(3)
            .setSourceTopology(2)
            .setUpstreamDeviceId(directCentralId)
            .build();
        addResp = deviceService.addDeviceInfo(
            ProtoUtils.protoToJson(AddDeviceInfoReq.newBuilder().setDeviceInfo(indirectCentral).build())
        );
        assertThat(addResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        int indirectCentralId = addResp.getId();

        DevicePB downstreamDevice = newDevInfoPB(upstreamRuleDeptId, 303, DEVICE_TYPE_MONITOR)
            .toBuilder()
            .setSourceMode(3)
            .setSourceTopology(2)
            .setUpstreamDeviceId(directCentralId)
            .build();
        addResp = deviceService.addDeviceInfo(
            ProtoUtils.protoToJson(AddDeviceInfoReq.newBuilder().setDeviceInfo(downstreamDevice).build())
        );
        assertThat(addResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        DevicePB directWithUpstream = newDevInfoPB(upstreamRuleDeptId, 304, DEVICE_TYPE_MONITOR)
            .toBuilder()
            .setSourceMode(1)
            .setSourceTopology(1)
            .setUpstreamDeviceId(directCentralId)
            .build();
        addResp = deviceService.addDeviceInfo(
            ProtoUtils.protoToJson(AddDeviceInfoReq.newBuilder().setDeviceInfo(directWithUpstream).build())
        );
        assertThat(addResp.getRt().getCode()).isEqualTo(StatusCode.DEVICE_SOURCE_CONFIG_INVALID.ordinal());

        DevicePB upstreamNotSource = newDevInfoPB(upstreamRuleDeptId, 305, DEVICE_TYPE_MONITOR)
            .toBuilder()
            .setSourceMode(3)
            .setSourceTopology(2)
            .setUpstreamDeviceId(indirectCentralId)
            .build();
        addResp = deviceService.addDeviceInfo(
            ProtoUtils.protoToJson(AddDeviceInfoReq.newBuilder().setDeviceInfo(upstreamNotSource).build())
        );
        assertThat(addResp.getRt().getCode()).isEqualTo(StatusCode.DEVICE_SOURCE_CONFIG_INVALID.ordinal());
    }

    private DevicePB newDevInfoPB(String deptId, Integer devId, Integer devType) {
        return DevicePB.newBuilder()
            .setDepartmentId(deptId)
            .setDeviceSn("device-sn-" + devId)
            .setDeviceBedNumber("")
            .setDeviceType(devType)
            .setDeviceName("device-name-" + devId)
            .setDeviceIp("device-ip-" + devId)
            .setDevicePort("device-port-" + devId)
            .setDeviceDriverCode("device-driver-code-" + devId)
            .setSourceMode(1)
            .setSourceTopology(1)
            .setUpstreamDeviceId(0)
            .setPdsIpSeq(0)
            .build();
    }

    private BedConfigPB newBedConfigPB(String deptId, Integer hisPid, Integer bedType) {
        return BedConfigPB.newBuilder()
            .setDepartmentId(deptId)
            .setHisBedNumber("hisBedNumber" + hisPid)
            .setDeviceBedNumber("devBedNumber" + hisPid)
            .setDisplayBedNumber("displayBedNumber" + hisPid)
            .setBedType(bedType)
            .build();
    }

    private final String accountId;
    private final String deptId;
    private final String devDeptId1;
    private final String devDeptId2;
    private final String devDeptId3;
    private final String devDeptId4;

    private final PatientService patientService;
    private final PatientDeviceService patientDeviceService;
    private final DeviceBindingService deviceBindingService;
    private final DeviceService deviceService;
    private final PatientConfig patientConfig;
    private final BedMetaService bedMetaService;
    private final PatientRecordRepository patientRecordRepo;
    private final PatientBedHistoryRepository patientBedHistoryRepo;
    private final PatientDeviceRepository patientDeviceRepo;
}
