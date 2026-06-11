package com.jingyicare.jingyi_icis_engine.service.monitorings;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.entity.lis.PatientLisItem;
import com.jingyicare.jingyi_icis_engine.entity.lis.PatientLisResult;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.BgaCategoryMapping;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.BgaParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientBgaRecord;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientBgaRecordDetail;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.RawBgaRecord;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.RawBgaRecordDetail;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisBga.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;
import com.jingyicare.jingyi_icis_engine.repository.lis.PatientLisItemRepository;
import com.jingyicare.jingyi_icis_engine.repository.lis.PatientLisResultRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.BgaCategoryMappingRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientBgaRecordDetailRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientBgaRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.BgaParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.MonitoringParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.RawBgaRecordDetailRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.RawBgaRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.PatientTestUtils;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.testutils.ValueMetaTestUtils;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

public class BgaServiceTests extends TestsBase {
    public BgaServiceTests(
        @Autowired ConfigProtoService protoService,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired BgaService bgaService,
        @Autowired BgaParamRepository bgaParamRepository,
        @Autowired BgaCategoryMappingRepository bgaCategoryMappingRepository,
        @Autowired PatientBgaRecordRepository patientBgaRecordRepository,
        @Autowired PatientBgaRecordDetailRepository patientBgaRecordDetailRepository,
        @Autowired RawBgaRecordRepository rawBgaRecordRepository,
        @Autowired RawBgaRecordDetailRepository rawBgaRecordDetailRepository,
        @Autowired PatientLisItemRepository patientLisItemRepository,
        @Autowired PatientLisResultRepository patientLisResultRepository,
        @Autowired PatientRecordRepository patientRecordRepository,
        @Autowired MonitoringParamRepository monitoringParamRepository,
        @Autowired RbacDepartmentRepository deptRepository
    ) {
        this.accountId = "admin";
        this.protoService = protoService;
        this.monitoringConfig = monitoringConfig;
        this.bgaService = bgaService;
        this.bgaParamRepository = bgaParamRepository;
        this.bgaCategoryMappingRepository = bgaCategoryMappingRepository;
        this.patientBgaRecordRepository = patientBgaRecordRepository;
        this.patientBgaRecordDetailRepository = patientBgaRecordDetailRepository;
        this.rawBgaRecordRepository = rawBgaRecordRepository;
        this.rawBgaRecordDetailRepository = rawBgaRecordDetailRepository;
        this.patientLisItemRepository = patientLisItemRepository;
        this.patientLisResultRepository = patientLisResultRepository;
        this.patientRecordRepository = patientRecordRepository;
        this.monitoringParamRepository = monitoringParamRepository;
        this.deptRepository = deptRepository;
    }

    @Test
    public void testGetBgaParamsInitializesDefaults() {
        setCurrentUser();
        Assumptions.assumeFalse(protoService.getConfig().getBga().getBgaParamCodeList().isEmpty());
        String deptId = initDepartment("get-params");

        GetBgaParamsResp resp = bgaService.getBgaParams(ProtoUtils.protoToJson(
            GetBgaParamsReq.newBuilder().setDeptId(deptId).build()
        ));

        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        List<String> expectedParamCodes = protoService.getConfig().getBga().getBgaParamCodeList();
        assertThat(resp.getParamList()).hasSize(expectedParamCodes.size());
        assertThat(resp.getParamList()).extracting(BgaParamPB::getParamCode)
            .containsExactlyElementsOf(expectedParamCodes);
        assertThat(resp.getParamList()).allSatisfy(param -> {
            assertThat(param.getEnabled()).isFalse();
            assertThat(param.getLisResultCode()).isEmpty();
        });
    }

    @Test
    public void testSaveBgaParamPersistsLisResultCodeAndEnabled() {
        setCurrentUser();
        String deptId = initDepartment("save-param");
        String paramCode = createBgaParam(deptId);

        GetBgaParamsResp initialResp = bgaService.getBgaParams(ProtoUtils.protoToJson(
            GetBgaParamsReq.newBuilder().setDeptId(deptId).build()
        ));
        BgaParamPB targetParam = initialResp.getParamList().stream()
            .filter(param -> param.getParamCode().equals(paramCode))
            .findFirst()
            .orElseThrow();

        GenericResp saveResp = bgaService.saveBgaParam(ProtoUtils.protoToJson(
            SaveBgaParamReq.newBuilder()
                .setDeptId(deptId)
                .setParamCode(targetParam.getParamCode())
                .setLisResultCode("  pH_result_code  ")
                .setEnabled(true)
                .build()
        ));
        assertThat(saveResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        BgaParam savedParam = bgaParamRepository.findByDeptIdAndMonitoringParamCodeAndIsDeletedFalse(
            deptId, targetParam.getParamCode()
        ).orElse(null);
        assertThat(savedParam).isNotNull();
        assertThat(savedParam.getEnabled()).isTrue();
        assertThat(savedParam.getLisResultCode()).isEqualTo("pH_result_code");

        GetBgaParamsResp updatedResp = bgaService.getBgaParams(ProtoUtils.protoToJson(
            GetBgaParamsReq.newBuilder().setDeptId(deptId).build()
        ));
        BgaParamPB updatedParam = updatedResp.getParamList().stream()
            .filter(param -> param.getParamCode().equals(targetParam.getParamCode()))
            .findFirst()
            .orElseThrow();
        assertThat(updatedParam.getEnabled()).isTrue();
        assertThat(updatedParam.getLisResultCode()).isEqualTo("pH_result_code");

        GenericResp clearResp = bgaService.saveBgaParam(ProtoUtils.protoToJson(
            SaveBgaParamReq.newBuilder()
                .setDeptId(deptId)
                .setParamCode(targetParam.getParamCode())
                .setLisResultCode("   ")
                .setEnabled(false)
                .build()
        ));
        assertThat(clearResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        savedParam = bgaParamRepository.findByDeptIdAndMonitoringParamCodeAndIsDeletedFalse(
            deptId, targetParam.getParamCode()
        ).orElse(null);
        assertThat(savedParam).isNotNull();
        assertThat(savedParam.getEnabled()).isFalse();
        assertThat(savedParam.getLisResultCode()).isNull();
    }

    @Test
    public void testGetBgaParamsRestoresSoftDeletedDefaultParam() {
        setCurrentUser();
        Assumptions.assumeFalse(protoService.getConfig().getBga().getBgaParamCodeList().isEmpty());
        String deptId = initDepartment("restore-param");

        monitoringConfig.getBgaParamList(deptId);
        String targetParamCode = protoService.getConfig().getBga().getBgaParamCodeList().get(0);

        BgaParam deletedParam = bgaParamRepository.findByDeptIdAndMonitoringParamCodeAndIsDeletedFalse(
            deptId, targetParamCode
        ).orElseThrow();
        deletedParam.setEnabled(true);
        deletedParam.setLisResultCode("restore_me");
        softDelete(deletedParam);
        bgaParamRepository.save(deletedParam);

        GetBgaParamsResp restoredResp = bgaService.getBgaParams(ProtoUtils.protoToJson(
            GetBgaParamsReq.newBuilder().setDeptId(deptId).build()
        ));

        assertThat(restoredResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(restoredResp.getParamList()).extracting(BgaParamPB::getParamCode).contains(targetParamCode);

        BgaParam restoredParam = bgaParamRepository.findByDeptIdAndMonitoringParamCodeAndIsDeletedFalse(
            deptId, targetParamCode
        ).orElseThrow();
        assertThat(restoredParam.getIsDeleted()).isFalse();
        assertThat(restoredParam.getDeletedAt()).isNull();
        assertThat(restoredParam.getEnabled()).isTrue();
        assertThat(restoredParam.getLisResultCode()).isEqualTo("restore_me");
    }

    @Test
    public void testGetBgaCategoryInitializesConfiguredMappings() {
        setCurrentUser();
        String deptId = initDepartment("get-category");

        GetBgaCategoryResp resp = bgaService.getBgaCategory(ProtoUtils.protoToJson(
            GetBgaCategoryReq.newBuilder().setDeptId(deptId).build()
        ));

        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        List<Integer> expectedCategoryIds = protoService.getConfig().getBga().getEnums().getBgaCategoryList()
            .stream()
            .map(EnumValue::getId)
            .toList();
        assertThat(resp.getMappingList()).hasSize(expectedCategoryIds.size());
        assertThat(resp.getMappingList()).extracting(BgaCategoryMappingPB::getBgaCategoryId)
            .containsExactlyElementsOf(expectedCategoryIds);
        assertThat(resp.getMappingList()).allSatisfy(mapping -> {
            assertThat(mapping.getDeptId()).isEqualTo(deptId);
            assertThat(mapping.getLisItemCode()).isEmpty();
        });
    }

    @Test
    public void testSaveBgaCategoryPersistsLisItemCode() {
        setCurrentUser();
        String deptId = initDepartment("save-category");

        GetBgaCategoryResp initialResp = bgaService.getBgaCategory(ProtoUtils.protoToJson(
            GetBgaCategoryReq.newBuilder().setDeptId(deptId).build()
        ));
        BgaCategoryMappingPB targetMapping = initialResp.getMapping(0);

        GenericResp saveResp = bgaService.saveBgaCategory(ProtoUtils.protoToJson(
            SaveBgaCategoryReq.newBuilder()
                .setBgaCategoryMapping(targetMapping.toBuilder()
                    .setLisItemCode("  BLOOD_GAS_PANEL  ")
                    .build())
                .build()
        ));
        assertThat(saveResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        BgaCategoryMapping savedMapping = bgaCategoryMappingRepository
            .findByIdAndIsDeletedFalse(targetMapping.getId())
            .orElse(null);
        assertThat(savedMapping).isNotNull();
        assertThat(savedMapping.getDeptId()).isEqualTo(deptId);
        assertThat(savedMapping.getBgaCategoryId()).isEqualTo(targetMapping.getBgaCategoryId());
        assertThat(savedMapping.getLisItemCode()).isEqualTo("BLOOD_GAS_PANEL");

        GetBgaCategoryResp updatedResp = bgaService.getBgaCategory(ProtoUtils.protoToJson(
            GetBgaCategoryReq.newBuilder().setDeptId(deptId).build()
        ));
        BgaCategoryMappingPB updatedMapping = updatedResp.getMappingList().stream()
            .filter(mapping -> mapping.getId() == targetMapping.getId())
            .findFirst()
            .orElseThrow();
        assertThat(updatedMapping.getLisItemCode()).isEqualTo("BLOOD_GAS_PANEL");

        GenericResp clearResp = bgaService.saveBgaCategory(ProtoUtils.protoToJson(
            SaveBgaCategoryReq.newBuilder()
                .setBgaCategoryMapping(updatedMapping.toBuilder()
                    .setLisItemCode("")
                    .build())
                .build()
        ));
        assertThat(clearResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        savedMapping = bgaCategoryMappingRepository.findByIdAndIsDeletedFalse(targetMapping.getId()).orElse(null);
        assertThat(savedMapping).isNotNull();
        assertThat(savedMapping.getLisItemCode()).isNull();
    }

    @Test
    public void testGetBgaCategoryRestoresSoftDeletedMapping() {
        setCurrentUser();
        String deptId = initDepartment("restore-category");

        GetBgaCategoryResp initialResp = bgaService.getBgaCategory(ProtoUtils.protoToJson(
            GetBgaCategoryReq.newBuilder().setDeptId(deptId).build()
        ));
        BgaCategoryMappingPB targetMapping = initialResp.getMapping(0);

        BgaCategoryMapping deletedMapping = bgaCategoryMappingRepository
            .findByIdAndIsDeletedFalse(targetMapping.getId())
            .orElseThrow();
        deletedMapping.setLisItemCode("RESTORE_CATEGORY");
        softDelete(deletedMapping);
        bgaCategoryMappingRepository.save(deletedMapping);

        GetBgaCategoryResp restoredResp = bgaService.getBgaCategory(ProtoUtils.protoToJson(
            GetBgaCategoryReq.newBuilder().setDeptId(deptId).build()
        ));

        assertThat(restoredResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(restoredResp.getMappingList()).extracting(BgaCategoryMappingPB::getBgaCategoryId)
            .contains(targetMapping.getBgaCategoryId());

        BgaCategoryMapping restoredMapping = bgaCategoryMappingRepository
            .findByDeptIdAndBgaCategoryIdAndIsDeletedFalse(deptId, targetMapping.getBgaCategoryId())
            .orElseThrow();
        assertThat(restoredMapping.getIsDeleted()).isFalse();
        assertThat(restoredMapping.getDeletedAt()).isNull();
        assertThat(restoredMapping.getLisItemCode()).isEqualTo("RESTORE_CATEGORY");
    }

    @Test
    public void testGetPatientBgaRecordsForceSyncUsesLisForMappedCategory() {
        setCurrentUser();
        String deptId = initDepartment("force-sync-lis");
        LocalDateTime queryStart = TimeUtils.getNowUtc().minusHours(6).withNano(0);
        PatientRecord patient = initPatient(deptId, "force-sync-lis", queryStart.minusHours(1));

        String lisParamCode = createBgaParam(deptId, "lis-sync", "ph_result_code");
        String rawParamCode = createBgaParam(deptId, "raw-sync", null);
        saveBgaCategoryMapping(deptId, 1, "BGA_PANEL");

        LocalDateTime lisAuthTime = queryStart.plusHours(1);
        LocalDateTime rawEffectiveTime = queryStart.plusHours(2);
        String lisReportId = "report-" + UUID.randomUUID();
        createLisItem(patient.getHisPatientId(), lisReportId, "BGA_PANEL", lisAuthTime);
        createLisResult(lisReportId, "ph_result_code", "7.35", lisAuthTime);

        createRawBgaRecord(patient.getHisMrn(), 1, lisAuthTime, rawParamCode, "99.9");
        RawBgaRecord rawCategory2 = createRawBgaRecord(patient.getHisMrn(), 2, rawEffectiveTime, rawParamCode, "36.6");

        GetPatientBgaRecordsResp resp = bgaService.getPatientBgaRecords(ProtoUtils.protoToJson(
            GetPatientBgaRecordsReq.newBuilder()
                .setPid(patient.getId())
                .setQueryStartIso8601(TimeUtils.toIso8601String(queryStart, "UTC"))
                .setQueryEndIso8601(TimeUtils.toIso8601String(rawEffectiveTime.plusHours(1), "UTC"))
                .setForceSync(true)
                .build()
        ));

        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getRecordList()).hasSize(2);

        List<PatientBgaRecord> savedRecords = patientBgaRecordRepository.findByPidAndEffectiveTimeBetweenAndIsDeletedFalse(
            patient.getId(), queryStart, rawEffectiveTime.plusHours(1)
        );
        PatientBgaRecord lisRecord = savedRecords.stream()
            .filter(record -> record.getBgaCategoryId().equals(1))
            .findFirst()
            .orElseThrow();
        assertThat(lisRecord.getRawRecordId()).isNull();
        assertThat(lisRecord.getLisItemCode()).isEqualTo("BGA_PANEL");
        assertThat(lisRecord.getRecordedBy()).isNotBlank();
        assertThat(lisRecord.getRecordedByAccountName()).isNotBlank();
        assertThat(lisRecord.getRecordedAt()).isEqualTo(lisAuthTime);

        List<PatientBgaRecordDetail> lisDetails = patientBgaRecordDetailRepository.findByRecordIdAndIsDeletedFalse(
            lisRecord.getId()
        );
        assertThat(lisDetails).extracting(PatientBgaRecordDetail::getMonitoringParamCode)
            .containsExactly(lisParamCode);
        assertThat(lisDetails).extracting(PatientBgaRecordDetail::getParamValueStr)
            .containsExactly("7.35");

        PatientBgaRecord rawRecord = savedRecords.stream()
            .filter(record -> record.getBgaCategoryId().equals(2))
            .findFirst()
            .orElseThrow();
        assertThat(rawRecord.getRawRecordId()).isEqualTo(rawCategory2.getId());
        assertThat(rawRecord.getLisItemCode()).isNull();
        List<PatientBgaRecordDetail> rawDetails = patientBgaRecordDetailRepository.findByRecordIdAndIsDeletedFalse(
            rawRecord.getId()
        );
        assertThat(rawDetails).extracting(PatientBgaRecordDetail::getMonitoringParamCode)
            .containsExactly(rawParamCode);
        assertThat(rawDetails).extracting(PatientBgaRecordDetail::getParamValueStr)
            .containsExactly("36.6");
    }

    @Test
    public void testGetPatientBgaRecordsForceSyncReplacesRawRecordWhenCategoryMappedToLis() {
        setCurrentUser();
        String deptId = initDepartment("switch-to-lis");
        LocalDateTime queryStart = TimeUtils.getNowUtc().minusHours(8).withNano(0);
        LocalDateTime effectiveTime = queryStart.plusHours(1);
        PatientRecord patient = initPatient(deptId, "switch-to-lis", queryStart.minusHours(1));

        String rawParamCode = createBgaParam(deptId, "raw-before-switch", null);
        String lisParamCode = createBgaParam(deptId, "lis-after-switch", "pco2_result_code");
        createRawBgaRecord(patient.getHisMrn(), 1, effectiveTime, rawParamCode, "10.1");

        GetPatientBgaRecordsReq syncReq = GetPatientBgaRecordsReq.newBuilder()
            .setPid(patient.getId())
            .setQueryStartIso8601(TimeUtils.toIso8601String(queryStart, "UTC"))
            .setQueryEndIso8601(TimeUtils.toIso8601String(effectiveTime.plusHours(1), "UTC"))
            .setForceSync(true)
            .build();

        GetPatientBgaRecordsResp initialResp = bgaService.getPatientBgaRecords(ProtoUtils.protoToJson(syncReq));
        assertThat(initialResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        PatientBgaRecord initialRecord = patientBgaRecordRepository.findByPidAndBgaCategoryIdAndEffectiveTimeAndIsDeletedFalse(
            patient.getId(), 1, effectiveTime
        ).orElseThrow();
        assertThat(initialRecord.getRawRecordId()).isNotNull();
        Long recordId = initialRecord.getId();

        saveBgaCategoryMapping(deptId, 1, "ABL90_PANEL");
        String lisReportId = "report-" + UUID.randomUUID();
        createLisItem(patient.getHisPatientId(), lisReportId, "ABL90_PANEL", effectiveTime);
        createLisResult(lisReportId, "pco2_result_code", "40.0", effectiveTime);

        GetPatientBgaRecordsResp switchedResp = bgaService.getPatientBgaRecords(ProtoUtils.protoToJson(syncReq));
        assertThat(switchedResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        PatientBgaRecord switchedRecord = patientBgaRecordRepository.findByPidAndBgaCategoryIdAndEffectiveTimeAndIsDeletedFalse(
            patient.getId(), 1, effectiveTime
        ).orElseThrow();
        assertThat(switchedRecord.getId()).isEqualTo(recordId);
        assertThat(switchedRecord.getRawRecordId()).isNull();
        assertThat(switchedRecord.getLisItemCode()).isEqualTo("ABL90_PANEL");
        assertThat(switchedRecord.getRecordedAt()).isEqualTo(effectiveTime);

        List<PatientBgaRecordDetail> activeDetails = patientBgaRecordDetailRepository.findByRecordIdAndIsDeletedFalse(recordId);
        assertThat(activeDetails).extracting(PatientBgaRecordDetail::getMonitoringParamCode)
            .containsExactly(lisParamCode);
        assertThat(activeDetails).extracting(PatientBgaRecordDetail::getParamValueStr)
            .containsExactly("40.0");
        assertThat(patientBgaRecordDetailRepository.findAll().stream()
            .filter(detail -> detail.getRecordId().equals(recordId)))
            .hasSize(2);
    }

    @Test
    public void testGetPatientBgaRecordsForceSyncDoesNotOverwriteUserUpdatedRecord() {
        setCurrentUser();
        String deptId = initDepartment("preserve-user-update");
        LocalDateTime queryStart = TimeUtils.getNowUtc().minusHours(8).withNano(0);
        LocalDateTime effectiveTime = queryStart.plusHours(1);
        PatientRecord patient = initPatient(deptId, "preserve-user-update", queryStart.minusHours(1));

        String paramCode = createBgaParam(deptId, "preserve-user-update", null);
        RawBgaRecord rawRecord = createRawBgaRecord(patient.getHisMrn(), 1, effectiveTime, paramCode, "10.1");

        GetPatientBgaRecordsReq syncReq = GetPatientBgaRecordsReq.newBuilder()
            .setPid(patient.getId())
            .setQueryStartIso8601(TimeUtils.toIso8601String(queryStart, "UTC"))
            .setQueryEndIso8601(TimeUtils.toIso8601String(effectiveTime.plusHours(1), "UTC"))
            .setForceSync(true)
            .build();

        GetPatientBgaRecordsResp initialResp = bgaService.getPatientBgaRecords(ProtoUtils.protoToJson(syncReq));
        assertThat(initialResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        PatientBgaRecord syncedRecord = patientBgaRecordRepository.findByPidAndBgaCategoryIdAndEffectiveTimeAndIsDeletedFalse(
            patient.getId(), 1, effectiveTime
        ).orElseThrow();
        assertThat(syncedRecord.getRawRecordId()).isEqualTo(rawRecord.getId());

        GenericResp updateResp = bgaService.updatePatientBgaRecord(ProtoUtils.protoToJson(
            AddPatientBgaRecordReq.newBuilder()
                .setRecord(PatientBgaRecordPB.newBuilder()
                    .setId(syncedRecord.getId())
                    .setPid(patient.getId())
                    .setBgaCategoryId(1)
                    .setBgaCategoryName(syncedRecord.getBgaCategoryName())
                    .setEffectiveTimeIso8601(TimeUtils.toIso8601String(effectiveTime, "UTC"))
                    .addBgaResult(BgaResultPB.newBuilder()
                        .setParamCode(paramCode)
                        .setValue(GenericValuePB.newBuilder().setFloatVal(12.3f).build())
                        .build())
                    .build())
                .build()
        ));
        assertThat(updateResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        PatientBgaRecord userUpdatedRecord = patientBgaRecordRepository.findByIdAndIsDeletedFalse(
            syncedRecord.getId()
        ).orElseThrow();
        assertThat(userUpdatedRecord.getRawRecordId()).isNull();
        assertThat(userUpdatedRecord.getLisItemCode()).isNull();
        assertThat(patientBgaRecordDetailRepository.findByRecordIdAndIsDeletedFalse(syncedRecord.getId()))
            .extracting(PatientBgaRecordDetail::getParamValueStr)
            .containsExactly("12.3");

        GetPatientBgaRecordsResp resyncResp = bgaService.getPatientBgaRecords(ProtoUtils.protoToJson(syncReq));
        assertThat(resyncResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        PatientBgaRecord preservedRecord = patientBgaRecordRepository.findByIdAndIsDeletedFalse(
            syncedRecord.getId()
        ).orElseThrow();
        assertThat(preservedRecord.getRawRecordId()).isNull();
        assertThat(preservedRecord.getLisItemCode()).isNull();
        assertThat(patientBgaRecordDetailRepository.findByRecordIdAndIsDeletedFalse(syncedRecord.getId()))
            .extracting(PatientBgaRecordDetail::getParamValueStr)
            .containsExactly("12.3");
    }

    private void setCurrentUser() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String initDepartment(String scenario) {
        String deptId = "bga-test-" + scenario + "-" + UUID.randomUUID().toString().substring(0, 8);
        RbacDepartment dept = new RbacDepartment();
        dept.setDeptId(deptId);
        dept.setDeptName("dept-" + scenario);
        deptRepository.save(dept);
        monitoringConfig.initialize();
        return deptId;
    }

    private String createBgaParam(String deptId) {
        return createBgaParam(deptId, "default", null);
    }

    private String createBgaParam(String deptId, String scenario, String lisResultCode) {
        String paramCode = "test_bga_param_" + scenario + "_" + UUID.randomUUID().toString().substring(0, 8);
        ValueMetaPB valueMeta = ValueMetaTestUtils.newValueMetaFloat(1, false);

        monitoringParamRepository.save(MonitoringParam.builder()
            .code(paramCode)
            .name("测试血气参数")
            .typePb(ProtoUtils.encodeValueMeta(valueMeta))
            .balanceType(0)
            .displayOrder(9999)
            .build());

        bgaParamRepository.save(BgaParam.builder()
            .deptId(deptId)
            .monitoringParamCode(paramCode)
            .displayOrder(1)
            .enabled(false)
            .lisResultCode(lisResultCode)
            .isDeleted(false)
            .modifiedBy("system")
            .modifiedAt(TimeUtils.getNowUtc())
            .build());
        return paramCode;
    }

    private PatientRecord initPatient(String deptId, String scenario, LocalDateTime admissionTime) {
        PatientRecord patient = PatientTestUtils.newPatientRecord(
            Long.parseLong(String.valueOf(Math.abs(UUID.randomUUID().hashCode()))),
            1,
            deptId
        );
        patient.setId(null);
        patient.setHisMrn("bga-mrn-" + scenario + "-" + UUID.randomUUID().toString().substring(0, 8));
        patient.setHisPatientId("bga-his-pid-" + scenario + "-" + UUID.randomUUID().toString().substring(0, 8));
        patient.setAdmissionTime(admissionTime);
        patient.setHisAdmissionTime(admissionTime);
        patient.setDischargeTime(null);
        patient.setDischargeEditTime(null);
        return patientRecordRepository.save(patient);
    }

    private void saveBgaCategoryMapping(String deptId, Integer bgaCategoryId, String lisItemCode) {
        GenericResp resp = bgaService.saveBgaCategory(ProtoUtils.protoToJson(
            SaveBgaCategoryReq.newBuilder()
                .setBgaCategoryMapping(BgaCategoryMappingPB.newBuilder()
                    .setDeptId(deptId)
                    .setBgaCategoryId(bgaCategoryId)
                    .setLisItemCode(lisItemCode)
                    .build())
                .build()
        ));
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
    }

    private RawBgaRecord createRawBgaRecord(
        String mrnBednum, Integer bgaCategoryId, LocalDateTime effectiveTime,
        String paramCode, String paramValueStr
    ) {
        RawBgaRecord rawRecord = rawBgaRecordRepository.save(RawBgaRecord.builder()
            .mrnBednum(mrnBednum)
            .bgaCategoryId(bgaCategoryId)
            .effectiveTime(effectiveTime)
            .build());
        rawBgaRecordDetailRepository.save(RawBgaRecordDetail.builder()
            .recordId(rawRecord.getId())
            .monitoringParamCode(paramCode)
            .paramValueStr(paramValueStr)
            .build());
        return rawRecord;
    }

    private void createLisItem(String hisPid, String reportId, String lisItemCode, LocalDateTime authTime) {
        patientLisItemRepository.save(PatientLisItem.builder()
            .reportId(reportId)
            .hisPid(hisPid)
            .lisItemCode(lisItemCode)
            .lisItemName("血气检验")
            .lisItemShortName("血气")
            .collectTime(authTime)
            .authTime(authTime)
            .status("已审核")
            .build());
    }

    private void createLisResult(
        String reportId, String externalParamCode, String resultStr, LocalDateTime authTime
    ) {
        patientLisResultRepository.save(PatientLisResult.builder()
            .reportId(reportId)
            .externalParamCode(externalParamCode)
            .externalParamName(externalParamCode)
            .resultStr(resultStr)
            .authTime(authTime)
            .isDeleted(false)
            .modifiedBy("system")
            .modifiedAt(TimeUtils.getNowUtc())
            .build());
    }

    private void softDelete(BgaParam param) {
        param.setIsDeleted(true);
        param.setDeletedBy(accountId);
        param.setDeletedByAccountName(accountId);
        param.setDeletedAt(TimeUtils.getNowUtc());
        param.setModifiedBy(accountId);
        param.setModifiedAt(TimeUtils.getNowUtc());
    }

    private void softDelete(BgaCategoryMapping mapping) {
        mapping.setIsDeleted(true);
        mapping.setDeletedBy(accountId);
        mapping.setDeletedByAccountName(accountId);
        mapping.setDeletedAt(TimeUtils.getNowUtc());
        mapping.setModifiedBy(accountId);
        mapping.setModifiedAt(TimeUtils.getNowUtc());
    }

    private final String accountId;

    private final ConfigProtoService protoService;
    private final MonitoringConfig monitoringConfig;
    private final BgaService bgaService;
    private final BgaParamRepository bgaParamRepository;
    private final BgaCategoryMappingRepository bgaCategoryMappingRepository;
    private final PatientBgaRecordRepository patientBgaRecordRepository;
    private final PatientBgaRecordDetailRepository patientBgaRecordDetailRepository;
    private final RawBgaRecordRepository rawBgaRecordRepository;
    private final RawBgaRecordDetailRepository rawBgaRecordDetailRepository;
    private final PatientLisItemRepository patientLisItemRepository;
    private final PatientLisResultRepository patientLisResultRepository;
    private final PatientRecordRepository patientRecordRepository;
    private final MonitoringParamRepository monitoringParamRepository;
    private final RbacDepartmentRepository deptRepository;
}
