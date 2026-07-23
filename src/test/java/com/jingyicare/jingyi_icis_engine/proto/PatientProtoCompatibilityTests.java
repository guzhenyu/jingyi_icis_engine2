package com.jingyicare.jingyi_icis_engine.proto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;

import com.google.protobuf.CodedOutputStream;
import com.jingyicare.jingyi_icis_databridge.grpc.HisPatientPB;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.PatientInfoPB;

class PatientProtoCompatibilityTests {
    @Test
    void shouldKeepPatientFieldNumbersWhileRenamingFields() {
        assertFieldNumber(HisPatientPB.getDescriptor(), "from_dept_name", 41);
        assertFieldNumber(HisPatientPB.getDescriptor(), "dept_admission_time_iso8601", 43);
        assertFieldNumber(HisPatientPB.getDescriptor(), "to_dept_id", 51);
        assertFieldNumber(HisPatientPB.getDescriptor(), "to_dept_name", 52);
        assertFieldNumber(HisPatientPB.getDescriptor(), "from_dept_id", 57);
        assertThat(HisPatientPB.getDescriptor().findFieldByName("discharged_type")).isNull();

        assertFieldNumber(PatientInfoPB.getDescriptor(), "from_dept_name", 60);
        assertFieldNumber(PatientInfoPB.getDescriptor(), "discharge_type", 65);
        assertFieldNumber(PatientInfoPB.getDescriptor(), "death_time_iso8601", 66);
        assertFieldNumber(PatientInfoPB.getDescriptor(), "his_discharge_time_iso8601", 67);
        assertFieldNumber(PatientInfoPB.getDescriptor(), "to_dept_name", 68);
        assertFieldNumber(PatientInfoPB.getDescriptor(), "discharge_diagnosis", 70);

        assertThat(StatusCode.INVALID_DISCHARGE_TYPE.getNumber()).isEqualTo(203);
    }

    @Test
    void shouldPreserveRemovedHisDischargeTypeAsUnknownField() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.writeInt32(50, 2);
        output.flush();

        HisPatientPB parsed = HisPatientPB.parseFrom(bytes.toByteArray());

        assertThat(parsed.getUnknownFields().asMap()).containsKey(50);
    }

    private static void assertFieldNumber(
        com.google.protobuf.Descriptors.Descriptor descriptor,
        String fieldName,
        int expectedNumber
    ) {
        assertThat(descriptor.findFieldByName(fieldName))
            .isNotNull()
            .extracting(com.google.protobuf.Descriptors.FieldDescriptor::getNumber)
            .isEqualTo(expectedNumber);
    }
}
