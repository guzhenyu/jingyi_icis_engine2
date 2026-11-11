package com.jingyicare.jingyi_icis_engine.service.users.menu;

import java.util.*;
import java.util.stream.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.reports.*;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkTemplateReader;
import com.jingyicare.jingyi_icis_engine.service.settings.SettingsStore;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class MenuService {
    public MenuService(
        @Autowired ProtoConfig protoConfig,
        @Autowired MenuMeta menuMeta,
        @Autowired SettingsStore settingsStore,
        @Autowired JfkTemplateReader jfkTemplateReader,
        @Autowired UserQuery userQuery
    ) {
        this.menuMeta = menuMeta;
        this.statusCodeMsgList = protoConfig.getConfig().getText().getStatusCodeMsgList();
        this.settingsStore = settingsStore;
        this.jfkTemplateReader = jfkTemplateReader;
        this.userQuery = userQuery;
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
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        MenuConfigPB config = loadAndNormalizeMenuConfig(req.getDeptId());
        List<MenuGroup> menuGroups = getMenuGroups(config);
        List<MenuItem> unassignedMenuItems = new ArrayList<>();
        for (MenuItem item : config.getMenuItemList()) {
            if (item.getType() == MENU_ITEM_TYPE_BUILTIN && item.getGroupId() <= 0) {
                unassignedMenuItems.add(item);
            }
        }
        return GetDeptMenuConfigResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
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
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String deptId = req.getDeptId();
        int functionId = req.getFunctionId();

        MenuConfigPB menuConfigPb = loadAndNormalizeMenuConfig(deptId);
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
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.MENU_ITEM_NOT_FOUND))
                .build();
        }

        List<JfkTemplatePB> templateList = new ArrayList<>();
        if (foundItem.getType() == 1) {
            // 单表单
            int templateId = foundItem.getSeparatedFormTemplate().getKey();
            if (templateId > 0) {
                DragableFormTemplate template = jfkTemplateReader.getById(templateId);
                if (template != null) {
                    JfkTemplatePB templatePb = ProtoUtils.decodeJfkTemplate(template.getTemplatePb());
                    templateList.add(templatePb);
                }
            }
            if (templateList.isEmpty()) {
                log.error("No separated form template found for function_id: {} in deptId: {}", functionId, deptId);
                return GetMenuItemResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.FORM_TEMPLATE_NOT_FOUND))
                    .build();
            }
        } else if (foundItem.getType() == 2) {
            // 表单组
            List<Integer> templateIds = foundItem.getAggregatedFormTemplateList().stream()
                .map(IntStrKvPB::getKey)
                .collect(Collectors.toList());
            if (templateIds.isEmpty()) {
                return GetMenuItemResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                    .setMenuItem(foundItem)
                    .build();
            }

            // 获取所有表单模板
            List<DragableFormTemplate> templates = jfkTemplateReader.getByIds(templateIds);
            for (DragableFormTemplate template : templates) {
                JfkTemplatePB templatePb = ProtoUtils.decodeJfkTemplate(template.getTemplatePb());
                templateList.add(templatePb);
            }
            if (templateList.isEmpty()) {
                log.error("No aggregated form templates found for function_id: {} in deptId: {}", functionId, deptId);
                return GetMenuItemResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.FORM_TEMPLATE_NOT_FOUND))
                    .build();
            }
        }

        return GetMenuItemResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
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
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String deptId = req.getDeptId();
        MenuConfigPB menuConfigPb = loadAndNormalizeMenuConfig(deptId);
        // 检查group_id是否存在
        Set<Integer> leafGroupIds = MenuMeta.getLeafGroupIds(menuConfigPb);
        final Integer groupId = req.getMenuItem().getGroupId();
        if (!leafGroupIds.contains(groupId)) {
            log.error("Invalid group_id: {} for deptId: {}", groupId, deptId);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.INVALID_MENU_GROUP_ID))
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
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.MENU_ITEM_NAME_EXISTS))
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
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.MENU_ITEM_NOT_FOUND))
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
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.INVALID_FORM_TEMPLATE_ID))
                    .build();
            }
            if (newItem.getAggregatedFormTemplateCount() > 0) {
                log.error("Aggregated form templates are not allowed for single form items in deptId: {}", deptId);
                return GenericResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.INVALID_MENU_ITEM_TYPE))
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
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.INVALID_MENU_ITEM_TYPE))
                    .build();
            }
            for (IntStrKvPB formTemplate : newItem.getAggregatedFormTemplateList()) {
                if (!formTempIds.contains(formTemplate.getKey())) {
                    log.error("Invalid aggregated form template key: {} for deptId: {}", formTemplate.getKey(), deptId);
                    return GenericResp.newBuilder()
                        .setRt(ReturnCodeUtils.getReturnCode(
                            statusCodeMsgList, StatusCode.INVALID_FORM_TEMPLATE_ID))
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
        settingsStore.saveMenuConfigPB(deptId, menuConfigPb, userQuery.getAccountAutoId());
    
        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
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
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
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
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String deptId = req.getDeptId();
        final Integer functionId = req.getFunctionId();
        final Integer formTemplateId = req.getFormTemplateId();

        return updateMenuItem(deptId, functionId, null, formTemplateId);
    }

    private GenericResp updateMenuItem(String deptId, Integer functionId, String name, Integer separatedFormTemplateId) {
        MenuConfigPB menuConfigPb = loadAndNormalizeMenuConfig(deptId);
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
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.MENU_ITEM_NOT_FOUND))
                .build();
        }
        if (groupId <= 0) {
            log.error("Menu item with function_id: {} is not assigned to a valid group_id in deptId: {}", functionId, deptId);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.CANNOT_RENAME_UNASSIGNED_MENU_ITEM))
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
                            .setRt(ReturnCodeUtils.getReturnCode(
                                statusCodeMsgList, StatusCode.FORM_TEMPLATE_NOT_FOUND))
                            .build();
                    }
                    builder.setSeparatedFormTemplate(formTemplate);
                }
                updatedItems.add(builder.build());
            } else {
                if (item.getGroupId() == groupId && name != null && item.getName().equals(name)) {
                    log.error("Menu item name already exists: {} in group_id: {}", name, groupId);
                    return GenericResp.newBuilder()
                        .setRt(ReturnCodeUtils.getReturnCode(
                            statusCodeMsgList, StatusCode.MENU_ITEM_NAME_EXISTS))
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
        settingsStore.saveMenuConfigPB(deptId, menuConfigPb, userQuery.getAccountAutoId());

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
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
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String deptId = req.getDeptId();
        final Integer functionId = req.getFunctionId();

        MenuConfigPB menuConfigPb = loadAndNormalizeMenuConfig(deptId);
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
        settingsStore.saveMenuConfigPB(deptId, menuConfigPb, userQuery.getAccountAutoId());

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
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
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 验证请求参数
        final String deptId = req.getDeptId();
        MenuConfigPB menuConfigPb = loadAndNormalizeMenuConfig(deptId);
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
        settingsStore.saveMenuConfigPB(deptId, menuConfigPb, userQuery.getAccountAutoId());

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
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
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String deptId = req.getDeptId();
        final Integer functionId = req.getFunctionId();
        List<Integer> templateIds = req.getFormTemplateIdList();

        // 验证模板信息
        Map<Integer, DragableFormTemplate> templateMap = jfkTemplateReader.getByIds(templateIds)
            .stream()
            .collect(Collectors.toMap(DragableFormTemplate::getId, template -> template));
        for (Integer templateId : templateIds) {
            DragableFormTemplate template = templateMap.get(templateId);
            if (template == null) {
                log.error("Form template with id: {} not found for deptId: {}", templateId, deptId);
                return GenericResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.FORM_TEMPLATE_NOT_FOUND))
                    .build();
            }
            if (template.getDeptId() == null || !template.getDeptId().equals(deptId)) {
                log.error("Form template with id: {} does not belong to deptId: {}", templateId, deptId);
                return GenericResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.FORM_TEMPLATE_NOT_BELONG_TO_DEPT))
                    .build();
            }
        }

        // 获取配置
        MenuConfigPB menuConfigPb = loadAndNormalizeMenuConfig(deptId);
        Set<Integer> allUnassignedTemplateIds = menuConfigPb.getUnassignedFormTemplateList().stream()
            .map(IntStrKvPB::getKey)
            .collect(Collectors.toSet());
        boolean templateFound = false;
        for (Integer templateId : templateIds) {
            if (!allUnassignedTemplateIds.contains(templateId)) {
                log.error("Form template with id: {} is not available for assignment in deptId: {}", templateId, deptId);
                return GenericResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.FORM_TEMPLATE_NOT_AVAILABLE_FOR_ASSIGNMENT))
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
                        .setRt(ReturnCodeUtils.getReturnCode(
                            statusCodeMsgList, StatusCode.NOT_AN_AGGREGATED_FORM_MENU_ITEM))
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
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.MENU_ITEM_NOT_FOUND))
                .build();
        }
        menuConfigPb = menuConfigPb.toBuilder()
            .clearMenuItem()
            .addAllMenuItem(updatedItems)
            .clearUnassignedFormTemplate()
            .build();
        settingsStore.saveMenuConfigPB(deptId, menuConfigPb, userQuery.getAccountAutoId());

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
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
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String deptId = req.getDeptId();
        final Integer templateId = req.getFormTemplateId();

        // 验证模板信息
        DragableFormTemplate template = jfkTemplateReader.getById(templateId);
        if (template == null) {
            log.error("Form template with id: {} not found for deptId: {}", templateId, deptId);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.FORM_TEMPLATE_NOT_FOUND))
                .build();
        }
        if (template.getDeptId() == null || !template.getDeptId().equals(deptId)) {
            log.error("Form template with id: {} does not belong to deptId: {}", templateId, deptId);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.FORM_TEMPLATE_NOT_BELONG_TO_DEPT))
                .build();
        }

        // 获取配置
        MenuConfigPB menuConfigPb = loadAndNormalizeMenuConfig(deptId);
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
            settingsStore.saveMenuConfigPB(deptId, menuConfigPb, userQuery.getAccountAutoId());
        }

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
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
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String deptId = req.getDeptId();
        final Integer functionId = req.getFunctionId();
        final List<Integer> reorderedTemplateIds = req.getFormTemplateIdList();
        // 获取配置
        MenuConfigPB menuConfigPb = loadAndNormalizeMenuConfig(deptId);
        boolean functionFound = false;
        List<MenuItem> updatedItems = new ArrayList<>();
        for (MenuItem item : menuConfigPb.getMenuItemList()) {
            if (item.getFunctionId() == functionId) {
                functionFound = true;
                if (item.getType() != 2) {
                    log.error("Menu item with function_id: {} is not a form group in deptId: {}", functionId, deptId);
                    return GenericResp.newBuilder()
                        .setRt(ReturnCodeUtils.getReturnCode(
                            statusCodeMsgList, StatusCode.NOT_AN_AGGREGATED_FORM_MENU_ITEM))
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
                            .setRt(ReturnCodeUtils.getReturnCode(
                                statusCodeMsgList, StatusCode.FORM_TEMPLATE_NOT_FOUND_IN_MENU_ITEM))
                            .build();
                    }
                }
                if (!templateMap.isEmpty()) {
                    log.error("Some form templates not included in the reorder list for function_id: {}", functionId);
                    return GenericResp.newBuilder()
                        .setRt(ReturnCodeUtils.getReturnCode(
                            statusCodeMsgList, StatusCode.FORM_TEMPLATE_NOT_IN_REORDER_LIST))
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
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.MENU_ITEM_NOT_FOUND))
                .build();
        }

        // 更新菜单配置
        menuConfigPb = menuConfigPb.toBuilder()
            .clearMenuItem()
            .addAllMenuItem(updatedItems)
            .clearUnassignedFormTemplate()
            .build();
        settingsStore.saveMenuConfigPB(deptId, menuConfigPb, userQuery.getAccountAutoId());

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public MenuConfigPB loadAndNormalizeMenuConfig(String deptId) {
        MenuConfigPB defaultMenuConfig = menuMeta.getConfig();
        Set<Integer> buildInItemIds = menuMeta.getBuildInItemIds();

        // 获取当前科室的有效表单模板，保留查询结果的顺序。
        List<IntStrKvPB> tempIdNameList = getDeptFormTemplates(deptId);
        Map<Integer, String> tempIdNameMap = tempIdNameList.stream()
            .collect(Collectors.toMap(IntStrKvPB::getKey, IntStrKvPB::getVal));

        MenuConfigPB config = null;
        try {
            config = settingsStore.getMenuConfigPB(deptId);
        } catch (SettingsStore.DecodeException e) {
            // 损坏菜单按未配置处理，重新生成默认菜单。
        }
        if (config == null) {
            config = defaultMenuConfig.toBuilder().setDeptId(deptId).build();
            settingsStore.saveMenuConfigPB(deptId, config, userQuery.getAccountAutoId());
            return config.toBuilder().addAllUnassignedFormTemplate(tempIdNameList).build();
        }

        // 按原顺序校验三类菜单项，剔除非法项并同步模板名称。
        Set<Integer> groupIds = MenuMeta.getGroupIds(config);
        Set<Integer> existingBuiltinItemIds = new HashSet<>();
        List<MenuItem> normalizedItems = new ArrayList<>();
        for (MenuItem item : config.getMenuItemList()) {
            MenuItem normalizedItem = normalizeMenuItem(
                item, buildInItemIds, groupIds, tempIdNameMap);
            if (normalizedItem == null) continue;
            normalizedItems.add(normalizedItem);
            if (normalizedItem.getType() == MENU_ITEM_TYPE_BUILTIN) {
                existingBuiltinItemIds.add(normalizedItem.getFunctionId());
            }
        }

        // 保留自动补齐行为；默认分组已不存在时，将补齐的内置项设为未分配。
        for (MenuItem item : defaultMenuConfig.getMenuItemList()) {
            if (existingBuiltinItemIds.contains(item.getFunctionId())) continue;
            if (item.getGroupId() > 0 && !groupIds.contains(item.getGroupId())) {
                item = item.toBuilder().setGroupId(-1).build();
            }
            normalizedItems.add(item);
        }

        // 仅菜单项发生变化时保存；未分配模板列表只用于返回。
        if (!normalizedItems.equals(config.getMenuItemList())) {
            config = config.toBuilder()
                .clearMenuItem()
                .addAllMenuItem(normalizedItems)
                .clearUnassignedFormTemplate()
                .build();
            settingsStore.saveMenuConfigPB(deptId, config, userQuery.getAccountAutoId());
        }

        // 只统计最终保留菜单项引用的模板，失效菜单项不占用模板。
        Set<Integer> assignedTemplateIds = new HashSet<>();
        for (MenuItem item : normalizedItems) {
            if (item.getType() == MENU_ITEM_TYPE_SEPARATED_FORM) {
                assignedTemplateIds.add(item.getSeparatedFormTemplate().getKey());
            } else if (item.getType() == MENU_ITEM_TYPE_AGGREGATED_FORMS) {
                for (IntStrKvPB template : item.getAggregatedFormTemplateList()) {
                    assignedTemplateIds.add(template.getKey());
                }
            }
        }
        List<IntStrKvPB> unassignedFormTemplates = tempIdNameList.stream()
            .filter(template -> !assignedTemplateIds.contains(template.getKey()))
            .collect(Collectors.toList());
        return config.toBuilder()
            .clearUnassignedFormTemplate()
            .addAllUnassignedFormTemplate(unassignedFormTemplates)
            .build();
    }

    // 返回修正后的菜单项；返回 null 表示该菜单项非法，需要剔除。
    private MenuItem normalizeMenuItem(
        MenuItem item, Set<Integer> buildInItemIds, Set<Integer> groupIds,
        Map<Integer, String> tempIdNameMap
    ) {
        // 内置菜单项允许未分配，已分配时必须指向现有菜单组。
        if (item.getType() == MENU_ITEM_TYPE_BUILTIN) {
            boolean validGroup = item.getGroupId() <= 0 || groupIds.contains(item.getGroupId());
            return buildInItemIds.contains(item.getFunctionId()) && validGroup ? item : null;
        }

        // 表单菜单项必须使用表单功能 ID，且必须分配到现有菜单组。
        if (item.getFunctionId() < TEMPLATE_MENU_ITEM_START_ID
            || item.getGroupId() <= 0 || !groupIds.contains(item.getGroupId())) {
            return null;
        }
        if (item.getType() == MENU_ITEM_TYPE_SEPARATED_FORM) {
            IntStrKvPB template = item.getSeparatedFormTemplate();
            String name = tempIdNameMap.get(template.getKey());
            if (name == null) return null;
            if (name.equals(template.getVal())) return item;
            return item.toBuilder()
                .setSeparatedFormTemplate(template.toBuilder().setVal(name))
                .build();
        }
        if (item.getType() == MENU_ITEM_TYPE_AGGREGATED_FORMS) {
            List<IntStrKvPB> templates = new ArrayList<>();
            for (IntStrKvPB template : item.getAggregatedFormTemplateList()) {
                String name = tempIdNameMap.get(template.getKey());
                if (name == null) continue;
                templates.add(name.equals(template.getVal())
                    ? template : template.toBuilder().setVal(name).build());
            }
            if (templates.isEmpty()) return null;
            if (templates.equals(item.getAggregatedFormTemplateList())) return item;
            return item.toBuilder()
                .clearAggregatedFormTemplate()
                .addAllAggregatedFormTemplate(templates)
                .build();
        }
        return null;
    }

    private List<IntStrKvPB> getDeptFormTemplates(String deptId) {
        List<IntStrKvPB> kvList = new ArrayList<>();
        List<DragableFormTemplate> templates = jfkTemplateReader.getByDeptId(deptId);
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
            if (groupId <= 0) continue; // 未分配的菜单项不加入分组。
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
        MenuConfigPB config = loadAndNormalizeMenuConfig(deptId);
        return getMenuGroups(config);
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

    private static final int MENU_ITEM_TYPE_BUILTIN = 0;
    private static final int MENU_ITEM_TYPE_SEPARATED_FORM = 1;
    private static final int MENU_ITEM_TYPE_AGGREGATED_FORMS = 2;
    private static final int TEMPLATE_MENU_ITEM_START_ID = 10000;

    private final MenuMeta menuMeta;
    private final List<String> statusCodeMsgList;
    private SettingsStore settingsStore;
    private JfkTemplateReader jfkTemplateReader;
    private UserQuery userQuery;
}
