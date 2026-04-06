package com.jingyicare.jingyi_icis_engine.service.skincares;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.skincares.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSkincare.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.IntStrKvPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.GenericValuePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.repository.skincares.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.*;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class SkincareService {
    public SkincareService(
        @Autowired ConfigProtoService protoService,
        @Autowired UserService userService,
        @Autowired PatientService patientService,
        @Autowired SkincareTypeRepository skincareTypeRepo,
        @Autowired SkincareTypeAttributeRepository skincareTypeAttrRepo,
        @Autowired PatientSkincarePlanRepository patientSkincarePlanRepo,
        @Autowired PatientSkincarePlanAttrRepository patientSkincarePlanAttrRepo,
        @Autowired PatientSkincareRecordRepository patientSkincareRecordRepo,
        @Autowired PatientSkincareRecordAttrRepository patientSkincareRecordAttrRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.validCategoryIds = protoService.getConfig().getSkincare().getSkincareCategoryList().stream()
            .map(IntStrKvPB::getKey)
            .collect(Collectors.toSet());

        this.protoService = protoService;
        this.userService = userService;
        this.patientService = patientService;
        this.skincareTypeRepo = skincareTypeRepo;
        this.skincareTypeAttrRepo = skincareTypeAttrRepo;
        this.patientSkincarePlanRepo = patientSkincarePlanRepo;
        this.patientSkincarePlanAttrRepo = patientSkincarePlanAttrRepo;
        this.patientSkincareRecordRepo = patientSkincareRecordRepo;
        this.patientSkincareRecordAttrRepo = patientSkincareRecordAttrRepo;
    }

    // region Type APIs

    public GetSkincareTypesResp getSkincareTypes(String getSkincareTypesReqJson) {
        final GetSkincareTypesReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getSkincareTypesReqJson, GetSkincareTypesReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GetSkincareTypesResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        List<SkincareType> types = skincareTypeRepo.findAll().stream()
            .filter(type -> req.getId() <= 0 || Objects.equals(type.getId(), req.getId()))
            .filter(type -> StrUtils.isBlank(req.getDeptId()) || req.getDeptId().equals(type.getDeptId()))
            .filter(type -> StrUtils.isBlank(req.getType()) || req.getType().equals(type.getType()))
            .filter(type -> StrUtils.isBlank(req.getName()) || req.getName().equals(type.getName()))
            .filter(type -> matchesDeleted(type.getIsDeleted(), req.getIsDeleted()))
            .sorted(Comparator.comparing(SkincareType::getDeptId, Comparator.nullsLast(String::compareTo))
                .thenComparing(SkincareType::getType, Comparator.nullsLast(String::compareTo))
                .thenComparing(SkincareType::getName, Comparator.nullsLast(String::compareTo))
                .thenComparing(SkincareType::getId))
            .toList();

        int childDeletedFilter = req.getIsDeleted() >= 0 ? req.getIsDeleted() : 0;
        Map<Integer, List<SkincareTypeAttribute>> attrMap = getTypeAttrMap(
            types.stream().map(SkincareType::getId).toList(), childDeletedFilter
        );

        return GetSkincareTypesResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllSkincareType(types.stream()
                .map(type -> toProto(type, attrMap.getOrDefault(type.getId(), List.of())))
                .toList())
            .build();
    }

    @Transactional
    public AddSkincareTypeResp addSkincareType(String addSkincareTypeReqJson) {
        final AddSkincareTypeReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addSkincareTypeReqJson, AddSkincareTypeReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return AddSkincareTypeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String accountId = getCurrentAccountId();
        if (accountId == null) {
            return AddSkincareTypeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        SkincareTypePB typePb = req.getSkincareType();
        SkincareType existingType = skincareTypeRepo.findByDeptIdAndTypeAndNameAndIsDeletedFalse(
            typePb.getDeptId(), typePb.getType(), typePb.getName()
        ).orElse(null);
        if (existingType != null) {
            return AddSkincareTypeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SKINCARE_TYPE_ALREADY_EXISTS))
                .build();
        }

        SkincareType deletedType = skincareTypeRepo.findAll().stream()
            .filter(type -> Boolean.TRUE.equals(type.getIsDeleted()))
            .filter(type -> Objects.equals(type.getDeptId(), typePb.getDeptId()))
            .filter(type -> Objects.equals(type.getType(), typePb.getType()))
            .filter(type -> Objects.equals(type.getName(), typePb.getName()))
            .findFirst()
            .orElse(null);

        LocalDateTime now = TimeUtils.getNowUtc();
        SkincareType type = deletedType == null ? new SkincareType() : deletedType;
        type.setDeptId(typePb.getDeptId());
        type.setType(typePb.getType());
        type.setName(typePb.getName());
        type.setIsDeleted(false);
        type.setDeletedBy(null);
        type.setDeletedAt(null);
        type.setModifiedBy(accountId);
        type.setModifiedAt(now);
        type = skincareTypeRepo.save(type);

        return AddSkincareTypeResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(type.getId())
            .build();
    }

    @Transactional
    public GenericResp updateSkincareType(String updateSkincareTypeReqJson) {
        final UpdateSkincareTypeReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updateSkincareTypeReqJson, UpdateSkincareTypeReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String accountId = getCurrentAccountId();
        if (accountId == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        SkincareTypePB typePb = req.getSkincareType();
        SkincareType type = skincareTypeRepo.findByIdAndIsDeletedFalse(typePb.getId()).orElse(null);
        if (type == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SKINCARE_TYPE_NOT_FOUND))
                .build();
        }

        SkincareType mappedType = skincareTypeRepo.findByDeptIdAndTypeAndNameAndIsDeletedFalse(
            typePb.getDeptId(), typePb.getType(), typePb.getName()
        ).orElse(null);
        if (mappedType != null && !Objects.equals(mappedType.getId(), typePb.getId())) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SKINCARE_TYPE_ID_NOT_MATCH))
                .build();
        }

        type.setDeptId(typePb.getDeptId());
        type.setType(typePb.getType());
        type.setName(typePb.getName());
        type.setModifiedBy(accountId);
        type.setModifiedAt(TimeUtils.getNowUtc());
        skincareTypeRepo.save(type);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteSkincareType(String deleteSkincareTypeReqJson) {
        final DeleteSkincareTypeReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteSkincareTypeReqJson, DeleteSkincareTypeReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String accountId = getCurrentAccountId();
        if (accountId == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        SkincareType type = skincareTypeRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (type == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        LocalDateTime now = TimeUtils.getNowUtc();
        softDeleteType(type, accountId, now);
        List<SkincareTypeAttribute> attrs = skincareTypeAttrRepo.findBySkincareTypeIdAndIsDeletedFalse(type.getId());
        for (SkincareTypeAttribute attr : attrs) {
            softDeleteTypeAttr(attr, accountId, now);
        }
        skincareTypeRepo.save(type);
        skincareTypeAttrRepo.saveAll(attrs);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    public GetSkincareTypeAttributesResp getSkincareTypeAttributes(String getSkincareTypeAttributesReqJson) {
        final GetSkincareTypeAttributesReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getSkincareTypeAttributesReqJson, GetSkincareTypeAttributesReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GetSkincareTypeAttributesResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        List<SkincareTypeAttributePB> attrs = skincareTypeAttrRepo.findAll().stream()
            .filter(attr -> req.getId() <= 0 || Objects.equals(attr.getId(), req.getId()))
            .filter(attr -> req.getSkincareTypeId() <= 0 || Objects.equals(attr.getSkincareTypeId(), req.getSkincareTypeId()))
            .filter(attr -> StrUtils.isBlank(req.getAttrCode()) || req.getAttrCode().equals(attr.getAttrCode()))
            .filter(attr -> StrUtils.isBlank(req.getAttrName()) || req.getAttrName().equals(attr.getAttrName()))
            .filter(attr -> req.getCategoryId() <= 0 || Objects.equals(attr.getCategoryId(), req.getCategoryId()))
            .filter(attr -> matchesBoolean(attr.getIsInitial(), req.getIsInitial()))
            .filter(attr -> matchesBoolean(attr.getIsMaintenance(), req.getIsMaintenance()))
            .filter(attr -> matchesBoolean(attr.getShowInTable(), req.getShowInTable()))
            .filter(attr -> matchesDeleted(attr.getIsDeleted(), req.getIsDeleted()))
            .sorted(Comparator.comparing(SkincareTypeAttribute::getSkincareTypeId)
                .thenComparing(SkincareTypeAttribute::getCategoryId, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(SkincareTypeAttribute::getAttrName, Comparator.nullsLast(String::compareTo))
                .thenComparing(SkincareTypeAttribute::getId))
            .map(this::toProto)
            .toList();

        return GetSkincareTypeAttributesResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllAttr(attrs)
            .build();
    }

    @Transactional
    public AddSkincareTypeAttributeResp addSkincareTypeAttribute(String addSkincareTypeAttributeReqJson) {
        final AddSkincareTypeAttributeReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addSkincareTypeAttributeReqJson, AddSkincareTypeAttributeReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return AddSkincareTypeAttributeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String accountId = getCurrentAccountId();
        if (accountId == null) {
            return AddSkincareTypeAttributeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        SkincareTypeAttributePB attrPb = req.getAttr();
        if (!isValidCategoryId(attrPb.getCategoryId())) {
            return AddSkincareTypeAttributeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_SKINCARE_CATEGORY_ID))
                .build();
        }

        SkincareType type = skincareTypeRepo.findByIdAndIsDeletedFalse(attrPb.getSkincareTypeId()).orElse(null);
        if (type == null) {
            return AddSkincareTypeAttributeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SKINCARE_TYPE_NOT_FOUND))
                .build();
        }

        SkincareTypeAttribute existingAttr = skincareTypeAttrRepo
            .findBySkincareTypeIdAndAttrCodeAndIsDeletedFalse(attrPb.getSkincareTypeId(), attrPb.getAttrCode())
            .orElse(null);
        if (existingAttr != null) {
            return AddSkincareTypeAttributeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SKINCARE_TYPE_ATTR_ALREADY_EXISTS))
                .build();
        }

        boolean nameExists = skincareTypeAttrRepo.findBySkincareTypeIdAndIsDeletedFalse(attrPb.getSkincareTypeId())
            .stream()
            .anyMatch(attr -> Objects.equals(attr.getAttrName(), attrPb.getAttrName()));
        if (nameExists) {
            return AddSkincareTypeAttributeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SKINCARE_TYPE_ATTR_ALREADY_EXISTS))
                .build();
        }

        SkincareTypeAttribute deletedAttr = skincareTypeAttrRepo.findAll().stream()
            .filter(attr -> Boolean.TRUE.equals(attr.getIsDeleted()))
            .filter(attr -> Objects.equals(attr.getSkincareTypeId(), attrPb.getSkincareTypeId()))
            .filter(attr -> Objects.equals(attr.getAttrCode(), attrPb.getAttrCode()))
            .findFirst()
            .orElse(null);

        LocalDateTime now = TimeUtils.getNowUtc();
        SkincareTypeAttribute attr = deletedAttr == null ? new SkincareTypeAttribute() : deletedAttr;
        fillTypeAttrEntity(attr, attrPb, accountId, now);
        attr = skincareTypeAttrRepo.save(attr);

        return AddSkincareTypeAttributeResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(attr.getId())
            .build();
    }

    @Transactional
    public GenericResp updateSkincareTypeAttribute(String updateSkincareTypeAttributeReqJson) {
        final UpdateSkincareTypeAttributeReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updateSkincareTypeAttributeReqJson, UpdateSkincareTypeAttributeReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String accountId = getCurrentAccountId();
        if (accountId == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        SkincareTypeAttributePB attrPb = req.getAttr();
        if (!isValidCategoryId(attrPb.getCategoryId())) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_SKINCARE_CATEGORY_ID))
                .build();
        }

        SkincareType type = skincareTypeRepo.findByIdAndIsDeletedFalse(attrPb.getSkincareTypeId()).orElse(null);
        if (type == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SKINCARE_TYPE_NOT_FOUND))
                .build();
        }

        SkincareTypeAttribute attr = skincareTypeAttrRepo
            .findBySkincareTypeIdAndAttrCodeAndIsDeletedFalse(attrPb.getSkincareTypeId(), attrPb.getAttrCode())
            .orElse(null);
        if (attr == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SKINCARE_TYPE_ATTR_NOT_FOUND))
                .build();
        }
        if (!Objects.equals(attr.getId(), attrPb.getId())) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SKINCARE_TYPE_ATTR_ID_NOT_MATCH))
                .build();
        }

        boolean nameExists = skincareTypeAttrRepo.findBySkincareTypeIdAndIsDeletedFalse(attrPb.getSkincareTypeId())
            .stream()
            .anyMatch(existing -> !Objects.equals(existing.getId(), attrPb.getId())
                && Objects.equals(existing.getAttrName(), attrPb.getAttrName()));
        if (nameExists) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SKINCARE_TYPE_ATTR_ALREADY_EXISTS))
                .build();
        }

        fillTypeAttrEntity(attr, attrPb, accountId, TimeUtils.getNowUtc());
        skincareTypeAttrRepo.save(attr);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteSkincareTypeAttribute(String deleteSkincareTypeAttributeReqJson) {
        final DeleteSkincareTypeAttributeReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteSkincareTypeAttributeReqJson, DeleteSkincareTypeAttributeReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String accountId = getCurrentAccountId();
        if (accountId == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        SkincareTypeAttribute attr = skincareTypeAttrRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (attr == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        softDeleteTypeAttr(attr, accountId, TimeUtils.getNowUtc());
        skincareTypeAttrRepo.save(attr);
        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    // endregion

    // region Plan APIs

    public GetPatientSkincarePlansResp getPatientSkincarePlans(String getPatientSkincarePlansReqJson) {
        final GetPatientSkincarePlansReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getPatientSkincarePlansReqJson, GetPatientSkincarePlansReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GetPatientSkincarePlansResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        TimeRangeResult timeRange = parseQueryTimeRange(req.getQueryStartIso8601(), req.getQueryEndIso8601());
        if (timeRange.invalidStatus != null) {
            return GetPatientSkincarePlansResp.newBuilder()
                .setRt(protoService.getReturnCode(timeRange.invalidStatus))
                .build();
        }

        if (req.getPid() > 0) {
            PatientRecord patient = patientService.getPatientRecord(req.getPid());
            if (patient == null) {
                return GetPatientSkincarePlansResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                    .build();
            }
            if (!StrUtils.isBlank(req.getDeptId()) && !Objects.equals(patient.getDeptId(), req.getDeptId())) {
                return GetPatientSkincarePlansResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PATIENT_DEPT_MISMATCH))
                    .build();
            }
        }

        List<PatientSkincarePlan> plans = patientSkincarePlanRepo.findAll().stream()
            .filter(plan -> req.getId() <= 0 || Objects.equals(plan.getId(), req.getId()))
            .filter(plan -> StrUtils.isBlank(req.getDeptId()) || req.getDeptId().equals(plan.getDeptId()))
            .filter(plan -> req.getPid() <= 0 || Objects.equals(plan.getPid(), req.getPid()))
            .filter(plan -> req.getSkincareTypeId() <= 0 || Objects.equals(plan.getSkincareTypeId(), req.getSkincareTypeId()))
            .filter(plan -> !plan.getCreatedAt().isBefore(timeRange.start) && !plan.getCreatedAt().isAfter(timeRange.end))
            .filter(plan -> matchesDeleted(plan.getIsDeleted(), req.getIsDeleted()))
            .sorted(Comparator.comparing(PatientSkincarePlan::getCreatedAt)
                .thenComparing(PatientSkincarePlan::getId))
            .toList();

        int childDeletedFilter = req.getIsDeleted() >= 0 ? req.getIsDeleted() : 0;
        Map<Long, List<PatientSkincarePlanAttr>> attrMap = getPlanAttrMap(
            plans.stream().map(PatientSkincarePlan::getId).toList(), childDeletedFilter
        );

        return GetPatientSkincarePlansResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllPlan(plans.stream()
                .map(plan -> toProto(plan, attrMap.getOrDefault(plan.getId(), List.of())))
                .toList())
            .build();
    }

    @Transactional
    public AddPatientSkincarePlanResp addPatientSkincarePlan(String addPatientSkincarePlanReqJson) {
        final AddPatientSkincarePlanReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addPatientSkincarePlanReqJson, AddPatientSkincarePlanReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return AddPatientSkincarePlanResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String accountId = getCurrentAccountId();
        if (accountId == null) {
            return AddPatientSkincarePlanResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        PatientSkincarePlanPB planPb = req.getPlan();
        PatientRecord patient = patientService.getPatientRecord(planPb.getPid());
        if (patient == null) {
            return AddPatientSkincarePlanResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        String deptId = resolvePatientDeptId(planPb.getDeptId(), patient);
        if (deptId == null) {
            return AddPatientSkincarePlanResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_DEPT_MISMATCH))
                .build();
        }

        SkincareType type = skincareTypeRepo.findByIdAndIsDeletedFalse(planPb.getSkincareTypeId()).orElse(null);
        if (type == null || !Objects.equals(type.getDeptId(), deptId)) {
            return AddPatientSkincarePlanResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SKINCARE_TYPE_NOT_FOUND))
                .build();
        }

        StatusCode attrValidationCode = validatePlanAttrPayload(planPb.getAttrList(), type.getId(), true);
        if (attrValidationCode != null) {
            return AddPatientSkincarePlanResp.newBuilder()
                .setRt(protoService.getReturnCode(attrValidationCode))
                .build();
        }

        LocalDateTime now = TimeUtils.getNowUtc();
        PatientSkincarePlan plan = PatientSkincarePlan.builder()
            .deptId(deptId)
            .pid(planPb.getPid())
            .skincareTypeId(type.getId())
            .createdAt(now)
            .isDeleted(false)
            .modifiedBy(accountId)
            .modifiedAt(now)
            .build();
        plan = patientSkincarePlanRepo.save(plan);

        if (planPb.getAttrCount() > 0) {
            List<PatientSkincarePlanAttr> attrs = new ArrayList<>();
            for (PatientSkincarePlanAttrPB attrPb : planPb.getAttrList()) {
                PatientSkincarePlanAttr attr = new PatientSkincarePlanAttr();
                fillPlanAttrEntity(attr, plan.getId(), attrPb, accountId, now);
                attrs.add(attr);
            }
            patientSkincarePlanAttrRepo.saveAll(attrs);
        }

        return AddPatientSkincarePlanResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(plan.getId())
            .build();
    }

    @Transactional
    public GenericResp updatePatientSkincarePlan(String updatePatientSkincarePlanReqJson) {
        final UpdatePatientSkincarePlanReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updatePatientSkincarePlanReqJson, UpdatePatientSkincarePlanReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String accountId = getCurrentAccountId();
        if (accountId == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        PatientSkincarePlanPB planPb = req.getPlan();
        PatientSkincarePlan plan = patientSkincarePlanRepo.findByIdAndIsDeletedFalse(planPb.getId()).orElse(null);
        if (plan == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_PLAN_NOT_FOUND))
                .build();
        }

        PatientRecord patient = patientService.getPatientRecord(planPb.getPid());
        if (patient == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        String deptId = resolvePatientDeptId(planPb.getDeptId(), patient);
        if (deptId == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_DEPT_MISMATCH))
                .build();
        }

        SkincareType type = skincareTypeRepo.findByIdAndIsDeletedFalse(planPb.getSkincareTypeId()).orElse(null);
        if (type == null || !Objects.equals(type.getDeptId(), deptId)) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SKINCARE_TYPE_NOT_FOUND))
                .build();
        }

        plan.setDeptId(deptId);
        plan.setPid(planPb.getPid());
        plan.setSkincareTypeId(type.getId());
        plan.setModifiedBy(accountId);
        plan.setModifiedAt(TimeUtils.getNowUtc());
        patientSkincarePlanRepo.save(plan);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deletePatientSkincarePlan(String deletePatientSkincarePlanReqJson) {
        final DeletePatientSkincarePlanReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deletePatientSkincarePlanReqJson, DeletePatientSkincarePlanReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String accountId = getCurrentAccountId();
        if (accountId == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        PatientSkincarePlan plan = patientSkincarePlanRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (plan == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        LocalDateTime now = TimeUtils.getNowUtc();
        softDeletePlan(plan, accountId, now);
        patientSkincarePlanRepo.save(plan);

        List<PatientSkincarePlanAttr> planAttrs = patientSkincarePlanAttrRepo
            .findByPatientSkincarePlanIdAndIsDeletedFalse(plan.getId());
        for (PatientSkincarePlanAttr attr : planAttrs) {
            softDeletePlanAttr(attr, accountId, now);
        }
        patientSkincarePlanAttrRepo.saveAll(planAttrs);

        List<PatientSkincareRecord> records = patientSkincareRecordRepo
            .findByPatientSkincarePlanIdAndIsDeletedFalse(plan.getId());
        if (!records.isEmpty()) {
            List<Long> recordIds = records.stream().map(PatientSkincareRecord::getId).toList();
            List<PatientSkincareRecordAttr> recordAttrs = patientSkincareRecordAttrRepo
                .findByPatientSkincareRecordIdInAndIsDeletedFalse(recordIds);
            for (PatientSkincareRecord record : records) {
                softDeleteRecord(record, accountId, now);
            }
            for (PatientSkincareRecordAttr attr : recordAttrs) {
                softDeleteRecordAttr(attr, accountId, now);
            }
            patientSkincareRecordRepo.saveAll(records);
            patientSkincareRecordAttrRepo.saveAll(recordAttrs);
        }

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    public GetPatientSkincarePlanAttrsResp getPatientSkincarePlanAttrs(String getPatientSkincarePlanAttrsReqJson) {
        final GetPatientSkincarePlanAttrsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getPatientSkincarePlanAttrsReqJson, GetPatientSkincarePlanAttrsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GetPatientSkincarePlanAttrsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        List<PatientSkincarePlanAttrPB> attrs = patientSkincarePlanAttrRepo.findAll().stream()
            .filter(attr -> req.getId() <= 0 || Objects.equals(attr.getId(), req.getId()))
            .filter(attr -> req.getPatientSkincarePlanId() <= 0 || Objects.equals(attr.getPatientSkincarePlanId(), req.getPatientSkincarePlanId()))
            .filter(attr -> req.getSkincareAttrId() <= 0 || Objects.equals(attr.getSkincareAttrId(), req.getSkincareAttrId()))
            .filter(attr -> matchesDeleted(attr.getIsDeleted(), req.getIsDeleted()))
            .sorted(Comparator.comparing(PatientSkincarePlanAttr::getPatientSkincarePlanId)
                .thenComparing(PatientSkincarePlanAttr::getSkincareAttrId)
                .thenComparing(PatientSkincarePlanAttr::getId))
            .map(this::toProto)
            .toList();

        return GetPatientSkincarePlanAttrsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllAttr(attrs)
            .build();
    }

    @Transactional
    public AddPatientSkincarePlanAttrResp addPatientSkincarePlanAttr(String addPatientSkincarePlanAttrReqJson) {
        final AddPatientSkincarePlanAttrReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addPatientSkincarePlanAttrReqJson, AddPatientSkincarePlanAttrReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return AddPatientSkincarePlanAttrResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String accountId = getCurrentAccountId();
        if (accountId == null) {
            return AddPatientSkincarePlanAttrResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        PatientSkincarePlanAttrPB attrPb = req.getAttr();
        PatientSkincarePlan plan = patientSkincarePlanRepo
            .findByIdAndIsDeletedFalse(attrPb.getPatientSkincarePlanId())
            .orElse(null);
        if (plan == null) {
            return AddPatientSkincarePlanAttrResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_PLAN_NOT_FOUND))
                .build();
        }

        StatusCode attrValidationCode = validateAttrBelongsToType(attrPb.getSkincareAttrId(), plan.getSkincareTypeId());
        if (attrValidationCode != null) {
            return AddPatientSkincarePlanAttrResp.newBuilder()
                .setRt(protoService.getReturnCode(attrValidationCode))
                .build();
        }

        PatientSkincarePlanAttr existing = patientSkincarePlanAttrRepo
            .findByPatientSkincarePlanIdAndSkincareAttrIdAndIsDeletedFalse(
                attrPb.getPatientSkincarePlanId(), attrPb.getSkincareAttrId())
            .orElse(null);
        if (existing != null) {
            return AddPatientSkincarePlanAttrResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_PLAN_ATTR_ALREADY_EXISTS))
                .build();
        }

        PatientSkincarePlanAttr deleted = patientSkincarePlanAttrRepo.findAll().stream()
            .filter(attr -> Boolean.TRUE.equals(attr.getIsDeleted()))
            .filter(attr -> Objects.equals(attr.getPatientSkincarePlanId(), attrPb.getPatientSkincarePlanId()))
            .filter(attr -> Objects.equals(attr.getSkincareAttrId(), attrPb.getSkincareAttrId()))
            .findFirst()
            .orElse(null);

        LocalDateTime now = TimeUtils.getNowUtc();
        PatientSkincarePlanAttr attr = deleted == null ? new PatientSkincarePlanAttr() : deleted;
        fillPlanAttrEntity(attr, plan.getId(), attrPb, accountId, now);
        attr = patientSkincarePlanAttrRepo.save(attr);
        touchPlan(plan, accountId, now);

        return AddPatientSkincarePlanAttrResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(attr.getId())
            .build();
    }

    @Transactional
    public GenericResp updatePatientSkincarePlanAttr(String updatePatientSkincarePlanAttrReqJson) {
        final UpdatePatientSkincarePlanAttrReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updatePatientSkincarePlanAttrReqJson, UpdatePatientSkincarePlanAttrReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String accountId = getCurrentAccountId();
        if (accountId == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        PatientSkincarePlanAttrPB attrPb = req.getAttr();
        PatientSkincarePlanAttr attr = patientSkincarePlanAttrRepo.findByIdAndIsDeletedFalse(attrPb.getId()).orElse(null);
        if (attr == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_PLAN_ATTR_NOT_FOUND))
                .build();
        }
        if (attrPb.getPatientSkincarePlanId() > 0
            && !Objects.equals(attr.getPatientSkincarePlanId(), attrPb.getPatientSkincarePlanId())
        ) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_PLAN_ATTR_NOT_FOUND))
                .build();
        }

        PatientSkincarePlan plan = patientSkincarePlanRepo.findByIdAndIsDeletedFalse(attr.getPatientSkincarePlanId()).orElse(null);
        if (plan == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_PLAN_NOT_FOUND))
                .build();
        }

        Integer skincareAttrId = attrPb.getSkincareAttrId() > 0 ? attrPb.getSkincareAttrId() : attr.getSkincareAttrId();
        StatusCode attrValidationCode = validateAttrBelongsToType(skincareAttrId, plan.getSkincareTypeId());
        if (attrValidationCode != null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(attrValidationCode))
                .build();
        }

        PatientSkincarePlanAttr duplicate = patientSkincarePlanAttrRepo
            .findByPatientSkincarePlanIdAndSkincareAttrIdAndIsDeletedFalse(plan.getId(), skincareAttrId)
            .orElse(null);
        if (duplicate != null && !Objects.equals(duplicate.getId(), attr.getId())) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_PLAN_ATTR_ALREADY_EXISTS))
                .build();
        }

        attr.setSkincareAttrId(skincareAttrId);
        attr.setValue(ProtoUtils.encodeGenericValue(attrPb.getValue()));
        attr.setModifiedBy(accountId);
        attr.setModifiedAt(TimeUtils.getNowUtc());
        patientSkincarePlanAttrRepo.save(attr);
        touchPlan(plan, accountId, attr.getModifiedAt());

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deletePatientSkincarePlanAttr(String deletePatientSkincarePlanAttrReqJson) {
        final DeletePatientSkincarePlanAttrReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deletePatientSkincarePlanAttrReqJson, DeletePatientSkincarePlanAttrReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String accountId = getCurrentAccountId();
        if (accountId == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        PatientSkincarePlanAttr attr = patientSkincarePlanAttrRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (attr == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        LocalDateTime now = TimeUtils.getNowUtc();
        softDeletePlanAttr(attr, accountId, now);
        patientSkincarePlanAttrRepo.save(attr);

        PatientSkincarePlan plan = patientSkincarePlanRepo.findByIdAndIsDeletedFalse(attr.getPatientSkincarePlanId()).orElse(null);
        if (plan != null) {
            touchPlan(plan, accountId, now);
        }

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    // endregion

    // region Record APIs

    public GetPatientSkincareRecordsResp getPatientSkincareRecords(String getPatientSkincareRecordsReqJson) {
        final GetPatientSkincareRecordsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getPatientSkincareRecordsReqJson, GetPatientSkincareRecordsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GetPatientSkincareRecordsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        TimeRangeResult timeRange = parseQueryTimeRange(req.getQueryStartIso8601(), req.getQueryEndIso8601());
        if (timeRange.invalidStatus != null) {
            return GetPatientSkincareRecordsResp.newBuilder()
                .setRt(protoService.getReturnCode(timeRange.invalidStatus))
                .build();
        }

        if (req.getPid() > 0) {
            PatientRecord patient = patientService.getPatientRecord(req.getPid());
            if (patient == null) {
                return GetPatientSkincareRecordsResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                    .build();
            }
            if (!StrUtils.isBlank(req.getDeptId()) && !Objects.equals(patient.getDeptId(), req.getDeptId())) {
                return GetPatientSkincareRecordsResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PATIENT_DEPT_MISMATCH))
                    .build();
            }
        }

        List<PatientSkincareRecord> records = patientSkincareRecordRepo.findAll().stream()
            .filter(record -> req.getId() <= 0 || Objects.equals(record.getId(), req.getId()))
            .filter(record -> StrUtils.isBlank(req.getDeptId()) || req.getDeptId().equals(record.getDeptId()))
            .filter(record -> req.getPid() <= 0 || Objects.equals(record.getPid(), req.getPid()))
            .filter(record -> req.getPatientSkincarePlanId() <= 0
                || Objects.equals(record.getPatientSkincarePlanId(), req.getPatientSkincarePlanId()))
            .filter(record -> !record.getCreatedAt().isBefore(timeRange.start) && !record.getCreatedAt().isAfter(timeRange.end))
            .filter(record -> matchesDeleted(record.getIsDeleted(), req.getIsDeleted()))
            .sorted(Comparator.comparing(PatientSkincareRecord::getCreatedAt)
                .thenComparing(PatientSkincareRecord::getId))
            .toList();

        int childDeletedFilter = req.getIsDeleted() >= 0 ? req.getIsDeleted() : 0;
        Map<Long, List<PatientSkincareRecordAttr>> attrMap = getRecordAttrMap(
            records.stream().map(PatientSkincareRecord::getId).toList(), childDeletedFilter
        );

        return GetPatientSkincareRecordsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllRecord(records.stream()
                .map(record -> toProto(record, attrMap.getOrDefault(record.getId(), List.of())))
                .toList())
            .build();
    }

    @Transactional
    public AddPatientSkincareRecordResp addPatientSkincareRecord(String addPatientSkincareRecordReqJson) {
        final AddPatientSkincareRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addPatientSkincareRecordReqJson, AddPatientSkincareRecordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return AddPatientSkincareRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String accountId = getCurrentAccountId();
        if (accountId == null) {
            return AddPatientSkincareRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        PatientSkincareRecordPB recordPb = req.getRecord();
        PatientRecord patient = patientService.getPatientRecord(recordPb.getPid());
        if (patient == null) {
            return AddPatientSkincareRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        String deptId = resolvePatientDeptId(recordPb.getDeptId(), patient);
        if (deptId == null) {
            return AddPatientSkincareRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_DEPT_MISMATCH))
                .build();
        }

        PatientSkincarePlan plan = patientSkincarePlanRepo.findByIdAndIsDeletedFalse(recordPb.getPatientSkincarePlanId())
            .orElse(null);
        if (plan == null) {
            return AddPatientSkincareRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_PLAN_NOT_FOUND))
                .build();
        }
        if (!Objects.equals(plan.getDeptId(), deptId) || !Objects.equals(plan.getPid(), recordPb.getPid())) {
            return AddPatientSkincareRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_PLAN_NOT_FOUND))
                .build();
        }

        StatusCode attrValidationCode = validateRecordAttrPayload(recordPb.getAttrList(), plan.getSkincareTypeId(), true);
        if (attrValidationCode != null) {
            return AddPatientSkincareRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(attrValidationCode))
                .build();
        }

        LocalDateTime now = TimeUtils.getNowUtc();
        PatientSkincareRecord record = PatientSkincareRecord.builder()
            .deptId(deptId)
            .pid(recordPb.getPid())
            .patientSkincarePlanId(plan.getId())
            .createdAt(now)
            .isDeleted(false)
            .modifiedBy(accountId)
            .modifiedAt(now)
            .build();
        record = patientSkincareRecordRepo.save(record);

        if (recordPb.getAttrCount() > 0) {
            List<PatientSkincareRecordAttr> attrs = new ArrayList<>();
            for (PatientSkincareRecordAttrPB attrPb : recordPb.getAttrList()) {
                PatientSkincareRecordAttr attr = new PatientSkincareRecordAttr();
                fillRecordAttrEntity(attr, record.getId(), attrPb, accountId, now);
                attrs.add(attr);
            }
            patientSkincareRecordAttrRepo.saveAll(attrs);
        }

        return AddPatientSkincareRecordResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(record.getId())
            .build();
    }

    @Transactional
    public GenericResp updatePatientSkincareRecord(String updatePatientSkincareRecordReqJson) {
        final UpdatePatientSkincareRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updatePatientSkincareRecordReqJson, UpdatePatientSkincareRecordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String accountId = getCurrentAccountId();
        if (accountId == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        PatientSkincareRecordPB recordPb = req.getRecord();
        PatientSkincareRecord record = patientSkincareRecordRepo.findByIdAndIsDeletedFalse(recordPb.getId()).orElse(null);
        if (record == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_RECORD_NOT_FOUND))
                .build();
        }

        PatientRecord patient = patientService.getPatientRecord(recordPb.getPid());
        if (patient == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        String deptId = resolvePatientDeptId(recordPb.getDeptId(), patient);
        if (deptId == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_DEPT_MISMATCH))
                .build();
        }

        PatientSkincarePlan plan = patientSkincarePlanRepo.findByIdAndIsDeletedFalse(recordPb.getPatientSkincarePlanId())
            .orElse(null);
        if (plan == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_PLAN_NOT_FOUND))
                .build();
        }
        if (!Objects.equals(plan.getDeptId(), deptId) || !Objects.equals(plan.getPid(), recordPb.getPid())) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_PLAN_NOT_FOUND))
                .build();
        }

        record.setDeptId(deptId);
        record.setPid(recordPb.getPid());
        record.setPatientSkincarePlanId(plan.getId());
        record.setModifiedBy(accountId);
        record.setModifiedAt(TimeUtils.getNowUtc());
        patientSkincareRecordRepo.save(record);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deletePatientSkincareRecord(String deletePatientSkincareRecordReqJson) {
        final DeletePatientSkincareRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deletePatientSkincareRecordReqJson, DeletePatientSkincareRecordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String accountId = getCurrentAccountId();
        if (accountId == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        PatientSkincareRecord record = patientSkincareRecordRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (record == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        LocalDateTime now = TimeUtils.getNowUtc();
        softDeleteRecord(record, accountId, now);
        patientSkincareRecordRepo.save(record);

        List<PatientSkincareRecordAttr> attrs = patientSkincareRecordAttrRepo
            .findByPatientSkincareRecordIdAndIsDeletedFalse(record.getId());
        for (PatientSkincareRecordAttr attr : attrs) {
            softDeleteRecordAttr(attr, accountId, now);
        }
        patientSkincareRecordAttrRepo.saveAll(attrs);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    public GetPatientSkincareRecordAttrsResp getPatientSkincareRecordAttrs(String getPatientSkincareRecordAttrsReqJson) {
        final GetPatientSkincareRecordAttrsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getPatientSkincareRecordAttrsReqJson, GetPatientSkincareRecordAttrsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GetPatientSkincareRecordAttrsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        List<PatientSkincareRecordAttrPB> attrs = patientSkincareRecordAttrRepo.findAll().stream()
            .filter(attr -> req.getId() <= 0 || Objects.equals(attr.getId(), req.getId()))
            .filter(attr -> req.getPatientSkincareRecordId() <= 0
                || Objects.equals(attr.getPatientSkincareRecordId(), req.getPatientSkincareRecordId()))
            .filter(attr -> req.getSkincareAttrId() <= 0 || Objects.equals(attr.getSkincareAttrId(), req.getSkincareAttrId()))
            .filter(attr -> matchesDeleted(attr.getIsDeleted(), req.getIsDeleted()))
            .sorted(Comparator.comparing(PatientSkincareRecordAttr::getPatientSkincareRecordId)
                .thenComparing(PatientSkincareRecordAttr::getSkincareAttrId)
                .thenComparing(PatientSkincareRecordAttr::getId))
            .map(this::toProto)
            .toList();

        return GetPatientSkincareRecordAttrsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllAttr(attrs)
            .build();
    }

    @Transactional
    public AddPatientSkincareRecordAttrResp addPatientSkincareRecordAttr(String addPatientSkincareRecordAttrReqJson) {
        final AddPatientSkincareRecordAttrReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addPatientSkincareRecordAttrReqJson, AddPatientSkincareRecordAttrReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return AddPatientSkincareRecordAttrResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String accountId = getCurrentAccountId();
        if (accountId == null) {
            return AddPatientSkincareRecordAttrResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        PatientSkincareRecordAttrPB attrPb = req.getAttr();
        PatientSkincareRecord record = patientSkincareRecordRepo
            .findByIdAndIsDeletedFalse(attrPb.getPatientSkincareRecordId())
            .orElse(null);
        if (record == null) {
            return AddPatientSkincareRecordAttrResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_RECORD_NOT_FOUND))
                .build();
        }

        PatientSkincarePlan plan = patientSkincarePlanRepo.findByIdAndIsDeletedFalse(record.getPatientSkincarePlanId())
            .orElse(null);
        if (plan == null) {
            return AddPatientSkincareRecordAttrResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_PLAN_NOT_FOUND))
                .build();
        }

        StatusCode attrValidationCode = validateAttrBelongsToType(attrPb.getSkincareAttrId(), plan.getSkincareTypeId());
        if (attrValidationCode != null) {
            return AddPatientSkincareRecordAttrResp.newBuilder()
                .setRt(protoService.getReturnCode(attrValidationCode))
                .build();
        }

        PatientSkincareRecordAttr existing = patientSkincareRecordAttrRepo
            .findByPatientSkincareRecordIdAndSkincareAttrIdAndIsDeletedFalse(
                attrPb.getPatientSkincareRecordId(), attrPb.getSkincareAttrId())
            .orElse(null);
        if (existing != null) {
            return AddPatientSkincareRecordAttrResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_RECORD_ATTR_ALREADY_EXISTS))
                .build();
        }

        PatientSkincareRecordAttr deleted = patientSkincareRecordAttrRepo.findAll().stream()
            .filter(attr -> Boolean.TRUE.equals(attr.getIsDeleted()))
            .filter(attr -> Objects.equals(attr.getPatientSkincareRecordId(), attrPb.getPatientSkincareRecordId()))
            .filter(attr -> Objects.equals(attr.getSkincareAttrId(), attrPb.getSkincareAttrId()))
            .findFirst()
            .orElse(null);

        LocalDateTime now = TimeUtils.getNowUtc();
        PatientSkincareRecordAttr attr = deleted == null ? new PatientSkincareRecordAttr() : deleted;
        fillRecordAttrEntity(attr, record.getId(), attrPb, accountId, now);
        attr = patientSkincareRecordAttrRepo.save(attr);
        touchRecord(record, accountId, now);

        return AddPatientSkincareRecordAttrResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(attr.getId())
            .build();
    }

    @Transactional
    public GenericResp updatePatientSkincareRecordAttr(String updatePatientSkincareRecordAttrReqJson) {
        final UpdatePatientSkincareRecordAttrReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updatePatientSkincareRecordAttrReqJson, UpdatePatientSkincareRecordAttrReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String accountId = getCurrentAccountId();
        if (accountId == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        PatientSkincareRecordAttrPB attrPb = req.getAttr();
        PatientSkincareRecordAttr attr = patientSkincareRecordAttrRepo.findByIdAndIsDeletedFalse(attrPb.getId()).orElse(null);
        if (attr == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_RECORD_ATTR_NOT_FOUND))
                .build();
        }
        if (attrPb.getPatientSkincareRecordId() > 0
            && !Objects.equals(attr.getPatientSkincareRecordId(), attrPb.getPatientSkincareRecordId())
        ) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_RECORD_ATTR_NOT_FOUND))
                .build();
        }

        PatientSkincareRecord record = patientSkincareRecordRepo.findByIdAndIsDeletedFalse(attr.getPatientSkincareRecordId())
            .orElse(null);
        if (record == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_RECORD_NOT_FOUND))
                .build();
        }

        PatientSkincarePlan plan = patientSkincarePlanRepo.findByIdAndIsDeletedFalse(record.getPatientSkincarePlanId())
            .orElse(null);
        if (plan == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_PLAN_NOT_FOUND))
                .build();
        }

        Integer skincareAttrId = attrPb.getSkincareAttrId() > 0 ? attrPb.getSkincareAttrId() : attr.getSkincareAttrId();
        StatusCode attrValidationCode = validateAttrBelongsToType(skincareAttrId, plan.getSkincareTypeId());
        if (attrValidationCode != null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(attrValidationCode))
                .build();
        }

        PatientSkincareRecordAttr duplicate = patientSkincareRecordAttrRepo
            .findByPatientSkincareRecordIdAndSkincareAttrIdAndIsDeletedFalse(record.getId(), skincareAttrId)
            .orElse(null);
        if (duplicate != null && !Objects.equals(duplicate.getId(), attr.getId())) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SKINCARE_RECORD_ATTR_ALREADY_EXISTS))
                .build();
        }

        attr.setSkincareAttrId(skincareAttrId);
        attr.setValue(ProtoUtils.encodeGenericValue(attrPb.getValue()));
        attr.setModifiedBy(accountId);
        attr.setModifiedAt(TimeUtils.getNowUtc());
        patientSkincareRecordAttrRepo.save(attr);
        touchRecord(record, accountId, attr.getModifiedAt());

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deletePatientSkincareRecordAttr(String deletePatientSkincareRecordAttrReqJson) {
        final DeletePatientSkincareRecordAttrReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deletePatientSkincareRecordAttrReqJson, DeletePatientSkincareRecordAttrReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String accountId = getCurrentAccountId();
        if (accountId == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        PatientSkincareRecordAttr attr = patientSkincareRecordAttrRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (attr == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        LocalDateTime now = TimeUtils.getNowUtc();
        softDeleteRecordAttr(attr, accountId, now);
        patientSkincareRecordAttrRepo.save(attr);

        PatientSkincareRecord record = patientSkincareRecordRepo.findByIdAndIsDeletedFalse(attr.getPatientSkincareRecordId())
            .orElse(null);
        if (record != null) {
            touchRecord(record, accountId, now);
        }

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    // endregion

    // region Mapping and helpers

    private SkincareTypePB toProto(SkincareType type, List<SkincareTypeAttribute> attrs) {
        SkincareTypePB.Builder builder = SkincareTypePB.newBuilder()
            .setId(type.getId())
            .setDeptId(defaultStr(type.getDeptId()))
            .setType(defaultStr(type.getType()))
            .setName(defaultStr(type.getName()));
        builder.addAllAttr(attrs.stream().map(this::toProto).toList());
        return builder.build();
    }

    private SkincareTypeAttributePB toProto(SkincareTypeAttribute attr) {
        ValueMetaPB attrType = ProtoUtils.decodeValueMeta(attr.getAttrTypePb());
        if (attrType == null) attrType = ValueMetaPB.getDefaultInstance();

        SkincareTypeAttributePB.Builder builder = SkincareTypeAttributePB.newBuilder()
            .setId(attr.getId())
            .setSkincareTypeId(attr.getSkincareTypeId())
            .setAttrCode(defaultStr(attr.getAttrCode()))
            .setAttrName(defaultStr(attr.getAttrName()))
            .setAttrType(attrType)
            .setIsInitial(Boolean.TRUE.equals(attr.getIsInitial()))
            .setIsMaintenance(Boolean.TRUE.equals(attr.getIsMaintenance()))
            .setShowInTable(Boolean.TRUE.equals(attr.getShowInTable()));
        if (attr.getCategoryId() != null) {
            builder.setCategoryId(attr.getCategoryId());
        }
        return builder.build();
    }

    private PatientSkincarePlanPB toProto(PatientSkincarePlan plan, List<PatientSkincarePlanAttr> attrs) {
        PatientSkincarePlanPB.Builder builder = PatientSkincarePlanPB.newBuilder()
            .setId(plan.getId())
            .setDeptId(defaultStr(plan.getDeptId()))
            .setPid(plan.getPid())
            .setSkincareTypeId(plan.getSkincareTypeId())
            .setCreatedAtIso8601(TimeUtils.toIso8601String(plan.getCreatedAt(), ZONE_ID));
        builder.addAllAttr(attrs.stream().map(this::toProto).toList());
        return builder.build();
    }

    private PatientSkincarePlanAttrPB toProto(PatientSkincarePlanAttr attr) {
        GenericValuePB value = ProtoUtils.decodeGenericValue(attr.getValue());
        if (value == null) value = GenericValuePB.getDefaultInstance();
        return PatientSkincarePlanAttrPB.newBuilder()
            .setId(attr.getId())
            .setPatientSkincarePlanId(attr.getPatientSkincarePlanId())
            .setSkincareAttrId(attr.getSkincareAttrId())
            .setValue(value)
            .build();
    }

    private PatientSkincareRecordPB toProto(PatientSkincareRecord record, List<PatientSkincareRecordAttr> attrs) {
        PatientSkincareRecordPB.Builder builder = PatientSkincareRecordPB.newBuilder()
            .setId(record.getId())
            .setDeptId(defaultStr(record.getDeptId()))
            .setPid(record.getPid())
            .setPatientSkincarePlanId(record.getPatientSkincarePlanId())
            .setCreatedAtIso8601(TimeUtils.toIso8601String(record.getCreatedAt(), ZONE_ID));
        builder.addAllAttr(attrs.stream().map(this::toProto).toList());
        return builder.build();
    }

    private PatientSkincareRecordAttrPB toProto(PatientSkincareRecordAttr attr) {
        GenericValuePB value = ProtoUtils.decodeGenericValue(attr.getValue());
        if (value == null) value = GenericValuePB.getDefaultInstance();
        return PatientSkincareRecordAttrPB.newBuilder()
            .setId(attr.getId())
            .setPatientSkincareRecordId(attr.getPatientSkincareRecordId())
            .setSkincareAttrId(attr.getSkincareAttrId())
            .setValue(value)
            .build();
    }

    private void fillTypeAttrEntity(
        SkincareTypeAttribute attr, SkincareTypeAttributePB attrPb, String accountId, LocalDateTime now
    ) {
        attr.setSkincareTypeId(attrPb.getSkincareTypeId());
        attr.setAttrCode(attrPb.getAttrCode());
        attr.setAttrName(attrPb.getAttrName());
        attr.setCategoryId(attrPb.getCategoryId() > 0 ? attrPb.getCategoryId() : null);
        attr.setAttrTypePb(ProtoUtils.encodeValueMeta(attrPb.getAttrType()));
        attr.setIsInitial(attrPb.getIsInitial());
        attr.setIsMaintenance(attrPb.getIsMaintenance());
        attr.setShowInTable(attrPb.getShowInTable());
        attr.setIsDeleted(false);
        attr.setDeletedBy(null);
        attr.setDeletedAt(null);
        attr.setModifiedBy(accountId);
        attr.setModifiedAt(now);
    }

    private void fillPlanAttrEntity(
        PatientSkincarePlanAttr attr, Long planId, PatientSkincarePlanAttrPB attrPb, String accountId, LocalDateTime now
    ) {
        attr.setPatientSkincarePlanId(planId);
        attr.setSkincareAttrId(attrPb.getSkincareAttrId());
        attr.setValue(ProtoUtils.encodeGenericValue(attrPb.getValue()));
        attr.setIsDeleted(false);
        attr.setDeletedBy(null);
        attr.setDeletedAt(null);
        attr.setModifiedBy(accountId);
        attr.setModifiedAt(now);
    }

    private void fillRecordAttrEntity(
        PatientSkincareRecordAttr attr, Long recordId, PatientSkincareRecordAttrPB attrPb, String accountId, LocalDateTime now
    ) {
        attr.setPatientSkincareRecordId(recordId);
        attr.setSkincareAttrId(attrPb.getSkincareAttrId());
        attr.setValue(ProtoUtils.encodeGenericValue(attrPb.getValue()));
        attr.setIsDeleted(false);
        attr.setDeletedBy(null);
        attr.setDeletedAt(null);
        attr.setModifiedBy(accountId);
        attr.setModifiedAt(now);
    }

    private Map<Integer, List<SkincareTypeAttribute>> getTypeAttrMap(List<Integer> typeIds, int isDeletedFilter) {
        if (typeIds.isEmpty()) return Map.of();

        List<SkincareTypeAttribute> attrs;
        if (isDeletedFilter == 0) {
            attrs = skincareTypeAttrRepo.findBySkincareTypeIdInAndIsDeletedFalse(typeIds);
        } else {
            Set<Integer> typeIdSet = new HashSet<>(typeIds);
            attrs = skincareTypeAttrRepo.findAll().stream()
                .filter(attr -> typeIdSet.contains(attr.getSkincareTypeId()))
                .filter(attr -> matchesDeleted(attr.getIsDeleted(), isDeletedFilter))
                .toList();
        }
        return attrs.stream()
            .sorted(Comparator.comparing(SkincareTypeAttribute::getCategoryId, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(SkincareTypeAttribute::getAttrName, Comparator.nullsLast(String::compareTo))
                .thenComparing(SkincareTypeAttribute::getId))
            .collect(Collectors.groupingBy(
                SkincareTypeAttribute::getSkincareTypeId,
                LinkedHashMap::new,
                Collectors.toList()));
    }

    private Map<Long, List<PatientSkincarePlanAttr>> getPlanAttrMap(List<Long> planIds, int isDeletedFilter) {
        if (planIds.isEmpty()) return Map.of();

        List<PatientSkincarePlanAttr> attrs;
        if (isDeletedFilter == 0) {
            attrs = patientSkincarePlanAttrRepo.findByPatientSkincarePlanIdInAndIsDeletedFalse(planIds);
        } else {
            Set<Long> planIdSet = new HashSet<>(planIds);
            attrs = patientSkincarePlanAttrRepo.findAll().stream()
                .filter(attr -> planIdSet.contains(attr.getPatientSkincarePlanId()))
                .filter(attr -> matchesDeleted(attr.getIsDeleted(), isDeletedFilter))
                .toList();
        }

        return attrs.stream()
            .sorted(Comparator.comparing(PatientSkincarePlanAttr::getSkincareAttrId)
                .thenComparing(PatientSkincarePlanAttr::getId))
            .collect(Collectors.groupingBy(
                PatientSkincarePlanAttr::getPatientSkincarePlanId,
                LinkedHashMap::new,
                Collectors.toList()));
    }

    private Map<Long, List<PatientSkincareRecordAttr>> getRecordAttrMap(List<Long> recordIds, int isDeletedFilter) {
        if (recordIds.isEmpty()) return Map.of();

        List<PatientSkincareRecordAttr> attrs;
        if (isDeletedFilter == 0) {
            attrs = patientSkincareRecordAttrRepo.findByPatientSkincareRecordIdInAndIsDeletedFalse(recordIds);
        } else {
            Set<Long> recordIdSet = new HashSet<>(recordIds);
            attrs = patientSkincareRecordAttrRepo.findAll().stream()
                .filter(attr -> recordIdSet.contains(attr.getPatientSkincareRecordId()))
                .filter(attr -> matchesDeleted(attr.getIsDeleted(), isDeletedFilter))
                .toList();
        }

        return attrs.stream()
            .sorted(Comparator.comparing(PatientSkincareRecordAttr::getSkincareAttrId)
                .thenComparing(PatientSkincareRecordAttr::getId))
            .collect(Collectors.groupingBy(
                PatientSkincareRecordAttr::getPatientSkincareRecordId,
                LinkedHashMap::new,
                Collectors.toList()));
    }

    private StatusCode validatePlanAttrPayload(
        List<PatientSkincarePlanAttrPB> attrs, Integer skincareTypeId, boolean checkDuplicateInRequest
    ) {
        Set<Integer> attrIds = new HashSet<>();
        for (PatientSkincarePlanAttrPB attr : attrs) {
            if (checkDuplicateInRequest && !attrIds.add(attr.getSkincareAttrId())) {
                return StatusCode.PATIENT_SKINCARE_PLAN_ATTR_ALREADY_EXISTS;
            }
            StatusCode code = validateAttrBelongsToType(attr.getSkincareAttrId(), skincareTypeId);
            if (code != null) return code;
        }
        return null;
    }

    private StatusCode validateRecordAttrPayload(
        List<PatientSkincareRecordAttrPB> attrs, Integer skincareTypeId, boolean checkDuplicateInRequest
    ) {
        Set<Integer> attrIds = new HashSet<>();
        for (PatientSkincareRecordAttrPB attr : attrs) {
            if (checkDuplicateInRequest && !attrIds.add(attr.getSkincareAttrId())) {
                return StatusCode.PATIENT_SKINCARE_RECORD_ATTR_ALREADY_EXISTS;
            }
            StatusCode code = validateAttrBelongsToType(attr.getSkincareAttrId(), skincareTypeId);
            if (code != null) return code;
        }
        return null;
    }

    private StatusCode validateAttrBelongsToType(Integer skincareAttrId, Integer skincareTypeId) {
        SkincareTypeAttribute attr = skincareTypeAttrRepo.findByIdAndIsDeletedFalse(skincareAttrId).orElse(null);
        if (attr == null) return StatusCode.SKINCARE_TYPE_ATTR_NOT_FOUND;
        if (!Objects.equals(attr.getSkincareTypeId(), skincareTypeId)) {
            return StatusCode.SKINCARE_TYPE_ATTR_ID_NOT_MATCH;
        }
        return null;
    }

    private String resolvePatientDeptId(String requestedDeptId, PatientRecord patient) {
        if (StrUtils.isBlank(requestedDeptId)) return patient.getDeptId();
        if (!Objects.equals(requestedDeptId, patient.getDeptId())) return null;
        return requestedDeptId;
    }

    private TimeRangeResult parseQueryTimeRange(String startIso8601, String endIso8601) {
        LocalDateTime start = Consts.MIN_TIME;
        LocalDateTime end = Consts.MAX_TIME;

        if (!StrUtils.isBlank(startIso8601)) {
            start = TimeUtils.fromIso8601String(startIso8601, "UTC");
            if (start == null) return new TimeRangeResult(null, null, StatusCode.INVALID_TIME_FORMAT);
        }
        if (!StrUtils.isBlank(endIso8601)) {
            end = TimeUtils.fromIso8601String(endIso8601, "UTC");
            if (end == null) return new TimeRangeResult(null, null, StatusCode.INVALID_TIME_FORMAT);
        }
        if (start.isAfter(end)) {
            return new TimeRangeResult(null, null, StatusCode.INVALID_TIME_RANGE);
        }
        return new TimeRangeResult(start, end, null);
    }

    private boolean matchesDeleted(Boolean isDeleted, int deletedFilter) {
        if (deletedFilter < 0) return true;
        return Boolean.TRUE.equals(isDeleted) == (deletedFilter > 0);
    }

    private boolean matchesBoolean(Boolean value, int filter) {
        if (filter < 0) return true;
        return Boolean.TRUE.equals(value) == (filter > 0);
    }

    private boolean isValidCategoryId(int categoryId) {
        return categoryId <= 0 || validCategoryIds.contains(categoryId);
    }

    private String getCurrentAccountId() {
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) return null;
        return account.getFirst();
    }

    private void softDeleteType(SkincareType type, String accountId, LocalDateTime now) {
        type.setIsDeleted(true);
        type.setDeletedBy(accountId);
        type.setDeletedAt(now);
        type.setModifiedBy(accountId);
        type.setModifiedAt(now);
    }

    private void softDeleteTypeAttr(SkincareTypeAttribute attr, String accountId, LocalDateTime now) {
        attr.setIsDeleted(true);
        attr.setDeletedBy(accountId);
        attr.setDeletedAt(now);
        attr.setModifiedBy(accountId);
        attr.setModifiedAt(now);
    }

    private void softDeletePlan(PatientSkincarePlan plan, String accountId, LocalDateTime now) {
        plan.setIsDeleted(true);
        plan.setDeletedBy(accountId);
        plan.setDeletedAt(now);
        plan.setModifiedBy(accountId);
        plan.setModifiedAt(now);
    }

    private void softDeletePlanAttr(PatientSkincarePlanAttr attr, String accountId, LocalDateTime now) {
        attr.setIsDeleted(true);
        attr.setDeletedBy(accountId);
        attr.setDeletedAt(now);
        attr.setModifiedBy(accountId);
        attr.setModifiedAt(now);
    }

    private void softDeleteRecord(PatientSkincareRecord record, String accountId, LocalDateTime now) {
        record.setIsDeleted(true);
        record.setDeletedBy(accountId);
        record.setDeletedAt(now);
        record.setModifiedBy(accountId);
        record.setModifiedAt(now);
    }

    private void softDeleteRecordAttr(PatientSkincareRecordAttr attr, String accountId, LocalDateTime now) {
        attr.setIsDeleted(true);
        attr.setDeletedBy(accountId);
        attr.setDeletedAt(now);
        attr.setModifiedBy(accountId);
        attr.setModifiedAt(now);
    }

    private void touchPlan(PatientSkincarePlan plan, String accountId, LocalDateTime now) {
        plan.setModifiedBy(accountId);
        plan.setModifiedAt(now);
        patientSkincarePlanRepo.save(plan);
    }

    private void touchRecord(PatientSkincareRecord record, String accountId, LocalDateTime now) {
        record.setModifiedBy(accountId);
        record.setModifiedAt(now);
        patientSkincareRecordRepo.save(record);
    }

    private String defaultStr(String val) {
        return val == null ? "" : val;
    }

    private static class TimeRangeResult {
        private TimeRangeResult(LocalDateTime start, LocalDateTime end, StatusCode invalidStatus) {
            this.start = start;
            this.end = end;
            this.invalidStatus = invalidStatus;
        }

        private final LocalDateTime start;
        private final LocalDateTime end;
        private final StatusCode invalidStatus;
    }

    private final String ZONE_ID;
    private final Set<Integer> validCategoryIds;

    private final ConfigProtoService protoService;
    private final UserService userService;
    private final PatientService patientService;
    private final SkincareTypeRepository skincareTypeRepo;
    private final SkincareTypeAttributeRepository skincareTypeAttrRepo;
    private final PatientSkincarePlanRepository patientSkincarePlanRepo;
    private final PatientSkincarePlanAttrRepository patientSkincarePlanAttrRepo;
    private final PatientSkincareRecordRepository patientSkincareRecordRepo;
    private final PatientSkincareRecordAttrRepository patientSkincareRecordAttrRepo;
}
