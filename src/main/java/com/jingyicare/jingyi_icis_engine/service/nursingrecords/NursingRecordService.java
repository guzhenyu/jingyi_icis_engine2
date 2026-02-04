package com.jingyicare.jingyi_icis_engine.service.nursingrecords;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientMonitoringRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.lis.*;
import com.jingyicare.jingyi_icis_engine.service.medications.*;
import com.jingyicare.jingyi_icis_engine.service.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.reports.*;
import com.jingyicare.jingyi_icis_engine.service.shifts.*;
import com.jingyicare.jingyi_icis_engine.service.tubes.*;
import com.jingyicare.jingyi_icis_engine.service.users.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class NursingRecordService {
    public NursingRecordService(
        @Autowired ConfigProtoService protoService,
        @Autowired UserService userService,
        @Autowired UserBasicOperator userBasicOp,
        @Autowired PatientService patientService,
        @Autowired MedReportUtils medReportUtils,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired PatientMonitoringService patientMonitoringService,
        @Autowired BalanceCalculator balanceCalculator,
        @Autowired ConfigShiftUtils shiftUtils,
        @Autowired PatientTubeImpl patientTubeImpl,
        @Autowired LisService lisService,
        @Autowired PatientNursingReportUtils pnrUtils,
        @Autowired NursingRecordConfig recordConfig,
        @Autowired NursingRecordUtils recordUtils,
        @Autowired NursingRecordTemplateGroupRepository templateGroupRepo,
        @Autowired NursingRecordTemplateRepository templateRepo,
        @Autowired NursingRecordRepository recordRepo,
        @Autowired PatientMonitoringRecordRepository monitoringRecordRepo
    ) {
        MonitoringPB monitoringPb = protoService.getConfig().getMonitoring();
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.ENTITY_TYPE_ACCOUNT = 0;
        this.ENTITY_TYPE_DEPT = 1;
        this.BALANCE_GROUP_TYPE_ID = monitoringPb.getEnums().getGroupTypeBalance().getId();
        this.BALANCE_IN_GROUP_NAME = monitoringPb.getBalanceInGroupName();
        this.BALANCE_OUT_GROUP_NAME = monitoringPb.getBalanceOutGroupName();
        this.BALANCE_NET_GROUP_NAME = monitoringPb.getBalanceNetGroupName();
        this.NET_HOURLY_IN_CODE = monitoringPb.getNetHourlyInCode();
        this.NET_HOURLY_OUT_CODE = monitoringPb.getNetHourlyOutCode();
        this.NET_HOURLY_NET_CODE = monitoringPb.getNetHourlyNetCode();
        this.SUMMARY_MONITORING_CODE = monitoringPb.getParamCodeSummary();

        this.protoService = protoService;
        this.configPb = protoService.getConfig().getNursingRecord();
        this.userService = userService;
        this.userBasicOp = userBasicOp;
        this.patientService = patientService;
        this.medReportUtils = medReportUtils;
        this.monitoringConfig = monitoringConfig;
        this.patientMonitoringService = patientMonitoringService;
        this.balanceCalculator = balanceCalculator;
        this.shiftUtils = shiftUtils;
        this.patientTubeImpl = patientTubeImpl;
        this.lisService = lisService;
        this.pnrUtils = pnrUtils;
        this.recordConfig = recordConfig;
        this.recordUtils = recordUtils;
        this.templateGroupRepo = templateGroupRepo;
        this.templateRepo = templateRepo;
        this.recordRepo = recordRepo;
        this.monitoringRecordRepo = monitoringRecordRepo;
    }

    public GetNursingRecordTemplatesResp getNursingRecordTemplates(String getNursingRecordTemplatesReqJson) {
        final GetNursingRecordTemplatesReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getNursingRecordTemplatesReqJson, GetNursingRecordTemplatesReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetNursingRecordTemplatesResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        GetNursingRecordTemplatesResp.Builder respBuilder = GetNursingRecordTemplatesResp.newBuilder();
        if (!StrUtils.isBlank(req.getDeptId())) {
            respBuilder.addAllDeptTemplate(recordConfig.getTemplates(ENTITY_TYPE_DEPT, req.getDeptId()));
        }

        // 获取用户信息
        String ctxAccountId = req.getAccountId();
        if (!StrUtils.isBlank(ctxAccountId)) {
            Account account = userBasicOp.getAccount(ctxAccountId);
            String accountId = account != null ? account.getId().toString() : ctxAccountId;
            respBuilder.addAllAccountTemplate(recordConfig.getTemplates(ENTITY_TYPE_ACCOUNT, accountId));
        }

        return respBuilder.setRt(protoService.getReturnCode(StatusCode.OK)).build();
    }

    @Transactional
    public AddNursingRecordTemplateGroupResp addNursingRecordTemplateGroup(String addNursingRecordTemplateGroupReqJson) {
        final AddNursingRecordTemplateGroupReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addNursingRecordTemplateGroupReqJson, AddNursingRecordTemplateGroupReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return AddNursingRecordTemplateGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return AddNursingRecordTemplateGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 获取模板类型
        final Integer entityType = !StrUtils.isBlank(req.getDeptId()) ? ENTITY_TYPE_DEPT : ENTITY_TYPE_ACCOUNT;
        final String entityId = !StrUtils.isBlank(req.getDeptId()) ? req.getDeptId() : accountId;

        // 查找是否存在
        if (templateGroupRepo.findByEntityTypeAndEntityIdAndNameAndIsDeletedFalse(
            entityType, entityId, req.getGroup().getName()).isPresent()
        ) {
            log.error("Template group already exists: {}", req.getGroup().getName());
            return AddNursingRecordTemplateGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_ALREADY_EXISTS))
                .build();
        }

        // 创建新的模板组
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        NursingRecordTemplateGroup group = NursingRecordTemplateGroup.builder()
            .entityType(entityType)
            .entityId(entityId)
            .name(req.getGroup().getName())
            .displayOrder(req.getGroup().getDisplayOrder())
            .isDeleted(false)
            .modifiedBy(accountId)
            .modifiedAt(nowUtc)
            .build();
        group = templateGroupRepo.save(group);

        return AddNursingRecordTemplateGroupResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(group.getId())
            .build();
    }

    @Transactional
    public GenericResp updateNursingRecordTemplateGroups(String updateNursingRecordTemplateGroupsReqJson) {
        final UpdateNursingRecordTemplateGroupsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updateNursingRecordTemplateGroupsReqJson, UpdateNursingRecordTemplateGroupsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 获取模板类型
        final Integer entityType = !StrUtils.isBlank(req.getDeptId()) ? ENTITY_TYPE_DEPT : ENTITY_TYPE_ACCOUNT;
        final String entityId = !StrUtils.isBlank(req.getDeptId()) ? req.getDeptId() : accountId;

        // 待更新
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        List<NursingRecordTemplateGroup> updatedGroups = new ArrayList<>();

        // 检查现有的组是否已存在
        Map<String, NursingRecordTemplateGroup> existingGroups = templateGroupRepo
            .findByEntityTypeAndEntityIdAndIsDeletedFalse(entityType, entityId).stream()
            .collect(Collectors.toMap(NursingRecordTemplateGroup::getName, group -> group));
        Map<Integer, NursingRecordTemplateGroup> existingGroupsById = existingGroups.values().stream()
            .collect(Collectors.toMap(NursingRecordTemplateGroup::getId, group -> group));
        for (NursingRecordTemplateGroupPB group : req.getGroupList()) {
            NursingRecordTemplateGroup existingGroup = existingGroupsById.get(group.getId());
            if (existingGroup == null) {
                log.error("Template group not exists: {}", group.getName());
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_NOT_EXISTS))
                    .build();
            } else {
                NursingRecordTemplateGroup dupGroup = existingGroups.get(group.getName());
                if (dupGroup != null && !existingGroup.getId().equals(dupGroup.getId())) {
                    log.error("Template group already exists: {}", group.getName());
                    return GenericResp.newBuilder()
                        .setRt(protoService.getReturnCode(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_ALREADY_EXISTS))
                        .build();
                }
            }
            if ((group.getDisplayOrder() > 0 && existingGroup.getDisplayOrder() != group.getDisplayOrder()) ||
                (!StrUtils.isBlank(group.getName()) && !existingGroup.getName().equals(group.getName()))
            ) {
                existingGroup.setDisplayOrder(group.getDisplayOrder());
                existingGroup.setName(group.getName());
                existingGroup.setModifiedBy(accountId);
                existingGroup.setModifiedAt(nowUtc);
                updatedGroups.add(existingGroup);
            }
        }

        // 更新
        templateGroupRepo.saveAll(updatedGroups);
        log.info("Updated nursing record template groups: {}, by {}\n, ", updatedGroups, accountId);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteNursingRecordTemplateGroup(String deleteNursingRecordTemplateGroupReqJson) {
        final DeleteNursingRecordTemplateGroupReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteNursingRecordTemplateGroupReqJson, DeleteNursingRecordTemplateGroupReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 查找模板组
        NursingRecordTemplateGroup group = templateGroupRepo.findById(req.getId()).orElse(null);
        if (group == null) {
            log.error("Template group not exists: {}", req.getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        // 检查该组下面是否有其他模板，如果有，不允许删除
        List<NursingRecordTemplate> templates = templateRepo.findByEntityTypeAndEntityIdAndGroupIdAndIsDeletedFalse(
            group.getEntityType(), group.getEntityId(), group.getId());
        if (!templates.isEmpty()) {
            log.error("Template group has dept templates: {}", group.getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_HAS_TEMPLATES))
                .build();
        }

        // 删除
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        group.setIsDeleted(true);
        group.setDeletedBy(accountId);
        group.setDeletedAt(nowUtc);
        templateGroupRepo.save(group);
        log.info("Deleted nursing record template group: {}, by {}\n, ", group, accountId);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    public AddNursingRecordTemplateResp addNursingRecordTemplate(String addNursingRecordTemplateReqJson) {
        final AddNursingRecordTemplateReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addNursingRecordTemplateReqJson, AddNursingRecordTemplateReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return AddNursingRecordTemplateResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return AddNursingRecordTemplateResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 创建模板
        final String templateName = req.getTemplate().getName();
        final Integer entityType = !StrUtils.isBlank(req.getDeptId()) ? ENTITY_TYPE_DEPT : ENTITY_TYPE_ACCOUNT;
        final String entityId = !StrUtils.isBlank(req.getDeptId()) ? req.getDeptId() : accountId;

        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        AddNursingRecordTemplateResp.Builder respBuilder = AddNursingRecordTemplateResp.newBuilder();

        // 查找模板组
        Integer groupId = req.getTemplate().getGroupId();
        NursingRecordTemplateGroup group = templateGroupRepo.findByIdAndIsDeletedFalse(groupId).orElse(null);
        if (group == null) {
            log.error("Template group not exists: {}", groupId);
            return AddNursingRecordTemplateResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_NOT_EXISTS))
                .build();
        }

        // 检查模板是否已存在
        if (templateRepo.existsByEntityTypeAndEntityIdAndNameAndIsDeletedFalse(
            entityType, entityId, templateName
        )) {
            log.error("Template already exists: {}", templateName);
            return AddNursingRecordTemplateResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_RECORD_TEMPLATE_ALREADY_EXISTS))
                .build();
        }

        // 创建模板
        NursingRecordTemplate newTemplate = NursingRecordTemplate.builder()
            .entityType(entityType)
            .entityId(entityId)
            .name(templateName)
            .content(req.getTemplate().getContent())
            .displayOrder(req.getTemplate().getDisplayOrder())
            .isCommon(req.getTemplate().getIsCommon())
            .groupId(groupId)
            .isDeleted(false)
            .modifiedBy(accountId)
            .modifiedAt(nowUtc)
            .build();
        newTemplate = templateRepo.save(newTemplate);
        respBuilder.setId(newTemplate.getId());

        return respBuilder.setRt(protoService.getReturnCode(StatusCode.OK)).build();
    }

    public GenericResp updateNursingRecordTemplate(String updateNursingRecordTemplateReqJson) {
        final UpdateNursingRecordTemplateReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updateNursingRecordTemplateReqJson, UpdateNursingRecordTemplateReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 更新模板
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        GenericResp.Builder respBuilder = GenericResp.newBuilder();
        NursingRecordTemplate templateToUpdate = templateRepo.findById(req.getTemplate().getId()).orElse(null);
        if (templateToUpdate == null) {
            log.error("Template not exists: {}", req.getTemplate().getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_RECORD_TEMPLATE_NOT_EXISTS))
                .build();
        }

        // 确认组存在
        Integer groupId = req.getTemplate().getGroupId();
        NursingRecordTemplateGroup group = templateGroupRepo.findById(groupId).orElse(null);
        if (group == null) {
            log.error("Template group not exists: {}", groupId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_NOT_EXISTS))
                .build();
        }

        // 更新
        templateToUpdate.setContent(req.getTemplate().getContent());
        templateToUpdate.setDisplayOrder(req.getTemplate().getDisplayOrder());
        templateToUpdate.setIsCommon(req.getTemplate().getIsCommon());
        templateToUpdate.setGroupId(groupId);
        templateToUpdate.setModifiedBy(accountId);
        templateToUpdate.setModifiedAt(nowUtc);
        templateRepo.save(templateToUpdate);
        log.info("Updated nursing record template: {}, by {}\n, ", templateToUpdate, accountId);

        return respBuilder.setRt(protoService.getReturnCode(StatusCode.OK)).build();
    }

    public GenericResp deleteNursingRecordTemplate(String deleteNursingRecordTemplateReqJson) {
        final DeleteNursingRecordTemplateReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteNursingRecordTemplateReqJson, DeleteNursingRecordTemplateReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 检查模板是否存在
        NursingRecordTemplate templateToDelete = templateRepo.findById(req.getId()).orElse(null);
        if (templateToDelete == null) {
            log.error("Template not exists: {}", req.getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        // 删除
        templateToDelete.setIsDeleted(true);
        templateToDelete.setDeletedBy(accountId);
        templateToDelete.setDeletedAt(TimeUtils.getNowUtc());
        templateRepo.save(templateToDelete);

        return GenericResp.newBuilder().setRt(protoService.getReturnCode(StatusCode.OK)).build();
    }

    public GetNursingRecordResp getNursingRecord(String getNursingRecordReqJson) {
        final GetNursingRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getNursingRecordReqJson, GetNursingRecordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetNursingRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        LocalDateTime queryStartUtc = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime queryEndUtc = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");

        List<PatientNursingRecordValPB> records = recordRepo
            .findByPatientIdAndEffectiveTimeBetweenAndIsDeletedFalse(req.getPid(), queryStartUtc, queryEndUtc)
            .stream()
            .sorted(Comparator.comparing(NursingRecord::getEffectiveTime))
            .map(record -> {
                PatientNursingRecordValPB.Builder recordBuilder = PatientNursingRecordValPB.newBuilder()
                    .setId(record.getId())
                    .setContent(record.getContent())
                    .setRecordedBy(StrUtils.isBlank(record.getCreatedBy()) ?
                        record.getModifiedBy() : record.getCreatedBy()
                    )
                    .setRecordedByAccountName(StrUtils.isBlank(record.getCreatedByAccountName()) ?
                        record.getModifiedByAccountName() : record.getCreatedByAccountName()
                    )
                    .setRecordedAtIso8601(TimeUtils.toIso8601String(record.getEffectiveTime(), ZONE_ID));
                return recordBuilder.build();
            })
            .collect(Collectors.toList());

        return GetNursingRecordResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllRecord(records)
            .build();
    }

    public AddNursingRecordResp addNursingRecord(String addNursingRecordReqJson) {
        final AddNursingRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addNursingRecordReqJson, AddNursingRecordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return AddNursingRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            log.error("accountId is empty.");
            return AddNursingRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 根据req.pid和effectiveTime查找护理记录是否存在
        Long patientId = req.getPid();
        PatientNursingRecordValPB recordPb = req.getRecord();
        LocalDateTime effectiveTime = TimeUtils.fromIso8601String(recordPb.getRecordedAtIso8601(), "UTC");
        NursingRecord patientNursingRecord = recordRepo
            .findByPatientIdAndEffectiveTimeAndPatientCriticalLisHandlingIdAndIsDeletedFalse(patientId, effectiveTime, null)
            .orElse(null);
        if (patientNursingRecord != null) {
            log.error("Nursing record already exists: {}", patientId);
            return AddNursingRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_RECORD_ALREADY_EXISTS))
                .build();
        }

        // 获取患者信息
        final PatientRecord patientRecord = patientService.getPatientRecord(patientId);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return AddNursingRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();

        // 创建新的护理记录
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        NursingRecord record = NursingRecord.builder()
            .patientId(patientId)
            .content(recordPb.getContent())
            .effectiveTime(effectiveTime)
            .isDeleted(false)
            .modifiedBy(accountId)
            .modifiedByAccountName(accountName)
            .modifiedAt(nowUtc)
            .createdBy(accountId)
            .createdByAccountName(accountName)
            .createdAt(nowUtc)
            .build();
        record = recordRepo.save(record);

        // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
        pnrUtils.updateLatestDataTime(patientId, deptId, effectiveTime, effectiveTime, nowUtc);

        return AddNursingRecordResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(record.getId())
            .build();
    }

    public GenericResp updateNursingRecord(String updateNursingRecordReqJson) {
        final UpdateNursingRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updateNursingRecordReqJson, UpdateNursingRecordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();
        final String accountName = accountWithAutoId.getSecond();

        // 提取关键参数
        PatientNursingRecordValPB recordPb = req.getRecord();
        long entityId = recordPb.getId();
        String content = recordPb.getContent();
        LocalDateTime effectiveTimeUtc = TimeUtils.fromIso8601String(recordPb.getRecordedAtIso8601(), "UTC");
        boolean enableUpdatingCreatedBy = recordConfig.getEnableUpdatingCreatedBy(entityId);
        // String modifiedBy = !StrUtils.isBlank(recordPb.getRecordedBy()) ? recordPb.getRecordedBy() : accountId;
        // String modifiedByAccountName = !StrUtils.isBlank(recordPb.getRecordedBy()) ?
        //     userService.getNameByAutoId(recordPb.getRecordedBy()) : accountName;

        // 查找记录
        NursingRecord record = recordRepo.findByIdAndIsDeletedFalse(entityId).orElse(null);
        if (record == null) {
            log.error("Nursing record not exists: {}", entityId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_RECORD_NOT_EXISTS))
                .build();
        }
        LocalDateTime effectiveTime = record.getEffectiveTime();

        // 获取患者信息
        final Long pid = record.getPatientId();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();

        // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
        pnrUtils.updateLatestDataTime(pid, deptId, effectiveTime, effectiveTime, TimeUtils.getNowUtc());

        StatusCode statusCode = recordUtils.updateNursingRecord(
            entityId, content, effectiveTimeUtc,
            accountId, accountName, enableUpdatingCreatedBy
        );

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(statusCode))
            .build();
    }

    public GenericResp deleteNursingRecord(String deleteNursingRecordReqJson) {
        final DeleteNursingRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteNursingRecordReqJson, DeleteNursingRecordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 查找记录
        NursingRecord record = recordRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (record == null) {
            log.error("Nursing record not exists: {}", req.getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }
        LocalDateTime effectiveTime = record.getEffectiveTime();

        // 获取患者信息
        final Long pid = record.getPatientId();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();

        // 删除
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        record.setIsDeleted(true);
        record.setModifiedBy(accountId);
        record.setModifiedAt(nowUtc);
        recordRepo.save(record);

        // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
        pnrUtils.updateLatestDataTime(pid, deptId, effectiveTime, effectiveTime, nowUtc);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    public GetNursingRecordShortcutsResp getNursingRecordShortcuts(String getNursingRecordShortcutsReqJson) {
        final GetNursingRecordShortcutsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getNursingRecordShortcutsReqJson, GetNursingRecordShortcutsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetNursingRecordShortcutsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GetNursingRecordShortcutsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 解析参数
        final Long pid = req.getPid();
        final LocalDateTime startTime = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        final LocalDateTime endTime = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            log.error("Invalid time range.");
            return GetNursingRecordShortcutsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_RANGE))
                .build();
        }

        // 获取患者信息
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetNursingRecordShortcutsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();

        // 获取执行医嘱
        List<MedExeRecordSummaryPB> mrsList = medReportUtils.generateMedExeRecordSummaryList(
            pid, deptId, startTime, endTime, TimeUtils.getNowUtc(), false
        ).stream()
        .sorted(Comparator.comparing(MedExeRecordSummaryPB::getIntakeGroupId)
            .thenComparing(MedExeRecordSummaryPB::getStartTimeIso8601)
        )
        .toList();
        List<NrShortcutMedExecPB> nrsMedExecList = new ArrayList<>();
        NrShortcutMedExecPB.Builder cur = null;
        for (MedExeRecordSummaryPB mrs : mrsList) {
            int gid = mrs.getIntakeGroupId();
            // 当 cur 为空或组别变化时，收尾上一组并开启新组
            if (cur == null || cur.getIntakeGroupId() != gid) {
                if (cur != null) nrsMedExecList.add(cur.build());
                cur = NrShortcutMedExecPB.newBuilder()
                        .setIntakeGroupId(gid)
                        .setIntakeGroupName(mrs.getIntakeGroupName());
            }
            cur.addMedExec(mrs);
        }
        if (cur != null) nrsMedExecList.add(cur.build());

        // 获取出入量记录
        Pair<LocalDateTime, LocalDateTime> queryUtcTimeRange = monitoringConfig.normalizePmrQueryTimeRange(
            BALANCE_GROUP_TYPE_ID, deptId, startTime, endTime
        );
        List<String> tubeParamCodes = patientTubeImpl.getMonitoringParamCodes(
            pid, queryUtcTimeRange.getFirst(), queryUtcTimeRange.getSecond()
        );
        List<MonitoringGroupBetaPB> groupBetaList = monitoringConfig.getMonitoringGroups(
            pid, deptId, BALANCE_GROUP_TYPE_ID, tubeParamCodes, accountId
        );
        PatientMonitoringService.GetMonitoringRecordsResult recordsResult = patientMonitoringService
            .getMonitoringRecords(pid, deptId, BALANCE_GROUP_TYPE_ID,
                startTime, endTime, true, groupBetaList, accountId
            );
        if (recordsResult.statusCode != StatusCode.OK) {
            log.error("Failed to get monitoring records: {}", recordsResult.statusCode);
            return GetNursingRecordShortcutsResp.newBuilder()
                .setRt(protoService.getReturnCode(recordsResult.statusCode))
                .build();
        }
        BalanceCalculator.GetGroupRecordsListArgs args = new BalanceCalculator.GetGroupRecordsListArgs(
            pid, deptId,
            recordsResult.recordsQueryStartUtc,
            recordsResult.recordsQueryEndUtc,
            recordsResult.balanceStatTimeUtcHistory,
            monitoringConfig.getMonitoringParams(deptId),
            recordsResult.groupBetaList,
            recordsResult.recordList,
            accountId
        );
        balanceCalculator.storeGroupRecordsStats(args);  // 存储小时汇总数据，统计对应的天数据
        List<PatientMonitoringRecord> recordList = recordsResult.recordList.stream()
            .filter(record -> !record.getEffectiveTime().isBefore(startTime) &&
                record.getEffectiveTime().isBefore(endTime)
            )
            .sorted(Comparator.comparing(PatientMonitoringRecord::getEffectiveTime))
            .toList();
        args.recordList = recordList;
        args.startTime = startTime;
        args.endTime = endTime;
        List<BalanceGroupSummaryPB> balanceGroupList = balanceCalculator.summarizeBalanceGroups(args);

        // 获取检验结果
        Pair<StatusCode, List<OverviewLisItemPB>> lisPair = lisService.getPatientLisItems(
            patientRecord, startTime, endTime
        );
        if (lisPair.getFirst() != StatusCode.OK) {
            return GetNursingRecordShortcutsResp.newBuilder()
                .setRt(protoService.getReturnCode(lisPair.getFirst()))
                .build();
        }
        List<OverviewLisItemPB> lisItemList = lisPair.getSecond();

        return GetNursingRecordShortcutsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllMedExec(nrsMedExecList)
            .addAllBalance(balanceGroupList)
            .addAllLisItem(lisItemList)
            .build();
    }

    public GetNursingRecordShortcuts2Resp getNursingRecordShortcuts2(String getNursingRecordShortcuts2ReqJson) {
        final GetNursingRecordShortcuts2Req req;
        try {
            req = ProtoUtils.parseJsonToProto(getNursingRecordShortcuts2ReqJson, GetNursingRecordShortcuts2Req.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetNursingRecordShortcuts2Resp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GetNursingRecordShortcuts2Resp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 解析参数
        final Long pid = req.getPid();
        LocalDateTime shiftStartUtc = TimeUtils.fromIso8601String(req.getShiftStartIso8601(), "UTC");
        if (shiftStartUtc == null) {
            log.error("Invalid shift start time: {}", req.getShiftStartIso8601());
            return GetNursingRecordShortcuts2Resp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_RANGE))
                .build();
        }

        // 获取患者信息
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetNursingRecordShortcuts2Resp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();

        // 班次起始时间
        List<LocalDateTime> deptBalanceStatsUtcs = shiftUtils.getBalanceStatTimeUtcHistory(deptId);
        LocalDateTime midnightUtc = pnrUtils.getMidnightUtc(deptBalanceStatsUtcs, shiftStartUtc);
        LocalDateTime dayEndUtc = midnightUtc.plusDays(1).minusSeconds(1);
        LocalDateTime dbsuCurUtc = null;
        for (LocalDateTime statUtc : deptBalanceStatsUtcs) {
            if (!dayEndUtc.isBefore(statUtc)) {
                dbsuCurUtc = statUtc;
            } else {
                break;
            }
        }
        if (dbsuCurUtc == null) {
            log.error("Cannot find deptBalanceStatUtc for midnightUtc={}", midnightUtc);
            return GetNursingRecordShortcuts2Resp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NO_SHIFT_SETTING))
                .build();
        }
        LocalDateTime dbsuCurLocal = TimeUtils.getLocalDateTimeFromUtc(dbsuCurUtc, ZONE_ID);
        int shiftStartHour = dbsuCurLocal.getHour();
        shiftStartUtc = midnightUtc.plusHours(shiftStartHour);
        final LocalDateTime shiftEndUtc = shiftStartUtc.plusDays(1);

        Pair<StatusCode, List<NrShortcutBalancesPB>> balancePair = buildShortcutBalances(
            pid, deptId, shiftStartUtc, shiftEndUtc, accountId);
        if (balancePair.getFirst() != StatusCode.OK) {
            return GetNursingRecordShortcuts2Resp.newBuilder()
                .setRt(protoService.getReturnCode(balancePair.getFirst()))
                .build();
        }
        List<NrShortcutBalancesPB> balanceList = balancePair.getSecond();

        return GetNursingRecordShortcuts2Resp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllBalance(balanceList)
            .build();
    }

    private Pair<StatusCode, List<NrShortcutBalancesPB>> buildShortcutBalances(
        Long pid, String deptId, LocalDateTime shiftStartUtc, LocalDateTime shiftEndUtc, String accountId
    ) {
        Pair<LocalDateTime, LocalDateTime> queryUtcTimeRange = monitoringConfig.normalizePmrQueryTimeRange(
            BALANCE_GROUP_TYPE_ID, deptId, shiftStartUtc, shiftEndUtc
        );
        List<String> tubeParamCodes = patientTubeImpl.getMonitoringParamCodes(
            pid, queryUtcTimeRange.getFirst(), queryUtcTimeRange.getSecond()
        );
        List<MonitoringGroupBetaPB> groupBetaList = monitoringConfig.getMonitoringGroups(
            pid, deptId, BALANCE_GROUP_TYPE_ID, tubeParamCodes, accountId
        );
        PatientMonitoringService.GetMonitoringRecordsResult recordsResult = patientMonitoringService
            .getMonitoringRecords(pid, deptId, BALANCE_GROUP_TYPE_ID,
                shiftStartUtc, shiftEndUtc, true, groupBetaList, accountId
            );
        if (recordsResult.statusCode != StatusCode.OK) {
            log.error("Failed to get monitoring records: {}", recordsResult.statusCode);
            return new Pair<>(recordsResult.statusCode, new ArrayList<>());
        }

        BalanceCalculator.GetGroupRecordsListArgs args = new BalanceCalculator.GetGroupRecordsListArgs(
            pid, deptId,
            recordsResult.recordsQueryStartUtc,
            recordsResult.recordsQueryEndUtc,
            recordsResult.balanceStatTimeUtcHistory,
            monitoringConfig.getMonitoringParams(deptId),
            recordsResult.groupBetaList,
            recordsResult.recordList,
            accountId
        );
        balanceCalculator.storeGroupRecordsStats(args);
        List<PatientMonitoringRecord> recordList = recordsResult.recordList.stream()
            .filter(record -> !record.getEffectiveTime().isBefore(shiftStartUtc) &&
                record.getEffectiveTime().isBefore(shiftEndUtc)
            )
            .sorted(Comparator.comparing(PatientMonitoringRecord::getEffectiveTime))
            .toList();
        args.recordList = recordList;
        args.startTime = shiftStartUtc;
        args.endTime = shiftEndUtc;

        List<MonitoringGroupRecordsPB> groupRecordsList = balanceCalculator.getGroupRecordsList(args);
        Map<Integer, String> groupNameMap = recordsResult.groupBetaList.stream()
            .collect(Collectors.toMap(MonitoringGroupBetaPB::getId, MonitoringGroupBetaPB::getName));
        MonitoringGroupRecordsPB inGroupRecords = null;
        MonitoringGroupRecordsPB outGroupRecords = null;
        MonitoringGroupRecordsPB netGroupRecords = null;
        for (MonitoringGroupRecordsPB gr : groupRecordsList) {
            String groupName = groupNameMap.get(gr.getDeptMonitoringGroupId());
            if (BALANCE_IN_GROUP_NAME.equals(groupName)) inGroupRecords = gr;
            else if (BALANCE_OUT_GROUP_NAME.equals(groupName)) outGroupRecords = gr;
            else if (BALANCE_NET_GROUP_NAME.equals(groupName)) netGroupRecords = gr;
        }

        Map<String, MonitoringParamPB> paramMap = monitoringConfig.getMonitoringParams(deptId);
        Map<LocalDateTime, NrShortcutBalancesPB.Builder> balanceMap = new TreeMap<>();

        List<String> hourlyCodes = new ArrayList<>();
        if (!StrUtils.isBlank(NET_HOURLY_IN_CODE)) hourlyCodes.add(NET_HOURLY_IN_CODE);
        if (!StrUtils.isBlank(NET_HOURLY_OUT_CODE)) hourlyCodes.add(NET_HOURLY_OUT_CODE);
        List<PatientMonitoringRecord> hourlyRecords = hourlyCodes.isEmpty()
            ? new ArrayList<>()
            : monitoringRecordRepo.findByPidAndParamCodesAndEffectiveTimeRange(
                pid, hourlyCodes, shiftStartUtc, shiftEndUtc
            ).stream()
            .sorted(Comparator.comparing(PatientMonitoringRecord::getEffectiveTime))
            .toList();
        fillShortcutBalanceHourlyValues(balanceMap, hourlyRecords, paramMap);

        appendBalanceDetails(balanceMap, inGroupRecords, paramMap, NrShortcutBalancesPB.Builder::addInDetails, null);
        appendBalanceDetails(balanceMap, outGroupRecords, paramMap, NrShortcutBalancesPB.Builder::addOutDetails, null);
        appendBalanceDetails(balanceMap, netGroupRecords, paramMap, NrShortcutBalancesPB.Builder::addNetDetails,
            Set.of(NET_HOURLY_IN_CODE, NET_HOURLY_OUT_CODE, NET_HOURLY_NET_CODE));
        fillShortcutBalanceAccumulatedDetails(balanceMap, paramMap);

        List<NrShortcutBalancesPB> balanceList = balanceMap.values().stream()
            .map(NrShortcutBalancesPB.Builder::build)
            .toList();
        return new Pair<>(StatusCode.OK, balanceList);
    }

    private NrShortcutBalancesPB.Builder getBalanceBuilder(
        Map<LocalDateTime, NrShortcutBalancesPB.Builder> balanceMap, LocalDateTime timeUtc
    ) {
        return balanceMap.computeIfAbsent(timeUtc,
            t -> NrShortcutBalancesPB.newBuilder()
                .setEffectiveTimeIso8601(TimeUtils.toIso8601String(t, ZONE_ID))
        );
    }

    private void fillShortcutBalanceHourlyValues(
        Map<LocalDateTime, NrShortcutBalancesPB.Builder> balanceMap,
        List<PatientMonitoringRecord> hourlyRecords,
        Map<String, MonitoringParamPB> paramMap
    ) {
        if (balanceMap == null || hourlyRecords == null || hourlyRecords.isEmpty()) return;

        MonitoringParamPB inParam = paramMap == null ? null : paramMap.get(NET_HOURLY_IN_CODE);
        MonitoringParamPB outParam = paramMap == null ? null : paramMap.get(NET_HOURLY_OUT_CODE);
        MonitoringParamPB netParam = paramMap == null ? null : paramMap.get(NET_HOURLY_NET_CODE);
        ValueMetaPB inMeta = inParam == null ? null : inParam.getValueMeta();
        ValueMetaPB outMeta = outParam == null ? null : outParam.getValueMeta();
        ValueMetaPB netMeta = netParam == null ? null : netParam.getValueMeta();

        Map<LocalDateTime, GenericValuePB> inValueMap = new TreeMap<>();
        Map<LocalDateTime, GenericValuePB> outValueMap = new TreeMap<>();
        for (PatientMonitoringRecord record : hourlyRecords) {
            if (record == null) continue;
            LocalDateTime timeUtc = record.getEffectiveTime();
            if (timeUtc == null ||
                timeUtc.getMinute() != 0 || timeUtc.getSecond() != 0 || timeUtc.getNano() != 0
            ) {
                continue;
            }
            String code = record.getMonitoringParamCode();
            if (NET_HOURLY_IN_CODE.equals(code) && inMeta != null) {
                GenericValuePB value = getMonitoringRecordValue(record, inMeta);
                if (value == null) continue;
                value = ValueMetaUtils.formatParamValue(value, inMeta);
                GenericValuePB existing = inValueMap.get(timeUtc);
                inValueMap.put(timeUtc,
                    existing == null ? value : ValueMetaUtils.addGenericValue(existing, value, inMeta)
                );
            } else if (NET_HOURLY_OUT_CODE.equals(code) && outMeta != null) {
                GenericValuePB value = getMonitoringRecordValue(record, outMeta);
                if (value == null) continue;
                value = ValueMetaUtils.formatParamValue(value, outMeta);
                GenericValuePB existing = outValueMap.get(timeUtc);
                outValueMap.put(timeUtc,
                    existing == null ? value : ValueMetaUtils.addGenericValue(existing, value, outMeta)
                );
            }
        }

        if (inMeta != null) {
            GenericValuePB accIn = ValueMetaUtils.getDefaultValue(inMeta);
            for (Map.Entry<LocalDateTime, GenericValuePB> entry : inValueMap.entrySet()) {
                LocalDateTime timeUtc = entry.getKey();
                GenericValuePB value = entry.getValue();
                if (value == null) continue;
                NrShortcutBalancesPB.Builder builder = getBalanceBuilder(balanceMap, timeUtc);
                builder.setInMl(ValueMetaUtils.extractAndFormatParamValue(value, inMeta));
                accIn = ValueMetaUtils.addGenericValue(accIn, value, inMeta);
                builder.setAccInMl(ValueMetaUtils.extractAndFormatParamValue(accIn, inMeta));
            }
        }

        if (outMeta != null) {
            GenericValuePB accOut = ValueMetaUtils.getDefaultValue(outMeta);
            for (Map.Entry<LocalDateTime, GenericValuePB> entry : outValueMap.entrySet()) {
                LocalDateTime timeUtc = entry.getKey();
                GenericValuePB value = entry.getValue();
                if (value == null) continue;
                NrShortcutBalancesPB.Builder builder = getBalanceBuilder(balanceMap, timeUtc);
                builder.setOutMl(ValueMetaUtils.extractAndFormatParamValue(value, outMeta));
                accOut = ValueMetaUtils.addGenericValue(accOut, value, outMeta);
                builder.setAccOutMl(ValueMetaUtils.extractAndFormatParamValue(accOut, outMeta));
            }
        }

        ValueMetaPB netValueMeta = netMeta != null ? netMeta : (inMeta != null ? inMeta : outMeta);
        if (netValueMeta == null) return;

        Set<LocalDateTime> netTimes = new TreeSet<>();
        netTimes.addAll(inValueMap.keySet());
        netTimes.addAll(outValueMap.keySet());
        GenericValuePB accNet = ValueMetaUtils.getDefaultValue(netValueMeta);
        for (LocalDateTime timeUtc : netTimes) {
            GenericValuePB netValue = ValueMetaUtils.getDefaultValue(netValueMeta);
            GenericValuePB inValue = inValueMap.get(timeUtc);
            if (inValue != null && inMeta != null) {
                netValue = ValueMetaUtils.addGenericValue(
                    netValue,
                    convertMonitoringValue(inValue, inMeta, netValueMeta),
                    netValueMeta
                );
            }
            GenericValuePB outValue = outValueMap.get(timeUtc);
            if (outValue != null && outMeta != null) {
                netValue = ValueMetaUtils.subtractGenericValue(
                    netValue,
                    convertMonitoringValue(outValue, outMeta, netValueMeta),
                    netValueMeta
                );
            }
            NrShortcutBalancesPB.Builder builder = getBalanceBuilder(balanceMap, timeUtc);
            builder.setNetMl(ValueMetaUtils.extractAndFormatParamValue(netValue, netValueMeta));
            accNet = ValueMetaUtils.addGenericValue(accNet, netValue, netValueMeta);
            builder.setAccNetMl(ValueMetaUtils.extractAndFormatParamValue(accNet, netValueMeta));
        }
    }

    private void fillShortcutBalanceAccumulatedDetails(
        Map<LocalDateTime, NrShortcutBalancesPB.Builder> balanceMap,
        Map<String, MonitoringParamPB> paramMap
    ) {
        if (balanceMap == null || balanceMap.isEmpty()) return;

        Map<String, ValueMetaPB> metaByKey = new LinkedHashMap<>();
        if (paramMap != null) {
            for (MonitoringParamPB param : paramMap.values()) {
                if (param == null) continue;
                String name = param.getName();
                ValueMetaPB valueMeta = param.getValueMeta();
                if (StrUtils.isBlank(name) || valueMeta == null) continue;
                metaByKey.putIfAbsent(name, valueMeta);
            }
        }

        Map<String, Double> accInMap = new LinkedHashMap<>();
        Map<String, Double> accOutMap = new LinkedHashMap<>();
        Map<String, Double> accNetMap = new LinkedHashMap<>();
        for (NrShortcutBalancesPB.Builder builder : balanceMap.values()) {
            accumulateDetailsByKey(builder.getInDetailsList(), accInMap);
            builder.addAllAccInDetails(buildAccumulatedDetailList(accInMap, metaByKey));
            accumulateDetailsByKey(builder.getOutDetailsList(), accOutMap);
            builder.addAllAccOutDetails(buildAccumulatedDetailList(accOutMap, metaByKey));
            accumulateDetailsByKey(builder.getNetDetailsList(), accNetMap);
            builder.addAllAccNetDetails(buildAccumulatedDetailList(accNetMap, metaByKey));
        }
    }

    private void accumulateDetailsByKey(
        List<StrKeyValPB> details,
        Map<String, Double> accMap
    ) {
        if (details == null || details.isEmpty() || accMap == null) return;
        for (StrKeyValPB detail : details) {
            if (detail == null) continue;
            String key = detail.getKey();
            if (StrUtils.isBlank(key)) continue;
            Double value = parseDetailValue(detail.getVal());
            if (value == null) continue;
            accMap.put(key, accMap.getOrDefault(key, 0.0d) + value);
        }
    }

    private List<StrKeyValPB> buildAccumulatedDetailList(
        Map<String, Double> accMap,
        Map<String, ValueMetaPB> metaByKey
    ) {
        if (accMap == null || accMap.isEmpty()) return new ArrayList<>();
        List<StrKeyValPB> result = new ArrayList<>();
        for (Map.Entry<String, Double> entry : accMap.entrySet()) {
            String key = entry.getKey();
            Double value = entry.getValue();
            ValueMetaPB valueMeta = metaByKey == null ? null : metaByKey.get(key);
            String valueStr = formatAccumulatedValue(value, valueMeta);
            result.add(StrKeyValPB.newBuilder()
                .setKey(key == null ? "" : key)
                .setVal(valueStr == null ? "" : valueStr)
                .build());
        }
        return result;
    }

    private Double parseDetailValue(String valueStr) {
        if (StrUtils.isBlank(valueStr)) return null;
        String normalized = valueStr.replace(",", "").trim();
        if (normalized.isEmpty()) return null;
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatAccumulatedValue(Double value, ValueMetaPB valueMeta) {
        if (value == null) return "";
        if (valueMeta != null && ValueMetaUtils.isNumber(valueMeta)) {
            return ValueMetaUtils.formatNumberValue(value, valueMeta);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private GenericValuePB getMonitoringRecordValue(PatientMonitoringRecord record, ValueMetaPB valueMeta) {
        if (record == null) return null;
        MonitoringValuePB monitoringValuePB = ProtoUtils.decodeMonitoringValue(record.getParamValue());
        if (monitoringValuePB != null) return monitoringValuePB.getValue();
        String valueStr = record.getParamValueStr();
        if (StrUtils.isBlank(valueStr) || valueMeta == null) return null;
        return ValueMetaUtils.toGenericValue(valueStr, valueMeta);
    }

    private GenericValuePB convertMonitoringValue(
        GenericValuePB value, ValueMetaPB srcMeta, ValueMetaPB dstMeta
    ) {
        if (value == null || srcMeta == null || dstMeta == null) return value;
        return ValueMetaUtils.convertGenericValue(value, srcMeta, dstMeta);
    }

    private void appendBalanceDetails(
        Map<LocalDateTime, NrShortcutBalancesPB.Builder> balanceMap,
        MonitoringGroupRecordsPB groupRecords,
        Map<String, MonitoringParamPB> paramMap,
        BiConsumer<NrShortcutBalancesPB.Builder, StrKeyValPB> detailAppender,
        Set<String> allowedParamCodes
    ) {
        if (groupRecords == null || detailAppender == null) return;
        for (MonitoringCodeRecordsPB codeRecords : groupRecords.getCodeRecordsList()) {
            String paramCode = codeRecords.getParamCode();
            if (SUMMARY_MONITORING_CODE.equals(paramCode)) continue;
            if (allowedParamCodes != null && !allowedParamCodes.contains(paramCode)) continue;

            MonitoringParamPB paramMeta = paramMap.get(paramCode);
            String key = paramMeta == null ? paramCode : paramMeta.getName();
            ValueMetaPB valueMeta = paramMeta == null ? null : paramMeta.getValueMeta();
            for (MonitoringRecordValPB val : codeRecords.getRecordValueList()) {
                if (StrUtils.isBlank(val.getRecordedAtIso8601())) continue;
                LocalDateTime timeUtc = TimeUtils.fromIso8601String(val.getRecordedAtIso8601(), "UTC");
                if (timeUtc == null) continue;
                String valueStr = val.getValueStr();
                if (StrUtils.isBlank(valueStr) && valueMeta != null) {
                    valueStr = ValueMetaUtils.extractAndFormatParamValue(val.getValue(), valueMeta);
                }
                NrShortcutBalancesPB.Builder builder = getBalanceBuilder(balanceMap, timeUtc);
                detailAppender.accept(builder, StrKeyValPB.newBuilder()
                    .setKey(key == null ? "" : key)
                    .setVal(valueStr == null ? "" : valueStr)
                    .build());
            }
        }
    }

    private String formatMonitoringValue(PatientMonitoringRecord record, String deptId) {
        String valueStr = record.getParamValueStr();
        if (!StrUtils.isBlank(valueStr)) return valueStr;

        MonitoringValuePB monitoringValuePB = ProtoUtils.decodeMonitoringValue(record.getParamValue());
        if (monitoringValuePB == null) return valueStr;
        ValueMetaPB meta = monitoringConfig.getMonitoringParamMeta(deptId, record.getMonitoringParamCode());
        if (meta == null) return valueStr;
        return ValueMetaUtils.extractAndFormatParamValue(monitoringValuePB.getValue(), meta);
    }

    private final String ZONE_ID;
    private final Integer ENTITY_TYPE_ACCOUNT;
    private final Integer ENTITY_TYPE_DEPT;
    private final Integer BALANCE_GROUP_TYPE_ID;
    private final String BALANCE_IN_GROUP_NAME;
    private final String BALANCE_OUT_GROUP_NAME;
    private final String BALANCE_NET_GROUP_NAME;
    private final String NET_HOURLY_IN_CODE;
    private final String NET_HOURLY_OUT_CODE;
    private final String NET_HOURLY_NET_CODE;
    private final String SUMMARY_MONITORING_CODE;

    private final ConfigProtoService protoService;
    private final NursingRecordConfigPB configPb;
    private final UserService userService;
    private final UserBasicOperator userBasicOp;
    private final PatientService patientService;
    private final MedReportUtils medReportUtils;
    private final MonitoringConfig monitoringConfig;
    private final PatientMonitoringService patientMonitoringService;
    private final BalanceCalculator balanceCalculator;
    private final ConfigShiftUtils shiftUtils;
    private final PatientTubeImpl patientTubeImpl;
    private final LisService lisService;
    private final PatientNursingReportUtils pnrUtils;
    private final NursingRecordConfig recordConfig;
    private final NursingRecordUtils recordUtils;
    private final NursingRecordTemplateGroupRepository templateGroupRepo;
    private final NursingRecordTemplateRepository templateRepo;
    private final NursingRecordRepository recordRepo;
    private final PatientMonitoringRecordRepository monitoringRecordRepo;
}
