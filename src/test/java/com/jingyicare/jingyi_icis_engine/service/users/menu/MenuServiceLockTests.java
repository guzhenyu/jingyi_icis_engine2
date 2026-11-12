package com.jingyicare.jingyi_icis_engine.service.users.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddMenuItemFormTemplateReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddMenuItemReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeleteMenuItemFormTemplateReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeleteMenuItemReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDeptMenuConfigReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.ReorderMenuItemFormTemplatesReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.ReorderMenuItemsReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.UpdateMenuItemNameReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.UpdateMenuItemWithOneFormReq;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.MenuConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.MenuItem;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.IntStrKvPB;
import com.jingyicare.jingyi_icis_engine.repository.settings.DeptSystemSettingsRepository;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkTemplateReader;
import com.jingyicare.jingyi_icis_engine.service.settings.SettingsStore;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class MenuServiceLockTests extends TestsBase {
    @BeforeEach
    void prepare() {
        cleanUp();
        for (String deptId : List.of(DEPT, OTHER_DEPT)) {
            jdbcTemplate.update(
                "INSERT INTO rbac_departments(dept_id, dept_name) VALUES (?, ?)", deptId, deptId);
        }
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        for (String deptId : List.of(DEPT, OTHER_DEPT)) {
            jdbcTemplate.update("DELETE FROM dept_system_settings WHERE dept_id = ?", deptId);
            jdbcTemplate.update("DELETE FROM dragable_form_templates WHERE dept_id = ?", deptId);
            jdbcTemplate.update("DELETE FROM app_transaction_locks WHERE resource_key = ?",
                menuLockKey(deptId));
            jdbcTemplate.update("DELETE FROM rbac_departments WHERE dept_id = ?", deptId);
        }
    }

    @Test
    void normalizationWaitsForTheSameDepartmentLockAndReadsAfterItIsAcquired() throws Exception {
        MenuConfigPB initial = menuMeta.getConfig().toBuilder().setDeptId(DEPT).build();
        MenuConfigPB updated = initial.toBuilder()
            .setMenuItem(0, initial.getMenuItem(0).toBuilder().setName("锁内更新的菜单名称"))
            .build();
        store.saveMenuConfigPB(DEPT, initial, "initial-user");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(
                List.of(menuLockKey(DEPT)), () -> {
                    holderPid.set(jdbcTemplate.queryForObject(
                        "SELECT pg_backend_pid()", Integer.class));
                    locked.countDown();
                    awaitRelease(release);
                    store.saveMenuConfigPB(DEPT, updated, "holder-user");
                    return null;
                }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<MenuConfigPB> sameDepartment = pool.submit(
                () -> service.loadAndNormalizeMenuConfig(DEPT));

            // 先确认请求在业务锁上等待，此时持锁方尚未修改菜单设置行。
            assertThat(waitForBlockers(holderPid.get(), 1)).isTrue();
            Future<MenuConfigPB> otherDepartment = pool.submit(
                () -> service.loadAndNormalizeMenuConfig(OTHER_DEPT));
            assertThat(otherDepartment.get(10, TimeUnit.SECONDS).getDeptId()).isEqualTo(OTHER_DEPT);
            assertThat(sameDepartment.isDone()).isFalse();

            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            assertThat(sameDepartment.get(15, TimeUnit.SECONDS).getMenuItemList())
                .isEqualTo(updated.getMenuItemList());
            assertThat(lockCount(DEPT)).isEqualTo(1);
            assertThat(lockCount(OTHER_DEPT)).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void normalizationPersistsUniqueFunctionIdsAndReleasesDiscardedTemplates() {
        int keptTemplateId = insertTemplate("保留模板");
        int discardedTemplateId = insertTemplate("被剔除菜单的模板");
        MenuConfigPB defaults = menuMeta.getConfig().toBuilder().setDeptId(DEPT).build();
        MenuItem assigned = defaults.getMenuItem(0).toBuilder().setName("已分配原项")
            .setDisplayName("原显示名").setDisabled(true).build();
        MenuItem keptForm = MenuItem.newBuilder().setFunctionId(10000).setType(1)
            .setGroupId(assigned.getGroupId()).setName("保留菜单")
            .setSeparatedFormTemplate(IntStrKvPB.newBuilder()
                .setKey(keptTemplateId).setVal("保留模板")).build();
        MenuItem discardedForm = MenuItem.newBuilder().setFunctionId(10000).setType(2)
            .setGroupId(assigned.getGroupId()).setName("重复功能编号菜单")
            .addAggregatedFormTemplate(IntStrKvPB.newBuilder()
                .setKey(discardedTemplateId).setVal("被剔除菜单的模板")).build();
        store.saveMenuConfigPB(DEPT, defaults.toBuilder()
            .setMenuItem(0, assigned.toBuilder().setGroupId(0).setName("未分配副本"))
            .addMenuItem(keptForm).addMenuItem(discardedForm).addMenuItem(assigned)
            .addUnassignedFormTemplate(IntStrKvPB.newBuilder().setKey(keptTemplateId)).build(),
            "initial-user");

        MenuConfigPB actual = service.loadAndNormalizeMenuConfig(DEPT);

        assertThat(actual.getMenuItemList()).isEqualTo(defaults.toBuilder()
            .setMenuItem(0, assigned).addMenuItem(keptForm).build().getMenuItemList());
        assertThat(actual.getMenuItemList()).extracting(MenuItem::getFunctionId)
            .doesNotHaveDuplicates();
        assertThat(actual.getUnassignedFormTemplateList()).containsExactly(
            IntStrKvPB.newBuilder().setKey(discardedTemplateId).setVal("被剔除菜单的模板").build());
        MenuConfigPB persisted = store.getMenuConfigPB(DEPT);
        assertThat(persisted.getMenuItemList()).isEqualTo(actual.getMenuItemList());
        assertThat(persisted.getUnassignedFormTemplateList()).isEmpty();
        assertThat(service.loadAndNormalizeMenuConfig(DEPT)).isEqualTo(actual);
        assertThat(store.getMenuConfigPB(DEPT)).isEqualTo(persisted);
    }

    @Test
    void failedDefaultSaveRollsBackBothMenuSettingsAndTheNewLockRow() {
        SettingsStore failingStore = mock(SettingsStore.class, delegatesTo(store));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            store.saveMenuConfigPB(invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2));
            deptSettingsRepo.flush();
            throw new IllegalStateException("模拟菜单保存失败");
        }).when(failingStore).saveMenuConfigPB(eq(DEPT), any(), anyString());
        MenuService failingService = new MenuService(
            protoConfig, menuMeta, failingStore, reader, users, executor);

        assertThatThrownBy(() -> failingService.loadAndNormalizeMenuConfig(DEPT))
            .isInstanceOf(IllegalStateException.class).hasMessage("模拟菜单保存失败");
        assertThat(store.getMenuConfigPB(DEPT)).isNull();
        assertThat(lockCount(DEPT)).isZero();
    }

    @Test
    void menuResponseAndGroupReadersCanInitializeSettingsThroughTheirInternalCalls() {
        GetDeptMenuConfigReq request = GetDeptMenuConfigReq.newBuilder().setDeptId(DEPT).build();

        assertThat(protoConfig.getConfig().getText().getStatusCodeMsgCount())
            .isEqualTo(StatusCode.LAST_CODE_VALUE);
        assertThat(protoConfig.getConfig().getText()
            .getStatusCodeMsg(StatusCode.INVALID_FORM_TEMPLATE_VALUE)).isEqualTo("表单模板内容无效");

        assertThat(service.getDeptMenuConfig(ProtoUtils.protoToJson(request)).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        assertThat(service.getMenuGroups(OTHER_DEPT)).isNotEmpty();
        assertThat(store.getMenuConfigPB(DEPT)).isNotNull();
        assertThat(store.getMenuConfigPB(OTHER_DEPT)).isNotNull();
        assertThat(lockCount(DEPT)).isEqualTo(1);
        assertThat(lockCount(OTHER_DEPT)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentAdditionsDoNotLoseItemsReuseTemplatesOrAllocateDuplicateIds(
        boolean reuseTemplate
    ) throws Exception {
        int firstTemplateId = insertTemplate("模板一");
        int secondTemplateId = reuseTemplate ? firstTemplateId : insertTemplate("模板二");
        MenuConfigPB initial = menuMeta.getConfig().toBuilder().setDeptId(DEPT).build();
        store.saveMenuConfigPB(DEPT, initial, "initial-user");
        String firstRequest = addRequest(firstTemplateId, "并发菜单一");
        String secondRequest = addRequest(secondTemplateId, "并发菜单二");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(
                List.of(menuLockKey(DEPT)), () -> {
                    holderPid.set(jdbcTemplate.queryForObject(
                        "SELECT pg_backend_pid()", Integer.class));
                    locked.countDown();
                    awaitRelease(release);
                    return null;
                }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<GenericResp> first = pool.submit(() -> service.addMenuItem(firstRequest));
            Future<GenericResp> second = pool.submit(() -> service.addMenuItem(secondRequest));
            assertThat(waitForBlockers(holderPid.get(), 2)).isTrue();

            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            List<Integer> codes = List.of(first.get(15, TimeUnit.SECONDS).getRt().getCode(),
                second.get(15, TimeUnit.SECONDS).getRt().getCode());
            List<MenuItem> forms = store.getMenuConfigPB(DEPT).getMenuItemList().stream()
                .filter(item -> item.getType() == 1).toList();
            if (reuseTemplate) {
                assertThat(codes).containsExactlyInAnyOrder(
                    StatusCode.OK_VALUE, StatusCode.INVALID_FORM_TEMPLATE_ID_VALUE);
                assertThat(forms).hasSize(1);
                assertThat(forms.get(0).getFunctionId()).isEqualTo(10000);
            } else {
                assertThat(codes).containsOnly(StatusCode.OK_VALUE);
                assertThat(forms).extracting(MenuItem::getFunctionId)
                    .containsExactlyInAnyOrder(10000, 10001);
                assertThat(forms).extracting(MenuItem::getName)
                    .containsExactlyInAnyOrder("并发菜单一", "并发菜单二");
            }
            assertThat(lockCount(DEPT)).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void assignedBuiltinIsRejectedWithoutChangingStoredSettingsOrAuditFields() {
        MenuConfigPB initial = menuMeta.getConfig().toBuilder().setDeptId(DEPT).build();
        MenuItem original = initial.getMenuItem(0);
        int targetGroup = MenuMeta.getLeafGroupIds(initial).stream()
            .filter(groupId -> groupId > 0 && groupId != original.getGroupId())
            .findFirst().orElseThrow();
        store.saveMenuConfigPB(DEPT, initial, "initial-user");
        String request = ProtoUtils.protoToJson(AddMenuItemReq.newBuilder().setDeptId(DEPT)
            .setMenuItem(original.toBuilder().setGroupId(targetGroup)
                .setName("客户端覆盖名称").setDisabled(!original.getDisabled())).build());

        GenericResp response = service.addMenuItem(request);

        assertThat(response.getRt().getCode())
            .isEqualTo(StatusCode.MENU_ITEM_ALREADY_ASSIGNED_VALUE);
        assertThat(response.getRt().getMsg()).isEqualTo("菜单项已分配，不能重复添加");
        assertThat(store.getMenuConfigPB(DEPT)).isEqualTo(initial);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT modified_by FROM dept_system_settings WHERE dept_id = ? AND function_id = ?
            """, String.class, DEPT, SettingsStore.GET_DEPT_MENU_SETTINGS))
            .isEqualTo("initial-user");
    }

    @Test
    void concurrentBuiltinAssignmentsSucceedOnlyOnceAndKeepOriginalProperties() throws Exception {
        MenuConfigPB defaults = menuMeta.getConfig().toBuilder().setDeptId(DEPT).build();
        MenuItem original = defaults.getMenuItem(0).toBuilder().setGroupId(-1)
            .setName("原内置菜单").setDisplayName("原显示名").setDisabled(true).build();
        MenuConfigPB initial = defaults.toBuilder().setMenuItem(0, original).build();
        store.saveMenuConfigPB(DEPT, initial, "initial-user");
        List<Integer> groups = MenuMeta.getLeafGroupIds(initial).stream()
            .filter(groupId -> groupId > 0).sorted().limit(2).toList();
        assertThat(groups).hasSize(2);
        AddMenuItemReq firstRequest = AddMenuItemReq.newBuilder().setDeptId(DEPT)
            .setMenuItem(original.toBuilder().setGroupId(groups.get(0)).setName("客户端名称一")
                .setDisplayName("客户端显示名").setDisabled(false)).build();
        AddMenuItemReq secondRequest = firstRequest.toBuilder()
            .setMenuItem(firstRequest.getMenuItem().toBuilder().setGroupId(groups.get(1))
                .setName("客户端名称二")).build();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(
                List.of(menuLockKey(DEPT)), () -> {
                    holderPid.set(jdbcTemplate.queryForObject(
                        "SELECT pg_backend_pid()", Integer.class));
                    locked.countDown();
                    awaitRelease(release);
                    return null;
                }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<GenericResp> first = pool.submit(
                () -> service.addMenuItem(ProtoUtils.protoToJson(firstRequest)));
            Future<GenericResp> second = pool.submit(
                () -> service.addMenuItem(ProtoUtils.protoToJson(secondRequest)));
            assertThat(waitForBlockers(holderPid.get(), 2)).isTrue();

            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            assertThat(List.of(first.get(15, TimeUnit.SECONDS).getRt().getCode(),
                second.get(15, TimeUnit.SECONDS).getRt().getCode()))
                .containsExactlyInAnyOrder(StatusCode.OK_VALUE,
                    StatusCode.MENU_ITEM_ALREADY_ASSIGNED_VALUE);
            List<MenuItem> stored = store.getMenuConfigPB(DEPT).getMenuItemList().stream()
                .filter(item -> item.getFunctionId() == original.getFunctionId()).toList();
            assertThat(stored).hasSize(1);
            assertThat(stored.get(0).getGroupId()).isIn(groups);
            assertThat(stored.get(0).toBuilder().setGroupId(-1).build()).isEqualTo(original);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void failedAdditionRollsBackDefaultInitializationAndMenuChangesTogether() {
        int templateId = insertTemplate("模板一");
        AtomicInteger saves = new AtomicInteger();
        SettingsStore failingStore = mock(SettingsStore.class, delegatesTo(store));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            store.saveMenuConfigPB(invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2));
            deptSettingsRepo.flush();
            if (saves.incrementAndGet() == 2) {
                throw new IllegalStateException("模拟新增菜单保存失败");
            }
            return null;
        }).when(failingStore).saveMenuConfigPB(eq(DEPT), any(), anyString());
        MenuService failingService = new MenuService(
            protoConfig, menuMeta, failingStore, reader, users, executor);

        assertThatThrownBy(() -> failingService.addMenuItem(addRequest(templateId, "新增菜单")))
            .isInstanceOf(IllegalStateException.class).hasMessage("模拟新增菜单保存失败");
        assertThat(saves.get()).isEqualTo(2);
        assertThat(store.getMenuConfigPB(DEPT)).isNull();
        assertThat(lockCount(DEPT)).isZero();
    }

    @Test
    void concurrentRenamingAndTemplateReplacementKeepBothChanges() throws Exception {
        int originalTemplateId = insertTemplate("原模板");
        int replacementId = insertTemplate("新模板");
        MenuItem original = formMenuItem(10000, originalTemplateId, "原模板", "原菜单")
            .toBuilder().setDisplayName("原显示名").setDisabled(true).build();
        MenuConfigPB initial = menuMeta.getConfig().toBuilder().setDeptId(DEPT)
            .addMenuItem(original).build();
        store.saveMenuConfigPB(DEPT, initial, "initial-user");

        List<GenericResp> responses = runConcurrentlyUnderMenuLock(
            () -> service.updateMenuItemName(renameRequest(10000, "新菜单")),
            () -> service.updateMenuItemWithOneForm(replaceRequest(10000, replacementId)));

        assertThat(responses).extracting(response -> response.getRt().getCode())
            .containsOnly(StatusCode.OK_VALUE);
        MenuItem expected = original.toBuilder().setName("新菜单")
            .setSeparatedFormTemplate(IntStrKvPB.newBuilder()
                .setKey(replacementId).setVal("新模板")).build();
        assertThat(store.getMenuConfigPB(DEPT)).isEqualTo(initial.toBuilder()
            .setMenuItem(initial.getMenuItemCount() - 1, expected).build());
        assertThat(service.loadAndNormalizeMenuConfig(DEPT).getUnassignedFormTemplateList())
            .containsExactly(IntStrKvPB.newBuilder()
                .setKey(originalTemplateId).setVal("原模板").build());
    }

    @Test
    void concurrentRenamingCannotCreateDuplicateNames() throws Exception {
        MenuConfigPB initial = menuMeta.getConfig().toBuilder().setDeptId(DEPT).build();
        store.saveMenuConfigPB(DEPT, initial, "initial-user");

        List<GenericResp> responses = runConcurrentlyUnderMenuLock(
            () -> service.updateMenuItemName(
                renameRequest(initial.getMenuItem(0).getFunctionId(), "并发同名菜单")),
            () -> service.updateMenuItemName(
                renameRequest(initial.getMenuItem(1).getFunctionId(), "并发同名菜单")));

        assertThat(responses).extracting(response -> response.getRt().getCode())
            .containsExactlyInAnyOrder(StatusCode.OK_VALUE, StatusCode.MENU_ITEM_NAME_EXISTS_VALUE);
        int changedIndex = responses.get(0).getRt().getCode() == StatusCode.OK_VALUE ? 0 : 1;
        assertThat(store.getMenuConfigPB(DEPT)).isEqualTo(initial.toBuilder()
            .setMenuItem(changedIndex, initial.getMenuItem(changedIndex).toBuilder()
                .setName("并发同名菜单")).build());
    }

    @Test
    void concurrentReplacementsCannotClaimTheSameTemplate() throws Exception {
        int firstId = insertTemplate("原模板一");
        int secondId = insertTemplate("原模板二");
        int replacementId = insertTemplate("新模板");
        MenuItem first = formMenuItem(10000, firstId, "原模板一", "菜单一");
        MenuItem second = formMenuItem(10001, secondId, "原模板二", "菜单二");
        MenuConfigPB initial = menuMeta.getConfig().toBuilder().setDeptId(DEPT)
            .addMenuItem(first).addMenuItem(second).build();
        store.saveMenuConfigPB(DEPT, initial, "initial-user");

        List<GenericResp> responses = runConcurrentlyUnderMenuLock(
            () -> service.updateMenuItemWithOneForm(replaceRequest(10000, replacementId)),
            () -> service.updateMenuItemWithOneForm(replaceRequest(10001, replacementId)));

        assertThat(responses).extracting(response -> response.getRt().getCode())
            .containsExactlyInAnyOrder(StatusCode.OK_VALUE,
                StatusCode.FORM_TEMPLATE_NOT_AVAILABLE_FOR_ASSIGNMENT_VALUE);
        int changedIndex = responses.get(0).getRt().getCode() == StatusCode.OK_VALUE
            ? initial.getMenuItemCount() - 2 : initial.getMenuItemCount() - 1;
        assertThat(store.getMenuConfigPB(DEPT)).isEqualTo(initial.toBuilder()
            .setMenuItem(changedIndex, initial.getMenuItem(changedIndex).toBuilder()
                .setSeparatedFormTemplate(IntStrKvPB.newBuilder()
                    .setKey(replacementId).setVal("新模板"))).build());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedUpdatesRollBackNormalizationAndMenuChangesTogether(boolean rename) {
        int originalId = insertTemplate("原模板");
        int replacementId = insertTemplate("新模板");
        MenuConfigPB initial = menuMeta.getConfig().toBuilder().setDeptId(DEPT)
            .addMenuItem(formMenuItem(10000, originalId, "原模板", "原菜单"))
            .addMenuItem(MenuItem.newBuilder().setFunctionId(999).setGroupId(1))
            .build();
        store.saveMenuConfigPB(DEPT, initial, "initial-user");
        AtomicInteger saves = new AtomicInteger();
        SettingsStore failingStore = mock(SettingsStore.class, delegatesTo(store));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            store.saveMenuConfigPB(invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2));
            deptSettingsRepo.flush();
            if (saves.incrementAndGet() == 2) {
                throw new IllegalStateException("模拟菜单更新保存失败");
            }
            return null;
        }).when(failingStore).saveMenuConfigPB(eq(DEPT), any(), anyString());
        MenuService failingService = new MenuService(
            protoConfig, menuMeta, failingStore, reader, users, executor);

        assertThatThrownBy(() -> {
            if (rename) failingService.updateMenuItemName(renameRequest(10000, "新菜单"));
            else failingService.updateMenuItemWithOneForm(replaceRequest(10000, replacementId));
        }).isInstanceOf(IllegalStateException.class).hasMessage("模拟菜单更新保存失败");
        assertThat(saves.get()).isEqualTo(2);
        assertThat(store.getMenuConfigPB(DEPT)).isEqualTo(initial);
        assertThat(lockCount(DEPT)).isZero();
    }

    @Test
    void concurrentDeletionAndReorderingPreserveBothChanges() throws Exception {
        MenuConfigPB initial = menuMeta.getConfig().toBuilder().setDeptId(DEPT).build();
        MenuItem first = initial.getMenuItem(0);
        MenuItem second = initial.getMenuItem(1);
        store.saveMenuConfigPB(DEPT, initial, "initial-user");

        List<GenericResp> responses = runConcurrentlyUnderMenuLock(
            () -> service.deleteMenuItem(deleteRequest(first.getFunctionId())),
            () -> service.reorderMenuItems(reorderRequest(
                List.of(second.getFunctionId(), first.getFunctionId()))));

        assertThat(responses).extracting(response -> response.getRt().getCode())
            .containsOnly(StatusCode.OK_VALUE);
        MenuConfigPB expected = initial.toBuilder().clearMenuItem()
            .addAllMenuItem(initial.getMenuItemList().subList(2, initial.getMenuItemCount()))
            .addMenuItem(second).addMenuItem(first.toBuilder().setGroupId(-1)).build();
        assertThat(store.getMenuConfigPB(DEPT)).isEqualTo(expected);
    }

    @Test
    void concurrentDeletionAndAdditionPreserveBothChanges() throws Exception {
        int templateId = insertTemplate("并发模板");
        MenuConfigPB initial = menuMeta.getConfig().toBuilder().setDeptId(DEPT).build();
        store.saveMenuConfigPB(DEPT, initial, "initial-user");

        List<GenericResp> responses = runConcurrentlyUnderMenuLock(
            () -> service.deleteMenuItem(deleteRequest(initial.getMenuItem(0).getFunctionId())),
            () -> service.addMenuItem(addRequest(templateId, "并发新增菜单")));

        assertThat(responses).extracting(response -> response.getRt().getCode())
            .containsOnly(StatusCode.OK_VALUE);
        assertThat(store.getMenuConfigPB(DEPT)).isEqualTo(initial.toBuilder()
            .setMenuItem(0, initial.getMenuItem(0).toBuilder().setGroupId(-1))
            .addMenuItem(formMenuItem(10000, templateId, "并发模板", "并发新增菜单")).build());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void deletedFormMenusReleaseTheirTemplatesAfterNormalization(int type) {
        int firstId = insertTemplate("模板一");
        int secondId = insertTemplate("模板二");
        MenuConfigPB defaults = menuMeta.getConfig().toBuilder().setDeptId(DEPT).build();
        MenuItem original = formMenuItem(10000, firstId, "模板一", "待删除菜单")
            .toBuilder().setDisplayName("原显示名").setDisabled(true).build();
        if (type == MenuMeta.MENU_ITEM_TYPE_AGGREGATED_FORMS) {
            original = original.toBuilder().setType(type).clearSeparatedFormTemplate()
                .addAggregatedFormTemplate(IntStrKvPB.newBuilder().setKey(firstId).setVal("模板一"))
                .addAggregatedFormTemplate(IntStrKvPB.newBuilder().setKey(secondId).setVal("模板二"))
                .build();
        }
        MenuConfigPB initial = defaults.toBuilder().addMenuItem(original).build();
        store.saveMenuConfigPB(DEPT, initial, "initial-user");

        GenericResp response = service.deleteMenuItem(deleteRequest(10000));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(store.getMenuConfigPB(DEPT)).isEqualTo(initial.toBuilder()
            .setMenuItem(initial.getMenuItemCount() - 1, original.toBuilder().setGroupId(-1))
            .build());
        MenuConfigPB normalized = service.loadAndNormalizeMenuConfig(DEPT);
        assertThat(normalized.getMenuItemList()).isEqualTo(defaults.getMenuItemList());
        assertThat(normalized.getUnassignedFormTemplateList()).containsExactlyInAnyOrder(
            IntStrKvPB.newBuilder().setKey(firstId).setVal("模板一").build(),
            IntStrKvPB.newBuilder().setKey(secondId).setVal("模板二").build());
        assertThat(store.getMenuConfigPB(DEPT)).isEqualTo(defaults);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedDeletionAndReorderingRollBackNormalizationTogether(boolean reorder) {
        MenuConfigPB initial = menuMeta.getConfig().toBuilder().setDeptId(DEPT)
            .addMenuItem(MenuItem.newBuilder().setFunctionId(999).setGroupId(1).setType(-1))
            .build();
        store.saveMenuConfigPB(DEPT, initial, "initial-user");
        AtomicInteger saves = new AtomicInteger();
        SettingsStore failingStore = mock(SettingsStore.class, delegatesTo(store));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            store.saveMenuConfigPB(invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2));
            deptSettingsRepo.flush();
            if (saves.incrementAndGet() == 2) {
                throw new IllegalStateException("模拟删除或排序保存失败");
            }
            return null;
        }).when(failingStore).saveMenuConfigPB(eq(DEPT), any(), anyString());
        MenuService failingService = new MenuService(
            protoConfig, menuMeta, failingStore, reader, users, executor);

        assertThatThrownBy(() -> {
            if (reorder) {
                failingService.reorderMenuItems(reorderRequest(List.of(
                    initial.getMenuItem(1).getFunctionId(),
                    initial.getMenuItem(0).getFunctionId())));
            } else {
                failingService.deleteMenuItem(
                    deleteRequest(initial.getMenuItem(0).getFunctionId()));
            }
        }).isInstanceOf(IllegalStateException.class).hasMessage("模拟删除或排序保存失败");
        assertThat(saves.get()).isEqualTo(2);
        assertThat(store.getMenuConfigPB(DEPT)).isEqualTo(initial);
        assertThat(lockCount(DEPT)).isZero();
    }

    @Test
    void concurrentTemplateAppendsCannotAssignTheSameTemplateTwice() throws Exception {
        int firstId = insertTemplate("模板一");
        int secondId = insertTemplate("模板二");
        int newId = insertTemplate("新增模板");
        MenuConfigPB initial = menuMeta.getConfig().toBuilder().setDeptId(DEPT)
            .addMenuItem(aggregatedMenuItem(10000, "聚合一", templateRef(firstId, "模板一")))
            .addMenuItem(aggregatedMenuItem(10001, "聚合二", templateRef(secondId, "模板二")))
            .build();
        store.saveMenuConfigPB(DEPT, initial, "initial-user");

        List<GenericResp> responses = runConcurrentlyUnderMenuLock(
            () -> service.addMenuItemFormTemplate(appendRequest(10000, List.of(newId))),
            () -> service.addMenuItemFormTemplate(appendRequest(10001, List.of(newId))));

        assertThat(responses).extracting(response -> response.getRt().getCode())
            .containsExactlyInAnyOrder(StatusCode.OK_VALUE,
                StatusCode.FORM_TEMPLATE_NOT_AVAILABLE_FOR_ASSIGNMENT_VALUE);
        int changedIndex = responses.get(0).getRt().getCode() == StatusCode.OK_VALUE
            ? initial.getMenuItemCount() - 2 : initial.getMenuItemCount() - 1;
        assertThat(store.getMenuConfigPB(DEPT)).isEqualTo(initial.toBuilder()
            .setMenuItem(changedIndex, initial.getMenuItem(changedIndex).toBuilder()
                .addAggregatedFormTemplate(templateRef(newId, "新增模板"))).build());
    }

    @Test
    void concurrentAppendsToOneItemKeepBothTemplatesAndTheirNames() throws Exception {
        int firstId = insertTemplate("原模板");
        int secondId = insertTemplate("新增二");
        int thirdId = insertTemplate("新增三");
        MenuItem original = aggregatedMenuItem(10000, "聚合菜单", templateRef(firstId, "原模板"))
            .toBuilder().setDisplayName("原显示名").setDisabled(true).build();
        MenuConfigPB initial = menuMeta.getConfig().toBuilder().setDeptId(DEPT)
            .addMenuItem(original).build();
        store.saveMenuConfigPB(DEPT, initial, "initial-user");

        List<GenericResp> responses = runConcurrentlyUnderMenuLock(
            () -> service.addMenuItemFormTemplate(appendRequest(10000, List.of(secondId))),
            () -> service.addMenuItemFormTemplate(appendRequest(10000, List.of(thirdId))));

        assertThat(responses).extracting(response -> response.getRt().getCode())
            .containsOnly(StatusCode.OK_VALUE);
        MenuConfigPB stored = store.getMenuConfigPB(DEPT);
        MenuItem updated = stored.getMenuItem(stored.getMenuItemCount() - 1);
        assertThat(updated.getAggregatedFormTemplate(0)).isEqualTo(templateRef(firstId, "原模板"));
        assertThat(updated.getAggregatedFormTemplateList()).containsExactlyInAnyOrder(
            templateRef(firstId, "原模板"), templateRef(secondId, "新增二"), templateRef(thirdId, "新增三"));
        assertThat(stored.toBuilder().setMenuItem(stored.getMenuItemCount() - 1,
            updated.toBuilder().clearAggregatedFormTemplate()
                .addAllAggregatedFormTemplate(original.getAggregatedFormTemplateList())).build())
            .isEqualTo(initial);
    }

    @Test
    void concurrentTemplateDeletionAndReassignmentObserveTheLockOrder() throws Exception {
        int firstId = insertTemplate("待移动模板");
        int secondId = insertTemplate("源菜单模板");
        int thirdId = insertTemplate("目标菜单模板");
        MenuItem source = aggregatedMenuItem(10000, "源菜单",
            templateRef(firstId, "待移动模板"), templateRef(secondId, "源菜单模板"));
        MenuItem destination = aggregatedMenuItem(10001, "目标菜单",
            templateRef(thirdId, "目标菜单模板"));
        MenuConfigPB initial = menuMeta.getConfig().toBuilder().setDeptId(DEPT)
            .addMenuItem(source).addMenuItem(destination).build();
        store.saveMenuConfigPB(DEPT, initial, "initial-user");

        List<GenericResp> responses = runConcurrentlyUnderMenuLock(
            () -> service.deleteMenuItemFormTemplate(deleteTemplateRequest(firstId)),
            () -> service.addMenuItemFormTemplate(appendRequest(10001, List.of(firstId))));

        assertThat(responses.get(0).getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        int appendCode = responses.get(1).getRt().getCode();
        assertThat(appendCode).isIn(StatusCode.OK_VALUE,
            StatusCode.FORM_TEMPLATE_NOT_AVAILABLE_FOR_ASSIGNMENT_VALUE);
        MenuItem.Builder expectedDestination = destination.toBuilder();
        if (appendCode == StatusCode.OK_VALUE) {
            expectedDestination.addAggregatedFormTemplate(templateRef(firstId, "待移动模板"));
        }
        assertThat(store.getMenuConfigPB(DEPT)).isEqualTo(initial.toBuilder()
            .setMenuItem(initial.getMenuItemCount() - 2, source.toBuilder()
                .clearAggregatedFormTemplate().addAggregatedFormTemplate(
                    templateRef(secondId, "源菜单模板")))
            .setMenuItem(initial.getMenuItemCount() - 1, expectedDestination).build());
    }

    @Test
    void concurrentAppendAndReorderCannotLoseTheNewTemplate() throws Exception {
        int firstId = insertTemplate("模板一");
        int secondId = insertTemplate("模板二");
        int thirdId = insertTemplate("新增模板");
        MenuItem original = aggregatedMenuItem(10000, "聚合菜单",
            templateRef(firstId, "模板一"), templateRef(secondId, "模板二"));
        MenuConfigPB initial = menuMeta.getConfig().toBuilder().setDeptId(DEPT)
            .addMenuItem(original).build();
        store.saveMenuConfigPB(DEPT, initial, "initial-user");

        List<GenericResp> responses = runConcurrentlyUnderMenuLock(
            () -> service.addMenuItemFormTemplate(appendRequest(10000, List.of(thirdId))),
            () -> service.reorderMenuItemFormTemplates(
                reorderTemplatesRequest(10000, List.of(secondId, firstId))));

        assertThat(responses.get(0).getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        int reorderCode = responses.get(1).getRt().getCode();
        assertThat(reorderCode).isIn(StatusCode.OK_VALUE,
            StatusCode.FORM_TEMPLATE_NOT_IN_REORDER_LIST_VALUE);
        List<IntStrKvPB> expected = reorderCode == StatusCode.OK_VALUE
            ? List.of(templateRef(secondId, "模板二"), templateRef(firstId, "模板一"),
                templateRef(thirdId, "新增模板"))
            : List.of(templateRef(firstId, "模板一"), templateRef(secondId, "模板二"),
                templateRef(thirdId, "新增模板"));
        assertThat(store.getMenuConfigPB(DEPT)).isEqualTo(initial.toBuilder()
            .setMenuItem(initial.getMenuItemCount() - 1, original.toBuilder()
                .clearAggregatedFormTemplate().addAllAggregatedFormTemplate(expected)).build());
    }

    @Test
    void deletingTheLastAggregatedReferenceReleasesItAfterNormalization() {
        int templateId = insertTemplate("唯一模板");
        MenuConfigPB defaults = menuMeta.getConfig().toBuilder().setDeptId(DEPT).build();
        MenuItem original = aggregatedMenuItem(10000, "聚合菜单", templateRef(templateId, "唯一模板"));
        MenuConfigPB initial = defaults.toBuilder().addMenuItem(original).build();
        store.saveMenuConfigPB(DEPT, initial, "initial-user");

        GenericResp response = service.deleteMenuItemFormTemplate(
            deleteTemplateRequest(templateId));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(store.getMenuConfigPB(DEPT)).isEqualTo(initial.toBuilder()
            .setMenuItem(initial.getMenuItemCount() - 1,
                original.toBuilder().clearAggregatedFormTemplate()).build());
        MenuConfigPB normalized = service.loadAndNormalizeMenuConfig(DEPT);
        assertThat(normalized.getMenuItemList()).isEqualTo(defaults.getMenuItemList());
        assertThat(normalized.getUnassignedFormTemplateList())
            .containsExactly(templateRef(templateId, "唯一模板"));
        assertThat(store.getMenuConfigPB(DEPT)).isEqualTo(defaults);
    }

    @ParameterizedTest
    @ValueSource(strings = {"add", "delete", "reorder"})
    void failedTemplateChangesRollBackNormalizationAndTheChangeTogether(String operation) {
        int firstId = insertTemplate("模板一");
        int secondId = insertTemplate("模板二");
        int thirdId = insertTemplate("新增模板");
        MenuConfigPB initial = menuMeta.getConfig().toBuilder().setDeptId(DEPT)
            .addMenuItem(aggregatedMenuItem(10000, "聚合菜单",
                templateRef(firstId, "模板一"), templateRef(secondId, "模板二")))
            .addMenuItem(MenuItem.newBuilder().setFunctionId(999).setType(-1).setGroupId(1))
            .build();
        store.saveMenuConfigPB(DEPT, initial, "initial-user");
        AtomicInteger saves = new AtomicInteger();
        SettingsStore failingStore = mock(SettingsStore.class, delegatesTo(store));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            store.saveMenuConfigPB(invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2));
            deptSettingsRepo.flush();
            if (saves.incrementAndGet() == 2) {
                throw new IllegalStateException("模拟聚合模板保存失败");
            }
            return null;
        }).when(failingStore).saveMenuConfigPB(eq(DEPT), any(), anyString());
        MenuService failingService = new MenuService(
            protoConfig, menuMeta, failingStore, reader, users, executor);

        assertThatThrownBy(() -> {
            switch (operation) {
                case "add" -> failingService.addMenuItemFormTemplate(
                    appendRequest(10000, List.of(thirdId)));
                case "delete" -> failingService.deleteMenuItemFormTemplate(
                    deleteTemplateRequest(firstId));
                case "reorder" -> failingService.reorderMenuItemFormTemplates(
                    reorderTemplatesRequest(10000, List.of(secondId, firstId)));
                default -> throw new IllegalArgumentException(operation);
            }
        }).isInstanceOf(IllegalStateException.class).hasMessage("模拟聚合模板保存失败");
        assertThat(saves.get()).isEqualTo(2);
        assertThat(store.getMenuConfigPB(DEPT)).isEqualTo(initial);
        assertThat(lockCount(DEPT)).isZero();
    }

    private MenuItem aggregatedMenuItem(int functionId, String name, IntStrKvPB... templates) {
        return MenuItem.newBuilder().setFunctionId(functionId).setName(name)
            .setType(MenuMeta.MENU_ITEM_TYPE_AGGREGATED_FORMS)
            .setGroupId(menuMeta.getConfig().getMenuItem(0).getGroupId())
            .addAllAggregatedFormTemplate(List.of(templates)).build();
    }

    private IntStrKvPB templateRef(int id, String name) {
        return IntStrKvPB.newBuilder().setKey(id).setVal(name).build();
    }

    private String appendRequest(int functionId, List<Integer> templateIds) {
        return ProtoUtils.protoToJson(AddMenuItemFormTemplateReq.newBuilder()
            .setDeptId(DEPT).setFunctionId(functionId).addAllFormTemplateId(templateIds).build());
    }

    private String deleteTemplateRequest(int templateId) {
        return ProtoUtils.protoToJson(DeleteMenuItemFormTemplateReq.newBuilder()
            .setDeptId(DEPT).setFormTemplateId(templateId).build());
    }

    private String reorderTemplatesRequest(int functionId, List<Integer> templateIds) {
        return ProtoUtils.protoToJson(ReorderMenuItemFormTemplatesReq.newBuilder()
            .setDeptId(DEPT).setFunctionId(functionId).addAllFormTemplateId(templateIds).build());
    }

    private String deleteRequest(int functionId) {
        return ProtoUtils.protoToJson(DeleteMenuItemReq.newBuilder()
            .setDeptId(DEPT).setFunctionId(functionId).build());
    }

    private String reorderRequest(List<Integer> functionIds) {
        return ProtoUtils.protoToJson(ReorderMenuItemsReq.newBuilder()
            .setDeptId(DEPT).addAllFunctionId(functionIds).build());
    }

    private List<GenericResp> runConcurrentlyUnderMenuLock(
        Supplier<GenericResp> firstAction, Supplier<GenericResp> secondAction
    ) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(
                List.of(menuLockKey(DEPT)), () -> {
                    holderPid.set(jdbcTemplate.queryForObject(
                        "SELECT pg_backend_pid()", Integer.class));
                    locked.countDown();
                    awaitRelease(release);
                    return null;
                }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<GenericResp> first = pool.submit(firstAction::get);
            Future<GenericResp> second = pool.submit(secondAction::get);
            assertThat(waitForBlockers(holderPid.get(), 2)).isTrue();

            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    private MenuItem formMenuItem(
        int functionId, int templateId, String templateName, String menuName
    ) {
        return MenuItem.newBuilder().setFunctionId(functionId).setName(menuName)
            .setType(MenuMeta.MENU_ITEM_TYPE_SEPARATED_FORM)
            .setGroupId(menuMeta.getConfig().getMenuItem(0).getGroupId())
            .setSeparatedFormTemplate(IntStrKvPB.newBuilder()
                .setKey(templateId).setVal(templateName)).build();
    }

    private String renameRequest(int functionId, String name) {
        return ProtoUtils.protoToJson(UpdateMenuItemNameReq.newBuilder()
            .setDeptId(DEPT).setFunctionId(functionId).setNewName(name).build());
    }

    private String replaceRequest(int functionId, int templateId) {
        return ProtoUtils.protoToJson(UpdateMenuItemWithOneFormReq.newBuilder()
            .setDeptId(DEPT).setFunctionId(functionId).setFormTemplateId(templateId).build());
    }

    private String addRequest(int templateId, String name) {
        int groupId = menuMeta.getConfig().getMenuItem(0).getGroupId();
        return ProtoUtils.protoToJson(AddMenuItemReq.newBuilder().setDeptId(DEPT)
            .setMenuItem(MenuItem.newBuilder().setGroupId(groupId).setType(1).setName(name)
                .setSeparatedFormTemplate(IntStrKvPB.newBuilder().setKey(templateId)))
            .build());
    }

    private int insertTemplate(String name) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO dragable_form_templates(dept_id, name, template_pb, is_deleted)
            VALUES (?, ?, '', false) RETURNING id
            """, Integer.class, DEPT, name);
    }

    private boolean waitForBlockers(int blockerPid, int expectedCount) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_stat_activity a
                WHERE a.datname = current_database() AND a.wait_event_type = 'Lock'
                  AND ? = ANY(pg_blocking_pids(a.pid))
                """, Integer.class, blockerPid);
            if (waiting != null && waiting >= expectedCount) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待释放菜单锁超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待释放菜单锁时被中断", e);
        }
    }

    private String menuLockKey(String deptId) {
        return LockKeys.deptSystemSettings(deptId, SettingsStore.GET_DEPT_MENU_SETTINGS);
    }

    private int lockCount(String deptId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_transaction_locks WHERE resource_key = ?",
            Integer.class, menuLockKey(deptId));
    }

    private static final String DEPT = "menu-lock-tests";
    private static final String OTHER_DEPT = "menu-lock-tests-other";

    @Autowired private MenuService service;
    @Autowired private MenuMeta menuMeta;
    @Autowired private SettingsStore store;
    @Autowired private LockExecutor executor;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ProtoConfig protoConfig;
    @Autowired private JfkTemplateReader reader;
    @Autowired private UserQuery users;
    @Autowired private DeptSystemSettingsRepository deptSettingsRepo;
}
