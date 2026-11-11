package com.jingyicare.jingyi_icis_engine.service.users.menu;

import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.Set;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.Menu;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.MenuConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.MenuGroup;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.MenuItem;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;

@Component
public class MenuMeta {
    //////// 工具函数
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

    public Set<Integer> getBuildInItemIds() {
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
