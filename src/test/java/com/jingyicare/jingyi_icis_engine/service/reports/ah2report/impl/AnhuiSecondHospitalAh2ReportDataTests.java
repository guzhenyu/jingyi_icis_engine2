package com.jingyicare.jingyi_icis_engine.service.reports.ah2report.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.reports.ah2report.Ah2PageData;
import com.jingyicare.jingyi_icis_engine.service.reports.ah2report.Ah2PdfContext;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

@Transactional
class AnhuiSecondHospitalAh2ReportDataTests extends TestsBase {
    @Test
    void missingAdmissionTimeReturnsErrorAndReleasesGenerationSlot() {
        PatientRecord patient = new PatientRecord();
        patient.setDeptId("ah2-missing-admission-tests");
        patient = patientRepo.save(patient);
        LocalDateTime startUtc = LocalDateTime.of(2025, 8, 1, 0, 0);

        // 连续请求都应得到具体时间错误，确认失败后没有遗留生成占位。
        for (int attempt = 0; attempt < 2; attempt++) {
            Pair<ReturnCode, List<Ah2PageData>> result = reportData.collectPageData(
                new Ah2PdfContext(), patient.getId(), patient.getDeptId(),
                startUtc, startUtc.plusDays(1), "ah2-test-account");

            assertThat(result.getFirst().getCode())
                .isEqualTo(StatusCode.INVALID_TIME_FORMAT.ordinal());
            assertThat(result.getSecond()).isNull();
        }
    }

    @Autowired private AnhuiSecondHospitalAh2ReportData reportData;
    @Autowired private PatientRecordRepository patientRepo;
}
