package com.jingyicare.jingyi_icis_engine.repository.nursingrecords;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.NursingRecord;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;

@Transactional
class NursingRecordJdbcTests extends TestsBase {
    @Test
    void emptyIdsReturnNoRecords() {
        assertThat(NursingRecordJdbc.findActiveRecords(jdbcTemplate, List.of())).isEmpty();
    }

    @Test
    void returnsUniqueActiveRecordsOrderedById() {
        NursingRecord first = records.saveAndFlush(record(TIME).build());
        NursingRecord second = records.saveAndFlush(record(TIME.minusHours(1)).build());
        NursingRecord deleted = records.saveAndFlush(
            record(TIME.plusHours(1)).isDeleted(true).build());
        records.saveAndFlush(record(TIME.plusHours(2)).build());

        List<NursingRecord> result = NursingRecordJdbc.findActiveRecords(jdbcTemplate,
            List.of(second.getId(), Long.MAX_VALUE, deleted.getId(), first.getId(),
                second.getId()));

        assertThat(result).extracting(NursingRecord::getId)
            .containsExactly(first.getId(), second.getId());
    }

    @Test
    void mapsEveryColumnAndPreservesUtcMicroseconds() {
        jdbcTemplate.execute("SET LOCAL TIME ZONE 'Asia/Shanghai'");
        NursingRecord expected = records.saveAndFlush(record(TIME)
            .deletedBy("deleting-user")
            .deletedAt(TIME.plusMinutes(1))
            .source("template_id=7&params=护理记录")
            .patientCriticalLisHandlingId(7)
            .reviewedBy("reviewing-user")
            .reviewedByAccountName("审核人")
            .reviewedAt(TIME.plusMinutes(2))
            .modifiedBy("modifying-user")
            .modifiedByAccountName("修改人")
            .modifiedAt(TIME.plusMinutes(3))
            .createdBy("creating-user")
            .createdByAccountName("创建人")
            .createdAt(TIME.minusMinutes(4))
            .build());

        List<NursingRecord> result = NursingRecordJdbc.findActiveRecords(
            jdbcTemplate, List.of(expected.getId()));

        assertThat(result).singleElement().usingRecursiveComparison().isEqualTo(expected);
        assertThat(result.getFirst().getEffectiveTime().getNano()).isEqualTo(123456000);
    }

    @Test
    void preservesNullOptionalColumns() {
        NursingRecord expected = records.saveAndFlush(record(TIME).build());

        List<NursingRecord> result = NursingRecordJdbc.findActiveRecords(
            jdbcTemplate, List.of(expected.getId()));

        assertThat(result).singleElement().usingRecursiveComparison().isEqualTo(expected);
        assertThat(result.getFirst().getPatientCriticalLisHandlingId()).isNull();
    }

    @Test
    void bypassesJpaCacheAndReadsUncommittedChangesInCurrentTransaction() {
        NursingRecord inserted = records.saveAndFlush(record(TIME).build());
        entityManager.clear();
        NursingRecord cached = entityManager.find(NursingRecord.class, inserted.getId());
        LocalDateTime changedTime = TIME.plusHours(1);
        jdbcTemplate.update("""
            UPDATE nursing_records SET content = ?, effective_time = ? WHERE id = ?
            """, "事务内更新", changedTime, inserted.getId());

        List<NursingRecord> result = NursingRecordJdbc.findActiveRecords(
            jdbcTemplate, List.of(inserted.getId()));

        assertThat(result).hasSize(1);
        NursingRecord fresh = result.getFirst();
        assertThat(fresh.getContent()).isEqualTo("事务内更新");
        assertThat(fresh.getEffectiveTime()).isEqualTo(changedTime);
        assertThat(entityManager.contains(fresh)).isFalse();
        assertThat(entityManager.find(NursingRecord.class, inserted.getId())).isSameAs(cached);
        assertThat(cached.getContent()).isEqualTo("原始护理记录");
        assertThat(cached.getEffectiveTime()).isEqualTo(TIME);
    }

    private NursingRecord.NursingRecordBuilder record(LocalDateTime time) {
        return NursingRecord.builder()
            .patientId(PID)
            .content("原始护理记录")
            .effectiveTime(time)
            .isDeleted(false);
    }

    private static final long PID = 9300041L;
    private static final LocalDateTime TIME =
        LocalDateTime.of(2026, 11, 3, 0, 1, 2, 123456000);

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private NursingRecordRepository records;
    @Autowired
    private EntityManager entityManager;
}
