package com.jingyicare.jingyi_icis_engine.service.nursingrecords;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.NursingRecord;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.NursingRecordPB;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.NursingRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Transactional
class NursingRecordReaderTests extends TestsBase {
    @Test
    void shouldReadHalfOpenIntervalInStableOrderAndExcludeDeletedAndOtherPatients() {
        NursingRecord later = records.save(record(START.plusHours(1)).build());
        NursingRecord first = records.save(record(START).build());
        NursingRecord sameTime = records.save(record(START)
            .patientCriticalLisHandlingId(31).build());
        records.saveAll(List.of(
            record(START.minusSeconds(1)).build(),
            record(END).build(),
            record(START.plusMinutes(1)).isDeleted(true).build(),
            record(START).patientId(PID + 1).build()));
        flushAndClear();

        assertThat(reader.getRecords(PID, START, END))
            .extracting(NursingRecordPB::getId)
            .containsExactly(first.getId(), sameTime.getId(), later.getId());
        assertThat(reader.getRecords(PID, START, START)).isEmpty();
    }

    @Test
    void shouldPreserveMetadataTimeZoneNullDefaultsAndLegacyAccountNames() {
        LocalDateTime effectiveTime = START.plusSeconds(59).plusNanos(123456000);
        NursingRecord full = records.save(record(effectiveTime)
            .content("完整护理记录").source("template_id=7").patientCriticalLisHandlingId(7)
            .reviewedBy("reviewer").reviewedByAccountName("审核人")
            .reviewedAt(effectiveTime.plusMinutes(1))
            .modifiedBy("modifier").modifiedByAccountName("修改人")
            .modifiedAt(effectiveTime.plusMinutes(2))
            .createdBy("creator").createdByAccountName("创建人")
            .createdAt(effectiveTime.minusMinutes(1)).build());
        NursingRecord nullable = records.save(record(START.plusHours(1))
            .content("旧记录").reviewedBy("legacy-reviewer")
            .modifiedBy("legacy-modifier").createdBy("legacy-creator").build());
        flushAndClear();
        String zoneId = protoService.getConfig().getZoneId();

        assertThat(reader.getRecords(PID, START, END)).containsExactly(
            NursingRecordPB.newBuilder().setId(full.getId()).setPatientId(PID)
                .setContent("完整护理记录").setSource("template_id=7")
                .setPatientCriticalLisHandlingId(7)
                .setEffectiveTimeIso8601(TimeUtils.toIso8601String(effectiveTime, zoneId))
                .setReviewedBy("reviewer").setReviewedByAccountName("审核人")
                .setReviewedAtIso8601(
                    TimeUtils.toIso8601String(effectiveTime.plusMinutes(1), zoneId))
                .setModifiedBy("modifier").setModifiedByAccountName("修改人")
                .setModifiedAtIso8601(
                    TimeUtils.toIso8601String(effectiveTime.plusMinutes(2), zoneId))
                .setCreatedBy("creator").setCreatedByAccountName("创建人")
                .setCreatedAtIso8601(
                    TimeUtils.toIso8601String(effectiveTime.minusMinutes(1), zoneId))
                .build(),
            NursingRecordPB.newBuilder().setId(nullable.getId()).setPatientId(PID)
                .setContent("旧记录")
                .setEffectiveTimeIso8601(TimeUtils.toIso8601String(START.plusHours(1), zoneId))
                .setReviewedBy("legacy-reviewer").setReviewedByAccountName("legacy-reviewer")
                .setModifiedBy("legacy-modifier").setModifiedByAccountName("legacy-modifier")
                .setCreatedBy("legacy-creator").setCreatedByAccountName("legacy-creator")
                .build());
    }

    private NursingRecord.NursingRecordBuilder record(LocalDateTime effectiveTime) {
        return NursingRecord.builder().patientId(PID).content("护理记录")
            .effectiveTime(effectiveTime).isDeleted(false);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Autowired private NursingRecordReader reader;
    @Autowired private NursingRecordRepository records;
    @Autowired private ConfigProtoService protoService;
    @Autowired private EntityManager entityManager;

    private static final long PID = 9910401L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 3, 0, 0);
    private static final LocalDateTime END = START.plusDays(1);
}
