package com.jingyicare.jingyi_icis_engine.service.nursingrecords;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.NursingRecordTemplate;
import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.NursingRecordTemplateGroup;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
@Slf4j
public class NursingOrderTemplateService {
    public static final Integer ENTITY_TYPE_ACCOUNT = 0;
    public static final Integer ENTITY_TYPE_DEPT = 1;

    public NursingOrderTemplateService(
        @Autowired ConfigProtoService protoService,
        @Autowired UserService userService,
        @Autowired NursingRecordTemplateGroupRepository templateGroupRepo,
        @Autowired NursingRecordTemplateRepository templateRepo,
        @Autowired RbacDepartmentRepository deptRepo,
        @Autowired LockExecutor lockExecutor
    ) {
        this.protoService = protoService;
        this.userService = userService;
        this.templateGroupRepo = templateGroupRepo;
        this.templateRepo = templateRepo;
        this.deptRepo = deptRepo;
        this.lockExecutor = lockExecutor;
    }

    public void initDeptTemplateGroups() {
        List<NursingRecordTemplateGroupPB> defaultGroups = protoService.getConfig()
            .getNursingRecord().getDefaultDeptGroupList();
        if (defaultGroups.isEmpty()) return;

        // 获取所有需要初始化的科室 ID, 创建资源锁
        Set<String> deptToInitialize = getDeptIdsToInitialize();
        List<String> resourceKeys = deptToInitialize.stream()
            .flatMap(deptId -> defaultGroups.stream().map(group ->
                LockKeys.nursingRecordTemplateGroup(ENTITY_TYPE_DEPT, deptId, group.getName())))
            .toList();
        if (resourceKeys.isEmpty()) return;

        lockExecutor.execute(resourceKeys, () -> {
            createDeptTemplateGroups(deptToInitialize, defaultGroups);
            return null;
        });
        log.info("Department nursing record templates initialization completed.");
    }

    public GetNursingRecordTemplatesResp getNursingRecordTemplates(String reqJson) {
        final GetNursingRecordTemplatesReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, GetNursingRecordTemplatesReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetNursingRecordTemplatesResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        GetNursingRecordTemplatesResp.Builder respBuilder = GetNursingRecordTemplatesResp
            .newBuilder();
        if (!StrUtils.isBlank(req.getDeptId())) {
            respBuilder.addAllDeptTemplate(getTemplates(ENTITY_TYPE_DEPT, req.getDeptId()));
        }

        // 获取用户信息
        String ctxAccountId = req.getAccountId();
        if (!StrUtils.isBlank(ctxAccountId)) {
            String accountId = userService.getAccountAutoId(ctxAccountId);
            respBuilder.addAllAccountTemplate(getTemplates(ENTITY_TYPE_ACCOUNT, accountId));
        }

        return respBuilder.setRt(protoService.getReturnCode(StatusCode.OK)).build();
    }

    private List<NursingRecordTemplateGroupPB> getTemplates(Integer entityType, String entityId) {
        return templateGroupRepo.findByEntityTypeAndEntityIdAndIsDeletedFalse(entityType, entityId)
            .stream()
            .map(group -> {
                NursingRecordTemplateGroupPB.Builder groupBuilder = NursingRecordTemplateGroupPB
                    .newBuilder()
                    .setId(group.getId())
                    .setName(group.getName())
                    .setDisplayOrder(group.getDisplayOrder());

                List<NursingRecordTemplatePB> templatePBs = templateRepo
                    .findByEntityTypeAndEntityIdAndGroupIdAndIsDeletedFalse(
                        entityType, entityId, group.getId())
                    .stream()
                    .map(template -> NursingRecordTemplatePB.newBuilder()
                        .setId(template.getId())
                        .setName(template.getName())
                        .setContent(template.getContent())
                        .setDisplayOrder(template.getDisplayOrder())
                        .setIsCommon(template.getIsCommon())
                        .setGroupId(template.getGroupId())
                        .build())
                    .sorted((a, b) -> Integer.compare(a.getDisplayOrder(), b.getDisplayOrder()))
                    .toList();
                groupBuilder.addAllNursingRecordTemplate(templatePBs);

                return groupBuilder.build();
            })
            .sorted((a, b) -> Integer.compare(a.getDisplayOrder(), b.getDisplayOrder()))
            .toList();
    }

    public AddNursingRecordTemplateGroupResp addNursingRecordTemplateGroup(String reqJson) {
        final AddNursingRecordTemplateGroupReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, AddNursingRecordTemplateGroupReq
                .newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return AddNursingRecordTemplateGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED)).build();
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return AddNursingRecordTemplateGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND)).build();
        }
        String accountId = account.getFirst();
        Integer entityType = StrUtils.isBlank(req.getDeptId())
            ? ENTITY_TYPE_ACCOUNT : ENTITY_TYPE_DEPT;
        String entityId = StrUtils.isBlank(req.getDeptId()) ? accountId : req.getDeptId();
        String groupName = req.getGroup().getName();
        if (StrUtils.isBlank(groupName)) {
            return AddNursingRecordTemplateGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_PARAM_VALUE)).build();
        }

        String resourceKey = LockKeys.nursingRecordTemplateGroup(entityType, entityId, groupName);
        return lockExecutor.execute(List.of(resourceKey), () -> {
            if (templateGroupRepo.existsByEntityTypeAndEntityIdAndNameAndIsDeletedFalse(
                entityType, entityId, groupName)
            ) {
                return AddNursingRecordTemplateGroupResp.newBuilder()
                    .setRt(protoService.getReturnCode(
                        StatusCode.NURSING_RECORD_TEMPLATE_GROUP_ALREADY_EXISTS)).build();
            }
            NursingRecordTemplateGroup group = templateGroupRepo.save(
                NursingRecordTemplateGroup.builder()
                    .entityType(entityType).entityId(entityId).name(groupName)
                    .displayOrder(req.getGroup().getDisplayOrder()).isDeleted(false)
                    .modifiedBy(accountId).modifiedAt(TimeUtils.getNowUtc()).build());
            return AddNursingRecordTemplateGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK)).setId(group.getId()).build();
        });
    }

    public GenericResp updateNursingRecordTemplateGroups(String reqJson) {
        final UpdateNursingRecordTemplateGroupsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, UpdateNursingRecordTemplateGroupsReq
                .newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return genericResponse(StatusCode.PARSE_JSON_FAILED);
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) return genericResponse(StatusCode.ACCOUNT_NOT_FOUND);
        if (req.getGroupCount() == 0) return genericResponse(StatusCode.OK);

        String accountId = account.getFirst();
        Integer entityType = StrUtils.isBlank(req.getDeptId())
            ? ENTITY_TYPE_ACCOUNT : ENTITY_TYPE_DEPT;
        String entityId = StrUtils.isBlank(req.getDeptId()) ? accountId : req.getDeptId();
        Map<Integer, NursingRecordTemplateGroup> groups = getTemplateGroups(entityType, entityId);
        Set<String> lockedNames = new HashSet<>();
        for (NursingRecordTemplateGroupPB groupPB : req.getGroupList()) {
            NursingRecordTemplateGroup group = groups.get(groupPB.getId());
            if (group == null) {
                return genericResponse(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_NOT_EXISTS);
            }
            lockedNames.add(group.getName());
            if (!StrUtils.isBlank(groupPB.getName())) lockedNames.add(groupPB.getName());
        }
        List<String> resourceKeys = lockedNames.stream()
            .map(name -> LockKeys.nursingRecordTemplateGroup(entityType, entityId, name)).toList();
        return lockExecutor.execute(resourceKeys, () -> updateTemplateGroups(
            req, entityType, entityId, accountId, lockedNames));
    }

    public GenericResp deleteNursingRecordTemplateGroup(String reqJson) {
        final DeleteNursingRecordTemplateGroupReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, DeleteNursingRecordTemplateGroupReq
                .newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return genericResponse(StatusCode.PARSE_JSON_FAILED);
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) return genericResponse(StatusCode.ACCOUNT_NOT_FOUND);
        NursingRecordTemplateGroup group = templateGroupRepo
            .findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (group == null) return genericResponse(StatusCode.OK);

        String resourceKey = LockKeys.nursingRecordTemplateGroup(
            group.getEntityType(), group.getEntityId(), group.getName());
        return lockExecutor.execute(List.of(resourceKey), () -> deleteTemplateGroup(
            req.getId(), account.getFirst(), resourceKey));
    }

    public AddNursingRecordTemplateResp addNursingRecordTemplate(String reqJson) {
        final AddNursingRecordTemplateReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, AddNursingRecordTemplateReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return templateResponse(StatusCode.PARSE_JSON_FAILED);
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) return templateResponse(StatusCode.ACCOUNT_NOT_FOUND);
        if (StrUtils.isBlank(req.getTemplate().getName())) {
            return templateResponse(StatusCode.INVALID_PARAM_VALUE);
        }
        NursingRecordTemplateGroup group = getTemplateGroup(req.getTemplate().getGroupId());
        if (group == null) {
            return templateResponse(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_NOT_EXISTS);
        }

        Set<String> resourceKeys = getTemplateResourceKeys(group, req.getTemplate().getName());
        return lockExecutor.execute(resourceKeys, () -> addTemplate(
            req.getTemplate(), account.getFirst(), resourceKeys));
    }

    public GenericResp updateNursingRecordTemplate(String reqJson) {
        final UpdateNursingRecordTemplateReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, UpdateNursingRecordTemplateReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return genericResponse(StatusCode.PARSE_JSON_FAILED);
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) return genericResponse(StatusCode.ACCOUNT_NOT_FOUND);
        NursingRecordTemplate template = templateRepo
            .findByIdAndIsDeletedFalse(req.getTemplate().getId()).orElse(null);
        if (template == null) return genericResponse(StatusCode.NURSING_RECORD_TEMPLATE_NOT_EXISTS);
        NursingRecordTemplateGroup source = getTemplateGroup(template.getGroupId());
        NursingRecordTemplateGroup target = getTemplateGroup(req.getTemplate().getGroupId());
        if (source == null || target == null) {
            return genericResponse(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_NOT_EXISTS);
        }

        String targetName = StrUtils.isBlank(req.getTemplate().getName())
            ? template.getName() : req.getTemplate().getName();
        Set<String> resourceKeys = new HashSet<>(
            getTemplateResourceKeys(source, template.getName()));
        resourceKeys.addAll(getTemplateResourceKeys(target, targetName));
        return lockExecutor.execute(resourceKeys, () -> updateTemplate(
            req.getTemplate(), account.getFirst(), resourceKeys));
    }

    public GenericResp deleteNursingRecordTemplate(String reqJson) {
        final DeleteNursingRecordTemplateReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, DeleteNursingRecordTemplateReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return genericResponse(StatusCode.PARSE_JSON_FAILED);
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) return genericResponse(StatusCode.ACCOUNT_NOT_FOUND);
        NursingRecordTemplate template = templateRepo
            .findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (template == null) return genericResponse(StatusCode.OK);
        NursingRecordTemplateGroup group = getTemplateGroup(template.getGroupId());
        if (group == null) {
            return genericResponse(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_NOT_EXISTS);
        }

        Set<String> resourceKeys = getTemplateResourceKeys(group, template.getName());
        return lockExecutor.execute(resourceKeys, () -> deleteTemplate(
            req.getId(), account.getFirst(), resourceKeys));
    }

    private AddNursingRecordTemplateResp addTemplate(
        NursingRecordTemplatePB templatePB, String accountId, Set<String> lockedKeys
    ) {
        NursingRecordTemplateGroup group = getTemplateGroup(templatePB.getGroupId());
        if (group == null) {
            return templateResponse(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_NOT_EXISTS);
        }
        if (!lockedKeys.containsAll(getTemplateResourceKeys(group, templatePB.getName()))) {
            return templateResponse(StatusCode.INVALID_PARAM_VALUE);
        }
        if (templateRepo.existsByEntityTypeAndEntityIdAndNameAndIsDeletedFalse(
            group.getEntityType(), group.getEntityId(), templatePB.getName())
        ) {
            return templateResponse(StatusCode.NURSING_RECORD_TEMPLATE_ALREADY_EXISTS);
        }
        NursingRecordTemplate template = templateRepo.save(NursingRecordTemplate.builder()
            .entityType(group.getEntityType()).entityId(group.getEntityId()).groupId(group.getId())
            .name(templatePB.getName()).content(templatePB.getContent())
            .displayOrder(templatePB.getDisplayOrder()).isCommon(templatePB.getIsCommon())
            .isDeleted(false).modifiedBy(accountId).modifiedAt(TimeUtils.getNowUtc()).build());
        return AddNursingRecordTemplateResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK)).setId(template.getId()).build();
    }

    private GenericResp updateTemplate(
        NursingRecordTemplatePB templatePB, String accountId, Set<String> lockedKeys
    ) {
        NursingRecordTemplate template = templateRepo
            .findByIdAndIsDeletedFalse(templatePB.getId()).orElse(null);
        if (template == null) return genericResponse(StatusCode.NURSING_RECORD_TEMPLATE_NOT_EXISTS);
        NursingRecordTemplateGroup source = getTemplateGroup(template.getGroupId());
        NursingRecordTemplateGroup target = getTemplateGroup(templatePB.getGroupId());
        if (source == null || target == null) {
            return genericResponse(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_NOT_EXISTS);
        }
        String name = StrUtils.isBlank(templatePB.getName())
            ? template.getName() : templatePB.getName();
        if (!lockedKeys.containsAll(getTemplateResourceKeys(source, template.getName()))
            || !lockedKeys.containsAll(getTemplateResourceKeys(target, name))
        ) {
            return genericResponse(StatusCode.INVALID_PARAM_VALUE);
        }
        NursingRecordTemplate duplicate = templateRepo
            .findByEntityTypeAndEntityIdAndNameAndIsDeletedFalse(
                target.getEntityType(), target.getEntityId(), name).orElse(null);
        if (duplicate != null && !duplicate.getId().equals(template.getId())) {
            // 保持原接口在更新重名时的返回码。
            return genericResponse(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_ALREADY_EXISTS);
        }
        template.setName(name);
        template.setContent(templatePB.getContent());
        template.setDisplayOrder(templatePB.getDisplayOrder());
        template.setIsCommon(templatePB.getIsCommon());
        template.setGroupId(target.getId());
        template.setEntityType(target.getEntityType());
        template.setEntityId(target.getEntityId());
        template.setModifiedBy(accountId);
        template.setModifiedAt(TimeUtils.getNowUtc());
        templateRepo.save(template);
        log.info("Updated nursing record template: {}, by {}", template.getId(), accountId);
        return genericResponse(StatusCode.OK);
    }

    private GenericResp deleteTemplate(Integer id, String accountId, Set<String> lockedKeys) {
        NursingRecordTemplate template = templateRepo.findByIdAndIsDeletedFalse(id).orElse(null);
        if (template == null) return genericResponse(StatusCode.OK);
        NursingRecordTemplateGroup group = getTemplateGroup(template.getGroupId());
        if (group == null) {
            return genericResponse(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_NOT_EXISTS);
        }
        if (!lockedKeys.containsAll(getTemplateResourceKeys(group, template.getName()))) {
            return genericResponse(StatusCode.INVALID_PARAM_VALUE);
        }
        template.setIsDeleted(true);
        template.setDeletedBy(accountId);
        template.setDeletedAt(TimeUtils.getNowUtc());
        templateRepo.save(template);
        return genericResponse(StatusCode.OK);
    }

    private NursingRecordTemplateGroup getTemplateGroup(Integer groupId) {
        return templateGroupRepo.findByIdAndIsDeletedFalse(groupId).orElse(null);
    }

    private Set<String> getTemplateResourceKeys(NursingRecordTemplateGroup group, String name) {
        return Set.of(
            LockKeys.nursingRecordTemplateGroup(
                group.getEntityType(), group.getEntityId(), group.getName()),
            LockKeys.nursingRecordTemplate(
                group.getEntityType(), group.getEntityId(), name));
    }

    private AddNursingRecordTemplateResp templateResponse(StatusCode code) {
        return AddNursingRecordTemplateResp.newBuilder()
            .setRt(protoService.getReturnCode(code)).build();
    }

    private GenericResp updateTemplateGroups(
        UpdateNursingRecordTemplateGroupsReq req, Integer entityType, String entityId,
        String accountId, Set<String> lockedNames
    ) {
        Map<Integer, NursingRecordTemplateGroup> groups = getTemplateGroups(entityType, entityId);
        Map<String, Integer> names = groups.values().stream().collect(Collectors.toMap(
            NursingRecordTemplateGroup::getName, NursingRecordTemplateGroup::getId));
        Set<Integer> requestedIds = new HashSet<>();
        Set<String> requestedNames = new HashSet<>();

        // 全部校验通过后再修改实体，避免返回错误时提交部分更新。
        for (NursingRecordTemplateGroupPB groupPB : req.getGroupList()) {
            NursingRecordTemplateGroup group = groups.get(groupPB.getId());
            if (group == null) {
                return genericResponse(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_NOT_EXISTS);
            }
            if (!lockedNames.contains(group.getName())) {
                log.warn("Template group name changed after locking: {}", group.getId());
                return genericResponse(StatusCode.INVALID_PARAM_VALUE);
            }
            if (!requestedIds.add(groupPB.getId())) {
                return genericResponse(StatusCode.INVALID_PARAM_VALUE);
            }
            String name = StrUtils.isBlank(groupPB.getName()) ? group.getName() : groupPB.getName();
            Integer existingId = names.get(name);
            if ((existingId != null && !existingId.equals(group.getId()))
                || !requestedNames.add(name)
            ) {
                return genericResponse(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_ALREADY_EXISTS);
            }
        }

        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        List<NursingRecordTemplateGroup> updatedGroups = new ArrayList<>();
        for (NursingRecordTemplateGroupPB groupPB : req.getGroupList()) {
            NursingRecordTemplateGroup group = groups.get(groupPB.getId());
            String name = StrUtils.isBlank(groupPB.getName()) ? group.getName() : groupPB.getName();
            if ((groupPB.getDisplayOrder() > 0
                && !group.getDisplayOrder().equals(groupPB.getDisplayOrder()))
                || !group.getName().equals(name)
            ) {
                group.setDisplayOrder(groupPB.getDisplayOrder());
                group.setName(name);
                group.setModifiedBy(accountId);
                group.setModifiedAt(nowUtc);
                updatedGroups.add(group);
            }
        }
        templateGroupRepo.saveAll(updatedGroups);
        log.info("Updated nursing record template groups: {}, by {}", updatedGroups, accountId);
        return genericResponse(StatusCode.OK);
    }

    private GenericResp deleteTemplateGroup(Integer groupId, String accountId, String lockedKey) {
        NursingRecordTemplateGroup group = templateGroupRepo
            .findByIdAndIsDeletedFalse(groupId).orElse(null);
        if (group == null) return genericResponse(StatusCode.OK);
        String currentKey = LockKeys.nursingRecordTemplateGroup(
            group.getEntityType(), group.getEntityId(), group.getName());
        if (!lockedKey.equals(currentKey)) {
            log.warn("Template group name changed after locking: {}", groupId);
            return genericResponse(StatusCode.INVALID_PARAM_VALUE);
        }
        if (!templateRepo.findByEntityTypeAndEntityIdAndGroupIdAndIsDeletedFalse(
            group.getEntityType(), group.getEntityId(), groupId).isEmpty()
        ) {
            return genericResponse(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_HAS_TEMPLATES);
        }
        group.setIsDeleted(true);
        group.setDeletedBy(accountId);
        group.setDeletedAt(TimeUtils.getNowUtc());
        templateGroupRepo.save(group);
        log.info("Deleted nursing record template group: {}, by {}", groupId, accountId);
        return genericResponse(StatusCode.OK);
    }

    private Map<Integer, NursingRecordTemplateGroup> getTemplateGroups(
        Integer entityType, String entityId
    ) {
        return templateGroupRepo.findByEntityTypeAndEntityIdAndIsDeletedFalse(entityType, entityId)
            .stream().collect(Collectors.toMap(NursingRecordTemplateGroup::getId, group -> group));
    }

    private GenericResp genericResponse(StatusCode code) {
        return GenericResp.newBuilder().setRt(protoService.getReturnCode(code)).build();
    }

    private Set<String> getDeptIdsToInitialize() {
        Set<String> deptToInitialize = deptRepo.findAll().stream()
            .map(RbacDepartment::getDeptId).collect(Collectors.toSet());
        deptToInitialize.removeAll(
            templateGroupRepo.findByEntityTypeAndIsDeletedFalse(ENTITY_TYPE_DEPT).stream()
                .map(NursingRecordTemplateGroup::getEntityId).collect(Collectors.toSet())
        );
        return deptToInitialize;
    }

    private void createDeptTemplateGroups(
        Set<String> deptIds, List<NursingRecordTemplateGroupPB> defaultGroups
    ) {
        // 检查锁定的资源 和 当前状态是否相符
        Set<String> curDeptIdsToInitialize = getDeptIdsToInitialize();
        if (!curDeptIdsToInitialize.containsAll(deptIds)) {
            log.warn("inconsistent state detected, skipping initialization: \n"
                + "deptIds {} \ncurDeptIdsToInitialize {}", deptIds, curDeptIdsToInitialize);
            return;
        }

        // 初始化护理记录模板
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        for (String deptId : deptIds) {
            log.info("Initializing nursing record department templates for deptId: {}", deptId);
            for (int groupIndex = 0; groupIndex < defaultGroups.size(); groupIndex++) {
                NursingRecordTemplateGroupPB groupPB = defaultGroups.get(groupIndex);
                NursingRecordTemplateGroup group = templateGroupRepo.save(
                    NursingRecordTemplateGroup.builder()
                        .entityType(ENTITY_TYPE_DEPT)
                        .entityId(deptId)
                        .name(groupPB.getName())
                        .displayOrder(groupIndex)
                        .isDeleted(false)
                        .modifiedBy("system")
                        .modifiedAt(nowUtc)
                        .build());

                for (int index = 0; index < groupPB.getNursingRecordTemplateCount(); index++) {
                    NursingRecordTemplatePB templatePB = groupPB.getNursingRecordTemplate(index);
                    templateRepo.save(NursingRecordTemplate.builder()
                        .entityType(ENTITY_TYPE_DEPT)
                        .entityId(deptId)
                        .name(templatePB.getName())
                        .content(templatePB.getContent())
                        .displayOrder(index)
                        .isCommon(templatePB.getIsCommon())
                        .groupId(group.getId())
                        .isDeleted(false)
                        .modifiedBy("system")
                        .modifiedAt(nowUtc)
                        .build());
                }
            }
        }
    }

    private final ConfigProtoService protoService;
    private final UserService userService;
    private final NursingRecordTemplateGroupRepository templateGroupRepo;
    private final NursingRecordTemplateRepository templateRepo;
    private final RbacDepartmentRepository deptRepo;
    private final LockExecutor lockExecutor;
}
