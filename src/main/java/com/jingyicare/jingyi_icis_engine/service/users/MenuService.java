package com.jingyicare.jingyi_icis_engine.service.users;

import java.time.*;
import java.util.*;
import java.util.stream.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.reports.*;
import com.jingyicare.jingyi_icis_engine.entity.settings.*;
import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.reports.*;
import com.jingyicare.jingyi_icis_engine.repository.settings.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class MenuService {
    public MenuService(
        @Autowired ConfigProtoService protoService,
        @Autowired DeptSystemSettingsRepository deptSettingsRepo,
        @Autowired DragableFormTemplateRepository dragableFormTemplateRepo,
        @Autowired AccountRepository accountRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.ADMIN_ACCOUNT_ID = protoService.getConfig().getUser().getAdminAccountId();
        this.GET_DEPT_MENU_SETTINGS = SystemSettingFunctionId.GET_DEPT_MENU_SETTINGS.ordinal();
        this.TEMPLATE_MENU_ITEM_START_ID = 10000;

        this.fullMenu = protoService.getConfig().getUser().getMenu();
        this.defaultMenuConfig = getDefaultMenuConfig();

        this.protoService = protoService;
        this.deptSettingsRepo = deptSettingsRepo;
        this.dragableFormTemplateRepo = dragableFormTemplateRepo;
        this.accountRepo = accountRepo;
    }

    @Transactional
    public GetDeptMenuConfigResp getDeptMenuConfig(String reqJson) {
        final GetDeptMenuConfigReq req;
        try {
            GetDeptMenuConfigReq.Builder builder = GetDeptMenuConfigReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to parse GetDeptMenuConfigReq: ", e);
            return GetDeptMenuConfigResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        MenuConfigPB config = getNormalizedMenuConfig(req.getDeptId());
        List<MenuGroup> menuGroups = getMenuGroups(config);
        List<MenuItem> unassignedMenuItems = new ArrayList<>();
        for (MenuItem item : config.getMenuItemList()) {
            if (item.getType() == 0 && item.getGroupId() < 0) { // 仅添加未分配的菜单项
                unassignedMenuItems.add(item);
            }
        }
        return GetDeptMenuConfigResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllMenuGroup(menuGroups)
            .addAllUnassignedMenuItem(unassignedMenuItems)
            .addAllUnassignedFormTemplate(config.getUnassignedFormTemplateList())
            .build();
    }

    @Transactional
    public GetMenuItemResp getMenuItem(String reqJson) {
        final GetMenuItemReq req;
        try {
            GetMenuItemReq.Builder builder = GetMenuItemReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to parse GetMenuItemReq: ", e);
            return GetMenuItemResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String deptId = req.getDeptId();
        int functionId = req.getFunctionId();

        MenuConfigPB menuConfigPb = getNormalizedMenuConfig(deptId);
        MenuItem foundItem = null;
        for (MenuItem item : menuConfigPb.getMenuItemList()) {
            if (item.getFunctionId() == functionId) {
                foundItem = item;
                break;
            }
        }
        if (foundItem == null) {
            log.error("Menu item with function_id: {} not found in deptId: {}", functionId, deptId);
            return GetMenuItemResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.MENU_ITEM_NOT_FOUND))
                .build();
        }

        List<JfkTemplatePB> templateList = new ArrayList<>();
        if (foundItem.getType() == 1) {
            // 单表单
            int templateId = foundItem.getSeparatedFormTemplate().getKey();
            if (templateId > 0) {
                DragableFormTemplate template = dragableFormTemplateRepo
                    .findByIdAndIsDeletedFalse(templateId).orElse(null);
                if (template != null) {
                    JfkTemplatePB templatePb = ProtoUtils.decodeJfkTemplate(template.getTemplatePb());
                    templateList.add(templatePb);
                }
            }
            if (templateList.isEmpty()) {
                log.error("No separated form template found for function_id: {} in deptId: {}", functionId, deptId);
                return GetMenuItemResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.FORM_TEMPLATE_NOT_FOUND))
                    .build();
            }
        } else if (foundItem.getType() == 2) {
            // 表单组
            List<Integer> templateIds = foundItem.getAggregatedFormTemplateList().stream()
                .map(IntStrKvPB::getKey)
                .collect(Collectors.toList());
            if (templateIds.isEmpty()) {
                return GetMenuItemResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.OK))
                    .setMenuItem(foundItem)
                    .build();
            }

            // 获取所有表单模板
            List<DragableFormTemplate> templates = dragableFormTemplateRepo
                .findByIdInAndIsDeletedFalse(templateIds);
            for (DragableFormTemplate template : templates) {
                JfkTemplatePB templatePb = ProtoUtils.decodeJfkTemplate(template.getTemplatePb());
                templateList.add(templatePb);
            }
            if (templateList.isEmpty()) {
                log.error("No aggregated form templates found for function_id: {} in deptId: {}", functionId, deptId);
                return GetMenuItemResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.FORM_TEMPLATE_NOT_FOUND))
                    .build();
            }
        }

        return GetMenuItemResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setMenuItem(foundItem)
            .addAllTemplate(templateList)
            .build();
    }

    @Transactional
    public GenericResp addMenuItem(String reqJson) {
        final AddMenuItemReq req;
        try {
            AddMenuItemReq.Builder builder = AddMenuItemReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to parse AddMenuItemReq: ", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String deptId = req.getDeptId();
        MenuConfigPB menuConfigPb = getNormalizedMenuConfig(deptId);
        // 检查group_id是否存在
        Set<Integer> leafGroupIds = new HashSet<>();
        for (MenuGroup group : menuConfigPb.getMenuGroupList()) {
            leafGroupIds.addAll(getLeafGroupIds(group));
        }
        final Integer groupId = req.getMenuItem().getGroupId();
        if (!leafGroupIds.contains(groupId)) {
            log.error("Invalid group_id: {} for deptId: {}", groupId, deptId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_MENU_GROUP_ID))
                .build();
        }

        // 检查name的合法性
        Set<String> existingNames = new HashSet<>();
        for (MenuItem item : menuConfigPb.getMenuItemList()) {
            if (item.getGroupId() == groupId) {
                existingNames.add(item.getName());
            }
        }
        String newName = req.getMenuItem().getName();
        if (existingNames.contains(newName)) {
            log.error("Menu item name already exists: {} in group_id: {}", newName, groupId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.MENU_ITEM_NAME_EXISTS))
                .build();
        }

        // 获取下一个表单function_id
        int nextTempFunctionId = TEMPLATE_MENU_ITEM_START_ID;
        for (MenuItem item : menuConfigPb.getMenuItemList()) {
            if (item.getFunctionId() >= nextTempFunctionId) {
                nextTempFunctionId = item.getFunctionId() + 1;
            }
        }

        // 获取所有合法的表单模板ID
        Set<Integer> formTempIds = menuConfigPb.getUnassignedFormTemplateList().stream()
            .map(IntStrKvPB::getKey)
            .collect(Collectors.toSet());

        MenuItem newItem = req.getMenuItem();
        Integer itemType = newItem.getType();
        if (itemType <= 0) {
            final Integer functionId = newItem.getFunctionId();
            List<MenuItem> unchangedItems = new ArrayList<>();
            boolean foundItem = false;
            for (MenuItem item : menuConfigPb.getMenuItemList()) {
                if (item.getFunctionId() == functionId) {
                    foundItem = true;
                    continue; // 跳过已存在的菜单项
                }
                unchangedItems.add(item); // 保留其他未变更的菜单项
            }
            if (!foundItem) {
                log.error("Menu item with function_id: {} not found in deptId: {}", functionId, deptId);
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.MENU_ITEM_NOT_FOUND))
                    .build();
            }
            // 添加新的菜单项
            menuConfigPb = menuConfigPb.toBuilder()
                .clearMenuItem()
                .addAllMenuItem(unchangedItems)
                .addMenuItem(newItem)
                .build();
        } else if (itemType == 1) {
            if (!formTempIds.contains(newItem.getSeparatedFormTemplate().getKey())) {
                log.error("Invalid separated_form_template key: {} for deptId: {}", newItem.getSeparatedFormTemplate().getKey(), req.getDeptId());
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.INVALID_FORM_TEMPLATE_ID))
                    .build();
            }
            if (newItem.getAggregatedFormTemplateCount() > 0) {
                log.error("Aggregated form templates are not allowed for single form items in deptId: {}", deptId);
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.INVALID_MENU_ITEM_TYPE))
                    .build();
            }
            // 添加新的单个表单菜单项
            menuConfigPb = menuConfigPb.toBuilder()
                .addMenuItem(newItem.toBuilder()
                    .setFunctionId(nextTempFunctionId) // 设置新的function_id
                    .build()
                )
                .build();
        } else {  // itemType == 2
            if (newItem.getSeparatedFormTemplate().getKey() > 0) {
                log.error("Separated form template is not allowed for aggregated form items in deptId: {}", deptId);
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.INVALID_MENU_ITEM_TYPE))
                    .build();
            }
            for (IntStrKvPB formTemplate : newItem.getAggregatedFormTemplateList()) {
                if (!formTempIds.contains(formTemplate.getKey())) {
                    log.error("Invalid aggregated form template key: {} for deptId: {}", formTemplate.getKey(), deptId);
                    return GenericResp.newBuilder()
                        .setRt(protoService.getReturnCode(StatusCode.INVALID_FORM_TEMPLATE_ID))
                        .build();
                }
                formTempIds.remove(formTemplate.getKey()); // 从未分配的表单中移除已使用的模板
            }
            // 添加新的表单组菜单项
            menuConfigPb = menuConfigPb.toBuilder()
                .addMenuItem(newItem.toBuilder()
                    .setFunctionId(nextTempFunctionId) // 设置新的function_id
                    .build()
                )
                .build();
        }
        // 更新数据库中的菜单配置
        menuConfigPb = menuConfigPb.toBuilder()
            .clearUnassignedFormTemplate()
            .build();
        toDb(deptId, menuConfigPb);
    
        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp updateMenuItemName(String reqJson) {
        final UpdateMenuItemNameReq req;
        try {
            UpdateMenuItemNameReq.Builder builder = UpdateMenuItemNameReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to parse UpdateMenuItemNameReq: ", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String deptId = req.getDeptId();
        final Integer functionId = req.getFunctionId();
        final String newName = req.getNewName();

        return updateMenuItem(deptId, functionId, newName, null);
    }

    @Transactional
    public GenericResp updateMenuItemWithOneForm(String reqJson) {
        final UpdateMenuItemWithOneFormReq req;
        try {
            UpdateMenuItemWithOneFormReq.Builder builder = UpdateMenuItemWithOneFormReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to parse UpdateMenuItemWithOneFormReq: ", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String deptId = req.getDeptId();
        final Integer functionId = req.getFunctionId();
        final Integer formTemplateId = req.getFormTemplateId();

        return updateMenuItem(deptId, functionId, null, formTemplateId);
    }

    private GenericResp updateMenuItem(String deptId, Integer functionId, String name, Integer separatedFormTemplateId) {
        MenuConfigPB menuConfigPb = getNormalizedMenuConfig(deptId);
        // 找到对应的group_id
        Integer groupId = null;
        for (MenuItem item : menuConfigPb.getMenuItemList()) {
            if (item.getFunctionId() == functionId) {
                groupId = item.getGroupId();
                break;
            }
        }
        if (groupId == null) {
            log.error("Menu item with function_id: {} not found in deptId: {}", functionId, deptId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.MENU_ITEM_NOT_FOUND))
                .build();
        }
        if (groupId < 0) {
            log.error("Menu item with function_id: {} is not assigned to a valid group_id in deptId: {}", functionId, deptId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.CANNOT_RENAME_UNASSIGNED_MENU_ITEM))
                .build();
        }

        // 更新
        List<MenuItem> updatedItems = new ArrayList<>();
        for (MenuItem item : menuConfigPb.getMenuItemList()) {
            if (item.getFunctionId() == functionId) {
                MenuItem.Builder builder = item.toBuilder();
                if (name != null) builder.setName(name);
                if (separatedFormTemplateId != null && item.getType() == 1) {
                    List<IntStrKvPB> formTemplates = getDeptFormTemplates(deptId);
                    IntStrKvPB formTemplate = formTemplates.stream()
                        .filter(template -> template.getKey() == separatedFormTemplateId)
                        .findFirst()
                        .orElse(null);
                    if (formTemplate == null) {
                        log.error("Form template with id: {} not found for deptId: {}", separatedFormTemplateId, deptId);
                        return GenericResp.newBuilder()
                            .setRt(protoService.getReturnCode(StatusCode.FORM_TEMPLATE_NOT_FOUND))
                            .build();
                    }
                    builder.setSeparatedFormTemplate(formTemplate);
                }
                updatedItems.add(builder.build());
            } else {
                if (item.getGroupId() == groupId && name != null && item.getName().equals(name)) {
                    log.error("Menu item name already exists: {} in group_id: {}", name, groupId);
                    return GenericResp.newBuilder()
                        .setRt(protoService.getReturnCode(StatusCode.MENU_ITEM_NAME_EXISTS))
                        .build();
                }
                updatedItems.add(item); // 保留其他未变更的菜单项
            }
        }
        menuConfigPb = menuConfigPb.toBuilder()
            .clearMenuItem()
            .addAllMenuItem(updatedItems)
            .clearUnassignedFormTemplate()
            .build();
        toDb(deptId, menuConfigPb);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteMenuItem(String reqJson) {
        final DeleteMenuItemReq req;
        try {
            DeleteMenuItemReq.Builder builder = DeleteMenuItemReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to parse DeleteMenuItemReq: ", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String deptId = req.getDeptId();
        final Integer functionId = req.getFunctionId();

        MenuConfigPB menuConfigPb = getNormalizedMenuConfig(deptId);
        List<MenuItem> updatedItems = new ArrayList<>();
        for (MenuItem item : menuConfigPb.getMenuItemList()) {
            if (item.getFunctionId() == functionId) {
                updatedItems.add(item.toBuilder()
                    .setGroupId(-1) // 设置为未分配状态
                    .build()
                );
            } else {
                updatedItems.add(item); // 保留其他未变更的菜单项
            }
        }
        menuConfigPb = menuConfigPb.toBuilder()
            .clearMenuItem()
            .addAllMenuItem(updatedItems)
            .clearUnassignedFormTemplate()
            .build();
        toDb(deptId, menuConfigPb);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp reorderMenuItems(String reqJson) {
        final ReorderMenuItemsReq req;
        try {
            ReorderMenuItemsReq.Builder builder = ReorderMenuItemsReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to parse ReorderMenuItemsReq: ", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 验证请求参数
        final String deptId = req.getDeptId();
        MenuConfigPB menuConfigPb = getNormalizedMenuConfig(deptId);
        Map<Integer, MenuItem> itemMap = menuConfigPb.getMenuItemList().stream()
            .collect(Collectors.toMap(MenuItem::getFunctionId, item -> item));
        Map<Integer, MenuItem> reorderedItemMap = new HashMap<>();
        List<MenuItem> reorderedItems = new ArrayList<>();
        for (Integer functionId : req.getFunctionIdList()) {
            MenuItem item = itemMap.get(functionId);
            if (item == null) continue; // 跳过不存在的菜单项
            reorderedItemMap.put(functionId, item);
            reorderedItems.add(item);
        }

        // 重新排序，更新数据库
        List<MenuItem> updatedItems = new ArrayList<>();
        for (MenuItem item : menuConfigPb.getMenuItemList()) {
            Integer functionId = item.getFunctionId();
            if (!reorderedItemMap.containsKey(functionId)) {
                updatedItems.add(item); // 保留未在请求中指定的菜单项
            }
        }
        updatedItems.addAll(reorderedItems); // 添加重新排序的菜单项
        menuConfigPb = menuConfigPb.toBuilder()
            .clearMenuItem()
            .addAllMenuItem(updatedItems)
            .clearUnassignedFormTemplate()
            .build();
        toDb(deptId, menuConfigPb);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp addMenuItemFormTemplate(String reqJson) {
        final AddMenuItemFormTemplateReq req;
        try {
            AddMenuItemFormTemplateReq.Builder builder = AddMenuItemFormTemplateReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to parse AddMenuItemFormTemplateReq: ", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String deptId = req.getDeptId();
        final Integer functionId = req.getFunctionId();
        List<Integer> templateIds = req.getFormTemplateIdList();

        // 验证模板信息
        Map<Integer, DragableFormTemplate> templateMap = dragableFormTemplateRepo
            .findByIdInAndIsDeletedFalse(templateIds).stream()
            .collect(Collectors.toMap(DragableFormTemplate::getId, template -> template));
        for (Integer templateId : templateIds) {
            DragableFormTemplate template = templateMap.get(templateId);
            if (template == null) {
                log.error("Form template with id: {} not found for deptId: {}", templateId, deptId);
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.FORM_TEMPLATE_NOT_FOUND))
                    .build();
            }
            if (template.getDeptId() == null || !template.getDeptId().equals(deptId)) {
                log.error("Form template with id: {} does not belong to deptId: {}", templateId, deptId);
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.FORM_TEMPLATE_NOT_BELONG_TO_DEPT))
                    .build();
            }
        }

        // 获取配置
        MenuConfigPB menuConfigPb = getNormalizedMenuConfig(deptId);
        Set<Integer> allUnassignedTemplateIds = menuConfigPb.getUnassignedFormTemplateList().stream()
            .map(IntStrKvPB::getKey)
            .collect(Collectors.toSet());
        boolean templateFound = false;
        for (Integer templateId : templateIds) {
            if (!allUnassignedTemplateIds.contains(templateId)) {
                log.error("Form template with id: {} is not available for assignment in deptId: {}", templateId, deptId);
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.FORM_TEMPLATE_NOT_AVAILABLE_FOR_ASSIGNMENT))
                    .build();
            }
        }

        // 检查菜单项类型合法性，更新配置
        List<MenuItem> updatedItems = new ArrayList<>();
        boolean functionFound = false;
        for (MenuItem item : menuConfigPb.getMenuItemList()) {
            if (item.getFunctionId() == functionId) {
                if (item.getType() != 2) {
                    log.error("Menu item with function_id: {} is not a form group in deptId: {}", functionId, deptId);
                    return GenericResp.newBuilder()
                        .setRt(protoService.getReturnCode(StatusCode.NOT_AN_AGGREGATED_FORM_MENU_ITEM))
                        .build();
                }
                List<IntStrKvPB> templatesToAdd = new ArrayList<>();
                for (Integer templateId : templateIds) {
                    DragableFormTemplate template = templateMap.get(templateId);
                    if (template == null) continue; // 跳过不存在的模板
                    templatesToAdd.add(IntStrKvPB.newBuilder()
                        .setKey(templateId)
                        .setVal(template.getName())
                        .build());
                }
                updatedItems.add(item.toBuilder()
                    .addAllAggregatedFormTemplate(templatesToAdd)
                    .build());
                functionFound = true;
            } else {
                updatedItems.add(item); // 保留其他未变更的菜单项
            }
        }
        if (!functionFound) {
            log.error("Menu item with function_id: {} not found in deptId: {}", functionId, deptId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.MENU_ITEM_NOT_FOUND))
                .build();
        }
        menuConfigPb = menuConfigPb.toBuilder()
            .clearMenuItem()
            .addAllMenuItem(updatedItems)
            .clearUnassignedFormTemplate()
            .build();
        toDb(deptId, menuConfigPb);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteMenuItemFormTemplate(String reqJson) {
        final DeleteMenuItemFormTemplateReq req;
        try {
            DeleteMenuItemFormTemplateReq.Builder builder = DeleteMenuItemFormTemplateReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to parse DeleteMenuItemFormTemplateReq: ", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String deptId = req.getDeptId();
        final Integer templateId = req.getFormTemplateId();

        // 验证模板信息
        DragableFormTemplate template = dragableFormTemplateRepo.findByIdAndIsDeletedFalse(templateId).orElse(null);
        if (template == null) {
            log.error("Form template with id: {} not found for deptId: {}", templateId, deptId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.FORM_TEMPLATE_NOT_FOUND))
                .build();
        }
        if (template.getDeptId() == null || !template.getDeptId().equals(deptId)) {
            log.error("Form template with id: {} does not belong to deptId: {}", templateId, deptId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.FORM_TEMPLATE_NOT_BELONG_TO_DEPT))
                .build();
        }

        // 获取配置
        MenuConfigPB menuConfigPb = getNormalizedMenuConfig(deptId);
        boolean templateFound = false;
        List<MenuItem> updatedItems = new ArrayList<>();
        for (MenuItem item : menuConfigPb.getMenuItemList()) {
            if (item.getType() == 2) {
                // 仅处理表单组类型的菜单项
                List<IntStrKvPB> updatedTemplates = new ArrayList<>();
                boolean foundTemplate = false;
                for (IntStrKvPB formTemplate : item.getAggregatedFormTemplateList()) {
                    if (formTemplate.getKey() == templateId) {
                        foundTemplate = true; // 找到要删除的模板
                    } else {
                        updatedTemplates.add(formTemplate); // 保留其他模板
                    }
                }
                if (foundTemplate) {
                    templateFound = true;
                    updatedItems.add(item.toBuilder()
                        .clearAggregatedFormTemplate()
                        .addAllAggregatedFormTemplate(updatedTemplates)
                        .build());
                } else {
                    updatedItems.add(item); // 保留未变更的菜单项
                }
            } else {
                updatedItems.add(item); // 保留非表单组类型的菜单项
            }
        }

        if (templateFound) {
            // 更新菜单配置
            menuConfigPb = menuConfigPb.toBuilder()
                .clearMenuItem()
                .addAllMenuItem(updatedItems)
                .clearUnassignedFormTemplate()
                .build();
            toDb(deptId, menuConfigPb);
        }

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp reorderMenuItemFormTemplates(String reqJson) {
        final ReorderMenuItemFormTemplatesReq req;
        try {
            ReorderMenuItemFormTemplatesReq.Builder builder = ReorderMenuItemFormTemplatesReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to parse ReorderMenuItemFormTemplatesReq: ", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String deptId = req.getDeptId();
        final Integer functionId = req.getFunctionId();
        final List<Integer> reorderedTemplateIds = req.getFormTemplateIdList();
        // 获取配置
        MenuConfigPB menuConfigPb = getNormalizedMenuConfig(deptId);
        boolean functionFound = false;
        List<MenuItem> updatedItems = new ArrayList<>();
        for (MenuItem item : menuConfigPb.getMenuItemList()) {
            if (item.getFunctionId() == functionId) {
                functionFound = true;
                if (item.getType() != 2) {
                    log.error("Menu item with function_id: {} is not a form group in deptId: {}", functionId, deptId);
                    return GenericResp.newBuilder()
                        .setRt(protoService.getReturnCode(StatusCode.NOT_AN_AGGREGATED_FORM_MENU_ITEM))
                        .build();
                }

                // 重新排序表单模板
                List<IntStrKvPB> reorderedTemplates = new ArrayList<>();
                Map<Integer, IntStrKvPB> templateMap = item.getAggregatedFormTemplateList().stream()
                    .collect(Collectors.toMap(IntStrKvPB::getKey, template -> template));
                for (Integer templateId : reorderedTemplateIds) {
                    IntStrKvPB template = templateMap.get(templateId);
                    if (template != null) {
                        reorderedTemplates.add(template); // 仅添加存在的模板
                        templateMap.remove(templateId); // 从map中移除已添加的模板
                    } else {
                        log.error("Form template with id: {} not found in menu item with function_id: {}", templateId, functionId);
                        return GenericResp.newBuilder()
                            .setRt(protoService.getReturnCode(StatusCode.FORM_TEMPLATE_NOT_FOUND_IN_MENU_ITEM))
                            .build();
                    }
                }
                if (!templateMap.isEmpty()) {
                    log.error("Some form templates not included in the reorder list for function_id: {}", functionId);
                    return GenericResp.newBuilder()
                        .setRt(protoService.getReturnCode(StatusCode.FORM_TEMPLATE_NOT_IN_REORDER_LIST))
                        .build();
                }
                // 更新菜单项
                updatedItems.add(item.toBuilder()
                    .clearAggregatedFormTemplate()
                    .addAllAggregatedFormTemplate(reorderedTemplates)
                    .build());
            } else {
                updatedItems.add(item); // 保留其他未变更的菜单项
            }
        }

        if (!functionFound) {
            log.error("Menu item with function_id: {} not found in deptId: {}", functionId, deptId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.MENU_ITEM_NOT_FOUND))
                .build();
        }

        // 更新菜单配置
        menuConfigPb = menuConfigPb.toBuilder()
            .clearMenuItem()
            .addAllMenuItem(updatedItems)
            .clearUnassignedFormTemplate()
            .build();
        toDb(deptId, menuConfigPb);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    private MenuConfigPB getDefaultMenuConfig() {
        MenuConfigPB.Builder configBuilder = MenuConfigPB.newBuilder();

        // Process MenuGroups recursively
        for (MenuGroup group : fullMenu.getGroupList()) {
            MenuGroup.Builder groupBuilder = processMenuGroup(group);
            configBuilder.addMenuGroup(groupBuilder);
        }
        
        // Process MenuItems recursively
        for (MenuGroup group : fullMenu.getGroupList()) {
            processMenuItems(group, configBuilder);
        }
        
        return configBuilder.build();
    }

    // Helper method to process MenuGroup recursively
    private MenuGroup.Builder processMenuGroup(MenuGroup group) {
        MenuGroup.Builder groupBuilder = MenuGroup.newBuilder(group);
        
        // Clear items for leaf groups
        if (group.getIsLeafGroup()) {
            groupBuilder.clearItems();
        }
        
        // Recursively process subgroups
        groupBuilder.clearSubGroup(); // Clear existing subgroups to rebuild
        for (MenuGroup subGroup : group.getSubGroupList()) {
            MenuGroup.Builder subGroupBuilder = processMenuGroup(subGroup); // Recursive call
            groupBuilder.addSubGroup(subGroupBuilder);
        }
        
        return groupBuilder;
    }

    // Helper method to process MenuItems recursively
    private void processMenuItems(MenuGroup group, MenuConfigPB.Builder configBuilder) {
        // Process items in current group
        for (MenuItem item : group.getItemsList()) {
            MenuItem.Builder itemBuilder = MenuItem.newBuilder(item);
            // Set group_id to the current group's ID
            itemBuilder.setGroupId(group.getGroupId());
            // Set type to 0 for all items
            itemBuilder.setType(0);
            configBuilder.addMenuItem(itemBuilder);
        }
        
        // Recursively process subgroups
        for (MenuGroup subGroup : group.getSubGroupList()) {
            processMenuItems(subGroup, configBuilder); // Recursive call
        }
    }

    private MenuConfigPB fromDb(String deptId) {
        DeptSystemSettingsId id = DeptSystemSettingsId.builder()
            .deptId(deptId)
            .functionId(GET_DEPT_MENU_SETTINGS)
            .build();
        DeptSystemSettings settings = deptSettingsRepo.findById(id).orElse(null);
        if (settings == null) return null;

        return ProtoUtils.decodeMenuConfig(settings.getSettingsPb());
    }

    private void toDb(String deptId, MenuConfigPB config) {
        DeptSystemSettingsId id = DeptSystemSettingsId.builder()
            .deptId(deptId)
            .functionId(GET_DEPT_MENU_SETTINGS)
            .build();
        DeptSystemSettings settings = deptSettingsRepo.findById(id).orElse(null);

        LocalDateTime now = TimeUtils.getNowUtc();
        String accountId = getContextAccountId();
        if (settings == null) {
            settings = DeptSystemSettings.builder()
                .id(id)
                .settingsPb(ProtoUtils.encodeMenuConfig(config))
                .modifiedAt(now)
                .modifiedBy(accountId)
                .build();
        } else {
            settings.setSettingsPb(ProtoUtils.encodeMenuConfig(config));
            settings.setModifiedAt(now);
            settings.setModifiedBy(accountId);
        }
        
        deptSettingsRepo.save(settings);
    }

    @Transactional
    public MenuConfigPB getNormalizedMenuConfig(String deptId) {
        // 获取所有部门表单配置
        List<IntStrKvPB> formTemplates = getDeptFormTemplates(deptId);
        Set<Integer> formTemplateIds = formTemplates.stream()
            .map(IntStrKvPB::getKey)
            .collect(Collectors.toSet());
        Map<Integer, IntStrKvPB> formTemplateMap = formTemplates.stream()
            .collect(Collectors.toMap(IntStrKvPB::getKey, template -> template));

        // 如果部门尚未配置，返回默认配置
        MenuConfigPB config = fromDb(deptId);
        if (config == null) {
            config = defaultMenuConfig.toBuilder()
                .setDeptId(deptId)
                .build();
            toDb(deptId, config);
            config = config.toBuilder()
                .addAllUnassignedFormTemplate(formTemplates)
                .build();
            return config; // 如果没有配置，直接返回默认配置
        }

        //                                     |----------------------> itemIdToAdd ------------------------------------------------|
        //                                     |                  |                                                                 |
        // defaultMenuConfig.menu_item --(allItemIds)--|          |                    |--------------------------------------------|--> config.menu_item
        //                                             |          |                    |                                            |
        // config.menu_item -----------------------------> existingItemIds + itemIdToRemove + existingTemplateIds + formTemplateMenuItemToUpdate
        //                             |                                                               |
        // db--> formTemplates + formTemplateIds                                                       |
        //             |                                                                               |
        //             |-------------------------------------------------------------------------------|--> unassignedFormTemplates
        Map<Integer, MenuItem> allItemIds = defaultMenuConfig.getMenuItemList().stream()
            .collect(Collectors.toMap(MenuItem::getFunctionId, item -> item));

        Set<Integer> existingItemIds = new HashSet<>();
        Set<Integer> itemIdToRemove = new HashSet<>();
        Set<Integer> existingTemplateIds = new HashSet<>();
        Map<Integer, MenuItem> formTemplateMenuItemToUpdate = new HashMap<>();
        for (MenuItem item : config.getMenuItemList()) {
            Integer functionId = item.getFunctionId();

            Integer itemType = item.getType();
            if (itemType <= 0) {
                existingItemIds.add(functionId); // 已存在的菜单项
                if (allItemIds.containsKey(functionId)) continue; // 已存在的菜单项
                itemIdToRemove.add(functionId); // 需要删除的菜单项
            } else if (itemType == 1) { // 单个表单
                if (item.getGroupId() < 0) {
                    itemIdToRemove.add(functionId); // 未分配的菜单项，标记为删除
                    continue; // 跳过未分配的菜单项
                }
                IntStrKvPB formTemplate = item.getSeparatedFormTemplate();
                existingTemplateIds.add(formTemplate.getKey());
                IntStrKvPB formTemplateFromDb = formTemplateMap.get(formTemplate.getKey());
                if (formTemplateFromDb != null && Objects.equals(formTemplateFromDb, formTemplate)) {
                    // 如果表单模板在数据库中存在且未分配，继续
                    continue;
                }
                final Integer formTemplateId = formTemplateFromDb != null ? formTemplateFromDb.getKey() : -1;
                final String formTemplateName = formTemplateFromDb != null ? formTemplateFromDb.getVal() : "";
                formTemplateMenuItemToUpdate.put(functionId, item.toBuilder()
                    .setSeparatedFormTemplate(IntStrKvPB.newBuilder()
                        .setKey(formTemplateId)
                        .setVal(formTemplateName)
                        .build()
                    )
                    .build()
                );
            } else { // 表单组
                if (item.getGroupId() < 0) {
                    itemIdToRemove.add(functionId); // 未分配的菜单项，标记为删除
                    continue; // 跳过未分配的菜单项
                }
                List<IntStrKvPB> aggFormTemplates = item.getAggregatedFormTemplateList();
                List<IntStrKvPB> newFormTemplates = new ArrayList<>();
                boolean allExist = true;
                for (IntStrKvPB formTemplate : aggFormTemplates) {
                    existingTemplateIds.add(formTemplate.getKey());
                    IntStrKvPB formTemplateFromDb = formTemplateMap.get(formTemplate.getKey());
                    if (formTemplateFromDb == null) {
                        allExist = false; // 有表单不存在
                        continue; // 跳过不存在的表单
                    }
                    if (!Objects.equals(formTemplateFromDb.getVal(), formTemplate.getVal())) {
                        allExist = false; // 有表单名称不匹配
                    }
                    newFormTemplates.add(formTemplateFromDb); // 保留存在的表单
                }
                if (allExist) continue; // 所有表单都存在，无需处理

                // 有表单不存在，更新为无效表单组
                formTemplateMenuItemToUpdate.put(functionId, item.toBuilder()
                    .clearAggregatedFormTemplate()
                    .addAllAggregatedFormTemplate(newFormTemplates)
                    .build()
                );
            }
        }

        List<Integer> itemIdToAdd = new ArrayList<>();
        for (MenuItem item : defaultMenuConfig.getMenuItemList()) {
            Integer functionId = item.getFunctionId();
            if (!existingItemIds.contains(functionId)) {
                itemIdToAdd.add(functionId); // 需要添加的菜单项
            }
        }

        List<IntStrKvPB> unassignedFormTemplates = new ArrayList<>();
        for (IntStrKvPB formTemplate : formTemplates) {
            Integer formTemplateId = formTemplate.getKey();
            if (!existingTemplateIds.contains(formTemplateId)) {
                IntStrKvPB existingTemplate = formTemplateMap.get(formTemplateId);
                if (existingTemplate != null) {
                    // 如果表单模板在数据库中存在且未分配，添加到未分配列表
                    unassignedFormTemplates.add(existingTemplate);
                }
            }
        }

        // 如有修正，在数据库中更新部门配置
        List<MenuItem> updatedItems = new ArrayList<>();
        for (MenuItem item : config.getMenuItemList()) {
            Integer functionId = item.getFunctionId();

            MenuItem itemToUpdate = formTemplateMenuItemToUpdate.get(functionId);
            if (itemToUpdate != null) {
                if ((itemToUpdate.getType() == 1 && itemToUpdate.getSeparatedFormTemplate().getKey() < 0) ||
                    (itemToUpdate.getType() == 2 && itemToUpdate.getAggregatedFormTemplateCount() == 0)
                ) {
                    // 如果是无效表单或表单组，直接跳过
                    continue;
                } else {
                    // 如果是有效表单或表单组，添加到更新列表
                    updatedItems.add(itemToUpdate);
                    continue;
                }
            }

            // 如果是默认菜单中的项，保留
            if (!itemIdToRemove.contains(functionId)) updatedItems.add(item);
        }
        for (Integer itemId : itemIdToAdd) {
            MenuItem defaultItem = allItemIds.get(itemId);
            if (defaultItem != null) {
                updatedItems.add(defaultItem);
            }
        }
        if (itemIdToRemove.size() > 0 || itemIdToAdd.size() > 0 || formTemplateMenuItemToUpdate.size() > 0) {
            log.info("Updating menu config for deptId: {}, removed: {}, added: {}, updated templates: {}",
                deptId, itemIdToRemove, itemIdToAdd, formTemplateMenuItemToUpdate.keySet());
            config = config.toBuilder()
                .clearMenuItem()
                .addAllMenuItem(updatedItems)
                .build();
            toDb(deptId, config); // 更新数据库中的配置
        }

        return config.toBuilder()
            .addAllUnassignedFormTemplate(unassignedFormTemplates)
            .build();
    }

    private List<IntStrKvPB> getDeptFormTemplates(String deptId) {
        List<IntStrKvPB> kvList = new ArrayList<>();
        List<DragableFormTemplate> templates = dragableFormTemplateRepo.findByDeptIdAndIsDeletedFalse(deptId);
        for (DragableFormTemplate template : templates) {
            kvList.add(IntStrKvPB.newBuilder()
                .setKey(template.getId())
                .setVal(template.getName())
                .build());
        }
        return kvList;
    }

    public List<MenuGroup> getMenuGroups(MenuConfigPB config) {
        Map<Integer, List<MenuItem>> itemsByGroupId = new HashMap<>();
        for (MenuItem item : config.getMenuItemList()) {
            int groupId = item.getGroupId();
            if (groupId < 0) continue; // Skip items without a valid group_id
            itemsByGroupId.computeIfAbsent(groupId, k -> new ArrayList<>()).add(item);
        }

        List<MenuGroup> groups = new ArrayList<>();
        for (MenuGroup group : config.getMenuGroupList()) {
            MenuGroup.Builder groupBuilder = rebuildMenuGroup(group, itemsByGroupId);
            groups.add(groupBuilder.build());
        }
        return groups;
    }

    public List<MenuGroup> getMenuGroups(String deptId) {
        MenuConfigPB config = getNormalizedMenuConfig(deptId);
        return getMenuGroups(config);
    }

    private GetDeptMenuConfigResp toDeptMenuConfigResp(MenuConfigPB config) {
        // Create a builder for the response
        GetDeptMenuConfigResp.Builder respBuilder = GetDeptMenuConfigResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK));

        // Create a map to quickly look up MenuItems by group_id
        Map<Integer, List<MenuItem>> itemsByGroupId = new HashMap<>();
        for (MenuItem item : config.getMenuItemList()) {
            int groupId = item.getGroupId();
            if (groupId < 0) continue; // Skip items without a valid group_id
            itemsByGroupId.computeIfAbsent(groupId, k -> new ArrayList<>()).add(item);
        }

        // Process MenuGroups recursively to rebuild the hierarchy
        for (MenuGroup group : config.getMenuGroupList()) {
            MenuGroup.Builder groupBuilder = rebuildMenuGroup(group, itemsByGroupId);
            respBuilder.addMenuGroup(groupBuilder);
        }

        // 新增unassigned_menu_item
        for (MenuItem item : config.getMenuItemList()) {
            if (item.getType() == 0 && item.getGroupId() < 0) { // 仅添加未分配的菜单项
                respBuilder.addUnassignedMenuItem(item);
            }
        }

        // 新增unassigned_form_template
        respBuilder.addAllUnassignedFormTemplate(config.getUnassignedFormTemplateList());

        return respBuilder.build();
    }

    // Helper method to rebuild MenuGroup recursively
    private MenuGroup.Builder rebuildMenuGroup(MenuGroup group, Map<Integer, List<MenuItem>> itemsByGroupId) {
        MenuGroup.Builder groupBuilder = MenuGroup.newBuilder(group);
        
        // Clear existing items and subgroups to rebuild
        groupBuilder.clearItems();
        groupBuilder.clearSubGroup();
        
        // Add MenuItems corresponding to this group's group_id
        List<MenuItem> items = itemsByGroupId.getOrDefault(group.getGroupId(), Collections.emptyList());
        groupBuilder.addAllItems(items);
        
        // Recursively process subgroups
        for (MenuGroup subGroup : group.getSubGroupList()) {
            MenuGroup.Builder subGroupBuilder = rebuildMenuGroup(subGroup, itemsByGroupId);
            groupBuilder.addSubGroup(subGroupBuilder);
        }
        
        return groupBuilder;
    }

    private String getContextAccountId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser"))
            return "";
        final String accountId = auth.getName();
        Account account = accountRepo.findByAccountIdAndIsDeletedFalse(accountId).orElse(null);
        if (account == null) {
            log.error("Account not found for account_id: {}", accountId);
            return "";
        }
        return account.getId().toString();
    }

    private Set<Integer> getLeafGroupIds(MenuGroup group) {
        Set<Integer> leafGroupIds = new HashSet<>();
        if (group.getIsLeafGroup()) {
            leafGroupIds.add(group.getGroupId());
        } else {
            for (MenuGroup subGroup : group.getSubGroupList()) {
                leafGroupIds.addAll(getLeafGroupIds(subGroup));
            }
        }
        return leafGroupIds;
    }

    private final String ZONE_ID;
    private final String ADMIN_ACCOUNT_ID;
    private final Integer GET_DEPT_MENU_SETTINGS;
    private final Integer TEMPLATE_MENU_ITEM_START_ID;

    private final Menu fullMenu;
    private final MenuConfigPB defaultMenuConfig;

    private ConfigProtoService protoService;
    private DeptSystemSettingsRepository deptSettingsRepo;
    private DragableFormTemplateRepository dragableFormTemplateRepo;
    private AccountRepository accountRepo;
}