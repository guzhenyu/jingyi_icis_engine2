package com.jingyicare.jingyi_icis_engine.service.doctors;

import java.time.*;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDiagnosis.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.doctors.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.doctors.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.users.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class PatientDiagnoseService {
    public PatientDiagnoseService(
        @Autowired ConfigProtoService protoService,
        @Autowired UserService userService,
        @Autowired PatientService patientService,
        @Autowired DoctorDataFetcher doctorDataFetcher,
        @Autowired PatientRecordRepository patientRecordRepo,
        @Autowired PatientDiagnosisRepository patientDiagnosisRepo,
        @Autowired DiseaseMetaRepository diseaseMetaRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();

        this.protoService = protoService;
        this.userService = userService;
        this.patientService = patientService;
        this.doctorDataFetcher = doctorDataFetcher;

        this.patientRecordRepo = patientRecordRepo;
        this.patientDiagnosisRepo = patientDiagnosisRepo;
        this.diseaseMetaRepo = diseaseMetaRepo;
    }

    public GetDiseaseMetaResp getDiseaseMeta(String getDiseaseMetaReqJson) {
        final GetDiseaseMetaReq req;
        try {
            GetDiseaseMetaReq.Builder builder = GetDiseaseMetaReq.newBuilder();
            JsonFormat.parser().merge(getDiseaseMetaReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetDiseaseMetaResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // todo(guzhenyu): 根据数字证书过滤疾病列表

        return GetDiseaseMetaResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllDiseaseMeta(protoService.getConfig().getDiagnosis().getDiseaseMetaList())
            .build();
    }

    @Transactional
    public CalcDiseaseMetricResp calcDiseaseMetric(String calcDiseaseMetricReqJson) {
        final CalcDiseaseMetricReq req;
        try {
            CalcDiseaseMetricReq.Builder builder = CalcDiseaseMetricReq.newBuilder();
            JsonFormat.parser().merge(calcDiseaseMetricReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return CalcDiseaseMetricResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        LocalDateTime startTime = TimeUtils.fromIso8601String(req.getStartTimeIso8601(), "UTC");
        LocalDateTime endTime = TimeUtils.fromIso8601String(req.getEndTimeIso8601(), "UTC");
        if (startTime == null || endTime == null) {
            log.error("Invalid time format: startTime={}, endTime={}", req.getStartTimeIso8601(), req.getEndTimeIso8601());
            return CalcDiseaseMetricResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_FORMAT))
                .build();
        }

        DiseaseMetaPB diseaseMeta = getDiseaseMetaByCode(req.getDiseaseCode());
        if (diseaseMeta == null) {
            log.error("Disease meta not found: diseaseCode={}", req.getDiseaseCode());
            return CalcDiseaseMetricResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        List<DiseasePhasePB> diseasePhases = new ArrayList<>();
        for (DiseasePhaseMetaPB phaseMeta : diseaseMeta.getPhaseList()) {
            DiseasePhasePB.Builder builder = DiseasePhasePB.newBuilder()
                .setCode(phaseMeta.getCode());

            for (DiseaseItemMetaPB itemMeta : phaseMeta.getItemList()) {
                if (itemMeta.getAutoCalculated()) {
                    DiseaseItemPB diseaseItem = doctorDataFetcher.fetchDiseaseItem(
                        req.getPid(), req.getDeptId(), itemMeta.getCode(), startTime, endTime);
                    if (diseaseItem != null) builder.addItem(diseaseItem);
                }
            }

            DiseasePhasePB phase = builder.build();
            if (phase.getItemCount() > 0) diseasePhases.add(phase);
        }

        return CalcDiseaseMetricResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllDiseasePhase(diseasePhases)
            .build();
    }

    @Transactional
    public ConfirmDiseaseResp confirmDisease(String confirmDiseaseReqJson) {
        final ConfirmDiseaseReq req;
        try {
            ConfirmDiseaseReq.Builder builder = ConfirmDiseaseReq.newBuilder();
            JsonFormat.parser().merge(confirmDiseaseReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return ConfirmDiseaseResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return ConfirmDiseaseResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 获取当前病人科室
        Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return ConfirmDiseaseResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();

        // 获取疾病信息
        final String diseaseCode = req.getDisease().getCode();
        DiseaseMetaPB diseaseMeta = getDiseaseMetaByCode(diseaseCode);
        if (diseaseMeta == null) {
            return ConfirmDiseaseResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DISEASE_CODE_NOT_FOUND))
                .build();
        }
        Map<String, ValueMetaPB> itemMetaMap = getDiseaseItemMetaMap(diseaseMeta);

        // 检查疾病诊断信息
        Map<String/*phase_code*/, Integer/*checked_item_cnt*/> phaseMap = new HashMap<>();
        for (DiseasePhaseMetaPB phaseMeta : diseaseMeta.getPhaseList()) {
            phaseMap.put(phaseMeta.getCode(), 0);
        }
        for (DiseasePhasePB phase : req.getDisease().getPhaseList()) {
            int checkedItemCnt = 0;
            for (DiseaseItemPB item : phase.getItemList()) {
                ValueMetaPB valueMeta = itemMetaMap.get(item.getCode());
                if (valueMeta == null) {
                    log.error("Item meta not found: diseaseCode={}, itemCode={}", diseaseCode, item.getCode());
                    continue;
                }
                if (valueMeta.getValueType().equals(TypeEnumPB.BOOL)) {
                    if (item.getValue().getBoolVal()) checkedItemCnt++;
                } else if (valueMeta.getValueType().equals(TypeEnumPB.INT32)) {
                    if (item.getValue().getInt32Val() > 0) checkedItemCnt++;
                } else if (valueMeta.getValueType().equals(TypeEnumPB.INT64)) {
                    if (item.getValue().getInt64Val() > 0) checkedItemCnt++;
                } else if (valueMeta.getValueType().equals(TypeEnumPB.FLOAT)) {
                    if (item.getValue().getFloatVal() > 0) checkedItemCnt++;
                } else if (valueMeta.getValueType().equals(TypeEnumPB.DOUBLE)) {
                    if (item.getValue().getDoubleVal() > 0) checkedItemCnt++;
                } else if (valueMeta.getValueType().equals(TypeEnumPB.STRING)) {
                    if (!StrUtils.isBlank(item.getValue().getStrVal())) checkedItemCnt++;
                }
            }
            phaseMap.put(phase.getCode(), checkedItemCnt);
        }
        if (phaseMap.values().stream().anyMatch(cnt -> cnt == 0)) {
            return ConfirmDiseaseResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DISEASE_PHASE_NOT_CONFIRMED))
                .build();
        }

        LocalDateTime confirmedAt = TimeUtils.fromIso8601String(req.getConfirmedAtIso8601(), "UTC");
        if (confirmedAt == null) {
            log.error("Invalid time format: confirmedAt={}", req.getConfirmedAtIso8601());
            return ConfirmDiseaseResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_FORMAT))
                .build();
        }
        LocalDateTime evalStart = TimeUtils.fromIso8601String(req.getStartTimeIso8601(), "UTC");
        if (evalStart == null) evalStart = confirmedAt;
        LocalDateTime evalEnd = TimeUtils.fromIso8601String(req.getEndTimeIso8601(), "UTC");
        if (evalEnd == null) evalEnd = confirmedAt;

        PatientDiagnosis diagnosis = patientDiagnosisRepo.findByPidAndDiseaseCodeAndConfirmedAt(
            pid, diseaseCode, confirmedAt).orElse(null);
        if (diagnosis != null) {
            return ConfirmDiseaseResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_DISEASE_ALREADY_CONFIRMED))
                .setId(diagnosis.getId())
                .build();
        }

        // 保存疾病诊断信息
        diagnosis = PatientDiagnosis.builder()
            .pid(pid)
            .deptId(deptId)
            .diseaseCode(diseaseCode)
            .diseasePbtxt(ProtoUtils.encodeDisease(req.getDisease()))
            .evalStartAt(evalStart)
            .evalEndAt(evalEnd)
            .confirmedBy(accountId)
            .confirmedByAccountName(accountName)
            .confirmedAt(confirmedAt)
            .isDeleted(false)
            .modifiedBy(accountId)
            .modifiedAt(TimeUtils.getNowUtc())
            .build();
        diagnosis = patientDiagnosisRepo.save(diagnosis);

        return ConfirmDiseaseResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(diagnosis.getId())
            .build();
    }

    @Transactional
    public GenericResp excludeDisease(String excludeDiseaseReqJson) {
        final ExcludeDiseaseReq req;
        try {
            ExcludeDiseaseReq.Builder builder = ExcludeDiseaseReq.newBuilder();
            JsonFormat.parser().merge(excludeDiseaseReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 合法性检验
        LocalDateTime excludedAt = TimeUtils.fromIso8601String(req.getExcludedAtIso8601(), "UTC");
        if (excludedAt == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_FORMAT))
                .build();
        }

        PatientDiagnosis diagnosis = patientDiagnosisRepo.findById(req.getId()).orElse(null);
        if (diagnosis == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_DISEASE_NOT_CONFIRMED))
                .build();
        }

        if (diagnosis.getIsDeleted()) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_DISEASE_ALREADY_EXCLUDED))
                .build();
        }

        // 排除/推翻，之前的确诊
        diagnosis.setIsDeleted(true);
        diagnosis.setDeletedBy(accountId);
        diagnosis.setDeletedByAccountName(accountName);
        diagnosis.setDeletedAt(excludedAt);
        diagnosis.setModifiedBy(accountId);
        diagnosis.setModifiedAt(TimeUtils.getNowUtc());
        diagnosis = patientDiagnosisRepo.save(diagnosis);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GetConfirmedDiseasesResp getConfirmedDiseases(String getConfirmedDiseasesReqJson) {
        final GetConfirmedDiseasesReq req;
        try {
            GetConfirmedDiseasesReq.Builder builder = GetConfirmedDiseasesReq.newBuilder();
            JsonFormat.parser().merge(getConfirmedDiseasesReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetConfirmedDiseasesResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }
        final String deptId = req.getDeptId();

        // 查找疾病
        LocalDateTime startTime = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        if (startTime == null) startTime = TimeUtils.getLocalTime(1900, 1, 1);
        LocalDateTime endTime = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (endTime == null) endTime = TimeUtils.getLocalTime(9900, 1, 1);

        List<PatientDiagnosis> diagnosisList = patientDiagnosisRepo.findByConfirmedAtBetweenAndIsDeletedFalse(
            startTime, endTime
        ).stream()
        .filter(diagnosis -> {
            if (StrUtils.isBlank(deptId)) return true;
            return diagnosis.getDeptId().equals(deptId);
        })
        .sorted(Comparator.comparing(PatientDiagnosis::getConfirmedAt).reversed()).toList();
        List<Long> pids = diagnosisList.stream().map(PatientDiagnosis::getPid).toList();
        Map<Long, PatientRecord> patientMap = getPatientMap(pids, req.getMrnOrName());

        // 组装结果
        List<ConfirmedDiseasePB> confirmedDiseases = new ArrayList<>();
        for (PatientDiagnosis diagnosis : diagnosisList) {
            PatientRecord patient = patientMap.get(diagnosis.getPid());
            if (patient == null) continue;

            DiseaseMetaPB diseaseMeta = getDiseaseMetaByCode(diagnosis.getDiseaseCode());
            if (diseaseMeta == null) {
                log.error("Disease meta not found: diseaseCode={}", diagnosis.getDiseaseCode());
                continue;
            }

            ConfirmedDiseasePB confirmedDisease = ConfirmedDiseasePB.newBuilder()
                .setId(diagnosis.getId())
                .setPatientMrn(patient.getHisMrn())
                .setPatientName(patient.getIcuName())
                .setDiseaseName(diseaseMeta.getName())
                .setConfirmedAtIso8601(TimeUtils.toIso8601String(diagnosis.getConfirmedAt(), ZONE_ID))
                .setConfirmedByAccountName(diagnosis.getConfirmedByAccountName())
                .build();
            confirmedDiseases.add(confirmedDisease);
        }

        return GetConfirmedDiseasesResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllConfirmedDisease(confirmedDiseases)
            .build();
    }

    @Transactional
    public GetExcludedDiseasesResp getExcludedDiseases(String getExcludedDiseasesReqJson) {
        final GetExcludedDiseasesReq req;
        try {
            GetExcludedDiseasesReq.Builder builder = GetExcludedDiseasesReq.newBuilder();
            JsonFormat.parser().merge(getExcludedDiseasesReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetExcludedDiseasesResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }
        final String deptId = req.getDeptId();

        // 查找疾病
        LocalDateTime startTime = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        if (startTime == null) startTime = TimeUtils.getLocalTime(1900, 1, 1);
        LocalDateTime endTime = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (endTime == null) endTime = TimeUtils.getLocalTime(9900, 1, 1);

        List<PatientDiagnosis> diagnosisList = patientDiagnosisRepo.findByDeletedAtBetweenAndIsDeletedTrue(
            startTime, endTime
        ).stream()
        .filter(diagnosis -> {
            if (StrUtils.isBlank(deptId)) return true;
            return diagnosis.getDeptId().equals(deptId);
        })
        .sorted(Comparator.comparing(PatientDiagnosis::getDeletedAt).reversed()).toList();
        List<Long> pids = diagnosisList.stream().map(PatientDiagnosis::getPid).toList();
        Map<Long, PatientRecord> patientMap = getPatientMap(pids, req.getMrnOrName());

        // 组装结果
        List<ExcludedDiseasePB> excludedDiseases = new ArrayList<>();
        for (PatientDiagnosis diagnosis : diagnosisList) {
            PatientRecord patient = patientMap.get(diagnosis.getPid());
            if (patient == null) continue;

            DiseaseMetaPB diseaseMeta = getDiseaseMetaByCode(diagnosis.getDiseaseCode());
            if (diseaseMeta == null) {
                log.error("Disease meta not found: diseaseCode={}", diagnosis.getDiseaseCode());
                continue;
            }

            ExcludedDiseasePB excludedDisease = ExcludedDiseasePB.newBuilder()
                .setId(diagnosis.getId())
                .setPatientMrn(patient.getHisMrn())
                .setPatientName(patient.getIcuName())
                .setDiseaseName(diseaseMeta.getName())
                .setConfirmedAtIso8601(TimeUtils.toIso8601String(diagnosis.getConfirmedAt(), ZONE_ID))
                .setConfirmedByAccountName(diagnosis.getConfirmedByAccountName())
                .setExcludedAtIso8601(TimeUtils.toIso8601String(diagnosis.getDeletedAt(), ZONE_ID))
                .setExcludedByAccountName(diagnosis.getDeletedByAccountName())
                .build();
            excludedDiseases.add(excludedDisease);
        }

        return GetExcludedDiseasesResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllExcludedDisease(excludedDiseases)
            .build();
    }

    public GetDiagnosisDetailsResp getDiagnosisDetails(String getDiagnosisDetailsReqJson) {
        final GetDiagnosisDetailsReq req;
        try {
            GetDiagnosisDetailsReq.Builder builder = GetDiagnosisDetailsReq.newBuilder();
            JsonFormat.parser().merge(getDiagnosisDetailsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetDiagnosisDetailsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        PatientDiagnosis diagnosis = patientDiagnosisRepo.findById(req.getId()).orElse(null);
        if (diagnosis == null) {
            return GetDiagnosisDetailsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_DISEASE_NOT_CONFIRMED))
                .build();
        }

        DiseasePB disease = ProtoUtils.decodeDisease(diagnosis.getDiseasePbtxt());
        if (disease == null) {
            log.error("Failed to decode disease: id={}", req.getId());
            return GetDiagnosisDetailsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DISEASE_DECODE_FAILED))
                .build();
        }

        return GetDiagnosisDetailsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setDiseaseDetails(disease)
            .build();
    }

    public GetDiseaseItemDetailsResp getDiseaseItemDetails(String getDiseaseItemDetailsReqJson) {
        final GetDiseaseItemDetailsReq req;
        try {
            GetDiseaseItemDetailsReq.Builder builder = GetDiseaseItemDetailsReq.newBuilder();
            JsonFormat.parser().merge(getDiseaseItemDetailsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetDiseaseItemDetailsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        LocalDateTime queryStart = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime queryEnd = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (queryStart == null || queryEnd == null) {
            log.error("Invalid time format: queryStart={}, queryEnd={}", req.getQueryStartIso8601(), req.getQueryEndIso8601());
            return GetDiseaseItemDetailsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_FORMAT))
                .build();
        }

        DiseaseItemDetailsPB details = doctorDataFetcher.fetchDiseaseItemDetails(
            req.getPid(), req.getDeptId(), req.getDiseaseItemCode(), queryStart, queryEnd);

        if (details == null) {
            log.error("Disease item details not found: pid={}, deptId={}, diseaseItemCode={}", req.getPid(), req.getDeptId(), req.getDiseaseItemCode());
            return GetDiseaseItemDetailsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DISEASE_ITEM_NOT_FOUND))
                .build();
        }

        return GetDiseaseItemDetailsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setDiseaseItemDetails(details)
            .build();
    }

    private DiseaseMetaPB getDiseaseMetaByCode(String diseaseCode) {
        return protoService.getConfig().getDiagnosis().getDiseaseMetaList().stream()
            .filter(diseaseMeta -> diseaseMeta.getCode().equals(diseaseCode))
            .findFirst()
            .orElse(null);
    }

    private Map<String, ValueMetaPB> getDiseaseItemMetaMap(DiseaseMetaPB diseaseMeta) {
        Map<String, ValueMetaPB> itemMetaMap = new HashMap<>();
        for (DiseasePhaseMetaPB phaseMeta : diseaseMeta.getPhaseList()) {
            for (DiseaseItemMetaPB itemMeta : phaseMeta.getItemList()) {
                itemMetaMap.put(itemMeta.getCode(), itemMeta.getValueMeta());
            }
        }
        return itemMetaMap;
    }

    private Map<Long, PatientRecord> getPatientMap(List<Long> pids, String mrnOrName) {
        Map<Long, PatientRecord> patientMap = new HashMap<>();
        if (pids.isEmpty()) return patientMap;

        List<PatientRecord> patients = patientRecordRepo.findByIdIn(pids);
        if (patients.isEmpty()) return patientMap;

        if (StrUtils.isBlank(mrnOrName)) {
            for (PatientRecord patient : patients) {
                patientMap.put(patient.getId(), patient);
            }
        } else {
            for (PatientRecord patient : patients) {
                if (patient.getHisMrn().contains(mrnOrName) || patient.getIcuName().contains(mrnOrName)) {
                    patientMap.put(patient.getId(), patient);
                }
            }
        }

        return patientMap;
    }

    private final String ZONE_ID;

    private final ConfigProtoService protoService;
    private final UserService userService;
    private final PatientService patientService;
    private final DoctorDataFetcher doctorDataFetcher;

    private final PatientRecordRepository patientRecordRepo;
    private final PatientDiagnosisRepository patientDiagnosisRepo;
    private final DiseaseMetaRepository diseaseMetaRepo;
}