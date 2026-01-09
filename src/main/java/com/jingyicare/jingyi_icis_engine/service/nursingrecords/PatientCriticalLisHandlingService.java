package com.jingyicare.jingyi_icis_engine.service.nursingrecords;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.lis.*;
import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.lis.*;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.reports.*;
import com.jingyicare.jingyi_icis_engine.service.users.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class PatientCriticalLisHandlingService {
    public PatientCriticalLisHandlingService(
        @Autowired ConfigProtoService protoService,
        @Autowired UserService userService,
        @Autowired PatientService patientService,
        @Autowired NursingRecordConfig nursingRecordConfig,
        @Autowired NursingRecordUtils nursingRecordUtils,
        @Autowired PatientNursingReportUtils pnrUtils,
        @Autowired NursingRecordRepository recordRepo,
        @Autowired PatientLisItemRepository lisItemRepo,
        @Autowired PatientLisResultRepository lisResultRepo,
        @Autowired PatientCriticalLisResultRepository criticalLisResultRepo,
        @Autowired PatientCriticalLisHandlingRepository criticalLisHandlingRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.statusCodeMsgList = protoService.getConfig().getText().getStatusCodeMsgList();

        this.protoService = protoService;
        this.userService = userService;
        this.patientService = patientService;
        this.nursingRecordConfig = nursingRecordConfig;
        this.nursingRecordUtils = nursingRecordUtils;
        this.pnrUtils = pnrUtils;
        this.recordRepo = recordRepo;
        this.lisItemRepo = lisItemRepo;
        this.lisResultRepo = lisResultRepo;
        this.criticalLisResultRepo = criticalLisResultRepo;
        this.criticalLisHandlingRepo = criticalLisHandlingRepo;
    }

    @Transactional
    public GetPatientCriticalLisHandlingsResp getPatientCriticalLisHandlings(String getPatientCriticalLisHandlingsReqJson) {
        final GetPatientCriticalLisHandlingsReq req;
        try {
            GetPatientCriticalLisHandlingsReq.Builder builder = GetPatientCriticalLisHandlingsReq.newBuilder();
            JsonFormat.parser().merge(getPatientCriticalLisHandlingsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetPatientCriticalLisHandlingsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GetPatientCriticalLisHandlingsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 获取患者信息
        Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetPatientCriticalLisHandlingsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String hisPid = patientRecord.getHisPatientId();
        final String deptId = patientRecord.getDeptId();

        // 提取关键参数
        LocalDateTime queryStart = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime queryEnd = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (queryStart == null || queryEnd == null) {
            log.error("Invalid query time: {} - {}", req.getQueryStartIso8601(), req.getQueryEndIso8601(), accountId);
            return GetPatientCriticalLisHandlingsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList,
                    StatusCode.INVALID_TIME_FORMAT,
                    ("start: " + req.getQueryStartIso8601() + ", end: " + req.getQueryEndIso8601())
                ))
                .build();
        }

        // 获取危急值记录
        List<PatientCriticalLisResult> lisResults = getPatientCriticalLisResults(
            pid, hisPid, deptId, queryStart, queryEnd, accountId
        );

        // 获取危急值处理记录
        List<Integer> handlingIds = new ArrayList<>();
        for (PatientCriticalLisResult result : lisResults) {
            if (result.getHandlingId() != null && result.getHandlingId() > 0) {
                handlingIds.add(result.getHandlingId());
            }
        }
        Map<Integer, PatientCriticalLisHandling> handlings = criticalLisHandlingRepo
            .findByIdInAndIsDeletedFalse(handlingIds)
            .stream()
            .collect(Collectors.toMap(PatientCriticalLisHandling::getId, handling -> handling));

        // 获取对应的护理记录内容
        Map<Integer/*handlingId*/, String> nursingRecordContents = new HashMap<>();
        if (!handlingIds.isEmpty()) {
            List<NursingRecord> nursingRecords = recordRepo
                .findByPatientIdAndPatientCriticalLisHandlingIdInAndIsDeletedFalse(pid, handlingIds);
            for (NursingRecord record : nursingRecords) {
                nursingRecordContents.put(record.getPatientCriticalLisHandlingId(), record.getContent());
            }
        }

        // 构建响应
        List<PatientCriticalLisHandlingRecordPB> handlingRecords = new ArrayList<>();
        for (PatientCriticalLisResult result : lisResults) {
            PatientCriticalLisHandlingRecordPB.Builder recordBuilder = PatientCriticalLisHandlingRecordPB.newBuilder()
                .setResult(toProto(result));
            // 添加处理记录
            PatientCriticalLisHandling handling = handlings.get(result.getHandlingId());
            if (handling != null) recordBuilder.setHandling(toProto(handling));
            // 添加护理记录内容
            String nursingRecordContent = nursingRecordContents.getOrDefault(result.getHandlingId(), "");
            recordBuilder.setNursingRecordContent(nursingRecordContent);

            handlingRecords.add(recordBuilder.build());
        }

        return GetPatientCriticalLisHandlingsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllRecord(handlingRecords)
            .build();
    }

    @Transactional
    public SavePatientCriticalLisHandlingResp savePatientCriticalLisHandling(String savePatientCriticalLisHandlingReqJson) {
        final SavePatientCriticalLisHandlingReq req;
        try {
            SavePatientCriticalLisHandlingReq.Builder builder = SavePatientCriticalLisHandlingReq.newBuilder();
            JsonFormat.parser().merge(savePatientCriticalLisHandlingReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return SavePatientCriticalLisHandlingResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return SavePatientCriticalLisHandlingResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 获取患者信息
        Long pid = req.getHandling().getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return SavePatientCriticalLisHandlingResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        String deptId = patientRecord.getDeptId();

        // 提取处理信息
        LocalDateTime effectiveTime = TimeUtils.fromIso8601String(
            req.getHandling().getEffectiveTimeIso8601(), "UTC"
        );
        if (effectiveTime == null) {
            log.error("Invalid effective time: {}", req.getHandling().getEffectiveTimeIso8601(), accountId);
            return SavePatientCriticalLisHandlingResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.INVALID_TIME_FORMAT))
                .build();
        }
        Integer resultId = req.getPatientCriticalLisResultId();
        Integer handlingId = req.getHandling().getId() > 0 ? req.getHandling().getId() : null;
        String actions = req.getHandling().getActions();
        LocalDateTime now = TimeUtils.getNowUtc();
        Boolean syncToNursingRecord = req.getSyncToNursingRecord();

        // 查找危急值记录
        PatientCriticalLisResult result = criticalLisResultRepo
            .findByIdAndPidAndIsDeletedFalse(resultId, pid)
            .orElse(null);
        if (result == null) {
            log.warn("Critical LIS result with ID {} for pid {} not found", resultId, pid);
            return SavePatientCriticalLisHandlingResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PATIENT_CRITICAL_LIS_RESULT_NOT_FOUND))
                .build();
        }

        // 创建或更新处理记录
        PatientCriticalLisHandling handling = null;
        if (handlingId != null) {
            handling = criticalLisHandlingRepo.findByIdAndIsDeletedFalse(handlingId).orElse(null);
            if (handling == null || handling.getPid() != pid) {
                log.warn("Handling with ID {}, pid {} not found", handlingId, pid);
                return SavePatientCriticalLisHandlingResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PATIENT_CRITICAL_LIS_HANDLING_NOT_FOUND))
                    .build();
            }
        } else {
            handling = new PatientCriticalLisHandling();
        }
        handling.setPid(pid);
        handling.setDeptId(deptId);
        handling.setActions(actions);
        handling.setEffectiveTime(effectiveTime);
        handling.setIsDeleted(false);
        handling.setModifiedBy(accountId);
        handling.setModifiedAt(now);
        handling = criticalLisHandlingRepo.save(handling);
        result.setHandlingId(handling.getId());
        result.setModifiedBy(accountId);
        result.setModifiedAt(now);
        criticalLisResultRepo.save(result);

        // 同步到护理记录
        if (syncToNursingRecord) {
          NursingRecord nursingRecord = recordRepo
                .findByPatientIdAndPatientCriticalLisHandlingIdAndIsDeletedFalse(
                    pid, handling.getId()
                ).orElse(null);
            if (nursingRecord == null) {
                nursingRecord = NursingRecord.builder()
                    .patientId(pid)
                    .content(actions)
                    .effectiveTime(effectiveTime)
                    .isDeleted(false)
                    .patientCriticalLisHandlingId(handling.getId())
                    .modifiedBy(accountId)
                    .modifiedByAccountName(accountName)
                    .modifiedAt(now)
                    .createdBy(accountId)
                    .createdByAccountName(accountName)
                    .createdAt(now)
                    .build();
                recordRepo.save(nursingRecord);
            } else {
                NursingRecordSettingsPB settingsPb = nursingRecordConfig.getNursingRecordSettings(deptId);
                boolean enableUpdatingCreatedBy = settingsPb.getEnableUpdatingCreatedBy();
                StatusCode status = nursingRecordUtils.updateNursingRecord(
                    nursingRecord.getId(), actions, effectiveTime, accountId, accountName,
                    enableUpdatingCreatedBy
                );
                if (status != StatusCode.OK) {
                    log.error("Failed to update nursing record for handling ID {}, status: {}", handling.getId(), status);
                    return SavePatientCriticalLisHandlingResp.newBuilder()
                        .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, status))
                        .build();
                }
            }

            // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
            pnrUtils.updateLatestDataTime(pid, deptId, effectiveTime, effectiveTime, now);
        }

        return SavePatientCriticalLisHandlingResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setId(handling.getId())
            .build();
    }

    @Transactional
    public GenericResp deletePatientCriticalLisHandling(String deletePatientCriticalLisHandlingReqJson) {
        final DeletePatientCriticalLisHandlingReq req;
        try {
            DeletePatientCriticalLisHandlingReq.Builder builder = DeletePatientCriticalLisHandlingReq.newBuilder();
            JsonFormat.parser().merge(deletePatientCriticalLisHandlingReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();
        final LocalDateTime now = TimeUtils.getNowUtc();

        // 删除对应的处理记录
        PatientCriticalLisHandling handling = criticalLisHandlingRepo
            .findByIdAndIsDeletedFalse(req.getId())
            .orElse(null);
        if (handling == null) {
            log.warn("Handling with ID {} not found", req.getId());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                .build();
        }
        handling.setIsDeleted(true);
        handling.setDeletedBy(accountId);
        handling.setDeletedAt(now);
        criticalLisHandlingRepo.save(handling);

        // 查找对应的危急值记录
        List<PatientCriticalLisResult> results = criticalLisResultRepo
            .findByPidAndHandlingIdAndIsDeletedFalse(handling.getPid(), handling.getId());
        for (PatientCriticalLisResult result : results) {
            result.setHandlingId(0); // 清除处理ID
            result.setModifiedBy(accountId);
            result.setModifiedAt(now);
        }
        criticalLisResultRepo.saveAll(results);

        // 删除护理记录
        NursingRecord nursingRecord = recordRepo
            .findByPatientIdAndPatientCriticalLisHandlingIdAndIsDeletedFalse(
                handling.getPid(), handling.getId()
            ).orElse(null);
        if (nursingRecord != null) {
            nursingRecord.setIsDeleted(true);
            nursingRecord.setDeletedBy(accountId);
            nursingRecord.setDeletedAt(now);
            recordRepo.save(nursingRecord);
        }

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    private List<PatientCriticalLisResult> getPatientCriticalLisResults(
        Long pid, String hisPid, String deptId, LocalDateTime queryStart, LocalDateTime queryEnd, String accountId
    ) {
        // 从lis表中同步记录
        List<PatientLisItem> lisItems = lisItemRepo.findByHisPidAndAuthTimeBetween(hisPid, queryStart, queryEnd);
        List<String> reportIds = lisItems.stream()
            .map(PatientLisItem::getReportId)
            .collect(Collectors.toList());
        Map<String, List<PatientLisResult>> reportIdToResultsMap = lisResultRepo.findByReportIdInAndIsDeletedFalse(reportIds)
            .stream()
            .filter(result -> !StrUtils.isBlank(result.getDangerFlag()))
            .collect(Collectors.groupingBy(PatientLisResult::getReportId));

        // 危急值记录查漏补缺
        List<PatientCriticalLisResult> criticalLisResults = criticalLisResultRepo
            .findByPidAndAuthTimeBetweenAndIsDeletedFalse(pid, queryStart, queryEnd);
        Set<Long> existingLisResultIds = criticalLisResults.stream()
            .map(PatientCriticalLisResult::getPatientLisResultId)
            .collect(Collectors.toSet());
        LocalDateTime now = TimeUtils.getNowUtc();

        List<PatientCriticalLisResult> newCriticalLisResults = new ArrayList<>();
        for (PatientLisItem item : lisItems) {
            String reportId = item.getReportId();
            List<PatientLisResult> results = reportIdToResultsMap.getOrDefault(reportId, Collections.emptyList());

            for (PatientLisResult result : results) {
                if (existingLisResultIds.contains(result.getId())) continue; // 已存在的危急值记录

                // 创建新的危急值记录
                PatientCriticalLisResult criticalResult = new PatientCriticalLisResult();
                criticalResult.setPid(pid);
                criticalResult.setDeptId(deptId);

                criticalResult.setReportId(reportId);
                criticalResult.setAuthTime(item.getAuthTime());
                criticalResult.setOrderDoctor(item.getOrderDoctor());
                criticalResult.setOrderDoctorId(item.getOrderDoctorId());
                criticalResult.setLisItemName(item.getLisItemName());
                criticalResult.setLisItemShortName(item.getLisItemShortName());
                criticalResult.setLisItemCode(item.getLisItemCode());

                criticalResult.setPatientLisResultId(result.getId());
                criticalResult.setExternalParamCode(result.getExternalParamCode());
                criticalResult.setExternalParamName(result.getExternalParamName());
                criticalResult.setUnit(result.getUnit());
                criticalResult.setResultStr(result.getResultStr());
                criticalResult.setNormalMinStr(result.getNormalMinStr());
                criticalResult.setNormalMaxStr(result.getNormalMaxStr());
                criticalResult.setAlarmFlag(result.getAlarmFlag());
                criticalResult.setDangerFlag(result.getDangerFlag());
                criticalResult.setNotes(result.getNotes());

                criticalResult.setHandlingId(0); // 初始处理ID为0，表示未处理

                criticalResult.setIsDeleted(false);
                criticalResult.setModifiedBy(accountId);
                criticalResult.setModifiedAt(now);

                newCriticalLisResults.add(criticalResult);
            }
        }

        // 保存新的危急值记录
        if (!newCriticalLisResults.isEmpty()) {
            criticalLisResultRepo.saveAll(newCriticalLisResults);
            criticalLisResults.addAll(newCriticalLisResults);
        }

        criticalLisResults.sort(Comparator.comparing(PatientCriticalLisResult::getAuthTime));
        return criticalLisResults;
    }

    private PatientCriticalLisResultPB toProto(PatientCriticalLisResult result) {
        if (result == null) return null;

        return PatientCriticalLisResultPB.newBuilder()
                .setId(result.getId())
                .setPid(result.getPid())
                .setDeptId(result.getDeptId())
                .setReportId(result.getReportId() == null ? "" : result.getReportId())
                .setAuthTimeIso8601(TimeUtils.toIso8601String(result.getAuthTime(), ZONE_ID))
                .setOrderDoctor(result.getOrderDoctor())
                .setOrderDoctorId(result.getOrderDoctorId() == null ? "" : result.getOrderDoctorId())
                .setLisItemName(result.getLisItemName())
                .setLisItemShortName(result.getLisItemShortName() == null ? "" : result.getLisItemShortName())
                .setLisItemCode(result.getLisItemCode() == null ? "" : result.getLisItemCode())
                .setExternalParamCode(result.getExternalParamCode())
                .setExternalParamName(result.getExternalParamName())
                .setUnit(result.getUnit() == null ? "" : result.getUnit())
                .setResultStr(result.getResultStr())
                .setNormalMinStr(result.getNormalMinStr() == null ? "" : result.getNormalMinStr())
                .setNormalMaxStr(result.getNormalMaxStr() == null ? "" : result.getNormalMaxStr())
                .setAlarmFlag(result.getAlarmFlag() == null ? "" : result.getAlarmFlag())
                .setDangerFlag(result.getDangerFlag() == null ? "" : result.getDangerFlag())
                .setNotes(result.getNotes() == null ? "" : result.getNotes())
                .setHandlingId(result.getHandlingId() == null ? 0 : result.getHandlingId())
                .build();
    }


    private PatientCriticalLisHandlingPB toProto(PatientCriticalLisHandling handling) {
        if (handling == null) return null;

        return PatientCriticalLisHandlingPB.newBuilder()
                .setId(handling.getId())
                .setPid(handling.getPid())
                .setDeptId(handling.getDeptId())
                .setActions(handling.getActions())
                .setEffectiveTimeIso8601(TimeUtils.toIso8601String(handling.getEffectiveTime(), ZONE_ID))
                .build();
    }

    private final String ZONE_ID;
    private final List<String> statusCodeMsgList;

    private final ConfigProtoService protoService;
    private final UserService userService;
    private final PatientService patientService;
    private final NursingRecordConfig nursingRecordConfig;
    private final NursingRecordUtils nursingRecordUtils;
    private final PatientNursingReportUtils pnrUtils;

    private final NursingRecordRepository recordRepo;
    private final PatientLisItemRepository lisItemRepo;
    private final PatientLisResultRepository lisResultRepo;
    private final PatientCriticalLisResultRepository criticalLisResultRepo;
    private final PatientCriticalLisHandlingRepository criticalLisHandlingRepo;
}