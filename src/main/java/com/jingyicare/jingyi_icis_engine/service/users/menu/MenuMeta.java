package com.jingyicare.jingyi_icis_engine.service.users.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.Menu;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.MenuConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.MenuGroup;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.MenuItem;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;

@Component
public class MenuMeta {
    //////// 工具函数
    public static final int MENU_ITEM_TYPE_BUILTIN = 0;
    public static final int MENU_ITEM_TYPE_SEPARATED_FORM = 1;
    public static final int MENU_ITEM_TYPE_AGGREGATED_FORMS = 2;

    public static Set<Integer> getGroupIds(MenuConfigPB config) {
        Set<Integer> groupIds = new HashSet<>();
        for (MenuGroup group : config.getMenuGroupList()) {
            collectGroupIds(group, groupIds);
        }
        return groupIds;
    }

    public static Set<Integer> getLeafGroupIds(MenuConfigPB config) {
        Set<Integer> leafGroupIds = new HashSet<>();
        for (MenuGroup group : config.getMenuGroupList()) {
            collectLeafGroupIds(group, leafGroupIds);
        }
        return leafGroupIds;
    }

    public static Set<String> getItemNames(MenuConfigPB config) {
        Set<String> existingNames = new HashSet<>();
        for (MenuItem item : config.getMenuItemList()) {
            existingNames.add(item.getName());
        }
        return existingNames;
    }

    public static List<MenuGroup> getMenuGroups(MenuConfigPB config) {
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

    // 返回第一个匹配项的下标，找不到返回 -1。
    public static int findMenuItemIndex(MenuConfigPB config, int functionId) {
        for (int index = 0; index < config.getMenuItemCount(); index++) {
            if (config.getMenuItem(index).getFunctionId() == functionId) {
                return index;
            }
        }
        return -1;
    }

    // 返回第一个匹配的内置菜单项
    public static MenuItem findBuiltinMenuItem(MenuConfigPB config, int functionId) {
        for (MenuItem item : config.getMenuItemList()) {
            if (item.getFunctionId() == functionId && item.getType() == MENU_ITEM_TYPE_BUILTIN) {
                return item;
            }
        }
        return null;
    }

    // 全局检查名称；excludedFunctionId 为 null 时，不排除任何菜单项。
    public static boolean hasItemName(
        MenuConfigPB config, String name, Integer excludedFunctionId
    ) {
        for (MenuItem item : config.getMenuItemList()) {
            if (excludedFunctionId != null
                && item.getFunctionId() == excludedFunctionId) {
                continue;
            }
            if (item.getName().equals(name)) return true;
        }
        return false;
    }

    private static void collectGroupIds(MenuGroup group, Set<Integer> groupIds /* OUT */) {
        groupIds.add(group.getGroupId());
        for (MenuGroup subGroup : group.getSubGroupList()) {
            collectGroupIds(subGroup, groupIds);
        }
    }

    private static void collectLeafGroupIds(MenuGroup group, Set<Integer> leafGroupIds /* OUT */) {
        if (group.getIsLeafGroup()) {
            leafGroupIds.add(group.getGroupId());
        } else {
            for (MenuGroup subGroup : group.getSubGroupList()) {
                collectLeafGroupIds(subGroup, leafGroupIds);
            }
        }
    }

    private static MenuGroup.Builder rebuildMenuGroup(
        MenuGroup group, Map<Integer, List<MenuItem>> itemsByGroupId
    ) {
        MenuGroup.Builder groupBuilder = MenuGroup.newBuilder(group);

        // 清空原有菜单项及子组，再按当前配置重建。
        groupBuilder.clearItems();
        groupBuilder.clearSubGroup();

        // 加入当前菜单组的菜单项。
        List<MenuItem> items = itemsByGroupId.getOrDefault(
            group.getGroupId(), Collections.emptyList());
        groupBuilder.addAllItems(items);

        // 递归重建子组。
        for (MenuGroup subGroup : group.getSubGroupList()) {
            MenuGroup.Builder subGroupBuilder = rebuildMenuGroup(subGroup, itemsByGroupId);
            groupBuilder.addSubGroup(subGroupBuilder);
        }

        return groupBuilder;
    }

    //////// 构造默认配置
    public MenuMeta(ProtoConfig protoConfig) {
        this.config = buildConfig(protoConfig.getConfig().getUser().getMenu());
        this.buildInItemIds = new HashSet<>();
        for (MenuItem item : this.config.getMenuItemList()) {
            this.buildInItemIds.add(item.getFunctionId());
        }
    }

    // 返回构造时生成的默认菜单配置，不设置科室 ID。
    public MenuConfigPB getConfig() {
        return config;
    }

    public Set<Integer> getBuiltinItemIds() {
        return buildInItemIds;
    }

    private MenuConfigPB buildConfig(Menu fullMenu) {
        MenuConfigPB.Builder configBuilder = MenuConfigPB.newBuilder();

        // 递归构建菜单组结构。
        for (MenuGroup group : fullMenu.getGroupList()) {
            MenuGroup.Builder groupBuilder = processMenuGroup(group);
            configBuilder.addMenuGroup(groupBuilder);
        }

        // 递归提取菜单项，单独保存到配置中。
        for (MenuGroup group : fullMenu.getGroupList()) {
            processMenuItems(group, configBuilder);
        }

        return configBuilder.build();
    }

    private MenuGroup.Builder processMenuGroup(MenuGroup group) {
        MenuGroup.Builder groupBuilder = MenuGroup.newBuilder(group);

        // 叶子菜单组仅保留分组信息。
        if (group.getIsLeafGroup()) {
            groupBuilder.clearItems();
        }

        // 递归重建子菜单组。
        groupBuilder.clearSubGroup();
        for (MenuGroup subGroup : group.getSubGroupList()) {
            MenuGroup.Builder subGroupBuilder = processMenuGroup(subGroup);
            groupBuilder.addSubGroup(subGroupBuilder);
        }

        return groupBuilder;
    }

    private void processMenuItems(
        MenuGroup group, MenuConfigPB.Builder configBuilder /* OUT */
    ) {
        for (MenuItem item : group.getItemsList()) {
            MenuItem.Builder itemBuilder = MenuItem.newBuilder(item);
            itemBuilder.setGroupId(group.getGroupId());
            itemBuilder.setType(0);
            configBuilder.addMenuItem(itemBuilder);
        }

        for (MenuGroup subGroup : group.getSubGroupList()) {
            processMenuItems(subGroup, configBuilder);
        }
    }

    private final MenuConfigPB config;
    private final Set<Integer> buildInItemIds;
}
