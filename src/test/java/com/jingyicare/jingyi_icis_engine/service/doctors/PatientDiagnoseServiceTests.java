package com.jingyicare.jingyi_icis_engine.service.doctors;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.google.protobuf.util.JsonFormat;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDiagnosis.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.doctors.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.doctors.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class PatientDiagnoseServiceTests extends TestsBase {
    public PatientDiagnoseServiceTests(
        @Autowired ConfigProtoService protoService,
        @Autowired PatientDiagnoseService patientDiagnoseService,
        @Autowired PatientRecordRepository patientRepo,
        @Autowired PatientDiagnosisRepository patientDiagnosisRepo
    ) {
        this.deptId = "10032";
        this.accountId = "admin";

        this.protoService = protoService;
        this.patientDiagnoseService = patientDiagnoseService;
        this.patientRepo = patientRepo;
        this.patientDiagnosisRepo = patientDiagnosisRepo;
    }

    @Test
    public void testConfirmDiseases() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 准备数据
        PatientRecord patient = PatientTestUtils.newPatientRecord(1701L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(TimeUtils.getLocalTime(2025, 3, 1));
        patient.setAdmissionTime(TimeUtils.getLocalTime(2025, 3, 1));
        patient = patientRepo.save(patient);

        // 诊断
        ConfirmDiseaseReq confirmDiseaseReq = ConfirmDiseaseReq.newBuilder()
            .setPid(patient.getId())
            .setDisease(DiseasePB.newBuilder()
                .setCode("SEPSIS")
                .addPhase(DiseasePhasePB.newBuilder()
                    .setCode("SUSPECTED_INFECTION")
                    .addItem(DiseaseItemPB.newBuilder()
                        .setCode("CHEST")
                        .setValue(GenericValuePB.newBuilder()
                            .setBoolVal(true)
                            .build()
                        ).build()
                    )
                    .build()
                )
                .addPhase(DiseasePhasePB.newBuilder()
                    .setCode("SEPSIS")
                    .addItem(DiseaseItemPB.newBuilder()
                        .setCode("GCS_LT_13")
                        .setValue(GenericValuePB.newBuilder()
                            .setBoolVal(true)
                            .build()
                        ).build()
                    )
                    .build()
                )
                .addPhase(DiseasePhasePB.newBuilder()
                    .setCode("SEPTIC_SHOCK")
                    .addItem(DiseaseItemPB.newBuilder()
                        .setCode("LACTATE_GE_2MMOL_L")
                        .setValue(GenericValuePB.newBuilder()
                            .setBoolVal(true)
                            .build()
                        ).build()
                    )
                    .build()
                )
                .build()
            )
            .setConfirmedAtIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2025, 3, 2, 1, 0), "UTC"))
            .build();
        String confirmDiseaseReqJson = ProtoUtils.protoToJson(confirmDiseaseReq);
        ConfirmDiseaseResp confirmDiseaseResp = patientDiagnoseService.confirmDisease(confirmDiseaseReqJson);
        assertThat(confirmDiseaseResp.getRt().getCode()).isEqualTo(0);

        GetConfirmedDiseasesReq getConfirmedDiseasesReq = GetConfirmedDiseasesReq.newBuilder()
            .setMrnOrName("1701")
            .setQueryStartIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2025, 3, 2), "UTC"))
            .setQueryEndIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2025, 3, 3), "UTC"))
            .build();
        String getConfirmedDiseasesReqJson = ProtoUtils.protoToJson(getConfirmedDiseasesReq);
        GetConfirmedDiseasesResp getConfirmedDiseasesResp = patientDiagnoseService.getConfirmedDiseases(getConfirmedDiseasesReqJson);
        assertThat(getConfirmedDiseasesResp.getRt().getCode()).isEqualTo(0);
        assertThat(getConfirmedDiseasesResp.getConfirmedDiseaseList()).hasSize(1);
    }

    @Test
    public void testExcludeDiseases() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 准备数据
        PatientRecord patient = PatientTestUtils.newPatientRecord(1702L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(TimeUtils.getLocalTime(2025, 3, 1));
        patient.setAdmissionTime(TimeUtils.getLocalTime(2025, 3, 1));
        patient = patientRepo.save(patient);

        // 诊断
        ConfirmDiseaseReq confirmDiseaseReq = ConfirmDiseaseReq.newBuilder()
            .setPid(patient.getId())
            .setDisease(DiseasePB.newBuilder()
                .setCode("SEPSIS")
                .addPhase(DiseasePhasePB.newBuilder()
                    .setCode("SUSPECTED_INFECTION")
                    .addItem(DiseaseItemPB.newBuilder()
                        .setCode("CHEST")
                        .setValue(GenericValuePB.newBuilder()
                            .setBoolVal(true)
                            .build()
                        ).build()
                    )
                    .build()
                )
                .addPhase(DiseasePhasePB.newBuilder()
                    .setCode("SEPSIS")
                    .addItem(DiseaseItemPB.newBuilder()
                        .setCode("GCS_LT_13")
                        .setValue(GenericValuePB.newBuilder()
                            .setBoolVal(true)
                            .build()
                        ).build()
                    )
                    .build()
                )
                .addPhase(DiseasePhasePB.newBuilder()
                    .setCode("SEPTIC_SHOCK")
                    .addItem(DiseaseItemPB.newBuilder()
                        .setCode("LACTATE_GE_2MMOL_L")
                        .setValue(GenericValuePB.newBuilder()
                            .setBoolVal(true)
                            .build()
                        ).build()
                    )
                    .build()
                )
                .build()
            )
            .setConfirmedAtIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2025, 3, 2, 2, 0), "UTC"))
            .build();
        String confirmDiseaseReqJson = ProtoUtils.protoToJson(confirmDiseaseReq);
        ConfirmDiseaseResp confirmDiseaseResp = patientDiagnoseService.confirmDisease(confirmDiseaseReqJson);
        assertThat(confirmDiseaseResp.getRt().getCode()).isEqualTo(0);

        // 排除诊断
        ExcludeDiseaseReq excludeDiseaseReq = ExcludeDiseaseReq.newBuilder()
            .setId(confirmDiseaseResp.getId())
            .setExcludedAtIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2025, 3, 2, 3, 0), "UTC"))
            .build();
        String excludeDiseaseReqJson = ProtoUtils.protoToJson(excludeDiseaseReq);
        GenericResp excludeDiseaseResp = patientDiagnoseService.excludeDisease(excludeDiseaseReqJson);

        GetExcludedDiseasesReq getExcludedDiseasesReq = GetExcludedDiseasesReq.newBuilder()
            .setMrnOrName("1702")
            .setQueryStartIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2025, 3, 2), "UTC"))
            .setQueryEndIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2025, 3, 3), "UTC"))
            .build();
        String getExcludedDiseasesReqJson = ProtoUtils.protoToJson(getExcludedDiseasesReq);
        GetExcludedDiseasesResp getExcludedDiseasesResp = patientDiagnoseService.getExcludedDiseases(getExcludedDiseasesReqJson);
        assertThat(getExcludedDiseasesResp.getRt().getCode()).isEqualTo(0);
        assertThat(getExcludedDiseasesResp.getExcludedDiseaseList()).hasSize(1);
    }

    private final String deptId;
    private final String accountId;

    private final ConfigProtoService protoService;
    private final PatientDiagnoseService patientDiagnoseService;

    private final PatientRecordRepository patientRepo;
    private final PatientDiagnosisRepository patientDiagnosisRepo;
}