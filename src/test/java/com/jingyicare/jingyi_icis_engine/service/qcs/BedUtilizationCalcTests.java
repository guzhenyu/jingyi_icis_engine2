package com.jingyicare.jingyi_icis_engine.service.qcs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisQualityControl.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Slf4j
public class BedUtilizationCalcTests extends TestsBase {
    public BedUtilizationCalcTests(
        @Autowired ConfigProtoService protoService,
        @Autowired BedUtilizationCalc bedUtilizationCalc,
        @Autowired PatientRecordRepository patientRecordRepo,
        @Autowired BedConfigRepository bedConfigRepo,
        @Autowired PatientBedHistoryRepository patientBedHistoryRepo,
        @Autowired BedCountRepository bedCountRepo
    ) {
        this.deptId = "10037";
        DeviceEnums devEnums = protoService.getConfig().getDevice().getEnums();
        this.SWITCH_TYPE_NORMAL = devEnums.getSwitchTypeNormal().getId();
        this.SWITCH_TYPE_READMISSION_DISCHARGE = devEnums.getSwitchTypeReadmissionDischarge().getId();
        this.SWITCH_TYPE_READMISSION_ADMIT = devEnums.getSwitchTypeReadmissionAdmit().getId();

        this.bedUtilizationCalc = bedUtilizationCalc;

        this.patientRecordRepo = patientRecordRepo;
        this.bedConfigRepo = bedConfigRepo;
        this.patientBedHistoryRepo = patientBedHistoryRepo;
        this.bedCountRepo = bedCountRepo;
    }

    @Test
    public void testCalculateAvailableBeds() {
        // 基准床位数据(上海时间)
        // 2025年2月10日 10:00:00 生效，10张床位
        // 2025年6月20日 16:00:00 生效，20张床位
        // 2025年7月4日 14:00:00 生效，30张床位
        BedCount bedCount = BedCount.builder()
            .deptId(deptId)
            .bedCount(10)
            .effectiveTime(TimeUtils.getLocalTime(2025, 2, 10, 2, 0))
            .isDeleted(false)
            .build();
        bedCountRepo.save(bedCount);

        bedCount.setId(null);
        bedCount.setBedCount(20);
        bedCount.setEffectiveTime(TimeUtils.getLocalTime(2025, 6, 20, 8, 0));
        bedCountRepo.save(bedCount);

        bedCount.setId(null);
        bedCount.setBedCount(30);
        bedCount.setEffectiveTime(TimeUtils.getLocalTime(2025, 7, 4, 6, 0));
        bedCountRepo.save(bedCount);

        // 测试时间范围1（上海时间）：2025年2月1日到2025年7月10日（不含7月10日）
        LocalDateTime startUtc = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 2, 1), "Asia/Shanghai");
        LocalDateTime endUtc = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 7, 10), "Asia/Shanghai");
        Map<LocalDateTime, List<QcICUBedAvailablePB>> bedAvailableMap = bedUtilizationCalc
            .calcAvailableBeds(deptId, startUtc, endUtc, "Asia/Shanghai");
        assertThat(bedAvailableMap.size()).isEqualTo(6);

        List<QcICUBedAvailablePB> bedAvailableList = bedAvailableMap.get(
            TimeUtils.getUtcFromLocalDateTime(TimeUtils.getLocalTime(2025, 2, 1), "Asia/Shanghai")
        );
        assertThat(bedAvailableList).isNotNull();
        assertThat(bedAvailableList.size()).isEqualTo(1);
        assertThat(bedAvailableList.get(0).getStartDateIso8601()).isEqualTo("2025-02-10T00:00+08:00");
        assertThat(bedAvailableList.get(0).getEndDateIso8601()).isEqualTo("2025-03-01T00:00+08:00");
        assertThat(bedAvailableList.get(0).getBedDays()).isEqualTo(190);

        bedAvailableList = bedAvailableMap.get(
            TimeUtils.getUtcFromLocalDateTime(TimeUtils.getLocalTime(2025, 3, 1), "Asia/Shanghai")
        );
        assertThat(bedAvailableList).isNotNull();
        assertThat(bedAvailableList.size()).isEqualTo(1);
        assertThat(bedAvailableList.get(0).getStartDateIso8601()).isEqualTo("2025-03-01T00:00+08:00");
        assertThat(bedAvailableList.get(0).getEndDateIso8601()).isEqualTo("2025-04-01T00:00+08:00");
        assertThat(bedAvailableList.get(0).getBedDays()).isEqualTo(310);

        bedAvailableList = bedAvailableMap.get(
            TimeUtils.getUtcFromLocalDateTime(TimeUtils.getLocalTime(2025, 4, 1), "Asia/Shanghai")
        );
        assertThat(bedAvailableList).isNotNull();
        assertThat(bedAvailableList.size()).isEqualTo(1);
        assertThat(bedAvailableList.get(0).getStartDateIso8601()).isEqualTo("2025-04-01T00:00+08:00");
        assertThat(bedAvailableList.get(0).getEndDateIso8601()).isEqualTo("2025-05-01T00:00+08:00");
        assertThat(bedAvailableList.get(0).getBedDays()).isEqualTo(300);

        bedAvailableList = bedAvailableMap.get(
            TimeUtils.getUtcFromLocalDateTime(TimeUtils.getLocalTime(2025, 5, 1), "Asia/Shanghai")
        );
        assertThat(bedAvailableList).isNotNull();
        assertThat(bedAvailableList.size()).isEqualTo(1);
        assertThat(bedAvailableList.get(0).getStartDateIso8601()).isEqualTo("2025-05-01T00:00+08:00");
        assertThat(bedAvailableList.get(0).getEndDateIso8601()).isEqualTo("2025-06-01T00:00+08:00");
        assertThat(bedAvailableList.get(0).getBedDays()).isEqualTo(310);

        bedAvailableList = bedAvailableMap.get(
            TimeUtils.getUtcFromLocalDateTime(TimeUtils.getLocalTime(2025, 6, 1), "Asia/Shanghai")
        );
        assertThat(bedAvailableList).isNotNull();
        assertThat(bedAvailableList.size()).isEqualTo(2);
        assertThat(bedAvailableList.get(0).getStartDateIso8601()).isEqualTo("2025-06-01T00:00+08:00");
        assertThat(bedAvailableList.get(0).getEndDateIso8601()).isEqualTo("2025-06-20T00:00+08:00");
        assertThat(bedAvailableList.get(0).getBedDays()).isEqualTo(190);
        assertThat(bedAvailableList.get(1).getStartDateIso8601()).isEqualTo("2025-06-20T00:00+08:00");
        assertThat(bedAvailableList.get(1).getEndDateIso8601()).isEqualTo("2025-07-01T00:00+08:00");
        assertThat(bedAvailableList.get(1).getBedDays()).isEqualTo(220);

        bedAvailableList = bedAvailableMap.get(
            TimeUtils.getUtcFromLocalDateTime(TimeUtils.getLocalTime(2025, 7, 1), "Asia/Shanghai")
        );
        assertThat(bedAvailableList).isNotNull();
        assertThat(bedAvailableList.size()).isEqualTo(2);
        assertThat(bedAvailableList.get(0).getStartDateIso8601()).isEqualTo("2025-07-01T00:00+08:00");
        assertThat(bedAvailableList.get(0).getEndDateIso8601()).isEqualTo("2025-07-04T00:00+08:00");
        assertThat(bedAvailableList.get(0).getBedDays()).isEqualTo(60);
        assertThat(bedAvailableList.get(1).getStartDateIso8601()).isEqualTo("2025-07-04T00:00+08:00");
        assertThat(bedAvailableList.get(1).getEndDateIso8601()).isEqualTo("2025-07-10T00:00+08:00");
        assertThat(bedAvailableList.get(1).getBedDays()).isEqualTo(180);

        // 测试时间范围2-边界测试（上海时间）：2025年2月1日到2025年2月10日（不含2月10日）
        startUtc = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 2, 1), "Asia/Shanghai");
        endUtc = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 2, 10), "Asia/Shanghai");
        bedAvailableMap = bedUtilizationCalc
            .calcAvailableBeds(deptId, startUtc, endUtc, "Asia/Shanghai");
        assertThat(bedAvailableMap.size()).isEqualTo(0);

        // 测试时间范围3-边界测试（上海时间）：2025年2月10日到2025年3月1日（不含3月1日）
        startUtc = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 2, 10), "Asia/Shanghai");
        endUtc = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 3, 1), "Asia/Shanghai");
        bedAvailableMap = bedUtilizationCalc
            .calcAvailableBeds(deptId, startUtc, endUtc, "Asia/Shanghai");
        assertThat(bedAvailableMap.size()).isEqualTo(1);
        bedAvailableList = bedAvailableMap.get(
            TimeUtils.getUtcFromLocalDateTime(TimeUtils.getLocalTime(2025, 2, 1), "Asia/Shanghai")
        );
        assertThat(bedAvailableList).isNotNull();
        assertThat(bedAvailableList.size()).isEqualTo(1);
        assertThat(bedAvailableList.get(0).getStartDateIso8601()).isEqualTo("2025-02-10T00:00+08:00");
        assertThat(bedAvailableList.get(0).getEndDateIso8601()).isEqualTo("2025-03-01T00:00+08:00");
        assertThat(bedAvailableList.get(0).getBedDays()).isEqualTo(190);
    }

    @Test
    public void testCalculateBedUsage() {
        // 统计区间：2025.2.1 - 2025.5.1（不含5.1）
        String zoneId = "Asia/Shanghai";
        LocalDateTime startUtc = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 2, 1), zoneId);
        LocalDateTime endUtc = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 5, 1), zoneId);

        // 床位基本信息：
        //     hisBedNumber1901, dispBedNumber1901
        //     hisBedNumber1902, dispBedNumber1902
        //     hisBedNumber1903, dispBedNumber1903
        BedConfig bedConfig = BedConfig.builder()
            .departmentId(deptId)
            .hisBedNumber("hisBedNumber1901")
            .deviceBedNumber("devBedNumber1901")
            .displayBedNumber("dispBedNumber1901")
            .bedType(1/*固定*/)
            .isDeleted(false)
            .build();
        bedConfigRepo.save(bedConfig);

        bedConfig.setId(null);
        bedConfig.setHisBedNumber("hisBedNumber1902");
        bedConfig.setDeviceBedNumber("devBedNumber1902");
        bedConfig.setDisplayBedNumber("dispBedNumber1902");
        bedConfigRepo.save(bedConfig);

        bedConfig.setId(null);
        bedConfig.setHisBedNumber("hisBedNumber1903");
        bedConfig.setDeviceBedNumber("devBedNumber1903");
        bedConfig.setDisplayBedNumber("dispBedNumber1903");
        bedConfigRepo.save(bedConfig);

        // 床位使用情况
        // hisPatient1901
        //     hisBedNumber1901: 2025.2.10 8:00 - 2025.4.6 2:00 (出科)
        // hisPatient1902
        //     hisBedNumber1902: 2025.2.8 2:00 - 2025.2.12 8:00 (出科)
        // hisPatient1903
        //     hisBedNumber1902: 2025.2.12 10:00 - 2025.2.12 12:00 (出科)
        // hisPatient1904
        //     hisBedNumber1902: 2025.2.12 14:00 - 2025.2.15 16:00
        //     hisBedNumber1903: 2025.2.15 16:00 - (在科)
        PatientRecord patient1 = PatientTestUtils.newPatientRecord(1901L, 3/*出科*/, deptId);
        patient1.setAdmissionTime(TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 2, 10, 8, 0), zoneId)
        );
        patient1.setDischargeTime(TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 4, 6, 2, 0), zoneId)
        );

        PatientRecord patient2 = PatientTestUtils.newPatientRecord(1902L, 3/*出科*/, deptId);
        patient2.setAdmissionTime(TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 2, 8, 2, 0), zoneId)
        );
        patient2.setDischargeTime(TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 2, 12, 8, 0), zoneId)
        );

        PatientRecord patient3 = PatientTestUtils.newPatientRecord(1903L, 3/*出科*/, deptId);
        patient3.setHisBedNumber("hisBedNumber1902");
        patient3.setAdmissionTime(TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 2, 12, 10, 0), zoneId)
        );
        patient3.setDischargeTime(TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 2, 12, 12, 0), zoneId)
        );

        PatientRecord patient4 = PatientTestUtils.newPatientRecord(1904L, 1/*在科*/, deptId);
        patient4.setHisBedNumber("hisBedNumber1903");
        patient4.setAdmissionTime(TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 2, 12, 14, 0), zoneId)
        );
        patient4.setDischargeTime(null); // 在科
        patient4 = patientRecordRepo.save(patient4);

        PatientBedHistory history = PatientBedHistory.builder()
            .patientId(patient4.getId())
            .hisBedNumber("hisBedNumber1902")
            .deviceBedNumber("devBedNumber1902")
            .displayBedNumber("dispBedNumber1902")
            .switchTime(TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2025, 2, 15, 16, 0), zoneId)
            )
            .switchType(SWITCH_TYPE_NORMAL)
            .build();
        patientBedHistoryRepo.save(history);

        // 统计床位使用情况
        List<PatientRecord> patients = Arrays.asList(patient1, patient2, patient3, patient4);
        Map<LocalDateTime/*月份*/, List<QcICUBedUsagePB>> bedUsageMap = bedUtilizationCalc
            .calcBedUsage(deptId, patients, startUtc, endUtc, zoneId);
        assertThat(bedUsageMap.size()).isEqualTo(3);

        // 验证床位使用情况 - 2025年2月1日到2025年3月1日（不含3月1日）
        List<QcICUBedUsagePB> usageList = bedUsageMap.get(
            TimeUtils.getUtcFromLocalDateTime(TimeUtils.getLocalTime(2025, 2, 1), zoneId)
        );
        assertThat(usageList).isNotNull();
        assertThat(usageList.size()).isEqualTo(5);
        assertThat(usageList.get(0).getDisplayBedNumber()).isEqualTo("dispBedNumber1901");
        assertThat(usageList.get(0).getPatientIcuName()).isEqualTo("icuName1901");
        assertThat(usageList.get(0).getStartDateIso8601()).isEqualTo("2025-02-10T08:00+08:00");
        assertThat(usageList.get(0).getEndDateIso8601()).isEqualTo("2025-03-01T00:00+08:00");
        assertThat(usageList.get(0).getBedDays()).isEqualTo(19);

        assertThat(usageList.get(1).getDisplayBedNumber()).isEqualTo("dispBedNumber1902");
        assertThat(usageList.get(1).getPatientIcuName()).isEqualTo("icuName1902");
        assertThat(usageList.get(1).getStartDateIso8601()).isEqualTo("2025-02-08T02:00+08:00");
        assertThat(usageList.get(1).getEndDateIso8601()).isEqualTo("2025-02-12T08:00+08:00");
        assertThat(usageList.get(1).getBedDays()).isEqualTo(4.3333335f);

        assertThat(usageList.get(2).getDisplayBedNumber()).isEqualTo("dispBedNumber1902");
        assertThat(usageList.get(2).getPatientIcuName()).isEqualTo("icuName1903");
        assertThat(usageList.get(2).getStartDateIso8601()).isEqualTo("2025-02-12T10:00+08:00");
        assertThat(usageList.get(2).getEndDateIso8601()).isEqualTo("2025-02-12T12:00+08:00");
        assertThat(usageList.get(2).getBedDays()).isEqualTo(0.33333334f);

        assertThat(usageList.get(3).getDisplayBedNumber()).isEqualTo("dispBedNumber1902");
        assertThat(usageList.get(3).getPatientIcuName()).isEqualTo("icuName1904");
        assertThat(usageList.get(3).getStartDateIso8601()).isEqualTo("2025-02-12T14:00+08:00");
        assertThat(usageList.get(3).getEndDateIso8601()).isEqualTo("2025-02-15T16:00+08:00");
        assertThat(usageList.get(3).getBedDays()).isEqualTo(3.3333333f);

        assertThat(usageList.get(4).getDisplayBedNumber()).isEqualTo("dispBedNumber1903");
        assertThat(usageList.get(4).getPatientIcuName()).isEqualTo("icuName1904");
        assertThat(usageList.get(4).getStartDateIso8601()).isEqualTo("2025-02-15T16:00+08:00");
        assertThat(usageList.get(4).getEndDateIso8601()).isEqualTo("2025-03-01T00:00+08:00");
        assertThat(usageList.get(4).getBedDays()).isEqualTo(14.0f);

        // 验证床位使用情况 - 2025年3月1日到2025年4月1日（不含4月1日）
        usageList = bedUsageMap.get(
            TimeUtils.getUtcFromLocalDateTime(TimeUtils.getLocalTime(2025, 3, 1), zoneId)
        );
        assertThat(usageList).isNotNull();
        assertThat(usageList.size()).isEqualTo(2);
        assertThat(usageList.get(0).getDisplayBedNumber()).isEqualTo("dispBedNumber1901");
        assertThat(usageList.get(0).getPatientIcuName()).isEqualTo("icuName1901");
        assertThat(usageList.get(0).getStartDateIso8601()).isEqualTo("2025-03-01T00:00+08:00");
        assertThat(usageList.get(0).getEndDateIso8601()).isEqualTo("2025-04-01T00:00+08:00");
        assertThat(usageList.get(0).getBedDays()).isEqualTo(31.0f);

        assertThat(usageList.get(1).getDisplayBedNumber()).isEqualTo("dispBedNumber1903");
        assertThat(usageList.get(1).getPatientIcuName()).isEqualTo("icuName1904");
        assertThat(usageList.get(1).getStartDateIso8601()).isEqualTo("2025-03-01T00:00+08:00");
        assertThat(usageList.get(1).getEndDateIso8601()).isEqualTo("2025-04-01T00:00+08:00");
        assertThat(usageList.get(1).getBedDays()).isEqualTo(31.0f);

        // 验证床位使用情况 - 2025年4月1日到2025年5月1日（不含5月1日）
        usageList = bedUsageMap.get(
            TimeUtils.getUtcFromLocalDateTime(TimeUtils.getLocalTime(2025, 4, 1), zoneId)
        );
        assertThat(usageList).isNotNull();
        assertThat(usageList.size()).isEqualTo(2);
        assertThat(usageList.get(0).getDisplayBedNumber()).isEqualTo("dispBedNumber1901");
        assertThat(usageList.get(0).getPatientIcuName()).isEqualTo("icuName1901");
        assertThat(usageList.get(0).getStartDateIso8601()).isEqualTo("2025-04-01T00:00+08:00");
        assertThat(usageList.get(0).getEndDateIso8601()).isEqualTo("2025-04-06T02:00+08:00");
        assertThat(usageList.get(0).getBedDays()).isEqualTo(6.0f);

        assertThat(usageList.get(1).getDisplayBedNumber()).isEqualTo("dispBedNumber1903");
        assertThat(usageList.get(1).getPatientIcuName()).isEqualTo("icuName1904");
        assertThat(usageList.get(1).getStartDateIso8601()).isEqualTo("2025-04-01T00:00+08:00");
        assertThat(usageList.get(1).getEndDateIso8601()).isEqualTo("2025-05-01T00:00+08:00");
        assertThat(usageList.get(1).getBedDays()).isEqualTo(30.0f);
    }

    private final String deptId;

    private final Integer SWITCH_TYPE_NORMAL;
    private final Integer SWITCH_TYPE_READMISSION_DISCHARGE;
    private final Integer SWITCH_TYPE_READMISSION_ADMIT;

    private final BedUtilizationCalc bedUtilizationCalc;

    private final PatientRecordRepository patientRecordRepo;
    private final BedConfigRepository bedConfigRepo;
    private final PatientBedHistoryRepository patientBedHistoryRepo;
    private final BedCountRepository bedCountRepo;
}