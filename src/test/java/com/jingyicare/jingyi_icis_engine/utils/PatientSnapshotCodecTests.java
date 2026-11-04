package com.jingyicare.jingyi_icis_engine.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.NursingRecordPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSnapshot.PatientSnapshotPB;

class PatientSnapshotCodecTests {
    @Test
    void shouldRoundTripSnapshotAndPreserveUnknownModuleFields() {
        UnknownFieldSet unknownFields = UnknownFieldSet.newBuilder().addField(100,
            UnknownFieldSet.Field.newBuilder()
                .addLengthDelimited(ByteString.copyFromUtf8("未来模块内容")).build()).build();
        PatientSnapshotPB snapshot = PatientSnapshotPB.newBuilder()
            .setPid(19L).setStatisticsDate("2026-11-04")
            .setMonitoringStartIso8601("2026-11-04T00:00:00Z")
            .setMonitoringEndIso8601("2026-11-05T00:00:00Z")
            .addNursingRecords(NursingRecordPB.newBuilder()
                .setId(123L).setPatientId(19L).setContent("护理记录"))
            .setNursingRecordLmaIso8601("2026-11-04T01:02:03Z")
            .setNursingRecordSaIso8601("2026-11-04T02:03:04Z")
            .setUnknownFields(unknownFields).build();

        PatientSnapshotPB decoded = ProtoUtils.decodePatientSnapshot(
            ProtoUtils.encodePatientSnapshot(snapshot));

        assertThat(decoded).isEqualTo(snapshot);
        assertThat(decoded.toBuilder().clearNursingRecords().build().getUnknownFields())
            .isEqualTo(unknownFields);
    }

    @Test
    void shouldReturnNullForBlankOrCorruptPayload() {
        assertThat(ProtoUtils.decodePatientSnapshot(null)).isNull();
        assertThat(ProtoUtils.decodePatientSnapshot("")).isNull();
        assertThat(ProtoUtils.decodePatientSnapshot(" \n\t")).isNull();
        assertThat(ProtoUtils.decodePatientSnapshot("invalid-base64!")).isNull();
        assertThat(ProtoUtils.decodePatientSnapshot(
            Base64.getEncoder().encodeToString(new byte[] {0}))).isNull();
    }
}
