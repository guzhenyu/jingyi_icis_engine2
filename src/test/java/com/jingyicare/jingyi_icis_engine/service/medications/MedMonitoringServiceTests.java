package com.jingyicare.jingyi_icis_engine.service.medications;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;

import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.service.medications.MedMonitoringService.FluidIntakeData;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class MedMonitoringServiceTests extends TestsBase {
    public MedMonitoringServiceTests(
        @Autowired ConfigProtoService protoService
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
    }

    @Test
    public void testFluidIntakeData() {
        FluidIntakeData data = new FluidIntakeData(1000.0, null, "UTC");
        assertThat(data.totalMl).isEqualTo(1000.0);
        assertThat(data.leftMl).isEqualTo(1000.0);
        assertThat(data.intakeMap).isEmpty();
        assertThat(data.mlPerHour).isEqualTo(0.0);
        assertThat(data.finishTime).isNull();
        assertThat(data.estimatedFinishTime).isNull();
        assertThat(data.calcTimeUtc).isNotNull();
        assertThat(data.ZONE_ID).isEqualTo("UTC");

        // 测试 isLeftZero
        data.leftMl = 0.0;
        assertThat(data.isLeftZero()).isTrue();
        data.leftMl = Consts.EPS + 0.1;
        assertThat(data.isLeftZero()).isFalse();

        // 测试 addIntakeStats (非持续用药)
        data.leftMl = 1000.0;
        LocalDateTime time1 = TimeUtils.getLocalTime(2025, 9, 1, 10, 30);
        data.addIntakeStats(time1, 300.0/*intakeMl*/, 0L/*intakeMinutes*/);
        assertThat(data.leftMl).isEqualTo(700.0);
        assertThat(data.intakeMap).hasSize(1);
        assertThat(data.intakeMap.get(TimeUtils.getLocalTime(2025, 9, 1, 10, 0))).isEqualTo(300.0);
        data.addIntakeStats(time1.plusHours(1), 700.0, 0L);
        assertThat(data.isLeftZero()).isTrue();
        assertThat(data.finishTime).isEqualTo(time1.plusHours(1));
        // 测试 addIntakeStats (非持续用药) / 验证 composeFluidIntake
        FluidIntakePB pb = data.composeFluidIntake();
        assertThat(pb.getTotalMl()).isEqualTo(1000.0, offset(Consts.EPS));
        assertThat(pb.getIntakeRecordList()).hasSize(2);
        FluidIntakePB.IntakeRecord record1 = pb.getIntakeRecordList().get(0);
        assertThat(record1.getMl()).isEqualTo(300.0, offset(Consts.EPS));
        assertThat(record1.getMlStr()).isEqualTo("300.00");
        assertThat(record1.getTimeIso8601()).isEqualTo("2025-09-01T10:00Z");
        FluidIntakePB.IntakeRecord record2 = pb.getIntakeRecordList().get(1);
        assertThat(record2.getMl()).isEqualTo(700.0, offset(Consts.EPS));
        assertThat(record2.getMlStr()).isEqualTo("700.00");
        assertThat(record2.getTimeIso8601()).isEqualTo("2025-09-01T11:00Z");
        FluidIntakePB.IntakeRecord remainingIntake = pb.getRemainingIntake();
        assertThat(remainingIntake.getMl()).isEqualTo(0.0, offset(Consts.EPS));
        assertThat(remainingIntake.getMlStr()).isEqualTo("0.00");
        assertThat(remainingIntake.getTimeIso8601()).isEqualTo("2025-09-01T11:30Z");
        assertThat(StrUtils.isBlank(pb.getEstimatedCompletionTimeIso8601())).isTrue();

        // 测试 addIntakeStats (持续用药)
        data = new FluidIntakeData(950.0, TimeUtils.getLocalTime(2025, 9, 2, 10, 30)/*calcTime*/, "UTC");
        data.mlPerHour = 100.0; // 100 ml/hour
        time1 = TimeUtils.getLocalTime(2025, 9, 1, 10, 0);
        for (int i = 0; i < 10; i++) {
            boolean isFinished = data.addIntakeStats(time1.plusHours(i), 0.0, 60L); // 每小时服用 100 ml
            if (i < 9) {
                assertThat(isFinished).isFalse();
                assertThat(data.finishTime).isNull();
                assertThat(data.leftMl).isEqualTo(950.0 - (i + 1) * 100.0, offset(Consts.EPS));
                assertThat(data.isLeftZero()).isFalse();
            } else {
                assertThat(isFinished).isTrue();
                assertThat(data.finishTime).isEqualTo(time1.plusHours(9).plusMinutes(30)); // 2025-09-01 19:30
                assertThat(data.leftMl).isEqualTo(0, offset(Consts.EPS));
                assertThat(data.isLeftZero()).isTrue();
            }
            assertThat(data.intakeMap).hasSize(i + 1);
        }
        for (int i = 0; i < 10; i++) {
            if (i < 9) {
                assertThat(data.intakeMap.get(TimeUtils.getLocalTime(2025, 9, 1, 10 + i, 0))).isEqualTo(100.0, offset(Consts.EPS));
            } else {
                assertThat(data.intakeMap.get(TimeUtils.getLocalTime(2025, 9, 1, 19, 0))).isEqualTo(50.0, offset(Consts.EPS));
            }
        }
        // 测试 addIntakeStats (持续用药) / 验证 composeFluidIntake
        pb = data.composeFluidIntake();
        assertThat(pb.getTotalMl()).isEqualTo(950.0, offset(Consts.EPS));
        assertThat(pb.getIntakeRecordList()).hasSize(10);
        for (int i = 0; i < 10; i++) {
            FluidIntakePB.IntakeRecord record = pb.getIntakeRecordList().get(i);
            if (i < 9) {
                assertThat(record.getMl()).isEqualTo(100.0, offset(Consts.EPS));
                assertThat(record.getMlStr()).isEqualTo("100.00");
                // assertThat(remainingIntake.getTimeIso8601()).isEqualTo("2025-09-01T20:30:00Z");
                String expectedTime = TimeUtils.toIso8601String(TimeUtils.getLocalTime(2025, 9, 1, 10 + i, 0), "UTC");
                assertThat(record.getTimeIso8601()).isEqualTo(expectedTime);
            } else {
                assertThat(record.getMl()).isEqualTo(50.0, offset(Consts.EPS));
                assertThat(record.getMlStr()).isEqualTo("50.00");
                String expectedTime = TimeUtils.toIso8601String(TimeUtils.getLocalTime(2025, 9, 1, 19, 0), "UTC");
                assertThat(record.getTimeIso8601()).isEqualTo(expectedTime);
            }
        }
        remainingIntake = pb.getRemainingIntake();
        assertThat(remainingIntake.getMl()).isEqualTo(0.0, offset(Consts.EPS));
        assertThat(remainingIntake.getMlStr()).isEqualTo("0.00");
        assertThat(remainingIntake.getTimeIso8601()).isEqualTo("2025-09-01T19:30Z");
        assertThat(StrUtils.isBlank(pb.getEstimatedCompletionTimeIso8601())).isTrue();

        // 测试 complete
        data = new FluidIntakeData(1000.0, TimeUtils.getLocalTime(2025, 9, 2, 10, 30), "UTC");
        LocalDateTime completeTime = TimeUtils.getLocalTime(2025, 9, 1, 12, 15);
        data.complete(completeTime, 500.0);
        assertThat(data.leftMl).isEqualTo(500.0, offset(Consts.EPS));
        assertThat(data.finishTime).isEqualTo(completeTime);
        assertThat(data.mlPerHour).isEqualTo(0.0, offset(Consts.EPS));
        // 测试 complete / 验证 composeFluidIntake
        pb = data.composeFluidIntake();
        assertThat(pb.getTotalMl()).isEqualTo(1000.0, offset(Consts.EPS));
        assertThat(pb.getIntakeRecordList()).hasSize(1);
        FluidIntakePB.IntakeRecord record = pb.getIntakeRecordList().get(0);
        assertThat(record.getMl()).isEqualTo(500.0, offset(Consts.EPS));
        assertThat(record.getMlStr()).isEqualTo("500.00");
        assertThat(record.getTimeIso8601()).isEqualTo("2025-09-01T12:00Z");
        remainingIntake = pb.getRemainingIntake();
        assertThat(remainingIntake.getMl()).isEqualTo(500.0, offset(Consts.EPS));
        assertThat(remainingIntake.getMlStr()).isEqualTo("500.00");
        assertThat(remainingIntake.getTimeIso8601()).isEqualTo("2025-09-01T12:15Z");
        assertThat(StrUtils.isBlank(pb.getEstimatedCompletionTimeIso8601())).isTrue();

        // 测试 pause1
        data = new FluidIntakeData(1000.0, TimeUtils.getLocalTime(2025, 9, 2, 10, 30), "UTC");
        data.mlPerHour = 100.0;
        LocalDateTime pauseTime = TimeUtils.getLocalTime(2025, 9, 1, 12, 15);
        data.pause(pauseTime, true);
        assertThat(data.finishTime).isEqualTo(pauseTime);  // **（和下面pause2的区别）
        assertThat(data.mlPerHour).isEqualTo(0.0, offset(Consts.EPS));
        // 测试 pause1 / 验证 composeFluidIntake
        pb = data.composeFluidIntake();
        assertThat(pb.getTotalMl()).isEqualTo(1000.0, offset(Consts.EPS));
        assertThat(pb.getIntakeRecordList()).hasSize(0);
        remainingIntake = pb.getRemainingIntake();
        assertThat(remainingIntake.getMl()).isEqualTo(1000.0, offset(Consts.EPS));
        assertThat(remainingIntake.getMlStr()).isEqualTo("1000.00");
        assertThat(remainingIntake.getTimeIso8601()).isEqualTo("2025-09-01T12:15Z");  // pauseTime
        assertThat(StrUtils.isBlank(pb.getEstimatedCompletionTimeIso8601())).isTrue();


        // 测试 pause2
        data = new FluidIntakeData(1000.0, TimeUtils.getLocalTime(2025, 9, 2, 10, 30), "UTC");
        data.mlPerHour = 100.0;
        data.pause(pauseTime, false);
        assertThat(data.finishTime).isNull();  // **（和上面pause1的区别）
        assertThat(data.mlPerHour).isEqualTo(0.0, offset(Consts.EPS));
        // 测试 pause2 / 验证 composeFluidIntake
        pb = data.composeFluidIntake();
        assertThat(pb.getTotalMl()).isEqualTo(1000.0, offset(Consts.EPS));
        assertThat(pb.getIntakeRecordList()).hasSize(0);
        remainingIntake = pb.getRemainingIntake();
        assertThat(remainingIntake.getMl()).isEqualTo(1000.0, offset(Consts.EPS));
        assertThat(remainingIntake.getMlStr()).isEqualTo("1000.00");
        assertThat(remainingIntake.getTimeIso8601()).isEqualTo("2025-09-02T10:30Z");  // calcTime
        assertThat(StrUtils.isBlank(pb.getEstimatedCompletionTimeIso8601())).isTrue();
    }

    private final String ZONE_ID;
}
