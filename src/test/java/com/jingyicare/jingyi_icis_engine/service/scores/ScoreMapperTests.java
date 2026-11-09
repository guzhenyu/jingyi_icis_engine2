package com.jingyicare.jingyi_icis_engine.service.scores;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.jingyicare.jingyi_icis_engine.entity.scores.PatientScore;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.ScoreGroupMeasurePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.ScoreGroupPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.ScoreItemPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.GenericValuePB;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class ScoreMapperTests {
    @Test
    void shouldStripOnlyRecordMetadataBeforePersistingScorePayload() {
        ScoreGroupPB original = embeddedMetadata();
        ScoreGroupPB stored = ProtoUtils.decodeScoreGroupPB(
            ProtoUtils.encodeScoreGroupPB(original));

        assertThat(stored).isEqualTo(original.toBuilder()
            .clearId().clearPid().clearRecordedBy().clearRecordedByAccountName()
            .clearRecordedAtIso8601().clearNote().build());
        assertThat(stored.getItemList()).isEqualTo(original.getItemList());
        assertThat(stored.getMeasureList()).isEqualTo(original.getMeasureList());
        assertThat(stored.getGroupScoreText()).isEqualTo("15");
        assertThat(original.getId()).isEqualTo(999L);
        assertThat(original.getPid()).isEqualTo(9999L);
        assertThat(original.getNote()).isEqualTo("payload-note");
    }

    @Test
    void shouldOverrideEmbeddedMetadataWithRawColumnsAndUseConfiguredZone() {
        PatientScore record = record(embeddedMetadata());
        record.setScoreStr(" 15分 ");
        record.setModifiedBy(" 123 ");
        record.setModifiedByAccountName(" 原始姓名 ");
        record.setNote(" 原始备注\n");

        ScoreGroupPB mapped = ScoreMapper.toProto(record, "Asia/Shanghai");

        assertThat(mapped.getId()).isEqualTo(42L);
        assertThat(mapped.getPid()).isEqualTo(1234L);
        assertThat(mapped.getRecordedBy()).isEqualTo(" 123 ");
        assertThat(mapped.getRecordedByAccountName()).isEqualTo(" 原始姓名 ");
        assertThat(mapped.getRecordedAtIso8601()).isEqualTo("2026-11-03T09:02+08:00");
        assertThat(mapped.getNote()).isEqualTo(" 原始备注\n");
        assertThat(mapped.getGroupScoreText()).isEqualTo(" 15分 ");
        assertThat(mapped.getGroupScore()).isEqualTo(embeddedMetadata().getGroupScore());
        assertThat(ProtoUtils.decodeScoreGroupPB(record.getScore())).isEqualTo(embeddedMetadata());
    }

    @Test
    void shouldClearNullColumnsWithoutFallingBackFromNameToAccount() {
        PatientScore record = record(embeddedMetadata());
        record.setPid(null);
        record.setModifiedBy("123");
        assertThat(record.getModifiedByAccountName()).isEqualTo("123");

        ScoreGroupPB mapped = ScoreMapper.toProto(record, "UTC");
        assertThat(mapped.getPid()).isZero();
        assertThat(mapped.getRecordedBy()).isEqualTo("123");
        assertThat(mapped.getRecordedByAccountName()).isEmpty();
        assertThat(mapped.getNote()).isEmpty();

        record.setModifiedBy(null);
        assertThat(ScoreMapper.toProto(record, "UTC").getRecordedBy()).isEmpty();
    }

    @Test
    void shouldUseStoredTextThenPayloadTextWithoutReformattingEither() {
        PatientScore record = record(embeddedMetadata().toBuilder()
            .setGroupScoreText(" 旧版15 ").build());
        record.setScoreStr(" \t ");
        assertThat(ScoreMapper.toProto(record, "UTC").getGroupScoreText())
            .isEqualTo(" 旧版15 ");
        record.setScoreStr(null);
        assertThat(ScoreMapper.toProto(record, "UTC").getGroupScoreText())
            .isEqualTo(" 旧版15 ");

        record.setScore(rawEncode(embeddedMetadata().toBuilder()
            .setGroupScoreText(" \n ").build()));
        assertThat(ScoreMapper.toProto(record, "UTC").getGroupScoreText()).isEmpty();

        record.setScoreStr("15分");
        assertThat(ScoreMapper.toProto(record, "UTC").getGroupScoreText()).isEqualTo("15分");
    }

    @Test
    void shouldUseProvidedZoneAndMinuteFormatAndHandleMissingTime() {
        PatientScore record = record(embeddedMetadata());
        assertThat(ScoreMapper.toProto(record, "UTC").getRecordedAtIso8601())
            .isEqualTo("2026-11-03T01:02Z");
        assertThat(ScoreMapper.toProto(record, "Asia/Shanghai").getRecordedAtIso8601())
            .isEqualTo("2026-11-03T09:02+08:00");

        record.setEffectiveTime(LocalDateTime.of(2026, 11, 3, 23, 59, 59, 999999000));
        assertThat(ScoreMapper.toProto(record, "Asia/Shanghai").getRecordedAtIso8601())
            .isEqualTo("2026-11-04T07:59+08:00");
        record.setEffectiveTime(null);
        assertThat(ScoreMapper.toProto(record, "Asia/Shanghai").getRecordedAtIso8601())
            .isEmpty();
    }

    private PatientScore record(ScoreGroupPB payload) {
        return PatientScore.builder()
            .id(42L).pid(1234L).scoreGroupCode("gcs")
            .score(rawEncode(payload)).scoreStr("15分")
            .effectiveTime(LocalDateTime.of(2026, 11, 3, 1, 2, 3, 123456000))
            .build();
    }

    private ScoreGroupPB embeddedMetadata() {
        return ScoreGroupPB.newBuilder()
            .setCode("gcs")
            .addItem(ScoreItemPB.newBuilder().setCode("eye").addScoreOptionCode("open"))
            .setGroupScore(GenericValuePB.newBuilder().setInt32Val(15))
            .setGroupScoreText("15")
            .addMeasure(ScoreGroupMeasurePB.newBuilder().setName("护理措施").setApply(true))
            .setId(999L).setPid(9999L).setRecordedBy("payload-account")
            .setRecordedByAccountName("payload-name")
            .setRecordedAtIso8601("2000-01-01T00:00:00Z").setNote("payload-note")
            .build();
    }

    private String rawEncode(ScoreGroupPB payload) {
        return Base64.getEncoder().encodeToString(payload.toByteArray());
    }
}
