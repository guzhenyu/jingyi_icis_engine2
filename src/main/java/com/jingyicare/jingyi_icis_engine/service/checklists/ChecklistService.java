package com.jingyicare.jingyi_icis_engine.service.checklists;

import java.time.*;
import java.util.*;
import java.util.stream.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisChecklist.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.checklists.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.checklists.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.users.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class ChecklistService {
    public ChecklistService(
        @Autowired ConfigProtoService protoService,
        @Autowired UserService userService,
        @Autowired PatientService patientService,
        @Autowired DeptChecklistGroupRepository deptGroupRepo,
        @Autowired DeptChecklistItemRepository deptItemRepo,
        @Autowired PatientChecklistRecordRepository patientRecordRepo,
        @Autowired PatientChecklistGroupRepository patientGroupRepo,
        @Autowired PatientChecklistItemRepository patientItemRepo,
        @Autowired RbacDepartmentRepository deptRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.statusCodeMsgList = protoService.getConfig().getText().getStatusCodeMsgList();

        this.protoService = protoService;
        this.userService = userService;
        this.patientService = patientService;

        this.deptGroupRepo = deptGroupRepo;
        this.deptItemRepo = deptItemRepo;
        this.patientRecordRepo = patientRecordRepo;
        this.patientGroupRepo = patientGroupRepo;
        this.patientItemRepo = patientItemRepo;

        this.deptRepo = deptRepo;
    }

    @Transactional
    public GetDeptChecklistGroupsResp getDeptChecklistGroups(String getDeptChecklistGroupsReqJson) {
        final GetDeptChecklistGroupsReq req;
        try {
            GetDeptChecklistGroupsReq.Builder builder = GetDeptChecklistGroupsReq.newBuilder();
            JsonFormat.parser().merge(getDeptChecklistGroupsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetDeptChecklistGroupsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 查询组长质控分组
        List<DeptChecklistGroup> groupList = deptGroupRepo.findByDeptIdAndIsDeletedFalse(req.getDeptId())
            .stream()
            .sorted(Comparator.comparingInt(DeptChecklistGroup::getDisplayOrder)
                .thenComparing(DeptChecklistGroup::getGroupName)
            )
            .toList();

        List<Integer> groupIdList = groupList.stream()
            .map(DeptChecklistGroup::getId)
            .toList();

        // 查询组长质控项
        Map<Integer/*groupId*/, List<DeptChecklistItem>> itemMap = deptItemRepo
            .findByGroupIdInAndIsDeletedFalse(groupIdList)
            .stream()
            .collect(Collectors.groupingBy(DeptChecklistItem::getGroupId));

        // 组装组长质控分组
        List<DeptChecklistGroupPB> groupPbList = new ArrayList<>();
        for (DeptChecklistGroup group : groupList) {
            DeptChecklistGroupPB.Builder groupPbBuilder = ChecklistUtils.toProto(group).toBuilder();

            List<DeptChecklistItem> itemList = itemMap.getOrDefault(group.getId(), new ArrayList<>())
                .stream()
                .sorted(Comparator.comparingInt(DeptChecklistItem::getDisplayOrder)
                    .thenComparing(DeptChecklistItem::getItemName)
                )
                .toList();

            List<DeptChecklistItemPB> itemPbList = itemList.stream()
                .map(ChecklistUtils::toProto)
                .toList();

            groupPbBuilder.addAllItem(itemPbList);
            groupPbList.add(groupPbBuilder.build());
        }


        return GetDeptChecklistGroupsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllGroup(groupPbList)
            .build();
    }

    @Transactional
    public AddDeptChecklistGroupResp addDeptChecklistGroup(String addDeptChecklistGroupReqJson) {
        final AddDeptChecklistGroupReq req;
        try {
            AddDeptChecklistGroupReq.Builder builder = AddDeptChecklistGroupReq.newBuilder();
            JsonFormat.parser().merge(addDeptChecklistGroupReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return AddDeptChecklistGroupResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return AddDeptChecklistGroupResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 提取关键参数
        String deptId = req.getGroup().getDeptId();
        if (!isValidDeptId(deptId)) {
            return AddDeptChecklistGroupResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.DEPT_NOT_FOUND))
                .build();
        }
        String groupName = req.getGroup().getGroupName();

        // 检查用户权限
        Pair<String, Boolean> permPair = userService.hasPermission(deptId, Consts.PERM_ID_CONFIG_CHECKLIST);
        if (permPair == null || !permPair.getSecond()) {
            String primaryRoleName = permPair == null ? Consts.VOID_ROLE : permPair.getFirst();
            return AddDeptChecklistGroupResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PERMISSION_DENIED, primaryRoleName
                ))
                .build();
        }

        // 检查组名是否已存在
        List<DeptChecklistGroup> existingGroups = deptGroupRepo.findByDeptIdAndIsDeletedFalse(deptId);
        Boolean newGroupNameExists = false;
        Integer maxDisplayOrder = 0;
        for (DeptChecklistGroup group : existingGroups) {
            if (group.getGroupName().equals(groupName)) {
                newGroupNameExists = true;
            }
            if (group.getDisplayOrder() > maxDisplayOrder) {
                maxDisplayOrder = group.getDisplayOrder();
            }
        }
        if (newGroupNameExists) {
            return AddDeptChecklistGroupResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.DEPT_CHECKLIST_GROUP_NAME_DUPLICATE))
                .build();
        }

        // 创建组长质控分组
        DeptChecklistGroup newGroup = ChecklistUtils.toEntity(req.getGroup());
        newGroup.setDisplayOrder(maxDisplayOrder + 1);
        newGroup.setModifiedBy(accountId);
        newGroup.setModifiedAt(TimeUtils.getNowUtc());
        newGroup = deptGroupRepo.save(newGroup);

        return AddDeptChecklistGroupResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setId(newGroup.getId())
            .build();
    }

    @Transactional
    public GenericResp deleteDeptChecklistGroup(String deleteDeptChecklistGroupReqJson) {
        final DeleteDeptChecklistGroupReq req;
        try {
            DeleteDeptChecklistGroupReq.Builder builder = DeleteDeptChecklistGroupReq.newBuilder();
            JsonFormat.parser().merge(deleteDeptChecklistGroupReqJson, builder);
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

        // 提取关键参数
        Integer groupIdToDelete = req.getGroupId();

        // 检查组长质控分组是否存在
        DeptChecklistGroup groupToDelete = deptGroupRepo.findByIdAndIsDeletedFalse(groupIdToDelete)
            .orElse(null);
        if (groupToDelete == null) {
            return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
        }
        String deptId = groupToDelete.getDeptId();

        // 检查用户权限
        Pair<String, Boolean> permPair = userService.hasPermission(deptId, Consts.PERM_ID_CONFIG_CHECKLIST);
        if (permPair == null || !permPair.getSecond()) {
            String primaryRoleName = permPair == null ? Consts.VOID_ROLE : permPair.getFirst();
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PERMISSION_DENIED, primaryRoleName
                ))
                .build();
        }

        // 删除组长质控分组
        groupToDelete.setIsDeleted(true);
        groupToDelete.setModifiedBy(accountId);
        groupToDelete.setModifiedAt(TimeUtils.getNowUtc());
        deptGroupRepo.save(groupToDelete);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp reorderDeptChecklistGroups(String reorderDeptChecklistGroupsReqJson) {
        final ReorderDeptChecklistGroupsReq req;
        try {
            ReorderDeptChecklistGroupsReq.Builder builder = ReorderDeptChecklistGroupsReq.newBuilder();
            JsonFormat.parser().merge(reorderDeptChecklistGroupsReqJson, builder);
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

        // 提取关键参数
        String deptId = req.getDeptId();
        List<Integer> groupIdList = req.getGroupIdList();

        // 检查用户权限
        Pair<String, Boolean> permPair = userService.hasPermission(deptId, Consts.PERM_ID_CONFIG_CHECKLIST);
        if (permPair == null || !permPair.getSecond()) {
            String primaryRoleName = permPair == null ? Consts.VOID_ROLE : permPair.getFirst();
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PERMISSION_DENIED, primaryRoleName
                ))
                .build();
        }

        // 获取组长质控分组
        Map<Integer/*groupId*/, DeptChecklistGroup> groupMap = deptGroupRepo
            .findByDeptIdAndIsDeletedFalse(deptId)
            .stream()
            .collect(Collectors.toMap(DeptChecklistGroup::getId, group -> group));
        
        // 检查组长质控分组是否一致
        if (groupIdList.size() != groupMap.size()) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.DEPT_CHECKLIST_GROUP_COUNT_MISMATCH))
                .build();
        }

        for (Integer groupId : groupIdList) {
            if (!groupMap.containsKey(groupId)) {
                return GenericResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList,
                        StatusCode.DEPT_CHECKLIST_GROUP_NOT_EXIST,
                        String.valueOf(groupId)))
                    .build();
            }
        }

        // 更新组长质控分组的显示顺序
        int displayOrder = 1;
        LocalDateTime now = TimeUtils.getNowUtc();
        List<DeptChecklistGroup> updatedGroups = new ArrayList<>();
        for (Integer groupId : groupIdList) {
            DeptChecklistGroup group = groupMap.get(groupId);
            group.setDisplayOrder(displayOrder++);
            group.setModifiedBy(accountId);
            group.setModifiedAt(now);
            updatedGroups.add(group);
        }
        deptGroupRepo.saveAll(updatedGroups);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public AddDeptChecklistItemResp addDeptChecklistItem(String addDeptChecklistItemReqJson) {
        final AddDeptChecklistItemReq req;
        try {
            AddDeptChecklistItemReq.Builder builder = AddDeptChecklistItemReq.newBuilder();
            JsonFormat.parser().merge(addDeptChecklistItemReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return AddDeptChecklistItemResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return AddDeptChecklistItemResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 提取关键参数
        Integer deptChecklistGroupId = req.getGroupId();
        DeptChecklistItemPB itemPb = req.getItem();
        String itemName = itemPb.getItemName();

        // 检查组长质控分组是否存在
        DeptChecklistGroup group = deptGroupRepo.findByIdAndIsDeletedFalse(deptChecklistGroupId)
            .orElse(null);
        if (group == null) {
            return AddDeptChecklistItemResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.DEPT_CHECKLIST_GROUP_NOT_EXIST))
                .build();
        }
        String deptId = group.getDeptId();

        // 检查用户权限
        Pair<String, Boolean> permPair = userService.hasPermission(deptId, Consts.PERM_ID_CONFIG_CHECKLIST);
        if (permPair == null || !permPair.getSecond()) {
            String primaryRoleName = permPair == null ? Consts.VOID_ROLE : permPair.getFirst();
            return AddDeptChecklistItemResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PERMISSION_DENIED, primaryRoleName
                ))
                .build();
        }

        // 检查组长质控项是否存在
        List<DeptChecklistItem> existingItems = deptItemRepo.findByGroupIdAndIsDeletedFalse(deptChecklistGroupId);
        Boolean newItemNameExists = false;
        Integer maxDisplayOrder = 0;
        for (DeptChecklistItem item : existingItems) {
            if (item.getItemName().equals(itemName)) {
                newItemNameExists = true;
            }
            if (item.getDisplayOrder() > maxDisplayOrder) {
                maxDisplayOrder = item.getDisplayOrder();
            }
        }
        if (newItemNameExists) {
            return AddDeptChecklistItemResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.DEPT_CHECKLIST_ITEM_NAME_DUPLICATE))
                .build();
        }

        // 创建组长质控分组
        DeptChecklistItem newItem = ChecklistUtils.toEntity(itemPb);
        newItem.setGroupId(deptChecklistGroupId);
        newItem.setDisplayOrder(maxDisplayOrder + 1);
        newItem.setModifiedBy(accountId);
        newItem.setModifiedAt(TimeUtils.getNowUtc());
        newItem = deptItemRepo.save(newItem);

        return AddDeptChecklistItemResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setId(newItem.getId())
            .build();
    }

    @Transactional
    public GenericResp deleteDeptChecklistItem(String deleteDeptChecklistItemReqJson) {
        final DeleteDeptChecklistItemReq req;
        try {
            DeleteDeptChecklistItemReq.Builder builder = DeleteDeptChecklistItemReq.newBuilder();
            JsonFormat.parser().merge(deleteDeptChecklistItemReqJson, builder);
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

        // 提取关键参数
        Integer itemId = req.getItemId();

        // 检查组长质控项是否存在
        DeptChecklistItem itemToDelete = deptItemRepo.findByIdAndIsDeletedFalse(itemId)
            .orElse(null);
        if (itemToDelete == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                .build();
        }

        // 获取组长质控分组
        DeptChecklistGroup group = deptGroupRepo.findByIdAndIsDeletedFalse(itemToDelete.getGroupId())
            .orElse(null);
        if (group == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.DEPT_CHECKLIST_GROUP_NOT_EXIST))
                .build();
        }
        String deptId = group.getDeptId();

        // 检查用户权限
        Pair<String, Boolean> permPair = userService.hasPermission(deptId, Consts.PERM_ID_CONFIG_CHECKLIST);
        if (permPair == null || !permPair.getSecond()) {
            String primaryRoleName = permPair == null ? Consts.VOID_ROLE : permPair.getFirst();
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PERMISSION_DENIED, primaryRoleName
                ))
                .build();
        }

        // 删除组长质控项
        itemToDelete.setIsDeleted(true);
        itemToDelete.setModifiedBy(accountId);
        itemToDelete.setModifiedAt(TimeUtils.getNowUtc());
        deptItemRepo.save(itemToDelete);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp reorderDeptChecklistItems(String reorderDeptChecklistItemsReqJson) {
        final ReorderDeptChecklistItemsReq req;
        try {
            ReorderDeptChecklistItemsReq.Builder builder = ReorderDeptChecklistItemsReq.newBuilder();
            JsonFormat.parser().merge(reorderDeptChecklistItemsReqJson, builder);
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

        // 提取关键参数
        Integer deptChecklistGroupId = req.getGroupId();
        List<Integer> itemIdList = req.getItemIdList();

        // 获取组长质控分组
        DeptChecklistGroup group = deptGroupRepo.findByIdAndIsDeletedFalse(deptChecklistGroupId)
            .orElse(null);
        if (group == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.DEPT_CHECKLIST_GROUP_NOT_EXIST))
                .build();
        }
        String deptId = group.getDeptId();

        // 检查用户权限
        Pair<String, Boolean> permPair = userService.hasPermission(deptId, Consts.PERM_ID_CONFIG_CHECKLIST);
        if (permPair == null || !permPair.getSecond()) {
            String primaryRoleName = permPair == null ? Consts.VOID_ROLE : permPair.getFirst();
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PERMISSION_DENIED, primaryRoleName
                ))
                .build();
        }

        // 获取组长质控项
        Map<Integer/*itemId*/, DeptChecklistItem> itemMap = deptItemRepo
            .findByGroupIdAndIsDeletedFalse(deptChecklistGroupId)
            .stream()
            .collect(Collectors.toMap(DeptChecklistItem::getId, item -> item));

        // 检查组长质控项是否一致
        if (itemIdList.size() != itemMap.size()) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.DEPT_CHECKLIST_ITEM_COUNT_MISMATCH))
                .build();
        }

        for (Integer itemId : itemIdList) {
            if (!itemMap.containsKey(itemId)) {
                return GenericResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList,
                        StatusCode.DEPT_CHECKLIST_ITEM_NOT_EXIST,
                        String.valueOf(itemId)))
                    .build();
            }
        }

        // 更新组长质控项的显示顺序
        int displayOrder = 1;
        LocalDateTime now = TimeUtils.getNowUtc();
        List<DeptChecklistItem> updatedItems = new ArrayList<>();
        for (Integer itemId : itemIdList) {
            DeptChecklistItem item = itemMap.get(itemId);
            item.setDisplayOrder(displayOrder++);
            item.setModifiedBy(accountId);
            item.setModifiedAt(now);
            updatedItems.add(item);
        }
        deptItemRepo.saveAll(updatedItems);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public GetPatientChecklistRecordsResp getPatientChecklistRecords(String getPatientChecklistRecordsReqJson) {
        final GetPatientChecklistRecordsReq req;
        try {
            GetPatientChecklistRecordsReq.Builder builder = GetPatientChecklistRecordsReq.newBuilder();
            JsonFormat.parser().merge(getPatientChecklistRecordsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetPatientChecklistRecordsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 整体思路：
        // List<PatientChecklistRecord> recordList // 按照日期升序
        // 每一条记录，对应一组分组
        // Map<PatientChecklistRecord.id, List<PatientChecklistGroup>>
        // Map<Integer/*PatientChecklistGroup.groupId*/, DeptChecklistGroup>
        // 每个分组，对应一组项
        // Map<Integer/*PatientChecklistGroup.id*/, List<PatientChecklistItem>>
        // Map<Integer/*PatientChecklistItem.itemId*/, DeptChecklistItem>
        // 最终结果：List<PatientChecklistRecordPB>

        // 提取关键参数
        final Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetPatientChecklistRecordsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        LocalDateTime queryStart = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime queryEnd = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (queryStart == null || queryEnd == null) {
            log.error("Invalid query start time: {}, {}", req.getQueryStartIso8601(), req.getQueryEndIso8601());
            return GetPatientChecklistRecordsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList,
                    StatusCode.INVALID_TIME_FORMAT,
                    ("start: " + req.getQueryStartIso8601() + ", end: " + req.getQueryEndIso8601())
                ))
                .build();
        }

        // 获取病人组长质控记录
        List<PatientChecklistRecord> recordList = patientRecordRepo
            .findByPidAndEffectiveTimeBetweenAndIsDeletedFalse(pid, queryStart, queryEnd)
            .stream()
            .sorted(Comparator.comparing(PatientChecklistRecord::getEffectiveTime))
            .toList();

        // 获取病人组长质控分组
        List<Integer> recordIdList = recordList.stream()
            .map(PatientChecklistRecord::getId)
            .toList();
        List<PatientChecklistGroup> patientGroupList = patientGroupRepo
            .findByRecordIdInAndIsDeletedFalse(recordIdList);
        Map<Integer/*recordId*/, List<PatientChecklistGroup>> patientGroupMap = patientGroupList
            .stream()
            .collect(Collectors.groupingBy(PatientChecklistGroup::getRecordId));  // **
        List<Integer> deptGroupIdList = patientGroupList.stream()
            .map(PatientChecklistGroup::getGroupId)
            .distinct()
            .toList();
        Map<Integer/*deptGroupId*/, DeptChecklistGroup> deptGroupMap = deptGroupRepo
            .findByIdIn(deptGroupIdList)
            .stream()
            .collect(Collectors.toMap(DeptChecklistGroup::getId, group -> group));  // **

        // 获取病人组长质控项
        List<Long> patientGroupIdList = patientGroupList.stream()
            .map(PatientChecklistGroup::getId)
            .toList();
        List<PatientChecklistItem> patientItemList = patientItemRepo
            .findByGroupIdInAndIsDeletedFalse(patientGroupIdList);
        Map<Long/*patientGroupId*/, List<PatientChecklistItem>> patientItemMap = patientItemList
            .stream()
            .collect(Collectors.groupingBy(PatientChecklistItem::getGroupId));  // **
        List<Integer> deptItemIdList = patientItemList.stream()
            .map(PatientChecklistItem::getItemId)
            .distinct()
            .toList();
        Map<Integer/*deptItemId*/, DeptChecklistItem> deptItemMap = deptItemRepo
            .findByIdIn(deptItemIdList)
            .stream()
            .collect(Collectors.toMap(DeptChecklistItem::getId, item -> item));  // **

        // 组装病人组长质控记录
        List<PatientChecklistRecordPB> recordPbList = new ArrayList<>();
        for (PatientChecklistRecord record : recordList) {
            Integer recordId = record.getId();
            PatientChecklistRecordPB.Builder recordPbBuilder = ChecklistUtils.toProto(record, ZONE_ID).toBuilder();
            String createdByAccountName = userService.getNameByAutoId(record.getCreatedBy());
            if (createdByAccountName != null) recordPbBuilder.setCreatedByAccountName(createdByAccountName);

            List<PatientChecklistGroupPB> groupPbList = new ArrayList<>();
            for (PatientChecklistGroup group : patientGroupMap.getOrDefault(recordId, new ArrayList<>())) {
                // 组装病人组长质控分组
                Long patientGroupId = group.getId();
                Integer deptGroupId = group.getGroupId();
                DeptChecklistGroup deptGroup = deptGroupMap.get(deptGroupId);
                if (deptGroup == null) {
                    log.error("DeptChecklistGroup not found for id: {}", deptGroupId);
                    continue;
                }
                PatientChecklistGroupPB.Builder groupPbBuilder = ChecklistUtils
                    .toProto(group, deptGroup).toBuilder();

                // 获取组长质控项
                List<PatientChecklistItemPB> itemPbList = new ArrayList<>();
                for (PatientChecklistItem item : patientItemMap.getOrDefault(patientGroupId, new ArrayList<>())) {
                    // 组装病人组长质控项
                    Integer itemId = item.getItemId();
                    DeptChecklistItem deptItem = deptItemMap.get(itemId);
                    if (deptItem == null) {
                        log.error("DeptChecklistItem not found for id: {}", itemId);
                        continue;
                    }
                    itemPbList.add(ChecklistUtils.toProto(item, deptItem));
                }

                itemPbList.sort(Comparator.comparing(PatientChecklistItemPB::getDisplayOrder)
                    .thenComparing(PatientChecklistItemPB::getItemName));
                groupPbBuilder.addAllItem(itemPbList);
                groupPbBuilder.setTotalCount(itemPbList.size());
                Integer checkedCount = 0;
                for (PatientChecklistItemPB itemPb : itemPbList) {
                    if (itemPb.getIsChecked()) {
                        checkedCount++;
                    }
                }
                groupPbBuilder.setCheckedCount(checkedCount);
                groupPbList.add(groupPbBuilder.build());
            }

            groupPbList.sort(Comparator.comparing(PatientChecklistGroupPB::getDisplayOrder)
                .thenComparing(PatientChecklistGroupPB::getGroupName));
            recordPbBuilder.addAllGroup(groupPbList);
            recordPbList.add(recordPbBuilder.build());
        }

        return GetPatientChecklistRecordsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllRecord(recordPbList)
            .build();
    }

    @Transactional
    public AddPatientChecklistRecordResp addPatientChecklistRecord(String addPatientChecklistRecordReqJson) {
        final AddPatientChecklistRecordReq req;
        try {
            AddPatientChecklistRecordReq.Builder builder = AddPatientChecklistRecordReq.newBuilder();
            JsonFormat.parser().merge(addPatientChecklistRecordReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return AddPatientChecklistRecordResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return AddPatientChecklistRecordResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 提取关键参数
        final PatientChecklistRecordPB recordPb = req.getRecord();
        final LocalDateTime effectiveTime = TimeUtils.fromIso8601String(
            recordPb.getEffectiveTimeIso8601(), "UTC");
        if (effectiveTime == null) {
            log.error("Invalid effective time: {}", recordPb.getEffectiveTimeIso8601());
            return AddPatientChecklistRecordResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList,
                    StatusCode.INVALID_TIME_FORMAT,
                    recordPb.getEffectiveTimeIso8601()))
                .build();
        }
        final Long pid = recordPb.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return AddPatientChecklistRecordResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();

        // 检查病人组长质控记录是否已存在
        PatientChecklistRecord existingRecord = patientRecordRepo
            .findByPidAndEffectiveTimeAndIsDeletedFalse(pid, effectiveTime)
            .orElse(null);
        if (existingRecord != null) {
            log.error("PatientChecklistRecord already exists for pid: {}, effectiveTime: {}", pid, effectiveTime);
            return AddPatientChecklistRecordResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PATIENT_CHECKLIST_RECORD_EXISTS))
                .build();
        }

        // 新建病人组长质控记录
        PatientChecklistRecord record = ChecklistUtils.toEntity(recordPb, deptId);
        record.setCreatedBy(accountId);
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        record.setCreatedAt(nowUtc);
        record.setModifiedBy(accountId);
        record.setModifiedAt(nowUtc);
        record = patientRecordRepo.save(record);

        // 新增病人组长质控分组和项
        List<DeptChecklistGroup> deptGroupList = deptGroupRepo.findByDeptIdAndIsDeletedFalse(deptId);
        List<Integer> deptGroupIdList = deptGroupList.stream().map(DeptChecklistGroup::getId).toList();
        List<DeptChecklistItem> deptItemList = deptItemRepo
            .findByGroupIdInAndIsDeletedFalse(deptGroupIdList);
        Map<Integer/*deptGroupId*/, List<DeptChecklistItem>> deptItemMap = deptItemList
            .stream()
            .collect(Collectors.groupingBy(DeptChecklistItem::getGroupId));  // **

        List<PatientChecklistItem> patientItemList = new ArrayList<>();
        for (DeptChecklistGroup deptGroup : deptGroupList) {
            // 新增病人组长质控项
            PatientChecklistGroup patientGroup = ChecklistUtils.toEntity(
                0L /*id自动生成*/, record.getId(), deptGroup.getId(), deptGroup.getDisplayOrder()
            );
            patientGroup.setModifiedBy(accountId);
            patientGroup.setModifiedAt(nowUtc);
            patientGroup = patientGroupRepo.save(patientGroup);

            List<DeptChecklistItem> itemList = deptItemMap.getOrDefault(deptGroup.getId(), new ArrayList<>());
            for (DeptChecklistItem deptItem : itemList) {
                // 新增病人组长质控项
                PatientChecklistItem patientItem = ChecklistUtils.toEntity(
                    0L /*id自动生成*/, patientGroup.getId(), deptItem.getId(), false/*checked*/,
                     deptItem.getDefaultNote(), deptItem.getDisplayOrder()
                );
                patientItem.setModifiedBy(accountId);
                patientItem.setModifiedAt(nowUtc);
                patientItemList.add(patientItem);
            }
        }
        // 批量保存病人组长质控项
        patientItemRepo.saveAll(patientItemList);

        return AddPatientChecklistRecordResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setId(record.getId())
            .build();
    }

    @Transactional
    public GenericResp deletePatientChecklistRecord(String deletePatientChecklistRecordReqJson) {
        final DeletePatientChecklistRecordReq req;
        try {
            DeletePatientChecklistRecordReq.Builder builder = DeletePatientChecklistRecordReq.newBuilder();
            JsonFormat.parser().merge(deletePatientChecklistRecordReqJson, builder);
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

        // 提取关键参数
        final Integer recordIdToDel = req.getId();

        // 检查病人组长质控记录是否存在
        PatientChecklistRecord recordToDel = patientRecordRepo.findByIdAndIsDeletedFalse(recordIdToDel)
            .orElse(null);
        if (recordToDel == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                .build();
        }

        // 删除病人组长质控记录
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        recordToDel.setIsDeleted(true);
        recordToDel.setDeletedBy(accountId);
        recordToDel.setDeletedAt(nowUtc);
        patientRecordRepo.save(recordToDel);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp updatePatientChecklistItem(String updatePatientChecklistItemReqJson) {
        final UpdatePatientChecklistItemReq req;
        try {
            UpdatePatientChecklistItemReq.Builder builder = UpdatePatientChecklistItemReq.newBuilder();
            JsonFormat.parser().merge(updatePatientChecklistItemReqJson, builder);
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

        // 提取关键参数
        final PatientChecklistItemPB patientItemPb = req.getItem();
        final Long patientItemId = patientItemPb.getId();

        // 检查病人组长质控项是否存在
        PatientChecklistItem patientItem = patientItemRepo.findByIdAndIsDeletedFalse(patientItemId)
            .orElse(null);
        if (patientItem == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PATIENT_CHECKLIST_ITEM_NOT_EXIST))
                .build();
        }
        DeptChecklistItem deptItem = deptItemRepo.findByIdAndIsDeletedFalse(patientItem.getItemId())
            .orElse(null);
        if (deptItem == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.DEPT_CHECKLIST_ITEM_NOT_EXIST))
                .build();
        }

        // 更新病人组长质控项
        patientItem.setIsChecked(patientItemPb.getIsChecked());
        if (deptItem.getHasNote()) {
            patientItem.setNote(patientItemPb.getNote());
        }
        patientItem.setModifiedBy(accountId);
        patientItem.setModifiedAt(TimeUtils.getNowUtc());
        patientItem = patientItemRepo.save(patientItem);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    private Boolean isValidDeptId(String deptId) {
        if (StrUtils.isBlank(deptId)) return false;

        RbacDepartment dept = deptRepo.findByDeptId(deptId).orElse(null);
        if (dept == null) return false;
        return true;
    }

    private final String ZONE_ID;
    private final List<String> statusCodeMsgList;

    private final ConfigProtoService protoService;
    private final UserService userService;
    private final PatientService patientService;

    private final DeptChecklistGroupRepository deptGroupRepo;
    private final DeptChecklistItemRepository deptItemRepo;
    private final PatientChecklistRecordRepository patientRecordRepo;
    private final PatientChecklistGroupRepository patientGroupRepo;
    private final PatientChecklistItemRepository patientItemRepo;

    private final RbacDepartmentRepository deptRepo;
}