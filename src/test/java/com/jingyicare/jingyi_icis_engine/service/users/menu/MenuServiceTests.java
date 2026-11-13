package com.jingyicare.jingyi_icis_engine.service.users.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.jingyicare.jingyi_icis_engine.entity.reports.DragableFormTemplate;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddMenuItemFormTemplateReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddMenuItemReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeleteMenuItemFormTemplateReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeleteMenuItemReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDeptMenuConfigResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetMenuItemReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetMenuItemResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.ReorderMenuItemFormTemplatesReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.ReorderMenuItemsReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.UpdateMenuItemNameReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.UpdateMenuItemWithOneFormReq;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTemplatePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.Text;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.Menu;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.MenuConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.MenuGroup;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.MenuItem;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.UserConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.IntStrKvPB;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkTemplateReader;
import com.jingyicare.jingyi_icis_engine.service.settings.SettingsStore;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class MenuServiceTests {
    @BeforeEach
    void setUp() {
        protoConfig = mock(ProtoConfig.class);
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
        reader = mock(JfkTemplateReader.class);
        List<DragableFormTemplate> templates = List.of(
            DragableFormTemplate.builder().id(501).deptId(DEPT).name("模板一").build(),
            DragableFormTemplate.builder().id(502).deptId(DEPT).name("模板二").build(),
            DragableFormTemplate.builder().id(503).deptId(DEPT).name("模板三").build());
        when(reader.getByDeptId(DEPT)).thenReturn(templates);
        when(reader.getByIds(any())).thenAnswer(invocation -> {
            List<Integer> ids = invocation.getArgument(0);
            return templates.stream().filter(template -> ids.contains(template.getId())).toList();
        });
        userQuery = mock(UserQuery.class);
        when(userQuery.getCurrentAccount())
            .thenReturn(new CurrentAccount("menu-user", "42", "菜单用户"));
        lockExecutor = mock(LockExecutor.class);
        when(lockExecutor.execute(anyCollection(), any())).thenAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(1);
            return action.get();
        });
        service = new MenuService(protoConfig, menuMeta, store, reader, userQuery, lockExecutor);
    }

    @Test
    void missingConfigurationSavesDefaultsAndReturnsAllTemplatesAsUnassigned() {
        MenuConfigPB actual = service.loadAndNormalizeMenuConfig(DEPT);

        assertThat(actual.getMenuItemList()).isEqualTo(baseConfig.getMenuItemList());
        assertThat(actual.getDeptId()).isEqualTo(DEPT);
        assertThat(actual.getUnassignedFormTemplateList()).containsExactly(
            template(501, "模板一"), template(502, "模板二"), template(503, "模板三"));
        assertThat(savedConfig()).isEqualTo(baseConfig);
        verify(lockExecutor).execute(
            eq(List.of(LockKeys.deptSystemSettings(DEPT, SettingsStore.GET_DEPT_MENU_SETTINGS))),
            any());
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateFunctionIdsKeepTheFirstValidItemAndReleaseDiscardedTemplates(
        boolean keepSeparated
    ) {
        MenuItem kept = keepSeparated ? separated(10000, GROUP, 501, "模板一")
            : aggregated(10000, GROUP, template(501, "模板一"));
        kept = kept.toBuilder().setName("保留菜单").setDisabled(true).build();
        MenuItem discarded = keepSeparated ? aggregated(10000, GROUP, template(502, "模板二"))
            : separated(10000, GROUP, 502, "模板二");
        MenuItem other = separated(10001, GROUP, 503, "模板三");
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .addMenuItem(separated(10000, 999, 502, "非法分组"))
            .addMenuItem(kept).addMenuItem(other).addMenuItem(discarded)
            .addMenuItem(kept.toBuilder().setName("重复副本"))
            .addUnassignedFormTemplate(template(501, "旧未分配项")).build());

        MenuConfigPB actual = service.loadAndNormalizeMenuConfig(DEPT);

        assertThat(actual.getMenuItemList()).containsExactly(
            baseConfig.getMenuItem(0), baseConfig.getMenuItem(1), kept, other);
        assertThat(actual.getMenuItemList()).extracting(MenuItem::getFunctionId)
            .doesNotHaveDuplicates();
        assertThat(actual.getUnassignedFormTemplateList()).containsExactly(template(502, "模板二"));
        MenuConfigPB saved = savedConfig();
        assertThat(saved.getMenuItemList()).isEqualTo(actual.getMenuItemList());
        assertThat(saved.getUnassignedFormTemplateList()).isEmpty();
        when(store.getMenuConfigPB(DEPT)).thenReturn(saved);
        assertThat(service.loadAndNormalizeMenuConfig(DEPT)).isEqualTo(actual);
        verify(store).saveMenuConfigPB(eq(DEPT), any(), eq("42"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateBuiltinIdsPreferTheFirstAssignedCopyAndKeepTheOriginalPosition(
        boolean assignedFirst
    ) {
        MenuItem unassigned = baseConfig.getMenuItem(0).toBuilder().setGroupId(0)
            .setName("未分配副本").build();
        MenuItem assigned = baseConfig.getMenuItem(0).toBuilder()
            .setName("已分配副本").setDisplayName("原显示名").setDisabled(true).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .setMenuItem(0, assignedFirst ? assigned : unassigned)
            .addMenuItem(assignedFirst ? unassigned : assigned)
            .addMenuItem(assigned.toBuilder().setGroupId(ROOT).setName("后续已分配副本"))
            .build());

        MenuConfigPB actual = service.loadAndNormalizeMenuConfig(DEPT);

        assertThat(actual.getMenuItemList()).containsExactly(assigned, baseConfig.getMenuItem(1));
        assertThat(savedConfig().getMenuItemList()).isEqualTo(actual.getMenuItemList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defaultBuiltinDuplicatesAreRemovedForNewAndExistingConfigurations(
        boolean existingConfig
    ) {
        MenuMeta duplicateMeta = mock(MenuMeta.class);
        when(duplicateMeta.getConfig()).thenReturn(baseConfig.toBuilder()
            .addMenuItem(baseConfig.getMenuItem(0).toBuilder().setName("重复默认项")).build());
        when(duplicateMeta.getBuiltinItemIds()).thenReturn(menuMeta.getBuiltinItemIds());
        service = new MenuService(protoConfig, duplicateMeta, store, reader,
            userQuery, lockExecutor);
        if (existingConfig) {
            when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
                .clearMenuItem().build());
        }

        MenuConfigPB actual = service.loadAndNormalizeMenuConfig(DEPT);

        assertThat(actual.getMenuItemList()).isEqualTo(baseConfig.getMenuItemList());
        assertThat(savedConfig()).isEqualTo(baseConfig);
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

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void unassignedBuiltinItemsCannotBeRenamed(int groupId) {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .setMenuItem(0, baseConfig.getMenuItem(0).toBuilder().setGroupId(groupId)).build());

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

        List<MenuGroup> groups = MenuMeta.getMenuGroups(input);

        assertThat(groups.get(1).getItemsList()).isEmpty();
        assertThat(groups.get(0).getSubGroup(0).getItemsList())
            .containsExactly(baseConfig.getMenuItem(1));
    }

    @Test
    void malformedMenuItemRequestReturnsParseErrorBeforeAccessingSettings() {
        GetMenuItemResp response = service.getMenuItem("{bad");

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.PARSE_JSON_FAILED_VALUE);
        verifyNoInteractions(lockExecutor, store);
    }

    @Test
    void missingMenuItemReturnsItsOwnErrorCode() {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig);

        GetMenuItemResp response = getMenuItem(99999);

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.MENU_ITEM_NOT_FOUND_VALUE);
        verify(reader, never()).getById(anyInt());
        verify(reader, never()).getByIds(any());
    }

    @Test
    void builtinMenuItemDoesNotFetchFormTemplateContents() {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig);

        GetMenuItemResp response = getMenuItem(10);

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(response.getMenuItem()).isEqualTo(baseConfig.getMenuItem(0));
        assertThat(response.getTemplateList()).isEmpty();
        verify(reader, never()).getById(anyInt());
        verify(reader, never()).getByIds(any());
    }

    @Test
    void separatedMenuItemReturnsItsDecodedTemplate() {
        MenuItem item = separated(10000, GROUP, 501, "模板一");
        when(store.getMenuConfigPB(DEPT))
            .thenReturn(baseConfig.toBuilder().addMenuItem(item).build());
        JfkTemplatePB template = JfkTemplatePB.newBuilder().setId(501).setName("模板一").build();
        when(reader.getById(501)).thenReturn(DragableFormTemplate.builder().id(501)
            .templatePb(ProtoUtils.encodeJfkTemplate(template)).build());

        GetMenuItemResp response = getMenuItem(10000);

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(response.getMenuItem()).isEqualTo(item);
        assertThat(response.getTemplateList()).containsExactly(template);
    }

    @Test
    void aggregatedMenuItemKeepsReaderOrderAndReturnsTheTemplatesStillAvailable() {
        MenuItem item = aggregated(10000, GROUP,
            template(501, "模板一"), template(502, "模板二"), template(503, "模板三"));
        when(store.getMenuConfigPB(DEPT))
            .thenReturn(baseConfig.toBuilder().addMenuItem(item).build());
        JfkTemplatePB first = JfkTemplatePB.newBuilder().setId(501).build();
        JfkTemplatePB second = JfkTemplatePB.newBuilder().setId(502).build();
        when(reader.getByIds(List.of(501, 502, 503))).thenReturn(List.of(
            DragableFormTemplate.builder().id(502)
                .templatePb(ProtoUtils.encodeJfkTemplate(second)).build(),
            DragableFormTemplate.builder().id(501)
                .templatePb(ProtoUtils.encodeJfkTemplate(first)).build()));

        GetMenuItemResp response = getMenuItem(10000);

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(response.getMenuItem()).isEqualTo(item);
        assertThat(response.getTemplateList()).containsExactly(second, first);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void formMenuItemsReportTemplatesThatDisappearAfterNormalization(int type) {
        MenuItem item = type == 1 ? separated(10000, GROUP, 501, "模板一")
            : aggregated(10000, GROUP, template(501, "模板一"));
        when(store.getMenuConfigPB(DEPT))
            .thenReturn(baseConfig.toBuilder().addMenuItem(item).build());
        when(reader.getByIds(List.of(501))).thenReturn(List.of());

        GetMenuItemResp response = getMenuItem(10000);

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.FORM_TEMPLATE_NOT_FOUND_VALUE);
        assertThat(response.getTemplateList()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void corruptFormTemplatesReturnInvalidTemplateWithoutPartialData(int type) {
        MenuItem item = type == 1 ? separated(10000, GROUP, 501, "模板一")
            : aggregated(10000, GROUP, template(502, "模板二"), template(501, "模板一"));
        when(store.getMenuConfigPB(DEPT))
            .thenReturn(baseConfig.toBuilder().addMenuItem(item).build());
        DragableFormTemplate broken = DragableFormTemplate.builder()
            .id(501).templatePb("broken!").build();
        JfkTemplatePB valid = JfkTemplatePB.newBuilder().setId(502).build();
        when(reader.getById(501)).thenReturn(broken);
        when(reader.getByIds(List.of(502, 501))).thenReturn(List.of(
            DragableFormTemplate.builder().id(502)
                .templatePb(ProtoUtils.encodeJfkTemplate(valid)).build(),
            broken));

        GetMenuItemResp response = getMenuItem(10000);

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.INVALID_FORM_TEMPLATE_VALUE);
        assertThat(response.getTemplateList()).isEmpty();
        assertThat(response.hasMenuItem()).isFalse();
    }

    @Test
    void malformedAddRequestDoesNotAcquireALock() {
        assertThat(service.addMenuItem("{bad").getRt().getCode())
            .isEqualTo(StatusCode.PARSE_JSON_FAILED_VALUE);
        verifyNoInteractions(lockExecutor, store);
    }

    @Test
    void missingDepartmentOrMenuItemIsRejectedBeforeLocking() {
        AddMenuItemReq blankDepartment = AddMenuItemReq.newBuilder().setDeptId(" ")
            .setMenuItem(baseConfig.getMenuItem(0)).build();
        AddMenuItemReq missingItem = AddMenuItemReq.newBuilder().setDeptId(DEPT).build();

        assertThat(service.addMenuItem(ProtoUtils.protoToJson(blankDepartment)).getRt().getCode())
            .isEqualTo(StatusCode.DEPT_IS_EMPTY_VALUE);
        assertThat(service.addMenuItem(ProtoUtils.protoToJson(missingItem)).getRt().getCode())
            .isEqualTo(StatusCode.INVALID_MENU_ITEM_TYPE_VALUE);
        assertThat(service.addMenuItem("{}").getRt().getCode())
            .isEqualTo(StatusCode.DEPT_IS_EMPTY_VALUE);
        verifyNoInteractions(lockExecutor, store);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void assigningBuiltinOnlyChangesItsGroupAndPreservesOriginalProperties(int oldGroupId) {
        MenuItem original = baseConfig.getMenuItem(0).toBuilder().setGroupId(oldGroupId)
            .setName("原名称").setDisplayName("原显示名").setDisabled(true).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .setMenuItem(0, original).build());
        MenuItem requested = original.toBuilder().setGroupId(GROUP)
            .setName(baseConfig.getMenuItem(1).getName())
            .setDisplayName("客户端显示名").setDisabled(false).build();

        assertThat(addMenuItem(requested).getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);

        MenuConfigPB saved = savedConfig();
        assertThat(saved.getMenuItemList()).containsExactly(baseConfig.getMenuItem(1),
            original.toBuilder().setGroupId(GROUP).build());
        assertThat(saved.getUnassignedFormTemplateList()).isEmpty();
        verify(lockExecutor).execute(
            eq(List.of(LockKeys.deptSystemSettings(DEPT, SettingsStore.GET_DEPT_MENU_SETTINGS))),
            any());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void assignedBuiltinCannotBeAddedToTheSameOrAnotherGroup(int targetGroupId) {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .addMenuGroup(MenuGroup.newBuilder().setGroupId(3).setIsLeafGroup(true)).build());
        MenuItem requested = baseConfig.getMenuItem(0).toBuilder().setGroupId(targetGroupId)
            .setName("客户端覆盖名称").build();

        assertThat(addMenuItem(requested).getRt().getCode())
            .isEqualTo(StatusCode.MENU_ITEM_ALREADY_ASSIGNED_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @Test
    void builtinAssignmentChecksItsOriginalNameForGlobalDuplicates() {
        MenuItem original = baseConfig.getMenuItem(0).toBuilder().setGroupId(-1)
            .setName(baseConfig.getMenuItem(1).getName()).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .setMenuItem(0, original).build());
        MenuItem requested = original.toBuilder().setGroupId(GROUP)
            .setName("客户端提供的不重复名称").build();

        assertThat(addMenuItem(requested).getRt().getCode())
            .isEqualTo(StatusCode.MENU_ITEM_NAME_EXISTS_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @Test
    void duplicateNamesAreStillRejectedAcrossDifferentGroups() {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .addMenuGroup(MenuGroup.newBuilder().setGroupId(3).setIsLeafGroup(true)).build());
        MenuItem requested = separated(0, 3, 501, "模板一").toBuilder()
            .setName(baseConfig.getMenuItem(1).getName()).build();

        assertThat(addMenuItem(requested).getRt().getCode())
            .isEqualTo(StatusCode.MENU_ITEM_NAME_EXISTS_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @Test
    void builtinAssignmentUsesTheNormalizedUniqueItemAndPreservesItsProperties() {
        MenuItem requested = baseConfig.getMenuItem(0);
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .setMenuItem(0, requested.toBuilder().setGroupId(-1))
            .addMenuItem(requested.toBuilder().setGroupId(0).setName("重复旧副本")).build());

        assertThat(addMenuItem(requested).getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);

        ArgumentCaptor<MenuConfigPB> captor = ArgumentCaptor.forClass(MenuConfigPB.class);
        verify(store, times(2)).saveMenuConfigPB(eq(DEPT), captor.capture(), eq("42"));
        assertThat(captor.getAllValues().get(0).getMenuItemList()).containsExactly(
            requested.toBuilder().setGroupId(-1).build(), baseConfig.getMenuItem(1));
        assertThat(captor.getAllValues().get(1).getMenuItemList())
            .containsExactly(baseConfig.getMenuItem(1), requested);
    }

    @Test
    void assignedBuiltinCopiesCannotBeOverwrittenThroughAnUnassignedCopy() {
        MenuItem requested = baseConfig.getMenuItem(0);
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .setMenuItem(0, requested.toBuilder().setGroupId(-1))
            .addMenuItem(requested).build());

        assertThat(addMenuItem(requested).getRt().getCode())
            .isEqualTo(StatusCode.MENU_ITEM_ALREADY_ASSIGNED_VALUE);
        assertThat(savedConfig().getMenuItemList())
            .containsExactly(requested, baseConfig.getMenuItem(1));
    }

    @Test
    void registeredBuiltinMissingFromTheNormalizedConfigReturnsNotFound() {
        MenuMeta incompleteMeta = mock(MenuMeta.class);
        when(incompleteMeta.getConfig()).thenReturn(baseConfig);
        when(incompleteMeta.getBuiltinItemIds()).thenReturn(Set.of(10, 11, 12));
        service = new MenuService(protoConfig, incompleteMeta, store, reader,
            userQuery, lockExecutor);
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig);
        MenuItem requested = MenuItem.newBuilder().setFunctionId(12).setGroupId(GROUP).build();

        assertThat(addMenuItem(requested).getRt().getCode())
            .isEqualTo(StatusCode.MENU_ITEM_NOT_FOUND_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 999})
    void addedItemsMustBelongToAnExistingPositiveLeafGroup(int groupId) {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig);

        assertThat(addMenuItem(separated(0, groupId, 501, "模板一")).getRt().getCode())
            .isEqualTo(StatusCode.INVALID_MENU_GROUP_ID_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 3})
    void unsupportedMenuItemTypesCannotBeAdded(int type) {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig);
        MenuItem requested = MenuItem.newBuilder().setGroupId(GROUP).setType(type)
            .setName("非法类型菜单").build();

        assertThat(addMenuItem(requested).getRt().getCode())
            .isEqualTo(StatusCode.INVALID_MENU_ITEM_TYPE_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void formItemsCannotBeOverwrittenByPretendingToBeBuiltinItems(int type) {
        MenuItem original = type == 1 ? separated(10000, GROUP, 501, "模板一")
            : aggregated(10000, GROUP, template(501, "模板一"));
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .addMenuItem(original).build());
        MenuItem requested = MenuItem.newBuilder().setFunctionId(10000)
            .setGroupId(GROUP).setType(0).setName("伪装的内置项").build();

        assertThat(addMenuItem(requested).getRt().getCode())
            .isEqualTo(StatusCode.MENU_ITEM_NOT_FOUND_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @Test
    void separatedItemsGetTheNextFunctionIdAndTheCurrentTemplateName() {
        MenuItem existing = aggregated(10004, GROUP, template(502, "模板二"));
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .addMenuItem(existing).build());
        MenuItem requested = separated(77, GROUP, 501, "过期模板名").toBuilder()
            .setName("新增独立菜单").setDisplayName("显示名").setDisabled(true).build();

        assertThat(addMenuItem(requested).getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);

        assertThat(savedConfig().getMenuItemList()).containsExactly(
            baseConfig.getMenuItem(0), baseConfig.getMenuItem(1), existing,
            requested.toBuilder().setFunctionId(10005)
                .setSeparatedFormTemplate(template(501, "模板一")).build());
        verify(reader).getByIds(List.of(501));
    }

    @Test
    void aggregatedItemsKeepTemplateOrderAndSynchronizeNamesBeforeSaving() {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig);
        MenuItem requested = aggregated(77, GROUP,
            template(503, "过期三"), template(501, "过期一")).toBuilder().setName("新增聚合菜单").build();

        assertThat(addMenuItem(requested).getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);

        MenuConfigPB saved = savedConfig();
        assertThat(saved.getMenuItem(2)).isEqualTo(requested.toBuilder().setFunctionId(10000)
            .clearAggregatedFormTemplate().addAggregatedFormTemplate(template(503, "模板三"))
            .addAggregatedFormTemplate(template(501, "模板一")).build());
        assertThat(saved.getUnassignedFormTemplateList()).isEmpty();
        verify(reader).getByIds(List.of(503, 501));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disappearedOrMovedTemplatesKeepTheExistingAddErrorCode(boolean movedDepartment) {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig);
        List<DragableFormTemplate> queried = movedDepartment
            ? List.of(DragableFormTemplate.builder().id(501).deptId("other-dept")
                .name("其他科室模板").build())
            : List.of();
        when(reader.getByIds(List.of(501))).thenReturn(queried);
        MenuItem requested = separated(0, GROUP, 501, "旧模板名").toBuilder()
            .setName("新增菜单").build();

        assertThat(addMenuItem(requested).getRt().getCode())
            .isEqualTo(StatusCode.INVALID_FORM_TEMPLATE_ID_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void mixedTemplateFieldsAreIgnoredOrClearedAccordingToMenuItemType(int type) {
        MenuItem original = baseConfig.getMenuItem(0).toBuilder().setGroupId(-1).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .setMenuItem(0, original).build());
        MenuItem requested = MenuItem.newBuilder().setType(type)
            .setFunctionId(type == MenuMeta.MENU_ITEM_TYPE_BUILTIN ? 10 : 0)
            .setGroupId(GROUP).setName("混合字段菜单")
            .setSeparatedFormTemplate(template(
                type == MenuMeta.MENU_ITEM_TYPE_SEPARATED_FORM ? 501 : 999, "客户端旧名称"))
            .addAggregatedFormTemplate(template(
                type == MenuMeta.MENU_ITEM_TYPE_AGGREGATED_FORMS ? 502 : 998, "客户端旧名称二"))
            .addAggregatedFormTemplate(template(
                type == MenuMeta.MENU_ITEM_TYPE_AGGREGATED_FORMS ? 503 : 997, "客户端旧名称三"))
            .build();

        assertThat(addMenuItem(requested).getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);

        MenuConfigPB saved = savedConfig();
        MenuItem added = saved.getMenuItem(saved.getMenuItemCount() - 1);
        assertThat(saved.getUnassignedFormTemplateList()).isEmpty();
        if (type == MenuMeta.MENU_ITEM_TYPE_BUILTIN) {
            assertThat(added).isEqualTo(original.toBuilder().setGroupId(GROUP).build());
            verify(reader, never()).getByIds(any());
        } else if (type == MenuMeta.MENU_ITEM_TYPE_SEPARATED_FORM) {
            assertThat(added.getSeparatedFormTemplate()).isEqualTo(template(501, "模板一"));
            assertThat(added.getAggregatedFormTemplateList()).isEmpty();
            verify(reader).getByIds(List.of(501));
        } else {
            assertThat(added.hasSeparatedFormTemplate()).isFalse();
            assertThat(added.getAggregatedFormTemplateList()).containsExactly(
                template(502, "模板二"), template(503, "模板三"));
            verify(reader).getByIds(List.of(502, 503));
        }
    }

    @Test
    void assignedMissingDuplicateAndEmptyTemplateSelectionsAreRejected() {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .addMenuItem(separated(10000, GROUP, 501, "模板一")).build());
        List<MenuItem> invalidItems = List.of(
            separated(0, GROUP, 501, "已分配"), separated(0, GROUP, 999, "不存在"),
            aggregated(0, GROUP),
            aggregated(0, GROUP, template(502, "模板二"), template(502, "重复模板")),
            aggregated(0, GROUP, template(502, "模板二"), template(999, "不存在")));
        for (MenuItem item : invalidItems) {
            MenuItem requested = item.toBuilder().setName("非法模板菜单").build();
            assertThat(addMenuItem(requested).getRt().getCode())
                .isEqualTo(StatusCode.INVALID_FORM_TEMPLATE_ID_VALUE);
        }
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedMenuUpdateRequestsDoNotAcquireALock(boolean rename) {
        GenericResp response = rename ? service.updateMenuItemName("{bad")
            : service.updateMenuItemWithOneForm("{bad");

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.PARSE_JSON_FAILED_VALUE);
        verifyNoInteractions(lockExecutor, store, reader);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void menuUpdatesRequireADepartmentBeforeLocking(boolean rename) {
        GenericResp response = rename ? renameMenuItem(" ", 10, "新名称")
            : replaceMenuItemTemplate(" ", 10, 501);

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.DEPT_IS_EMPTY_VALUE);
        verifyNoInteractions(lockExecutor, store, reader);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void menuUpdatesReportMissingItemsWithoutSaving(boolean rename) {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig);

        GenericResp response = rename ? renameMenuItem(DEPT, 99999, "新名称")
            : replaceMenuItemTemplate(DEPT, 99999, 501);

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.MENU_ITEM_NOT_FOUND_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
        verify(reader, never()).getByIds(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void renamingChangesOnlyTheNameForEveryMenuItemType(int type) {
        MenuItem original = type == MenuMeta.MENU_ITEM_TYPE_BUILTIN ? baseConfig.getMenuItem(0)
            : type == MenuMeta.MENU_ITEM_TYPE_SEPARATED_FORM
                ? separated(10000, GROUP, 501, "模板一")
                : aggregated(10000, GROUP, template(501, "模板一"), template(502, "模板二"));
        original = original.toBuilder().setName("原名称")
            .setDisplayName("原显示名").setDisabled(true).build();
        MenuConfigPB input = type == MenuMeta.MENU_ITEM_TYPE_BUILTIN
            ? baseConfig.toBuilder().setMenuItem(0, original).build()
            : baseConfig.toBuilder().addMenuItem(original).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(input);

        GenericResp response = renameMenuItem(DEPT, original.getFunctionId(), "新名称");

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        int index = MenuMeta.findMenuItemIndex(input, original.getFunctionId());
        assertThat(savedConfig()).isEqualTo(input.toBuilder()
            .setMenuItem(index, original.toBuilder().setName("新名称")).build());
        verify(lockExecutor).execute(
            eq(List.of(LockKeys.deptSystemSettings(DEPT, SettingsStore.GET_DEPT_MENU_SETTINGS))),
            any());
        verify(reader).getByDeptId(DEPT);
        verify(reader, never()).getByIds(any());
    }

    @Test
    void renamingToItsOwnNameDoesNotCountAsADuplicate() {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig);

        GenericResp response = renameMenuItem(DEPT, 10, baseConfig.getMenuItem(0).getName());

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(savedConfig()).isEqualTo(baseConfig);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3})
    void renamingRejectsNamesFromOtherGroupsAndUnassignedItems(int otherGroupId) {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .addMenuGroup(MenuGroup.newBuilder().setGroupId(3).setIsLeafGroup(true))
            .setMenuItem(1, baseConfig.getMenuItem(1).toBuilder().setGroupId(otherGroupId))
            .build());

        GenericResp response = renameMenuItem(DEPT, 10, baseConfig.getMenuItem(1).getName());

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.MENU_ITEM_NAME_EXISTS_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {501, 502})
    void replacingATemplateOnlyChangesItsReferenceAndAllowsReselectingIt(int templateId) {
        MenuItem original = separated(10000, GROUP, 501, "模板一").toBuilder()
            .setName("原菜单").setDisplayName("原显示名").setDisabled(true).build();
        MenuItem other = aggregated(10001, GROUP, template(503, "模板三"))
            .toBuilder().setName("其他菜单").build();
        MenuConfigPB input = baseConfig.toBuilder()
            .addMenuItem(original).addMenuItem(other).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(input);

        GenericResp response = replaceMenuItemTemplate(DEPT, 10000, templateId);

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        IntStrKvPB expected = template(templateId, templateId == 501 ? "模板一" : "模板二");
        assertThat(savedConfig()).isEqualTo(input.toBuilder()
            .setMenuItem(2, original.toBuilder().setSeparatedFormTemplate(expected)).build());
        verify(reader).getByIds(List.of(templateId));
        verify(lockExecutor).execute(
            eq(List.of(LockKeys.deptSystemSettings(DEPT, SettingsStore.GET_DEPT_MENU_SETTINGS))),
            any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacingATemplateRejectsTemplatesUsedByOtherMenuItems(boolean aggregatedOwner) {
        MenuItem original = separated(10000, GROUP, 501, "模板一")
            .toBuilder().setName("原菜单").build();
        MenuItem other = aggregatedOwner ? aggregated(10001, GROUP, template(502, "模板二"))
            : separated(10001, GROUP, 502, "模板二");
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .addMenuItem(original).addMenuItem(other.toBuilder().setName("其他菜单")).build());

        GenericResp response = replaceMenuItemTemplate(DEPT, 10000, 502);

        assertThat(response.getRt().getCode())
            .isEqualTo(StatusCode.FORM_TEMPLATE_NOT_AVAILABLE_FOR_ASSIGNMENT_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacingATemplateReportsMissingOrForeignTemplates(boolean foreignDepartment) {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .addMenuItem(separated(10000, GROUP, 501, "模板一")).build());
        List<DragableFormTemplate> templates = foreignDepartment
            ? List.of(DragableFormTemplate.builder().id(502).deptId("other-dept")
                .name("其他科室模板").build()) : List.of();
        when(reader.getByIds(List.of(502))).thenReturn(templates);

        GenericResp response = replaceMenuItemTemplate(DEPT, 10000, 502);

        StatusCode expected = foreignDepartment ? StatusCode.FORM_TEMPLATE_NOT_BELONG_TO_DEPT
            : StatusCode.FORM_TEMPLATE_NOT_FOUND;
        assertThat(response.getRt().getCode()).isEqualTo(expected.getNumber());
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void replacingATemplateRequiresASeparatedFormMenuItem(int type) {
        MenuConfigPB input = type == MenuMeta.MENU_ITEM_TYPE_BUILTIN ? baseConfig
            : baseConfig.toBuilder().addMenuItem(
                aggregated(10000, GROUP, template(501, "模板一"))).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(input);

        GenericResp response = replaceMenuItemTemplate(DEPT,
            type == MenuMeta.MENU_ITEM_TYPE_BUILTIN ? 10 : 10000, 502);

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.INVALID_MENU_ITEM_TYPE_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
        verify(reader, never()).getByIds(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void replacingAnUnassignedBuiltinKeepsTheExistingUnassignedError(int groupId) {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .setMenuItem(0, baseConfig.getMenuItem(0).toBuilder().setGroupId(groupId)).build());

        GenericResp response = replaceMenuItemTemplate(DEPT, 10, 501);

        assertThat(response.getRt().getCode())
            .isEqualTo(StatusCode.CANNOT_RENAME_UNASSIGNED_MENU_ITEM_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedDeleteAndReorderRequestsDoNotAcquireALock(boolean reorder) {
        GenericResp response = reorder ? service.reorderMenuItems("{bad")
            : service.deleteMenuItem("{bad");

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.PARSE_JSON_FAILED_VALUE);
        verifyNoInteractions(lockExecutor, store, reader);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionAndReorderingRequireADepartmentBeforeLocking(boolean reorder) {
        GenericResp response = reorder ? reorderMenuItems(" ", List.of(11, 10))
            : deleteMenuItem(" ", 10);

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.DEPT_IS_EMPTY_VALUE);
        verifyNoInteractions(lockExecutor, store, reader);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void deletionOnlyMarksTheTargetAsUnassignedAndPreservesItsPosition(int type) {
        MenuItem original = type == MenuMeta.MENU_ITEM_TYPE_BUILTIN ? baseConfig.getMenuItem(0)
            : type == MenuMeta.MENU_ITEM_TYPE_SEPARATED_FORM
                ? separated(10000, GROUP, 501, "模板一")
                : aggregated(10000, GROUP, template(501, "模板一"), template(502, "模板二"));
        original = original.toBuilder().setName("原菜单")
            .setDisplayName("原显示名").setDisabled(true).build();
        MenuConfigPB input = type == MenuMeta.MENU_ITEM_TYPE_BUILTIN
            ? baseConfig.toBuilder().setMenuItem(0, original).build()
            : baseConfig.toBuilder().addMenuItem(original).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(input);

        GenericResp response = deleteMenuItem(DEPT, original.getFunctionId());

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        int index = MenuMeta.findMenuItemIndex(input, original.getFunctionId());
        assertThat(savedConfig()).isEqualTo(input.toBuilder()
            .setMenuItem(index, original.toBuilder().setGroupId(-1)).build());
        verify(lockExecutor).execute(
            eq(List.of(LockKeys.deptSystemSettings(DEPT, SettingsStore.GET_DEPT_MENU_SETTINGS))),
            any());
        verify(reader, never()).getByIds(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 99999})
    void deletingMissingItemsStillSucceedsWithoutChangingMenuItems(int functionId) {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig);

        GenericResp response = deleteMenuItem(DEPT, functionId);

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(savedConfig()).isEqualTo(baseConfig);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void deletingAnUnassignedBuiltinAlsoSucceeds(int groupId) {
        MenuItem original = baseConfig.getMenuItem(0).toBuilder().setGroupId(groupId).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .setMenuItem(0, original).build());

        GenericResp response = deleteMenuItem(DEPT, original.getFunctionId());

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(savedConfig()).isEqualTo(baseConfig.toBuilder()
            .setMenuItem(0, original.toBuilder().setGroupId(-1)).build());
    }

    @Test
    void reorderingPreservesFieldsAndTheOrderOfUnrequestedItems() {
        MenuItem builtin = baseConfig.getMenuItem(0).toBuilder().setGroupId(0)
            .setDisplayName("原显示名").setDisabled(true).build();
        MenuItem separated = separated(10000, GROUP, 501, "模板一").toBuilder()
            .setName("独立菜单").setDisplayName("独立显示名").setDisabled(true).build();
        MenuItem aggregated = aggregated(10001, 3,
            template(502, "模板二"), template(503, "模板三")).toBuilder()
            .setName("聚合菜单").setDisplayName("聚合显示名").build();
        MenuConfigPB input = baseConfig.toBuilder().setMenuItem(0, builtin)
            .addMenuGroup(MenuGroup.newBuilder().setGroupId(3).setIsLeafGroup(true))
            .addMenuItem(separated).addMenuItem(aggregated)
            .addUnassignedFormTemplate(template(999, "旧未分配项")).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(input);

        GenericResp response = reorderMenuItems(DEPT, List.of(10001, 10));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(savedConfig()).isEqualTo(input.toBuilder().clearUnassignedFormTemplate()
            .clearMenuItem().addMenuItem(baseConfig.getMenuItem(1)).addMenuItem(separated)
            .addMenuItem(aggregated).addMenuItem(builtin).build());
        verify(lockExecutor).execute(
            eq(List.of(LockKeys.deptSystemSettings(DEPT, SettingsStore.GET_DEPT_MENU_SETTINGS))),
            any());
        verify(reader, never()).getByIds(any());
    }

    @Test
    void reorderingIgnoresMissingAndRepeatedIdsAndKeepsTheirFirstOccurrence() {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig);

        GenericResp response = reorderMenuItems(DEPT, List.of(99999, 11, 11, 10, 11, 99999));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        MenuConfigPB saved = savedConfig();
        assertThat(saved.getMenuItemList())
            .containsExactly(baseConfig.getMenuItem(1), baseConfig.getMenuItem(0));
        assertThat(saved.getMenuItemList()).extracting(MenuItem::getFunctionId)
            .doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyOrUnknownReorderSelectionsKeepTheConfiguration(boolean empty) {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig);

        GenericResp response = reorderMenuItems(DEPT, empty ? List.of() : List.of(99999, 99999));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(savedConfig()).isEqualTo(baseConfig);
    }

    @ParameterizedTest
    @ValueSource(strings = {"add", "delete", "reorder"})
    void malformedTemplateChangeRequestsDoNotAcquireALock(String operation) {
        assertThat(callTemplateOperation(operation, "{bad").getRt().getCode())
            .isEqualTo(StatusCode.PARSE_JSON_FAILED_VALUE);
        verifyNoInteractions(lockExecutor, store, reader);
    }

    @ParameterizedTest
    @ValueSource(strings = {"add", "delete", "reorder"})
    void templateChangesRequireADepartmentBeforeLocking(String operation) {
        assertThat(changeTemplates(operation, " ", 10000, List.of(501)).getRt().getCode())
            .isEqualTo(StatusCode.DEPT_IS_EMPTY_VALUE);
        verifyNoInteractions(lockExecutor, store, reader);
    }

    @Test
    void appendingTemplatesPreservesItemFieldsAndUsesCurrentNamesInRequestOrder() {
        MenuItem original = aggregated(10000, GROUP, template(501, "模板一")).toBuilder()
            .setName("聚合菜单").setDisplayName("原显示名").setDisabled(true).build();
        MenuConfigPB input = baseConfig.toBuilder().addMenuItem(original).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(input);

        GenericResp response = changeTemplates("add", DEPT, 10000, List.of(503, 502));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(savedConfig()).isEqualTo(input.toBuilder().setMenuItem(2, original.toBuilder()
            .addAggregatedFormTemplate(template(503, "模板三"))
            .addAggregatedFormTemplate(template(502, "模板二"))).build());
        verify(reader).getByIds(List.of(503, 502));
        verify(lockExecutor).execute(
            eq(List.of(LockKeys.deptSystemSettings(DEPT, SettingsStore.GET_DEPT_MENU_SETTINGS))),
            any());
    }

    @ParameterizedTest
    @ValueSource(ints = {501, 502})
    void appendingTemplatesRejectsExistingReferencesInThisOrAnotherItem(int templateId) {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .addMenuItem(aggregated(10000, GROUP, template(501, "模板一")))
            .addMenuItem(separated(10001, GROUP, 502, "模板二")).build());

        GenericResp response = changeTemplates("add", DEPT, 10000, List.of(templateId));

        assertThat(response.getRt().getCode())
            .isEqualTo(StatusCode.FORM_TEMPLATE_NOT_AVAILABLE_FOR_ASSIGNMENT_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @Test
    void appendingDuplicateTemplateIdsIsRejectedBeforeSaving() {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .addMenuItem(aggregated(10000, GROUP, template(501, "模板一"))).build());

        GenericResp response = changeTemplates("add", DEPT, 10000, List.of(502, 502));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.INVALID_FORM_TEMPLATE_ID_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @Test
    void appendingAnEmptySelectionKeepsTheExistingSuccessfulNoOp() {
        MenuConfigPB input = baseConfig.toBuilder()
            .addMenuItem(aggregated(10000, GROUP, template(501, "模板一"))).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(input);

        assertThat(changeTemplates("add", DEPT, 10000, List.of()).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        assertThat(savedConfig()).isEqualTo(input);
    }

    @ParameterizedTest
    @CsvSource({"add,0", "add,1", "reorder,0", "reorder,1"})
    void templateAppendAndReorderRequireAnAggregatedItem(String operation, int type) {
        MenuConfigPB input = type == MenuMeta.MENU_ITEM_TYPE_BUILTIN ? baseConfig
            : baseConfig.toBuilder().addMenuItem(separated(10000, GROUP, 501, "模板一")).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(input);

        GenericResp response = changeTemplates(operation, DEPT,
            type == MenuMeta.MENU_ITEM_TYPE_BUILTIN ? 10 : 10000, List.of(502));

        assertThat(response.getRt().getCode())
            .isEqualTo(StatusCode.NOT_AN_AGGREGATED_FORM_MENU_ITEM_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
        verify(reader, never()).getByIds(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"add", "reorder"})
    void templateAppendAndReorderReportMissingItems(String operation) {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig);

        assertThat(changeTemplates(operation, DEPT, 10000, List.of(501)).getRt().getCode())
            .isEqualTo(StatusCode.MENU_ITEM_NOT_FOUND_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @ParameterizedTest
    @CsvSource({"add,false", "add,true", "delete,false", "delete,true"})
    void templateAppendAndDeletionValidateExistenceAndDepartment(
        String operation, boolean foreignDepartment
    ) {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .addMenuItem(aggregated(10000, GROUP, template(501, "模板一"))).build());
        when(reader.getByIds(List.of(502))).thenReturn(foreignDepartment
            ? List.of(DragableFormTemplate.builder().id(502).deptId("other-dept")
                .name("其他科室模板").build()) : List.of());

        GenericResp response = changeTemplates(operation, DEPT, 10000, List.of(502));

        StatusCode expected = foreignDepartment ? StatusCode.FORM_TEMPLATE_NOT_BELONG_TO_DEPT
            : StatusCode.FORM_TEMPLATE_NOT_FOUND;
        assertThat(response.getRt().getCode()).isEqualTo(expected.getNumber());
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @Test
    void deletingATemplateRemovesAllAggregatedCopiesButPreservesSeparatedItems() {
        MenuItem first = aggregated(10000, GROUP,
            template(501, "模板一"), template(502, "模板二"), template(501, "模板一"))
            .toBuilder().setName("聚合一").setDisplayName("原显示名").setDisabled(true).build();
        MenuItem second = aggregated(10001, GROUP, template(503, "模板三"), template(501, "模板一"));
        MenuConfigPB input = baseConfig.toBuilder().addMenuItem(first).addMenuItem(second)
            .addMenuItem(separated(10002, GROUP, 501, "模板一")).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(input);

        GenericResp response = changeTemplates("delete", DEPT, 0, List.of(501));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(savedConfig()).isEqualTo(input.toBuilder()
            .setMenuItem(2, first.toBuilder().clearAggregatedFormTemplate()
                .addAggregatedFormTemplate(template(502, "模板二")))
            .setMenuItem(3, second.toBuilder().clearAggregatedFormTemplate()
                .addAggregatedFormTemplate(template(503, "模板三"))).build());
        verify(reader).getByIds(List.of(501));
        verify(reader, never()).getById(anyInt());
    }

    @Test
    void deletingTheLastTemplateKeepsTheEmptyItemUntilTheNextNormalization() {
        MenuItem original = aggregated(10000, GROUP, template(501, "模板一"));
        MenuConfigPB input = baseConfig.toBuilder().addMenuItem(original).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(input);

        assertThat(changeTemplates("delete", DEPT, 0, List.of(501)).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        assertThat(savedConfig()).isEqualTo(input.toBuilder()
            .setMenuItem(2, original.toBuilder().clearAggregatedFormTemplate()).build());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletingAValidTemplateWithoutAggregatedReferencesDoesNotSave(boolean separatedOwner) {
        MenuConfigPB.Builder input = baseConfig.toBuilder()
            .addMenuItem(aggregated(10000, GROUP, template(501, "模板一")));
        if (separatedOwner) input.addMenuItem(separated(10001, GROUP, 502, "模板二"));
        when(store.getMenuConfigPB(DEPT)).thenReturn(input.build());

        assertThat(changeTemplates("delete", DEPT, 0, List.of(502)).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
        verifyNoInteractions(userQuery);
    }

    @Test
    void reorderingTemplatesChangesOnlyTheirOrderAndDoesNotFetchThemAgain() {
        MenuItem original = aggregated(10000, GROUP,
            template(501, "模板一"), template(502, "模板二"), template(503, "模板三"))
            .toBuilder().setName("聚合菜单").setDisplayName("原显示名").setDisabled(true).build();
        MenuConfigPB input = baseConfig.toBuilder().addMenuItem(original).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(input);

        GenericResp response = changeTemplates("reorder", DEPT, 10000, List.of(503, 501, 502));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(savedConfig()).isEqualTo(input.toBuilder()
            .setMenuItem(2, original.toBuilder().clearAggregatedFormTemplate()
                .addAggregatedFormTemplate(template(503, "模板三"))
                .addAggregatedFormTemplate(template(501, "模板一"))
                .addAggregatedFormTemplate(template(502, "模板二"))).build());
        verify(reader, never()).getByIds(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unknown", "duplicate", "empty"})
    void reorderingTemplatesRequiresACompletePermutation(String selection) {
        when(store.getMenuConfigPB(DEPT)).thenReturn(baseConfig.toBuilder()
            .addMenuItem(aggregated(10000, GROUP,
                template(501, "模板一"), template(502, "模板二"), template(503, "模板三"))).build());
        List<Integer> ids = switch (selection) {
            case "missing" -> List.of(501, 502);
            case "unknown" -> List.of(501, 502, 999);
            case "duplicate" -> List.of(501, 502, 502);
            default -> List.of();
        };
        StatusCode expected = selection.equals("missing") || selection.equals("empty")
            ? StatusCode.FORM_TEMPLATE_NOT_IN_REORDER_LIST
            : StatusCode.FORM_TEMPLATE_NOT_FOUND_IN_MENU_ITEM;

        assertThat(changeTemplates("reorder", DEPT, 10000, ids).getRt().getCode())
            .isEqualTo(expected.getNumber());
        verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reorderingHistoricalDuplicateReferencesRequiresTheirFullCount(boolean complete) {
        MenuItem original = aggregated(10000, GROUP,
            template(501, "模板一"), template(501, "模板一"), template(502, "模板二"));
        MenuConfigPB input = baseConfig.toBuilder().addMenuItem(original).build();
        when(store.getMenuConfigPB(DEPT)).thenReturn(input);

        GenericResp response = changeTemplates("reorder", DEPT, 10000,
            complete ? List.of(501, 502, 501) : List.of(501, 502));

        if (complete) {
            assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
            assertThat(savedConfig()).isEqualTo(input.toBuilder().setMenuItem(2,
                aggregated(10000, GROUP, template(501, "模板一"),
                    template(502, "模板二"), template(501, "模板一"))).build());
        } else {
            assertThat(response.getRt().getCode())
                .isEqualTo(StatusCode.FORM_TEMPLATE_NOT_IN_REORDER_LIST_VALUE);
            verify(store, never()).saveMenuConfigPB(anyString(), any(), anyString());
        }
    }

    private GenericResp changeTemplates(
        String operation, String deptId, int functionId, List<Integer> templateIds
    ) {
        String json = switch (operation) {
            case "add" -> ProtoUtils.protoToJson(AddMenuItemFormTemplateReq.newBuilder()
                .setDeptId(deptId).setFunctionId(functionId)
                .addAllFormTemplateId(templateIds).build());
            case "delete" -> ProtoUtils.protoToJson(DeleteMenuItemFormTemplateReq.newBuilder()
                .setDeptId(deptId).setFormTemplateId(templateIds.get(0)).build());
            case "reorder" -> ProtoUtils.protoToJson(ReorderMenuItemFormTemplatesReq.newBuilder()
                .setDeptId(deptId).setFunctionId(functionId)
                .addAllFormTemplateId(templateIds).build());
            default -> throw new IllegalArgumentException(operation);
        };
        return callTemplateOperation(operation, json);
    }

    private GenericResp callTemplateOperation(String operation, String json) {
        return switch (operation) {
            case "add" -> service.addMenuItemFormTemplate(json);
            case "delete" -> service.deleteMenuItemFormTemplate(json);
            case "reorder" -> service.reorderMenuItemFormTemplates(json);
            default -> throw new IllegalArgumentException(operation);
        };
    }

    private GenericResp deleteMenuItem(String deptId, int functionId) {
        return service.deleteMenuItem(ProtoUtils.protoToJson(DeleteMenuItemReq.newBuilder()
            .setDeptId(deptId).setFunctionId(functionId).build()));
    }

    private GenericResp reorderMenuItems(String deptId, List<Integer> functionIds) {
        return service.reorderMenuItems(ProtoUtils.protoToJson(ReorderMenuItemsReq.newBuilder()
            .setDeptId(deptId).addAllFunctionId(functionIds).build()));
    }

    private GenericResp renameMenuItem(String deptId, int functionId, String name) {
        return service.updateMenuItemName(ProtoUtils.protoToJson(UpdateMenuItemNameReq.newBuilder()
            .setDeptId(deptId).setFunctionId(functionId).setNewName(name).build()));
    }

    private GenericResp replaceMenuItemTemplate(String deptId, int functionId, int templateId) {
        return service.updateMenuItemWithOneForm(ProtoUtils.protoToJson(
            UpdateMenuItemWithOneFormReq.newBuilder().setDeptId(deptId)
                .setFunctionId(functionId).setFormTemplateId(templateId).build()));
    }

    private GenericResp addMenuItem(MenuItem item) {
        return service.addMenuItem(ProtoUtils.protoToJson(AddMenuItemReq.newBuilder()
            .setDeptId(DEPT).setMenuItem(item).build()));
    }

    private GetMenuItemResp getMenuItem(int functionId) {
        return service.getMenuItem(ProtoUtils.protoToJson(GetMenuItemReq.newBuilder()
            .setDeptId(DEPT).setFunctionId(functionId).build()));
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
    private ProtoConfig protoConfig;
    private MenuConfigPB baseConfig;
    private SettingsStore store;
    private UserQuery userQuery;
    private LockExecutor lockExecutor;
    private JfkTemplateReader reader;
    private MenuService service;
}
