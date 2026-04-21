package com.jingyicare.jingyi_icis_engine.service.nursingorders;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingOrder.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.entity.nursingorders.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.nursingorders.*;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.service.medications.*;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.shifts.*;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class NursingOrderService {
    public NursingOrderService(
        @Autowired ConfigProtoService protoService,
        @Autowired UserService userService,
        @Autowired ConfigShiftUtils shiftUtils,
        @Autowired PatientService patientService,
        @Autowired MedicationConfig medConfig,
        @Autowired NursingOrderConfig orderConfig,
        @Autowired NursingOrderSyncService nursingOrderSyncService,
        @Autowired NursingOrderTemplateGroupRepository templateGroupRepo,
        @Autowired NursingOrderTemplateRepository templateRepo,
        @Autowired NursingOrderRepository orderRepo,
        @Autowired NursingExecutionRecordRepository recordRepo,
        @Autowired MedicationFrequencyRepository medFreqRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();

        this.protoService = protoService;
        this.userService = userService;
        this.shiftUtils = shiftUtils;
        this.patientService = patientService;
        this.medConfig = medConfig;
        this.orderConfig = orderConfig;
        this.nursingOrderSyncService = nursingOrderSyncService;
        this.templateGroupRepo = templateGroupRepo;
        this.templateRepo = templateRepo;
        this.orderRepo = orderRepo;
        this.recordRepo = recordRepo;
        this.medFreqRepo = medFreqRepo;
    }

    @Transactional
    public GetNursingOrderTemplatesResp getNursingOrderTemplates(String getNursingOrderTemplatesReqJson) {
        GetNursingOrderTemplatesReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getNursingOrderTemplatesReqJson, GetNursingOrderTemplatesReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetNursingOrderTemplatesResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        GetNursingOrderTemplatesResp.Builder respBuilder = GetNursingOrderTemplatesResp.newBuilder();
        if (!StrUtils.isBlank(req.getDeptId())) {
            respBuilder.addAllGroup(orderConfig.getDeptTemplates(req.getDeptId()));
        }

        return respBuilder.setRt(protoService.getReturnCode(StatusCode.OK)).build();
    }

    @Transactional
    public AddNursingOrderTemplateGroupResp addNursingOrderTemplateGroup(String addNursingOrderTemplateGroupReqJson) {
        AddNursingOrderTemplateGroupReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addNursingOrderTemplateGroupReqJson, AddNursingOrderTemplateGroupReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return AddNursingOrderTemplateGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return AddNursingOrderTemplateGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 检查部门ID是否为空
        if (StrUtils.isBlank(req.getDeptId())) {
            log.error("deptId is empty.");
            return AddNursingOrderTemplateGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEPT_IS_EMPTY))
                .build();
        }

        // 查找是否存在
        if (templateGroupRepo.findByDeptIdAndNameAndIsDeletedFalse(req.getDeptId(), req.getGroup().getName()).isPresent()) {
            log.error("Nursing order template group already exists: {}", req.getGroup().getName());
            return AddNursingOrderTemplateGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_DEPT_TEMPLATE_GROUP_ALREADY_EXISTS))
                .build();
        }

        // 创建新的模板组
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        NursingOrderTemplateGroup group = NursingOrderTemplateGroup.builder()
            .deptId(req.getDeptId())
            .name(req.getGroup().getName())
            .displayOrder(req.getGroup().getDisplayOrder())
            .isDeleted(false)
            .modifiedBy(accountId)
            .modifiedAt(nowUtc)
            .build();
        group = templateGroupRepo.save(group);
        log.info("Added nursing order template group: {}, by {}\n, ", group, accountId);

        return AddNursingOrderTemplateGroupResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(group.getId())
            .build();
    }

    @Transactional
    public GenericResp updateNursingOrderTemplateGroup(String updateNursingOrderTemplateGroupReqJson) {
        UpdateNursingOrderTemplateGroupReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updateNursingOrderTemplateGroupReqJson, UpdateNursingOrderTemplateGroupReq.newBuilder());
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

        // 待更新
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        List<NursingOrderTemplateGroup> updatedGroups = new ArrayList<>();

        // 检查现有的组是否已存在
        NursingOrderTemplateGroup existingGroup = templateGroupRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (existingGroup == null) {
            log.error("Template group not exists: {}", req.getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_DEPT_TEMPLATE_GROUP_NOT_EXISTS))
                .build();
        }

        // 更新
        existingGroup.setName(req.getName());
        templateGroupRepo.save(existingGroup);
        log.info("Updated nursing order template group: {}, by {}\n, ", existingGroup, accountId);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteNursingOrderTemplateGroup(String deleteNursingOrderTemplateGroupReqJson) {
        DeleteNursingOrderTemplateGroupReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteNursingOrderTemplateGroupReqJson, DeleteNursingOrderTemplateGroupReq.newBuilder());
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
        NursingOrderTemplateGroup group = templateGroupRepo.findById(req.getId()).orElse(null);
        if (group == null) {
            log.error("Nursing order template group not exists: {}", req.getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        // 检查改组下面是否有其他模板，如果有，不允许删除
        List<NursingOrderTemplate> templates = templateRepo
            .findByDeptIdAndGroupIdAndIsDeletedFalse(group.getDeptId(), group.getId());
        if (!templates.isEmpty()) {
            log.error("Nursing order template group has dept templates: {}", group.getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_DEPT_TEMPLATE_GROUP_HAS_TEMPLATES))
                .build();
        }

        // 删除
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        group.setIsDeleted(true);
        group.setDeletedBy(accountId);
        group.setDeletedAt(nowUtc);
        group.setModifiedBy(accountId);
        group.setModifiedAt(nowUtc);
        templateGroupRepo.save(group);
        log.info("Deleted nursing record template group: {}, by {}\n, ", group, accountId);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public AddNursingOrderTemplateResp addNursingOrderTemplate(String addNursingOrderTemplateReqJson) {
        AddNursingOrderTemplateReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addNursingOrderTemplateReqJson, AddNursingOrderTemplateReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return AddNursingOrderTemplateResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return AddNursingOrderTemplateResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 检查部门
        if (StrUtils.isBlank(req.getDeptId())) {
            log.error("deptId is empty.");
            return AddNursingOrderTemplateResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEPT_IS_EMPTY))
                .build();
        }

        // 查找模板组
        Integer groupId = req.getTemplate().getGroupId();
        NursingOrderTemplateGroup group = templateGroupRepo.findById(groupId).orElse(null);
        if (group == null) {
            log.error("Template group not exists: {}", groupId);
            return AddNursingOrderTemplateResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_DEPT_TEMPLATE_GROUP_NOT_EXISTS))
                .build();
        }

        // 查找频次编码是否存在
        MedicationFrequency freq = medFreqRepo.findByCodeAndIsDeletedFalse(
            req.getTemplate().getMedicationFreqCode()).orElse(null);
        if (freq == null || !freq.getSupportNursingOrder()) {
            log.error("Frequency not exists or not supported for nursing order: {}", req.getTemplate().getMedicationFreqCode());
            return AddNursingOrderTemplateResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_FREQUENCY_NOT_EXISTS))
                .build();
        }

        // 检查模板是否已存在
        String templateName = req.getTemplate().getName();
        if (templateRepo.existsByDeptIdAndGroupIdAndNameAndIsDeletedFalse(
            req.getDeptId(), groupId, templateName
        )) {
            log.error("Template already exists: {}", templateName);
            return AddNursingOrderTemplateResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_DEPT_TEMPLATE_ALREADY_EXISTS))
                .build();
        }

        // 创建模板
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        NursingOrderTemplate template = NursingOrderTemplate.builder()
            .deptId(req.getDeptId())
            .groupId(groupId)
            .name(templateName)
            .durationType(req.getTemplate().getDurationType())
            .medicationFreqCode(req.getTemplate().getMedicationFreqCode())
            .displayOrder(req.getTemplate().getDisplayOrder())
            .isDeleted(false)
            .modifiedBy(accountId)
            .modifiedAt(nowUtc)
            .build();
        template = templateRepo.save(template);
        log.info("Added nursing order template: {}, by {}\n, ", template, accountId);

        return AddNursingOrderTemplateResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(template.getId())
            .build();
    }

    @Transactional
    public GenericResp updateNursingOrderTemplate(String updateNursingOrderTemplateReqJson) {
        UpdateNursingOrderTemplateReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updateNursingOrderTemplateReqJson, UpdateNursingOrderTemplateReq.newBuilder());
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
        Integer groupId = req.getTemplate().getGroupId();
        NursingOrderTemplateGroup group = templateGroupRepo.findById(groupId).orElse(null);
        if (group == null) {
            log.error("Template group not exists: {}", groupId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_DEPT_TEMPLATE_GROUP_NOT_EXISTS))
                .build();
        }

        // 查找频次编码是否存在
        MedicationFrequency freq = medFreqRepo.findByCodeAndIsDeletedFalse(
            req.getTemplate().getMedicationFreqCode()).orElse(null);
        if (freq == null || !freq.getSupportNursingOrder()) {
            log.error("Frequency not exists or not supported for nursing order: {}", req.getTemplate().getMedicationFreqCode());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_FREQUENCY_NOT_EXISTS))
                .build();
        }

        // 检查模板是否已存在
        NursingOrderTemplate template = templateRepo.findById(req.getTemplate().getId()).orElse(null);
        if (template == null) {
            log.error("Template not exists: {}", req.getTemplate().getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_DEPT_TEMPLATE_NOT_EXISTS))
                .build();
        }

        // 更新模板
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        // template.setGroupId(groupId);
        template.setName(req.getTemplate().getName());
        template.setDurationType(req.getTemplate().getDurationType());
        template.setMedicationFreqCode(req.getTemplate().getMedicationFreqCode());
        template.setDisplayOrder(req.getTemplate().getDisplayOrder());
        template.setModifiedBy(accountId);
        template.setModifiedAt(nowUtc);
        templateRepo.save(template);
        log.info("Updated nursing order template: {}, by {}\n, ", template, accountId);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteNursingOrderTemplate(String deleteNursingOrderTemplateReqJson) {
        DeleteNursingOrderTemplateReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteNursingOrderTemplateReqJson, DeleteNursingOrderTemplateReq.newBuilder());
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

        // 检查模板是否已存在
        NursingOrderTemplate template = templateRepo.findById(req.getId()).orElse(null);
        if (template == null) {
            log.error("Template not exists: {}", req.getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_DEPT_TEMPLATE_NOT_EXISTS))
                .build();
        }

        // 更新模板
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        template.setIsDeleted(true);
        template.setDeletedAt(nowUtc);
        template.setDeletedBy(accountId);
        template.setModifiedAt(nowUtc);
        template.setModifiedBy(accountId);
        templateRepo.save(template);
        log.info("Deleted nursing order template: {}, by {}\n, ", template, accountId);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GetNursingOrderDetailsResp getNursingOrderDetails(String getNursingOrderDetailsReqJson) {
        GetNursingOrderDetailsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getNursingOrderDetailsReqJson, GetNursingOrderDetailsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetNursingOrderDetailsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GetNursingOrderDetailsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 获取查询条件
        final Long pid = req.getPid();
        final LocalDateTime queryStartUtc = TimeUtils.fromIso8601String(
            req.getQueryStartIso8601(), "UTC");
        final LocalDateTime queryEndUtc = TimeUtils.fromIso8601String(
            req.getQueryEndIso8601(), "UTC").minusMinutes(1);

        // 查询病人
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("PatientRecord not found: ", pid);
            return GetNursingOrderDetailsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final Boolean patientInIcu = (patientRecord.getAdmissionStatus() == patientService.getAdmissionStatusInIcuId());
        final String deptId = patientRecord.getDeptId();
        final ShiftSettingsPB shiftSettings = shiftUtils.getShiftByDeptId(deptId);

        StatusCode syncStatus = nursingOrderSyncService.syncFromMedicalOrders(patientRecord, accountId);
        if (syncStatus != StatusCode.OK) {
            return GetNursingOrderDetailsResp.newBuilder()
                .setRt(protoService.getReturnCode(syncStatus))
                .build();
        }

        // 查询护理计划
        List<NursingOrder> orders = orderRepo.findByPidAndIsDeletedFalse(pid);
        List<Long> orderIds = orders.stream().map(NursingOrder::getId).toList();
        final Map<Long, List<NursingExecutionRecord>> recordMap = orderIds.isEmpty() ?
            new HashMap<>() :
            recordRepo
                .findByNursingOrderIdInAndPlanTimeBetween(
                    orderIds, shiftUtils.getShiftStartTimeUtc(shiftSettings, queryStartUtc, ZONE_ID),
                    shiftUtils.getShiftStartTimeUtc(shiftSettings, queryEndUtc, ZONE_ID).plusDays(1).minusMinutes(1)
                ).stream()
                .collect(Collectors.groupingBy(NursingExecutionRecord::getNursingOrderId));

        // 分解没有护理记录的护理计划，获取护理记录
        List<NursingOrder> noRecordOrders = orders.stream()
            .filter(order -> !recordMap.containsKey(order.getId()))
            .toList();
        Map<Long, List<NursingExecutionRecord>> newRecordMap = parseNursingOrders(
            accountId, noRecordOrders, shiftSettings, queryStartUtc, queryEndUtc, patientInIcu);

        // 根据护理计划获得对应的分组
        List<NursingOrder> hisOrders = orders.stream()
            .filter(NursingOrderSyncService::isHisSynced)
            .toList();
        List<NursingOrder> templateOrders = orders.stream()
            .filter(order -> !NursingOrderSyncService.isHisSynced(order))
            .filter(order -> order.getOrderTemplateId() != null)
            .toList();
        Set<Integer> templateIds = templateOrders.stream()
            .map(NursingOrder::getOrderTemplateId)
            .collect(Collectors.toSet());
        Map<Integer, Integer> groupMap = templateIds.isEmpty() ?
            new HashMap<>() :
            templateRepo.findByIdIn(templateIds).stream()
                .collect(Collectors.toMap(NursingOrderTemplate::getId, NursingOrderTemplate::getGroupId));
        Set<Integer> groupIds = groupMap.values().stream().collect(Collectors.toSet());
        List<NursingOrderTemplateGroup> groups = groupIds.isEmpty() ?
            List.of() :
            templateGroupRepo.findByIdIn(groupIds).stream().toList();
        Map<Integer, List<NursingOrder>> groupOrderMap = new HashMap<>();
        for (NursingOrder order : templateOrders) {
            Integer groupId = groupMap.get(order.getOrderTemplateId());
            if (groupId == null) continue;
            groupOrderMap.computeIfAbsent(groupId, k -> new ArrayList<>()).add(order);
        }

        // 组装分组/护理计划/护理记录
        List<NursingOrderTemplateGroupPB> groupPbList = composeNursingOrderDetails(
            groups, groupOrderMap, hisOrders, recordMap, newRecordMap, queryStartUtc, queryEndUtc
        );

        return GetNursingOrderDetailsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllGroup(groupPbList)
            .build();
    }

    @Transactional
    public GetNursingOrdersResp getNursingOrders(String getNursingOrdersReqJson) {
        GetNursingOrdersReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getNursingOrdersReqJson, GetNursingOrdersReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetNursingOrdersResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息，用于同步HIS护嘱的modified_by
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GetNursingOrdersResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        final PatientRecord patientRecord = patientService.getPatientRecord(req.getPid());
        if (patientRecord == null) {
            log.error("PatientRecord not found: {}", req.getPid());
            return GetNursingOrdersResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        StatusCode syncStatus = nursingOrderSyncService.syncFromMedicalOrders(patientRecord, accountId);
        if (syncStatus != StatusCode.OK) {
            return GetNursingOrdersResp.newBuilder()
                .setRt(protoService.getReturnCode(syncStatus))
                .build();
        }

        List<NursingOrderPB> orderPbList = orderRepo.findByPidAndIsDeletedFalse(req.getPid()).stream()
            .map(order -> buildNursingOrderPB(order, List.of()))
            .toList();
        return GetNursingOrdersResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllOrder(orderPbList.stream().sorted(Comparator.comparing(NursingOrderPB::getOrderTimeIso8601)).toList())
            .build();
    }

    @Transactional
    public GetNursingOrderResp getNursingOrder(String getNursingOrderReqJson) {
        GetNursingOrderReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getNursingOrderReqJson, GetNursingOrderReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetNursingOrderResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取护嘱
        NursingOrder order = orderRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (order == null) {
            log.error("Nursing order not exists: {}", req.getId());
            return GetNursingOrderResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_NOT_EXISTS))
                .build();
        }

        // 查询病人
        final PatientRecord patientRecord = patientService.getPatientRecord(order.getPid());
        if (patientRecord == null) {
            log.error("PatientRecord not found: ", order.getPid());
            return GetNursingOrderResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final ShiftSettingsPB shiftSettings = shiftUtils.getShiftByDeptId(patientRecord.getDeptId());

        // 获取相关执行记录
        final LocalDateTime queryStartUtc = TimeUtils.fromIso8601String(
            req.getQueryStartIso8601(), "UTC");
        final LocalDateTime queryEndUtc = TimeUtils.fromIso8601String(
            req.getQueryEndIso8601(), "UTC");
        if (queryStartUtc == null || queryEndUtc == null) {
            log.error("Invalid query time: {} - {}", req.getQueryStartIso8601(), req.getQueryEndIso8601());
            return GetNursingOrderResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_RANGE))
                .build();
        }
        List<NursingExecutionRecord> records = recordRepo
            .findByNursingOrderIdAndPlanTimeBetweenAndIsDeletedFalse(
                order.getId(), queryStartUtc, queryEndUtc.minusMinutes(1)
            ).stream()
            .sorted(Comparator.comparing(NursingExecutionRecord::getPlanTime))
            .toList();
        List<NursingExeRecordPB> recordPbList = records.stream()
            .map(this::buildNursingExeRecordPB)
            .toList();

        return GetNursingOrderResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setOrder(buildNursingOrderPB(order, recordPbList))
            .build();
    }

    @Transactional
    public AddNursingOrdersResp addNursingOrders(String addNursingOrdersReqJson) {
        AddNursingOrdersReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addNursingOrdersReqJson, AddNursingOrdersReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return AddNursingOrdersResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return AddNursingOrdersResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 查询病人
        final Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecordInIcu(pid);
        if (patientRecord == null) {
            log.error("PatientRecord not found: ", pid);
            return AddNursingOrdersResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_IN_ICU))
                .build();
        }
        final String deptId = patientRecord.getDeptId();

        // 检查合法性
        for (NursingOrderPB orderPb : req.getOrderList()) {
            // 检查模板的合法性
            NursingOrderTemplate template = templateRepo.findById(orderPb.getOrderTemplateId()).orElse(null);
            if (template == null) {
                log.error("Nursing order template not exists: {}", orderPb.getOrderTemplateId());
                return AddNursingOrdersResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_DEPT_TEMPLATE_NOT_EXISTS))
                    .build();
            }

            // 检查频次的合法性
            MedicationFrequency freq = medFreqRepo.findByCodeAndIsDeletedFalse(
                orderPb.getMedicationFreqCode()).orElse(null);
            if (freq == null || !freq.getSupportNursingOrder()) {
                log.error("Nursing order frequency not exists or not supported: {}", orderPb.getMedicationFreqCode());
                return AddNursingOrdersResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_FREQUENCY_NOT_EXISTS))
                    .build();
            }

            // 检查护理计划的合法性
            LocalDateTime orderTime = TimeUtils.fromIso8601String(orderPb.getOrderTimeIso8601(), "UTC");
            if (orderTime == null) {
                log.error("Invalid order time: {}", orderPb.getOrderTimeIso8601());
                return AddNursingOrdersResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.INVALID_NURSING_ORDER_TIME))
                    .build();
            }
            if (orderTime.isBefore(patientRecord.getAdmissionTime())) {
                log.error("Order time is before admission time: {}", orderPb.getOrderTimeIso8601());
                return AddNursingOrdersResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_TIME_BEFORE_ADMISSION))
                    .build();
            }
            NursingOrder order = orderRepo.findByPidAndOrderTemplateIdAndOrderTime(
                req.getPid(), orderPb.getOrderTemplateId(), orderTime).orElse(null);
            if (order != null) {
                log.error("Nursing order already exists: {}", order);
                return AddNursingOrdersResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_ALREADY_EXISTS))
                    .build();
            }
        }

        // 新增
        List<Long> orderIds = new ArrayList<>();
        final LocalDateTime nowUtc = TimeUtils.getNowUtc();
        for (NursingOrderPB orderPb : req.getOrderList()) {
            LocalDateTime orderTime = TimeUtils.fromIso8601String(orderPb.getOrderTimeIso8601(), "UTC");
            LocalDateTime stopTime = StrUtils.isBlank(orderPb.getStopTimeIso8601()) ?
                null : TimeUtils.fromIso8601String(orderPb.getStopTimeIso8601(), "UTC");
            if (stopTime != null && !stopTime.isAfter(orderTime)) {
                log.error("Invalid stop time: {}", orderPb.getStopTimeIso8601());
                return AddNursingOrdersResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_STOP_TIME_IS_NOT_AFTER_ORDER_TIME))
                    .build();
            }
            NursingOrder order = NursingOrder.builder()
                .pid(pid)
                .deptId(deptId)
                .orderTemplateId(orderPb.getOrderTemplateId())
                .name(orderPb.getName())
                .durationType(orderPb.getDurationType())
                .medicationFreqCode(orderPb.getMedicationFreqCode())
                .orderBy(accountId)
                .orderTime(orderTime)
                .note(orderPb.getNote())
                .isDeleted(false)
                .modifiedBy(accountId)
                .modifiedAt(nowUtc)
                .build();
            if (stopTime != null) {
                order.setStopBy(accountId);
                order.setStopTime(stopTime);
            }
            order = orderRepo.save(order);
            orderIds.add(order.getId());
            log.info("Added nursing order: {}, by {}\n, ", order, accountId);
        }

        return AddNursingOrdersResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllId(orderIds)
            .build();
    }

    @Transactional
    public GenericResp stopNursingOrder(String stopNursingOrderReqJson) {
        StopNursingOrderReq req;
        try {
            req = ProtoUtils.parseJsonToProto(stopNursingOrderReqJson, StopNursingOrderReq.newBuilder());
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

        // 查询护理计划
        List<NursingOrder> ordersToStop = new ArrayList<>();
        for (Long id : req.getIdList()) {
            NursingOrder order = orderRepo.findById(id).orElse(null);
            if (order == null) {
                log.error("Nursing order not exists: {}", id);
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_NOT_EXISTS))
                    .build();
            }
            if (NursingOrderSyncService.isHisSynced(order)) {
                log.error("HIS synced nursing order cannot be stopped manually: {}", id);
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_HIS_SYNCED_NOT_EDITABLE))
                    .build();
            }
            ordersToStop.add(order);
        }

        // 停止
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        LocalDateTime stopTime = TimeUtils.fromIso8601String(req.getStopTimeIso8601(), "UTC");
        if (stopTime == null) {
            log.error("Invalid stop time: {}", req.getStopTimeIso8601());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_NURSING_ORDER_STOP_TIME))
                .build();
        }
        for (NursingOrder order : ordersToStop) {
            order.setStopBy(accountId);
            order.setStopTime(stopTime);
            if (!StrUtils.isBlank(req.getNote())) {
                order.setNote(req.getNote());
            }
            order.setModifiedBy(accountId);
            order.setModifiedAt(nowUtc);
            orderRepo.save(order);
            log.info("Stopped nursing order: {}, by {}\n, ", order, accountId);
        }

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteNursingOrder(String deleteNursingOrderReqJson) {
        DeleteNursingOrderReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteNursingOrderReqJson, DeleteNursingOrderReq.newBuilder());
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

        // 查询护理计划
        List<NursingOrder> ordersToDelete = new ArrayList<>();
        for (Long id : req.getIdList()) {
            NursingOrder order = orderRepo.findById(id).orElse(null);
            if (order == null) {
                log.error("Nursing order not exists: {}", id);
                continue;
            }
            if (NursingOrderSyncService.isHisSynced(order)) {
                log.error("HIS synced nursing order cannot be deleted manually: {}", id);
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.NURSING_ORDER_HIS_SYNCED_NOT_EDITABLE))
                    .build();
            }
            ordersToDelete.add(order);
        }
        

        // 删除
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        for (NursingOrder order : ordersToDelete) {
            order.setIsDeleted(true);
            order.setDeletedBy(accountId);
            order.setDeletedAt(nowUtc);
            orderRepo.save(order);
            log.info("Delete nursing order: id {}, account {}", order.getId(), accountId);
        }
        

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp updateNursingExeRecord(String updateNursingExeRecordReqJson) {
        UpdateNursingExeRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updateNursingExeRecordReqJson, UpdateNursingExeRecordReq.newBuilder());
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

        // 查询护理记录
        NursingExecutionRecord record = recordRepo.findById(req.getId()).orElse(null);
        if (record == null) {
            log.error("Nursing execution record not exists: {}", req.getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NURSING_EXE_RECORD_NOT_EXISTS))
                .build();
        }

        // 更新
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        LocalDateTime completedTime = StrUtils.isBlank(req.getCompletedTimeIso8601()) ? null :
            TimeUtils.fromIso8601String(req.getCompletedTimeIso8601(), "UTC");
        record.setCompletedTime(completedTime);
        if (completedTime == null) {
            record.setCompletedBy("");
            record.setNote("");
        } else {
            record.setCompletedBy(accountId);
            record.setNote(req.getNote());
        }
        record.setModifiedBy(accountId);
        record.setModifiedAt(nowUtc);
        recordRepo.save(record);
        log.info("Updated nursing execution record: {}, by {}\n, ", record, accountId);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteNursingExeRecord(String deleteNursingExeRecordReqJson) {
        DeleteNursingExeRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteNursingExeRecordReqJson, DeleteNursingExeRecordReq.newBuilder());
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

        // 查询护理记录
        NursingExecutionRecord record = recordRepo.findById(req.getId()).orElse(null);
        if (record == null) {
            log.error("Nursing execution record not exists: {}", req.getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        // 删除
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        record.setIsDeleted(true);
        record.setDeletedBy(accountId);
        record.setDeletedAt(nowUtc);
        recordRepo.save(record);
        log.info("Deleted nursing execution record: {}, by {}\n, ", record, accountId);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    private Map<Long, List<NursingExecutionRecord>> parseNursingOrders(
        String accountId, List<NursingOrder> orders, ShiftSettingsPB shiftSettings,
        LocalDateTime startUtc, LocalDateTime endUtc, boolean inIcu
    ) {
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        Map<Long, List<NursingExecutionRecord>> recordMap = new HashMap<>();
        for (NursingOrder order : orders) {
            List<NursingExecutionRecord> records = new ArrayList<>();
            List<LocalDateTime> planTimes = getPlanTimes(
                inIcu, shiftSettings, medConfig.getMedicationFrequencySpec(order.getMedicationFreqCode()),
                order.getId(), order.getDurationType() == 1, order.getOrderTime(), order.getStopTime(),
                startUtc, endUtc
            );

            for (LocalDateTime planTimeUtc : planTimes) {
                NursingExecutionRecord record = NursingExecutionRecord.builder()
                    .pid(order.getPid())
                    .nursingOrderId(order.getId())
                    .planTime(planTimeUtc)
                    .isDeleted(false)
                    .modifiedBy(accountId)
                    .modifiedAt(nowUtc)
                    .build();
                record = recordRepo.save(record);
                records.add(record);
            }

            recordMap.put(order.getId(), records);
        }

        return recordMap;
    }

    private List<LocalDateTime> getPlanTimes(
        Boolean inIcu, ShiftSettingsPB shiftSettings, MedicationFrequencySpec freqSpec,
        Long orderId, boolean isLongTermDuration,
        LocalDateTime orderTimeUtc, LocalDateTime stopTimeUtc,
        LocalDateTime queryStartTimeUtc, LocalDateTime queryStopTimeUtc
    ) {
        List<LocalDateTime> exeTimes = new ArrayList<>();

        if (!inIcu) return exeTimes;

        if (!queryStopTimeUtc.isAfter(orderTimeUtc)) {
            return exeTimes;
        }

        if (!isLongTermDuration) {
            // 临时
            if (orderTimeUtc.isBefore(queryStartTimeUtc)) return exeTimes;

            if (freqSpec == null) {
                // Once
                exeTimes.add(orderTimeUtc);
            } else {
                // repeated
                for (MedicationFrequencySpec.Time t : freqSpec.getTimeList()) {
                    LocalDateTime planTimeLocal = orderTimeUtc.withHour(t.getHour()).withMinute(t.getMinute());
                    LocalDateTime planTimeUtc = TimeUtils.getUtcFromLocalDateTime(planTimeLocal, ZONE_ID);
                    if (!planTimeUtc.isBefore(queryStartTimeUtc) && planTimeUtc.isBefore(queryStopTimeUtc)) {
                        exeTimes.add(planTimeUtc);
                    }
                }
            }
        } else {
            // 长期
            queryStartTimeUtc = queryStartTimeUtc.isBefore(orderTimeUtc) ? orderTimeUtc : queryStartTimeUtc;
            List<LocalDateTime> shiftStartUtcList = shiftUtils.getShiftStartTimeUtcs(
                shiftSettings, queryStartTimeUtc, queryStopTimeUtc.minusMinutes(1), ZONE_ID
            );

            for (LocalDateTime shiftStartUtc : shiftStartUtcList) {
                if (stopTimeUtc != null && shiftStartUtc.isAfter(stopTimeUtc)) break;

                if (freqSpec == null) {
                    // Once
                    LocalDateTime planTimeLocal = shiftStartUtc
                        .withHour(orderTimeUtc.getHour())
                        .withMinute(orderTimeUtc.getMinute());
                    exeTimes.add(planTimeLocal);
                } else {
                    // 检查日期是否符合
                    boolean dateQualified = false;
                    if (freqSpec.getSpecTypeCase() == MedicationFrequencySpec.SpecTypeCase.BY_WEEK) {
                        MedicationFrequencySpec.ByWeek byWeek = freqSpec.getByWeek();
                        for (int dayOfWeek : byWeek.getDayOfWeekList()) {
                            if (dayOfWeek == shiftStartUtc.getDayOfWeek().getValue()) {
                                dateQualified = true;
                                break;
                            }
                        }
                    } else if (freqSpec.getSpecTypeCase() == MedicationFrequencySpec.SpecTypeCase.BY_INTERVAL) {
                        NursingExecutionRecord lastExeRecord = recordRepo.findLatestValidRecord(orderId).orElse(null);
                        final int intervalDays = freqSpec.getByInterval().getIntervalDays();
                        if (lastExeRecord == null) dateQualified = true;
                        else if (intervalDays <= 0) {
                            dateQualified = true;
                        } else {
                            final long days = Duration.between(
                                lastExeRecord.getPlanTime(), shiftStartUtc
                            ).toDays();
                            dateQualified = (days % (intervalDays + 1) == 0);
                        }
                    }
                    if (!dateQualified) continue;

                    // 添加时点
                    exeTimes = MedUtils.getPlanTimesUtc(shiftStartUtc, ZONE_ID, freqSpec);
                }
            }
        }
        return exeTimes;
    }

    private List<NursingOrderTemplateGroupPB> composeNursingOrderDetails(
        List<NursingOrderTemplateGroup> groups,
        Map<Integer, List<NursingOrder>> groupOrderMap,
        List<NursingOrder> hisOrders,
        Map<Long, List<NursingExecutionRecord>> recordMap,
        Map<Long, List<NursingExecutionRecord>> newRecordMap,
        LocalDateTime queryStartUtc, LocalDateTime queryStopUtc
    ) {
        List<NursingOrderTemplateGroupPB> groupPbList = new ArrayList<>();

        List<NursingOrderPB> hisOrderPbList = buildNursingOrderPbList(
            hisOrders, recordMap, newRecordMap, queryStartUtc, queryStopUtc);
        if (!hisOrderPbList.isEmpty()) {
            groupPbList.add(NursingOrderTemplateGroupPB.newBuilder()
                .setId(NursingOrderSyncService.HIS_ORDER_TEMPLATE_ID)
                .setName(NursingOrderSyncService.HIS_ORDER_GROUP_NAME)
                .setDisplayOrder(0)
                .addAllNursingOrder(hisOrderPbList)
                .build());
        }

        List<NursingOrderTemplateGroupPB> templateGroupPbList = new ArrayList<>();

        for (NursingOrderTemplateGroup group : groups) {
            NursingOrderTemplateGroupPB.Builder groupPbBuilder = NursingOrderTemplateGroupPB.newBuilder()
                .setId(group.getId())
                .setName(group.getName())
                .setDisplayOrder(group.getDisplayOrder());

            // 查找组内的护理计划
            List<NursingOrder> orders = groupOrderMap.get(group.getId());
            if (orders == null) continue;

            // 组装组内的护理计划
            List<NursingOrderPB> orderPbList = buildNursingOrderPbList(
                orders, recordMap, newRecordMap, queryStartUtc, queryStopUtc);
            if (orderPbList.isEmpty()) continue;
            templateGroupPbList.add(groupPbBuilder
                .addAllNursingOrder(orderPbList)
                .build());
        }

        groupPbList.addAll(templateGroupPbList.stream()
            .sorted(Comparator.comparing(NursingOrderTemplateGroupPB::getDisplayOrder))
            .toList());
        return groupPbList;
    }

    private List<NursingOrderPB> buildNursingOrderPbList(
        List<NursingOrder> orders,
        Map<Long, List<NursingExecutionRecord>> recordMap,
        Map<Long, List<NursingExecutionRecord>> newRecordMap,
        LocalDateTime queryStartUtc, LocalDateTime queryStopUtc
    ) {
        List<NursingOrderPB> orderPbList = new ArrayList<>();
        for (NursingOrder order : orders) {
            if (Boolean.TRUE.equals(order.getIsDeleted())) continue;

            List<NursingExecutionRecord> allRecords = new ArrayList<>();
            addRecordsInRange(allRecords, recordMap.get(order.getId()), queryStartUtc, queryStopUtc);
            addRecordsInRange(allRecords, newRecordMap.get(order.getId()), queryStartUtc, queryStopUtc);
            if (allRecords.isEmpty()) continue;

            List<NursingExeRecordPB> recordPbList = allRecords.stream()
                .map(this::buildNursingExeRecordPB)
                .sorted(Comparator.comparing(NursingExeRecordPB::getPlanTimeIso8601))
                .toList();
            if (recordPbList.isEmpty()) continue;

            orderPbList.add(buildNursingOrderPB(order, recordPbList));
        }
        return orderPbList.stream()
            .sorted(Comparator.comparing(NursingOrderPB::getOrderTimeIso8601))
            .toList();
    }

    private void addRecordsInRange(
        List<NursingExecutionRecord> allRecords,
        List<NursingExecutionRecord> records,
        LocalDateTime queryStartUtc,
        LocalDateTime queryStopUtc
    ) {
        if (records == null) return;
        for (NursingExecutionRecord record : records) {
            if (Boolean.TRUE.equals(record.getIsDeleted())) continue;
            if (record.getPlanTime().isBefore(queryStartUtc) ||
                record.getPlanTime().isAfter(queryStopUtc)
            ) continue;
            allRecords.add(record);
        }
    }

    private NursingOrderPB buildNursingOrderPB(NursingOrder order, List<NursingExeRecordPB> recordPbList) {
        return NursingOrderPB.newBuilder()
            .setId(order.getId())
            .setOrderTemplateId(order.getOrderTemplateId() == null ?
                NursingOrderSyncService.HIS_ORDER_TEMPLATE_ID : order.getOrderTemplateId())
            .setName(order.getName() == null ? "" : order.getName())
            .setDurationType(order.getDurationType() == null ? 0 : order.getDurationType())
            .setMedicationFreqCode(order.getMedicationFreqCode() == null ? "" : order.getMedicationFreqCode())
            .setOrderBy(getAccountDisplayName(order.getOrderBy(), NursingOrderSyncService.isHisSynced(order)))
            .setOrderTimeIso8601(TimeUtils.toIso8601String(order.getOrderTime(), ZONE_ID))
            .setStopBy(getAccountDisplayName(order.getStopBy(), NursingOrderSyncService.isHisSynced(order)))
            .setStopTimeIso8601(order.getStopTime() == null ? "" : TimeUtils.toIso8601String(order.getStopTime(), ZONE_ID))
            .setNote(order.getNote() == null ? "" : order.getNote())
            .addAllExeRecord(recordPbList)
            .build();
    }

    private NursingExeRecordPB buildNursingExeRecordPB(NursingExecutionRecord record) {
        return NursingExeRecordPB.newBuilder()
            .setId(record.getId())
            .setNursingOrderId(record.getNursingOrderId())
            .setPlanTimeIso8601(TimeUtils.toIso8601String(record.getPlanTime(), ZONE_ID))
            .setCompletedBy(record.getCompletedBy() == null ? "" : record.getCompletedBy())
            .setCompletedTimeIso8601(
                record.getCompletedTime() == null ? "" : TimeUtils.toIso8601String(record.getCompletedTime(), ZONE_ID)
            )
            .setNote(record.getNote() == null ? "" : record.getNote())
            .build();
    }

    private String getAccountDisplayName(String accountRef, boolean hisSynced) {
        if (StrUtils.isBlank(accountRef)) return "";
        if (hisSynced) return accountRef;
        String accountName = userService.getNameByAutoId(accountRef);
        return StrUtils.isBlank(accountName) ? accountRef : accountName;
    }

    private final String ZONE_ID;

    private final ConfigProtoService protoService;
    private final UserService userService;
    private final ConfigShiftUtils shiftUtils;
    private final PatientService patientService;
    private final MedicationConfig medConfig;
    private final NursingOrderConfig orderConfig;
    private final NursingOrderSyncService nursingOrderSyncService;

    private final NursingOrderTemplateGroupRepository templateGroupRepo;
    private final NursingOrderTemplateRepository templateRepo;
    private final NursingOrderRepository orderRepo;
    private final NursingExecutionRecordRepository recordRepo;
    private final MedicationFrequencyRepository medFreqRepo;
}
