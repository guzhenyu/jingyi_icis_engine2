package com.jingyicare.jingyi_icis_engine.service.users.menu;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.protobuf.util.JsonFormat;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.reports.*;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
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
        @Autowired UserQuery userQuery,
        @Autowired LockExecutor lockExecutor
    ) {
        this.menuMeta = menuMeta;
        this.statusCodeMsgList = protoConfig.getConfig().getText().getStatusCodeMsgList();
        this.settingsStore = settingsStore;
        this.jfkTemplateReader = jfkTemplateReader;
        this.userQuery = userQuery;
        this.lockExecutor = lockExecutor;
    }

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
        List<MenuGroup> menuGroups = MenuMeta.getMenuGroups(config);
        List<MenuItem> unassignedMenuItems = new ArrayList<>();
        for (MenuItem item : config.getMenuItemList()) {
            if (item.getType() == MenuMeta.MENU_ITEM_TYPE_BUILTIN && item.getGroupId() <= 0) {
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

    public GetMenuItemResp getMenuItem(String reqJson) {
        final GetMenuItemReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, GetMenuItemReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse GetMenuItemReq: ", e);
            return GetMenuItemResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        MenuItem item = loadAndNormalizeMenuConfig(req.getDeptId()).getMenuItemList().stream()
            .filter(candidate -> candidate.getFunctionId() == req.getFunctionId())
            .findFirst().orElse(null);
        if (item == null) {
            return GetMenuItemResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.MENU_ITEM_NOT_FOUND))
                .build();
        }

        List<DragableFormTemplate> templates = new ArrayList<>();
        if (item.getType() == MenuMeta.MENU_ITEM_TYPE_SEPARATED_FORM) {
            DragableFormTemplate template = jfkTemplateReader.getById(
                item.getSeparatedFormTemplate().getKey());
            if (template != null) templates.add(template);
        } else if (item.getType() == MenuMeta.MENU_ITEM_TYPE_AGGREGATED_FORMS) {
            List<Integer> templateIds = item.getAggregatedFormTemplateList().stream()
                .map(IntStrKvPB::getKey).collect(Collectors.toList());
            templates = jfkTemplateReader.getByIds(templateIds);
        }
        if (item.getType() != MenuMeta.MENU_ITEM_TYPE_BUILTIN && templates.isEmpty()) {
            return GetMenuItemResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.FORM_TEMPLATE_NOT_FOUND))
                .build();
        }

        // 两类表单菜单共用解码校验，避免将损坏模板加入响应。
        List<JfkTemplatePB> templateList = new ArrayList<>();
        for (DragableFormTemplate template : templates) {
            JfkTemplatePB templatePb = ProtoUtils.decodeJfkTemplate(template.getTemplatePb());
            if (templatePb == null) {
                return GetMenuItemResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.INVALID_FORM_TEMPLATE))
                    .build();
            }
            templateList.add(templatePb);
        }
        return GetMenuItemResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setMenuItem(item)
            .addAllTemplate(templateList)
            .build();
    }

    public GenericResp addMenuItem(String reqJson) {
        final AddMenuItemReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, AddMenuItemReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse AddMenuItemReq: ", e);
            return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.PARSE_JSON_FAILED));
        }
        if (!req.hasMenuItem()) {
            StatusCode code = StrUtils.isBlank(req.getDeptId())
                ? StatusCode.DEPT_IS_EMPTY : StatusCode.INVALID_MENU_ITEM_TYPE;
            return ReturnCodeUtils.genericResponse(
                ReturnCodeUtils.getReturnCode(statusCodeMsgList, code));
        }
        return executeMenuChange(req.getDeptId(), config -> addMenuItemInTransaction(req, config));
    }

    private GenericResp addMenuItemInTransaction(AddMenuItemReq req, MenuConfigPB config) {
        MenuItem item = req.getMenuItem();
        int type = item.getType();

        // 检查 groupId, type
        if (item.getGroupId() <= 0
            || !MenuMeta.getLeafGroupIds(config).contains(item.getGroupId())) {
            return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.INVALID_MENU_GROUP_ID));
        }
        if (type != MenuMeta.MENU_ITEM_TYPE_BUILTIN
            && type != MenuMeta.MENU_ITEM_TYPE_SEPARATED_FORM
            && type != MenuMeta.MENU_ITEM_TYPE_AGGREGATED_FORMS) {
            return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.INVALID_MENU_ITEM_TYPE));
        }

        // 内置项按 function_id 查找；新增表单项时检查名称全局唯一。
        MenuConfigPB.Builder newConfig = config.toBuilder();
        newConfig.setDeptId(req.getDeptId());
        if (type == MenuMeta.MENU_ITEM_TYPE_BUILTIN) {
            MenuItem existing = MenuMeta.findBuiltinMenuItem(config, item.getFunctionId());
            if (existing == null) {
                return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.MENU_ITEM_NOT_FOUND));
            }
            if (existing.getGroupId() > 0) {
                return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.MENU_ITEM_ALREADY_ASSIGNED));
            }
            if (MenuMeta.hasItemName(config, existing.getName(), existing.getFunctionId())) {
                return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.MENU_ITEM_NAME_EXISTS));
            }

            // 新增的内置菜单放到最后
            newConfig.clearMenuItem();
            for (MenuItem mi : config.getMenuItemList()) {
                if (mi.getFunctionId() == existing.getFunctionId()) continue;
                newConfig.addMenuItem(mi);
            }
            newConfig.addMenuItem(existing.toBuilder().setGroupId(item.getGroupId()).build());
        } else {
            if (MenuMeta.hasItemName(config, item.getName(), null)) {
                return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.MENU_ITEM_NAME_EXISTS));
            }

            boolean separated = type == MenuMeta.MENU_ITEM_TYPE_SEPARATED_FORM;

            // 模板非空检查
            List<Integer> templateIds = separated
                ? List.of(item.getSeparatedFormTemplate().getKey())
                : item.getAggregatedFormTemplateList().stream()
                    .map(IntStrKvPB::getKey).collect(Collectors.toList());
            if (templateIds.isEmpty()) {
                return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.INVALID_FORM_TEMPLATE_ID));
            }

            // 模板校验：数据库存在，且未分配给已有菜单
            Pair<StatusCode, Map<Integer, DragableFormTemplate>> checked =
                validateDeptTemplates(req.getDeptId(), templateIds);
            if (checked.getFirst() != StatusCode.OK
                || validateAssignableTemplates(config, templateIds, null) != StatusCode.OK) {
                return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.INVALID_FORM_TEMPLATE_ID));
            }
            Map<Integer, DragableFormTemplate> templateMap = checked.getSecond();
            List<IntStrKvPB> formIdNameList = templateIds.stream()
                .map(id -> IntStrKvPB.newBuilder()
                    .setKey(id)
                    .setVal(templateMap.get(id).getName())
                    .build())
                .collect(Collectors.toList());

            // 分配新的 function id
            Integer newFunctionId = TEMPLATE_MENU_ITEM_START_ID;
            for (MenuItem mi : config.getMenuItemList()) {
                newFunctionId = Math.max(newFunctionId, mi.getFunctionId() + 1);
            }

            MenuItem.Builder formItemToAdd = item.toBuilder().setFunctionId(newFunctionId);
            formItemToAdd.clearSeparatedFormTemplate();
            formItemToAdd.clearAggregatedFormTemplate();
            if (separated) {
                formItemToAdd.setSeparatedFormTemplate(formIdNameList.get(0));
            } else {
                formItemToAdd.addAllAggregatedFormTemplate(formIdNameList);
            }
            newConfig.addMenuItem(formItemToAdd.build());
        }

        saveMenuConfig(req.getDeptId(), newConfig.build());
        return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
            statusCodeMsgList, StatusCode.OK));
    }

    public GenericResp updateMenuItemName(String reqJson) {
        final UpdateMenuItemNameReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, UpdateMenuItemNameReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse UpdateMenuItemNameReq: ", e);
            return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.PARSE_JSON_FAILED));
        }
        return executeMenuChange(req.getDeptId(), config -> updateMenuItemInTransaction(
            req.getDeptId(), config, req.getFunctionId(), req.getNewName(), null));
    }

    public GenericResp updateMenuItemWithOneForm(String reqJson) {
        final UpdateMenuItemWithOneFormReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, UpdateMenuItemWithOneFormReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse UpdateMenuItemWithOneFormReq: ", e);
            return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.PARSE_JSON_FAILED));
        }
        return executeMenuChange(req.getDeptId(), config -> updateMenuItemInTransaction(
            req.getDeptId(), config, req.getFunctionId(), null, req.getFormTemplateId()));
    }

    // 调用方已持有科室菜单设置锁及事务；两个入口分别只修改名称或独立模板引用。
    private GenericResp updateMenuItemInTransaction(
        String deptId, MenuConfigPB config, int functionId,
        String name, Integer separatedFormTemplateId
    ) {
        int index = MenuMeta.findMenuItemIndex(config, functionId);
        if (index < 0) {
            return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.MENU_ITEM_NOT_FOUND));
        }
        MenuItem item = config.getMenuItem(index);
        if (item.getGroupId() <= 0) {
            return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.CANNOT_RENAME_UNASSIGNED_MENU_ITEM));
        }

        MenuItem.Builder updatedItem = item.toBuilder();
        if (name != null) {
            if (MenuMeta.hasItemName(config, name, functionId)) {
                return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.MENU_ITEM_NAME_EXISTS));
            }
            updatedItem.setName(name);
        } else {
            if (item.getType() != MenuMeta.MENU_ITEM_TYPE_SEPARATED_FORM) {
                return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.INVALID_MENU_ITEM_TYPE));
            }
            List<Integer> templateIds = List.of(separatedFormTemplateId);
            Pair<StatusCode, Map<Integer, DragableFormTemplate>> checked =
                validateDeptTemplates(deptId, templateIds);
            if (checked.getFirst() != StatusCode.OK) {
                return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, checked.getFirst()));
            }
            StatusCode assignable = validateAssignableTemplates(config, templateIds, functionId);
            if (assignable != StatusCode.OK) {
                return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, assignable));
            }
            updatedItem.setSeparatedFormTemplate(
                buildTemplateRefs(templateIds, checked.getSecond()).get(0));
        }

        saveMenuConfig(deptId, config.toBuilder().setMenuItem(index, updatedItem).build());
        return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
            statusCodeMsgList, StatusCode.OK));
    }

    public GenericResp deleteMenuItem(String reqJson) {
        final DeleteMenuItemReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, DeleteMenuItemReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse DeleteMenuItemReq: ", e);
            return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.PARSE_JSON_FAILED));
        }
        return executeMenuChange(req.getDeptId(), config -> {
            MenuConfigPB.Builder updated = config.toBuilder();
            int index = MenuMeta.findMenuItemIndex(config, req.getFunctionId());
            if (index >= 0) {
                updated.setMenuItem(index, config.getMenuItem(index).toBuilder().setGroupId(-1));
            }
            // 保持删除幂等：不存在的 ID 也返回成功。
            saveMenuConfig(req.getDeptId(), updated.build());
            return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.OK));
        });
    }

    public GenericResp reorderMenuItems(String reqJson) {
        final ReorderMenuItemsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, ReorderMenuItemsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse ReorderMenuItemsReq: ", e);
            return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.PARSE_JSON_FAILED));
        }
        return executeMenuChange(req.getDeptId(), config -> {
            Map<Integer, MenuItem> remainingItems = new LinkedHashMap<>();
            for (MenuItem item : config.getMenuItemList()) {
                remainingItems.put(item.getFunctionId(), item);
            }
            List<MenuItem> reorderedItems = new ArrayList<>();
            for (Integer functionId : req.getFunctionIdList()) {
                // 首次命中后移除，忽略不存在或重复的 ID。
                MenuItem item = remainingItems.remove(functionId);
                if (item != null) reorderedItems.add(item);
            }
            saveMenuConfig(req.getDeptId(), config.toBuilder().clearMenuItem()
                .addAllMenuItem(remainingItems.values()).addAllMenuItem(reorderedItems).build());
            return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.OK));
        });
    }

    public GenericResp addMenuItemFormTemplate(String reqJson) {
        final AddMenuItemFormTemplateReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, AddMenuItemFormTemplateReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse AddMenuItemFormTemplateReq: ", e);
            return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.PARSE_JSON_FAILED));
        }
        return executeMenuChange(req.getDeptId(), config -> {
            int index = MenuMeta.findMenuItemIndex(config, req.getFunctionId());
            if (index < 0) {
                return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.MENU_ITEM_NOT_FOUND));
            }
            MenuItem item = config.getMenuItem(index);
            if (item.getType() != MenuMeta.MENU_ITEM_TYPE_AGGREGATED_FORMS) {
                return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.NOT_AN_AGGREGATED_FORM_MENU_ITEM));
            }
            List<Integer> templateIds = req.getFormTemplateIdList();
            Pair<StatusCode, Map<Integer, DragableFormTemplate>> checked =
                validateDeptTemplates(req.getDeptId(), templateIds);
            if (checked.getFirst() != StatusCode.OK) {
                return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, checked.getFirst()));
            }
            StatusCode assignable = validateAssignableTemplates(config, templateIds, null);
            if (assignable != StatusCode.OK) {
                return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, assignable));
            }
            MenuItem updated = item.toBuilder().addAllAggregatedFormTemplate(
                buildTemplateRefs(templateIds, checked.getSecond())).build();
            saveMenuConfig(req.getDeptId(), config.toBuilder().setMenuItem(index, updated).build());
            return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.OK));
        });
    }

    public GenericResp deleteMenuItemFormTemplate(String reqJson) {
        final DeleteMenuItemFormTemplateReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, DeleteMenuItemFormTemplateReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse DeleteMenuItemFormTemplateReq: ", e);
            return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.PARSE_JSON_FAILED));
        }
        return executeMenuChange(req.getDeptId(), config -> {
            StatusCode validation = validateDeptTemplates(
                req.getDeptId(), List.of(req.getFormTemplateId())).getFirst();
            if (validation != StatusCode.OK) {
                return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, validation));
            }
            MenuConfigPB.Builder updated = config.toBuilder();
            boolean changed = false;
            for (int index = 0; index < config.getMenuItemCount(); index++) {
                MenuItem item = config.getMenuItem(index);
                if (item.getType() != MenuMeta.MENU_ITEM_TYPE_AGGREGATED_FORMS) continue;
                List<IntStrKvPB> templates = item.getAggregatedFormTemplateList().stream()
                    .filter(template -> template.getKey() != req.getFormTemplateId())
                    .collect(Collectors.toList());
                if (templates.size() == item.getAggregatedFormTemplateCount()) continue;
                updated.setMenuItem(index, item.toBuilder().clearAggregatedFormTemplate()
                    .addAllAggregatedFormTemplate(templates));
                changed = true;
            }
            if (changed) saveMenuConfig(req.getDeptId(), updated.build());
            return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.OK));
        });
    }

    public GenericResp reorderMenuItemFormTemplates(String reqJson) {
        final ReorderMenuItemFormTemplatesReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                reqJson, ReorderMenuItemFormTemplatesReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse ReorderMenuItemFormTemplatesReq: ", e);
            return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.PARSE_JSON_FAILED));
        }
        return executeMenuChange(req.getDeptId(), config -> {
            int index = MenuMeta.findMenuItemIndex(config, req.getFunctionId());
            if (index < 0) {
                return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.MENU_ITEM_NOT_FOUND));
            }
            MenuItem item = config.getMenuItem(index);
            if (item.getType() != MenuMeta.MENU_ITEM_TYPE_AGGREGATED_FORMS) {
                return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.NOT_AN_AGGREGATED_FORM_MENU_ITEM));
            }
            // 按 ID 保存所有引用，兼容历史配置中的重复模板；排序必须完整覆盖其数量。
            Map<Integer, Deque<IntStrKvPB>> remaining = new HashMap<>();
            for (IntStrKvPB template : item.getAggregatedFormTemplateList()) {
                remaining.computeIfAbsent(template.getKey(), key -> new ArrayDeque<>())
                    .addLast(template);
            }
            List<IntStrKvPB> reordered = new ArrayList<>();
            for (Integer templateId : req.getFormTemplateIdList()) {
                Deque<IntStrKvPB> matching = remaining.get(templateId);
                if (matching == null) {
                    return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.FORM_TEMPLATE_NOT_FOUND_IN_MENU_ITEM));
                }
                reordered.add(matching.removeFirst());
                if (matching.isEmpty()) remaining.remove(templateId);
            }
            if (!remaining.isEmpty()) {
                return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.FORM_TEMPLATE_NOT_IN_REORDER_LIST));
            }
            MenuItem updated = item.toBuilder().clearAggregatedFormTemplate()
                .addAllAggregatedFormTemplate(reordered).build();
            saveMenuConfig(req.getDeptId(), config.toBuilder().setMenuItem(index, updated).build());
            return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.OK));
        });
    }

    public List<MenuGroup> getMenuGroups(String deptId) {
        MenuConfigPB config = loadAndNormalizeMenuConfig(deptId);
        return MenuMeta.getMenuGroups(config);
    }

    public MenuConfigPB loadAndNormalizeMenuConfig(String deptId) {
        String resourceKey = LockKeys.deptSystemSettings(
            deptId, SettingsStore.GET_DEPT_MENU_SETTINGS);
        return lockExecutor.execute(List.of(resourceKey),
            () -> loadAndNormalizeMenuConfigInTransaction(deptId));
    }

    // 调用方已持有科室菜单设置锁及事务。
    private MenuConfigPB loadAndNormalizeMenuConfigInTransaction(String deptId) {
        MenuConfigPB defaultMenuConfig = menuMeta.getConfig();
        Set<Integer> buildInItemIds = menuMeta.getBuiltinItemIds();

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
        boolean missingConfig = config == null;
        if (missingConfig) {
            config = defaultMenuConfig.toBuilder().setDeptId(deptId).build();
        }

        // 按原顺序保留首个有效项；内置项有重复时优先保留已分配项。
        Set<Integer> groupIds = MenuMeta.getGroupIds(config);
        Map<Integer, MenuItem> itemsByFunctionId = new LinkedHashMap<>();
        for (MenuItem item : config.getMenuItemList()) {
            MenuItem normalizedItem = normalizeMenuItem(
                item, buildInItemIds, groupIds, tempIdNameMap);
            if (normalizedItem == null) continue;
            MenuItem existing = itemsByFunctionId.get(normalizedItem.getFunctionId());
            if (existing == null || (normalizedItem.getType() == MenuMeta.MENU_ITEM_TYPE_BUILTIN
                && existing.getGroupId() <= 0 && normalizedItem.getGroupId() > 0)) {
                itemsByFunctionId.put(normalizedItem.getFunctionId(), normalizedItem);
            }
        }

        // 保留自动补齐行为；默认分组已不存在时，将补齐的内置项设为未分配。
        for (MenuItem item : defaultMenuConfig.getMenuItemList()) {
            if (itemsByFunctionId.containsKey(item.getFunctionId())) continue;
            if (item.getGroupId() > 0 && !groupIds.contains(item.getGroupId())) {
                item = item.toBuilder().setGroupId(-1).build();
            }
            itemsByFunctionId.put(item.getFunctionId(), item);
        }
        List<MenuItem> normalizedItems = new ArrayList<>(itemsByFunctionId.values());

        // 缺少配置或菜单项发生变化时保存；未分配模板列表只用于返回。
        if (missingConfig || !normalizedItems.equals(config.getMenuItemList())) {
            config = config.toBuilder()
                .clearMenuItem()
                .addAllMenuItem(normalizedItems)
                .clearUnassignedFormTemplate()
                .build();
            CurrentAccount account = userQuery.getCurrentAccount();
            settingsStore.saveMenuConfigPB(
                deptId, config, account == null ? "" : account.autoAcctId());
        }

        // 只统计最终保留菜单项引用的模板，失效菜单项不占用模板。
        Set<Integer> assignedTemplateIds = new HashSet<>();
        for (MenuItem item : normalizedItems) {
            if (item.getType() == MenuMeta.MENU_ITEM_TYPE_SEPARATED_FORM) {
                assignedTemplateIds.add(item.getSeparatedFormTemplate().getKey());
            } else if (item.getType() == MenuMeta.MENU_ITEM_TYPE_AGGREGATED_FORMS) {
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
        if (item.getType() == MenuMeta.MENU_ITEM_TYPE_BUILTIN) {
            boolean validGroup = item.getGroupId() <= 0 || groupIds.contains(item.getGroupId());
            return buildInItemIds.contains(item.getFunctionId()) && validGroup ? item : null;
        }

        // 表单菜单项必须使用表单功能 ID，且必须分配到现有菜单组。
        if (item.getFunctionId() < TEMPLATE_MENU_ITEM_START_ID
            || item.getGroupId() <= 0 || !groupIds.contains(item.getGroupId())) {
            return null;
        }
        if (item.getType() == MenuMeta.MENU_ITEM_TYPE_SEPARATED_FORM) {
            IntStrKvPB template = item.getSeparatedFormTemplate();
            String name = tempIdNameMap.get(template.getKey());
            if (name == null) return null;
            if (name.equals(template.getVal())) return item;
            return item.toBuilder()
                .setSeparatedFormTemplate(template.toBuilder().setVal(name))
                .build();
        }
        if (item.getType() == MenuMeta.MENU_ITEM_TYPE_AGGREGATED_FORMS) {
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

    // 验证请求中的未删除模板均存在，且属于 deptId。
    private Pair<StatusCode, Map<Integer, DragableFormTemplate>> validateDeptTemplates(
        String deptId, List<Integer> templateIds
    ) {
        Map<Integer, DragableFormTemplate> templates =
            jfkTemplateReader.getByIds(templateIds).stream()
                .collect(Collectors.toMap(DragableFormTemplate::getId, template -> template));

        for (Integer templateId : templateIds) {
            DragableFormTemplate template = templates.get(templateId);
            if (template == null) {
                return new Pair<>(StatusCode.FORM_TEMPLATE_NOT_FOUND, Map.of());
            }
            if (!deptId.equals(template.getDeptId())) {
                return new Pair<>(StatusCode.FORM_TEMPLATE_NOT_BELONG_TO_DEPT, Map.of());
            }
        }
        return new Pair<>(StatusCode.OK, templates);
    }

    // 验证 templateIds 是否可分配给 replacingFunctionId(原本就属于该菜单项，或在未分配模板中)
    private StatusCode validateAssignableTemplates(
        MenuConfigPB config,
        List<Integer> templateIds,
        Integer replacingFunctionId
    ) {
        if (new HashSet<>(templateIds).size() != templateIds.size()) {
            return StatusCode.INVALID_FORM_TEMPLATE_ID;
        }

        Set<Integer> allowedIds = new HashSet<>();
        for (IntStrKvPB template : config.getUnassignedFormTemplateList()) {
            allowedIds.add(template.getKey());
        }

        if (replacingFunctionId != null) {
            int index = MenuMeta.findMenuItemIndex(config, replacingFunctionId);
            if (index < 0) return StatusCode.MENU_ITEM_NOT_FOUND;

            MenuItem item = config.getMenuItem(index);
            if (item.getType() == MenuMeta.MENU_ITEM_TYPE_SEPARATED_FORM) {
                allowedIds.add(item.getSeparatedFormTemplate().getKey());
            } else if (item.getType() == MenuMeta.MENU_ITEM_TYPE_AGGREGATED_FORMS) {
                for (IntStrKvPB template : item.getAggregatedFormTemplateList()) {
                    allowedIds.add(template.getKey());
                }
            } else {
                return StatusCode.INVALID_MENU_ITEM_TYPE;
            }
        }

        for (Integer templateId : templateIds) {
            if (!allowedIds.contains(templateId)) {
                return StatusCode.FORM_TEMPLATE_NOT_AVAILABLE_FOR_ASSIGNMENT;
            }
        }
        return StatusCode.OK;
    }

    // 校验通过后调用，按请求顺序生成引用，使用查询得到的最新名称。
    private List<IntStrKvPB> buildTemplateRefs(
        List<Integer> templateIds,
        Map<Integer, DragableFormTemplate> templates
    ) {
        List<IntStrKvPB> refs = new ArrayList<>(templateIds.size());
        for (Integer templateId : templateIds) {
            DragableFormTemplate template = templates.get(templateId);
            refs.add(IntStrKvPB.newBuilder()
                .setKey(templateId)
                .setVal(template.getName())
                .build());
        }
        return refs;
    }

    private GenericResp executeMenuChange(
        String deptId, Function<MenuConfigPB, GenericResp> action
    ) {
        if (StrUtils.isBlank(deptId)) {
            return ReturnCodeUtils.genericResponse(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.DEPT_IS_EMPTY));
        }

        String resourceKey = LockKeys.deptSystemSettings(
            deptId, SettingsStore.GET_DEPT_MENU_SETTINGS);
        return lockExecutor.execute(List.of(resourceKey), () -> {
            MenuConfigPB config = loadAndNormalizeMenuConfigInTransaction(deptId);
            return action.apply(config);
        });
    }

    private void saveMenuConfig(String deptId, MenuConfigPB config) {
        MenuConfigPB persisted = config.toBuilder()
            .clearUnassignedFormTemplate()
            .build();

        CurrentAccount account = userQuery.getCurrentAccount();
        settingsStore.saveMenuConfigPB(
            deptId, persisted, account == null ? "" : account.autoAcctId());
    }

    private static final int TEMPLATE_MENU_ITEM_START_ID = 10000;

    private final MenuMeta menuMeta;
    private final List<String> statusCodeMsgList;
    private SettingsStore settingsStore;
    private JfkTemplateReader jfkTemplateReader;
    private UserQuery userQuery;
    private final LockExecutor lockExecutor;
}
