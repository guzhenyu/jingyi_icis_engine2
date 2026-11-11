package com.jingyicare.jingyi_icis_engine.service.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.jingyicare.jingyi_icis_engine.entity.reports.DragableFormTemplate;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDeptMenuConfigResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.Text;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.Menu;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.MenuConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.MenuGroup;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.MenuItem;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.UserConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.IntStrKvPB;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkTemplateReader;
import com.jingyicare.jingyi_icis_engine.service.settings.SettingsStore;
import com.jingyicare.jingyi_icis_engine.service.users.menu.MenuMeta;
import com.jingyicare.jingyi_icis_engine.service.users.menu.MenuService;

class MenuServiceTests {
    @BeforeEach
    void setUp() {
        ProtoConfig protoConfig = mock(ProtoConfig.class);
        MenuGroup leaf = MenuGroup.newBuilder().setGroupId(GROUP).setIsLeafGroup(true)
            .addItems(MenuItem.newBuilder().setFunctionId(10).setName("内置一"))
            .addItems(MenuItem.newBuilder().setFunctionId(11).setName("内置二"))
            .build();
        MenuGroup root = MenuGroup.newBuilder().setGroupId(ROOT).addSubGroup(leaf).build();
        when(protoConfig.getConfig()).thenReturn(Config.newBuilder()
            .setText(Text.newBuilder().addAllStatusCodeMsg(
                Collections.nCopies(StatusCode.LAST_CODE_VALUE, "message")))
            .setUser(UserConfigPB.newBuilder().setMenu(Menu.newBuilder().addGroup(root))).build());
        menuMeta = new MenuMeta(protoConfig);
        baseConfig = menuMeta.getConfig().toBuilder().setDeptId(DEPT).build();
        store = mock(SettingsStore.class);
        JfkTemplateReader reader = mock(JfkTemplateReader.class);
        when(reader.getByDeptId(DEPT)).thenReturn(List.of(
            DragableFormTemplate.builder().id(501).name("模板一").build(),
            DragableFormTemplate.builder().id(502).name("模板二").build(),
            DragableFormTemplate.builder().id(503).name("模板三").build()));
        userQuery = mock(UserQuery.class);
        when(userQuery.getAccountAutoId()).thenReturn("42");
        service = new MenuService(protoConfig, menuMeta, store, reader, userQuery);
    }

    @Test
    void missingConfigurationSavesDefaultsAndReturnsAllTemplatesAsUnassigned() {
        MenuConfigPB actual = service.loadAndNormalizeMenuConfig(DEPT);

        assertThat(actual.getMenuItemList()).isEqualTo(baseConfig.getMenuItemList());
        assertThat(actual.getDeptId()).isEqualTo(DEPT);
        assertThat(actual.getUnassignedFormTemplateList()).containsExactly(
            template(501, "模板一"), template(502, "模板二"), template(503, "模板三"));
        assertThat(savedConfig()).isEqualTo(baseConfig);
    }

    @Test
    void builtinItemsAcceptUnassignedGroupsAndRejectInvalidIdsGroupsAndTypes() {
        MenuConfigPB expected = baseConfig.toBuilder()
            .setMenuItem(0, baseConfig.getMenuItem(0).toBuilder().setGroupId(0))
            .setMenuItem(1, baseConfig.getMenuItem(1).toBuilder().setGroupId(-7))
            .build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(expected.toBuilder()
            .addMenuItem(MenuItem.newBuilder().setFunctionId(999).setGroupId(GROUP))
            .addMenuItem(MenuItem.newBuilder().setFunctionId(10).setGroupId(999))
            .addMenuItem(separated(10000, GROUP, 501, "模板一").toBuilder().setType(3))
            .addMenuItem(MenuItem.newBuilder().setFunctionId(10).setGroupId(GROUP).setType(-1))
            .build());

        MenuConfigPB actual = service.loadAndNormalizeMenuConfig(DEPT);

        assertThat(actual.getMenuItemList()).isEqualTo(expected.getMenuItemList());
        assertThat(savedConfig()).isEqualTo(expected);
        assertThat(actual.getUnassignedFormTemplateCount()).isEqualTo(3);
    }

    @Test
    void formItemsRequireReservedFunctionIdsAndExistingGroupsIncludingNestedGroups() {
        MenuItem separated = separated(10000, GROUP, 501, "模板一");
        MenuItem aggregated = aggregated(10001, ROOT, template(502, "模板二"));
        MenuConfigPB.Builder input = baseConfig.toBuilder()
            .addMenuGroup(MenuGroup.newBuilder().setGroupId(0).setIsLeafGroup(true))
            .addMenuItem(separated).addMenuItem(aggregated)
            .addMenuItem(separated(9999, GROUP, 503, "模板三"))
            .addMenuItem(aggregated(9999, GROUP, template(503, "模板三")));
        for (int groupId : new int[] {0, -1, 999}) {
            input.addMenuItem(separated(10002, groupId, 503, "模板三"));
            input.addMenuItem(aggregated(10003, groupId, template(503, "模板三")));
        }
        when(store.getMenuConfigPB(DEPT)).thenReturn(input.build());

        MenuConfigPB actual = service.loadAndNormalizeMenuConfig(DEPT);

        assertThat(actual.getMenuItemList()).containsExactlyElementsOf(baseConfig.toBuilder()
            .addMenuItem(separated).addMenuItem(aggregated).build().getMenuItemList());
        assertThat(actual.getUnassignedFormTemplateList()).containsExactly(template(503, "模板三"));
        assertThat(savedConfig().getMenuItemList()).isEqualTo(actual.getMenuItemList());
    }

    @Test
    void separatedItemsRefreshTemplateNamesAndPreserveOtherMenuFields() {
        MenuItem item = separated(10000, GROUP, 501, "旧名称").toBuilder()
            .setName("自定义菜单").setDisplayName("自定义显示名").setDisabled(true).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .addMenuItem(item)
            .addMenuItem(separated(10001, GROUP, 999, "已失效"))
            .addUnassignedFormTemplate(template(999, "旧未分配项"))
            .build());

        MenuConfigPB actual = service.loadAndNormalizeMenuConfig(DEPT);

        assertThat(actual.getMenuItem(2)).isEqualTo(item.toBuilder()
            .setSeparatedFormTemplate(template(501, "模板一")).build());
        assertThat(actual.getMenuItemCount()).isEqualTo(3);
        assertThat(actual.getUnassignedFormTemplateList()).containsExactly(
            template(502, "模板二"), template(503, "模板三"));
        assertThat(savedConfig().getUnassignedFormTemplateList()).isEmpty();
    }

    @Test
    void aggregatedItemsDropInvalidTemplatesRefreshNamesAndKeepOrderAndDuplicates() {
        MenuItem item = aggregated(10000, GROUP,
            template(502, "旧名称"), template(999, "已失效"),
            template(501, "模板一"), template(502, "旧名称"));
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .addMenuItem(item)
            .addMenuItem(aggregated(10001, GROUP, template(999, "全部失效")))
            .addMenuItem(aggregated(10002, GROUP))
            .build());

        MenuConfigPB actual = service.loadAndNormalizeMenuConfig(DEPT);

        assertThat(actual.getMenuItemCount()).isEqualTo(3);
        assertThat(actual.getMenuItem(2).getAggregatedFormTemplateList()).containsExactly(
            template(502, "模板二"), template(501, "模板一"), template(502, "模板二"));
        assertThat(actual.getUnassignedFormTemplateList()).containsExactly(template(503, "模板三"));
        assertThat(savedConfig().getMenuItemList()).isEqualTo(actual.getMenuItemList());
    }

    @Test
    void unchangedItemsDoNotWriteAndUnassignedTemplatesAreRecomputedWithoutDuplicates() {
        MenuConfigPB input = baseConfig.toBuilder()
            .addMenuItem(separated(10000, GROUP, 501, "模板一"))
            .addMenuItem(aggregated(10001, GROUP, template(502, "模板二")))
            .addUnassignedFormTemplate(template(501, "过期未分配项"))
            .addUnassignedFormTemplate(template(503, "模板三"))
            .build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(input);

        MenuConfigPB actual = service.loadAndNormalizeMenuConfig(DEPT);

        assertThat(actual.getMenuItemList()).isEqualTo(input.getMenuItemList());
        assertThat(actual.getUnassignedFormTemplateList()).containsExactly(template(503, "模板三"));
        assertThat(service.loadAndNormalizeMenuConfig(DEPT)).isEqualTo(actual);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
        verifyNoInteractions(userQuery);
    }

    @Test
    void missingBuiltinItemsAreAppendedAndBecomeUnassignedIfTheirDefaultGroupIsMissing() {
        MenuItem form = separated(10000, 50, 501, "模板一");
        MenuItem builtin = baseConfig.getMenuItem(1).toBuilder().setGroupId(0).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .clearMenuGroup()
            .addMenuGroup(MenuGroup.newBuilder().setGroupId(50).setIsLeafGroup(true))
            .clearMenuItem().addMenuItem(form).addMenuItem(builtin).build());

        MenuConfigPB actual = service.loadAndNormalizeMenuConfig(DEPT);

        assertThat(actual.getMenuItemList()).containsExactly(form, builtin,
            menuMeta.getConfig().getMenuItem(0).toBuilder().setGroupId(-1).build());
        MenuConfigPB saved = savedConfig();
        when(store.getMenuConfigPB(DEPT)).thenReturn(saved);
        assertThat(service.loadAndNormalizeMenuConfig(DEPT)).isEqualTo(actual);
        verify(store).saveMenuConfigPB(eq(DEPT), any(), eq("42"));
    }

    @Test
    void builtinItemWithInvalidGroupIsRemovedBeforeMissingDefaultsAreRestored() {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .setMenuItem(0, baseConfig.getMenuItem(0).toBuilder()
                .setGroupId(999).setName("非法分组中的菜单项"))
            .build());

        MenuConfigPB actual = service.loadAndNormalizeMenuConfig(DEPT);

        assertThat(actual.getMenuItemList()).containsExactly(
            baseConfig.getMenuItem(1), baseConfig.getMenuItem(0));
        assertThat(savedConfig().getMenuItemList()).isEqualTo(actual.getMenuItemList());
    }

    @Test
    void zeroGroupBuiltinItemsAppearAsUnassignedInMenuResponse() {
        MenuItem unassigned = baseConfig.getMenuItem(0).toBuilder().setGroupId(0).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .setMenuItem(0, unassigned).build());

        GetDeptMenuConfigResp response = service.getDeptMenuConfig(
            "{\"deptId\":\"" + DEPT + "\"}");

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(response.getUnassignedMenuItemList()).containsExactly(unassigned);
        assertThat(response.getMenuGroup(0).getSubGroup(0).getItemsList())
            .containsExactly(baseConfig.getMenuItem(1));
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @Test
    void zeroGroupBuiltinItemsCannotBeRenamed() {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .setMenuItem(0, baseConfig.getMenuItem(0).toBuilder().setGroupId(0)).build());

        GenericResp response = service.updateMenuItemName(
            "{\"deptId\":\"" + DEPT + "\",\"functionId\":10,\"newName\":\"新名称\"}");

        assertThat(response.getRt().getCode())
            .isEqualTo(StatusCode.CANNOT_RENAME_UNASSIGNED_MENU_ITEM_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @Test
    void zeroGroupDoesNotReceiveItemsEvenIfTheConfigurationContainsAGroupWithIdZero() {
        MenuConfigPB input = baseConfig.toBuilder()
            .addMenuGroup(MenuGroup.newBuilder().setGroupId(0).setIsLeafGroup(true))
            .setMenuItem(0, baseConfig.getMenuItem(0).toBuilder().setGroupId(0)).build();

        List<MenuGroup> groups = service.getMenuGroups(input);

        assertThat(groups.get(1).getItemsList()).isEmpty();
        assertThat(groups.get(0).getSubGroup(0).getItemsList())
            .containsExactly(baseConfig.getMenuItem(1));
    }

    private MenuConfigPB savedConfig() {
        ArgumentCaptor<MenuConfigPB> captor = ArgumentCaptor.forClass(MenuConfigPB.class);
        verify(store).saveMenuConfigPB(eq(DEPT), captor.capture(), eq("42"));
        return captor.getValue();
    }

    private MenuItem separated(int functionId, int groupId, int templateId, String name) {
        return MenuItem.newBuilder().setFunctionId(functionId).setGroupId(groupId).setType(1)
            .setSeparatedFormTemplate(template(templateId, name)).build();
    }

    private MenuItem aggregated(int functionId, int groupId, IntStrKvPB... templates) {
        return MenuItem.newBuilder().setFunctionId(functionId).setGroupId(groupId).setType(2)
            .addAllAggregatedFormTemplate(Arrays.asList(templates)).build();
    }

    private IntStrKvPB template(int id, String name) {
        return IntStrKvPB.newBuilder().setKey(id).setVal(name).build();
    }

    private static final String DEPT = "menu-normalization-tests";
    private static final int ROOT = 1;
    private static final int GROUP = 2;

    private MenuMeta menuMeta;
    private MenuConfigPB baseConfig;
    private SettingsStore store;
    private UserQuery userQuery;
    private MenuService service;
}
