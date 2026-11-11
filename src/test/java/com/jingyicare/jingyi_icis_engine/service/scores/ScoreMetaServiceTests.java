package com.jingyicare.jingyi_icis_engine.service.scores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.entity.scores.DeptScoreGroup;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.repository.scores.DeptScoreGroupRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class ScoreMetaServiceTests extends TestsBase {
    @BeforeEach
    void prepare() {
        cleanDepartment();
        jdbcTemplate.update(
            "INSERT INTO rbac_departments(dept_id, dept_name) VALUES (?, ?)", DEPT_ID, DEPT_ID);
        jdbcTemplate.update(
            "INSERT INTO rbac_accounts(account_id, account_name) VALUES (?, ?)", ACCOUNT, ACCOUNT);
        authenticate();
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        cleanDepartment();
    }

    @Test
    void concurrentInitializationIsIdempotentAndLocksEveryDefaultCode() throws Exception {
        Supplier<Integer> initialize = () -> {
            service.initDept(DEPT_ID);
            return StatusCode.OK_VALUE;
        };
        assertThat(runContending(initialize, initialize))
            .containsExactly(StatusCode.OK_VALUE, StatusCode.OK_VALUE);

        List<DeptScoreGroup> initialized = activeGroups();
        List<String> defaults = protoService.getConfig().getScore().getDefaultScoreGroupCodeList();
        assertThat(initialized).extracting(DeptScoreGroup::getScoreGroupCode)
            .containsExactlyElementsOf(defaults);
        for (int i = 0; i < initialized.size(); i++) {
            DeptScoreGroup group = initialized.get(i);
            assertThat(group.getDisplayOrder()).isEqualTo(i + 1);
            assertThat(group.getModifiedBy()).isEqualTo("System");
            assertThat(lockCount(LockKeys.deptScoreGroup(DEPT_ID, group.getScoreGroupCode())))
                .isEqualTo(1);
        }
        service.initDept(DEPT_ID);
        assertThat(activeGroups()).extracting(DeptScoreGroup::getId)
            .containsExactlyElementsOf(initialized.stream().map(DeptScoreGroup::getId).toList());
    }

    @Test
    void initializationSkipsExistingGroupAndRecreatesDefaultsAfterAllGroupsAreDeleted() {
        AddDeptScoreGroupResp added = addGroup(SECOND_CODE);
        assertThat(added.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        service.initDept(DEPT_ID);
        assertThat(activeGroups()).extracting(DeptScoreGroup::getScoreGroupCode)
            .containsExactly(SECOND_CODE);

        String deleteRequest = ProtoUtils.protoToJson(DeleteDeptScoreGroupReq.newBuilder()
            .setId(added.getId()).build());
        assertOk(service.deleteDeptScoreGroup(deleteRequest));
        assertOk(service.deleteDeptScoreGroup(deleteRequest));
        DeptScoreGroup deleted = repository.findById(added.getId()).orElseThrow();
        assertThat(deleted.getIsDeleted()).isTrue();
        assertThat(deleted.getDeletedAt()).isNotNull();
        assertThat(deleted.getDeletedBy()).isNotBlank();

        service.initDept(DEPT_ID);
        assertThat(activeGroups()).extracting(DeptScoreGroup::getScoreGroupCode)
            .containsExactlyElementsOf(
                protoService.getConfig().getScore().getDefaultScoreGroupCodeList());
        assertThat(repository.findById(added.getId()).orElseThrow()).isEqualTo(deleted);
    }

    @Test
    void invalidInitializationConfigurationRollsBackAndPropagatesFailure() {
        ConfigProtoService invalidConfig = mock(ConfigProtoService.class);
        when(invalidConfig.getConfig()).thenReturn(protoService.getConfig().toBuilder()
            .setScore(protoService.getConfig().getScore().toBuilder()
                .clearDefaultScoreGroupCode().addDefaultScoreGroupCode(FIRST_CODE)
                .addDefaultScoreGroupCode("missing-default-code")).build());
        ScoreMetaService invalidService = new ScoreMetaService(invalidConfig,
            mock(UserQuery.class), repository, executor, jdbcTemplate);

        assertThatIllegalStateException().isThrownBy(() -> invalidService.initDept(DEPT_ID));

        assertThat(activeGroups()).isEmpty();
        assertThat(lockCount(LockKeys.deptScoreGroup(DEPT_ID))).isZero();
        assertThat(lockCount(LockKeys.deptScoreGroup(DEPT_ID, FIRST_CODE))).isZero();
    }

    @Test
    void readsReturnGlobalTemplatesAndFallbackWithoutCreatingRowsOrLocks() {
        GetScoreGroupMetaResp global = service.getScoreGroupMeta(ProtoUtils.protoToJson(
            GetScoreGroupMetaReq.getDefaultInstance()));
        assertThat(global.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(global.getScoreGroupMetaList()).extracting(ScoreGroupMetaPB::getCode)
            .containsExactlyElementsOf(protoService.getConfig().getScore().getGroupList()
                .stream().map(ScoreGroupMetaPB::getCode).toList());
        assertThat(readMeta(FIRST_CODE).getCode()).isEqualTo(FIRST_CODE);
        assertThat(activeGroups()).isEmpty();
        assertThat(lockCount(LockKeys.deptScoreGroup(DEPT_ID))).isZero();
        assertThat(lockCount(LockKeys.deptScoreGroup(DEPT_ID, FIRST_CODE))).isZero();
    }

    @Test
    void listWithUnknownCodeAndCorruptMetadataReturnsNotFound() {
        assertThat(addGroup(FIRST_CODE).getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        repository.save(DeptScoreGroup.builder().deptId(DEPT_ID)
            .scoreGroupCode("unknown-corrupt-score").scoreGroupMeta("not-base64")
            .displayOrder(2).isDeleted(false).build());

        GetScoreGroupMetaResp response = service.getScoreGroupMeta(ProtoUtils.protoToJson(
            GetScoreGroupMetaReq.newBuilder().setDeptId(DEPT_ID).build()));

        assertThat(response.getRt().getCode())
            .isEqualTo(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND_VALUE);
    }

    @Test
    void concurrentAddsOfSameCodeCreateOnlyOneActiveGroup() throws Exception {
        List<Integer> statusCodes = runContending(
            () -> addGroup(FIRST_CODE).getRt().getCode(),
            () -> addGroup(FIRST_CODE).getRt().getCode());

        assertThat(statusCodes).containsExactlyInAnyOrder(
            StatusCode.OK_VALUE, StatusCode.DEPT_SCORE_GROUP_ALREADY_EXISTS_VALUE);
        assertThat(activeGroups()).hasSize(1);
        assertThat(lockCount(LockKeys.deptScoreGroup(DEPT_ID, FIRST_CODE))).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentFirstWritesOfDifferentCodesAppendDistinctOrders(boolean implicit)
            throws Exception {
        List<Integer> statusCodes = runContending(
            () -> firstWrite(FIRST_CODE, implicit), () -> firstWrite(SECOND_CODE, implicit));

        assertThat(statusCodes).containsExactly(StatusCode.OK_VALUE, StatusCode.OK_VALUE);
        assertThat(activeGroups()).extracting(DeptScoreGroup::getDisplayOrder)
            .containsExactly(1, 2);
        assertThat(activeGroups()).extracting(DeptScoreGroup::getScoreGroupCode)
            .containsExactlyInAnyOrder(FIRST_CODE, SECOND_CODE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentMetadataUpdatesPreserveBothFields(boolean groupExists) throws Exception {
        if (groupExists) assertThat(addGroup(FIRST_CODE).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        String nameRequest = ProtoUtils.protoToJson(UpdateScoreGroupMetaReq.newBuilder()
            .setDeptId(DEPT_ID).setScoreGroupCode(FIRST_CODE)
            .setUpdateName(true).setName("科室评分别名").build());
        String notesRequest = ProtoUtils.protoToJson(UpdateScoreGroupMetaReq.newBuilder()
            .setDeptId(DEPT_ID).setScoreGroupCode(FIRST_CODE)
            .setUpdateNotes(true).setNotes("评分说明").build());

        assertThat(runContending(
            () -> service.updateScoreGroupMeta(nameRequest).getRt().getCode(),
            () -> service.updateScoreGroupMeta(notesRequest).getRt().getCode()))
            .containsExactly(StatusCode.OK_VALUE, StatusCode.OK_VALUE);

        ScoreGroupMetaPB actual = readMeta(FIRST_CODE);
        assertThat(actual.getName()).isEqualTo("科室评分别名");
        assertThat(actual.getNotes()).isEqualTo("评分说明");
        assertThat(activeGroups()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown-score-code", "vte_caprini"})
    void batchWithUnknownCodeDoesNotPartiallyInsert(String unknownCode) {
        AddDeptScoreGroupsResp response = addGroups(FIRST_CODE, unknownCode);

        assertThat(response.getRt().getCode())
            .isEqualTo(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND_VALUE);
        assertThat(response.getIdList()).isEmpty();
        assertThat(activeGroups()).isEmpty();
    }

    @Test
    void batchWithDuplicateCodeDoesNotInsertAnyGroup() {
        AddDeptScoreGroupsResp response = addGroups(FIRST_CODE, FIRST_CODE);

        assertThat(response.getRt().getCode())
            .isEqualTo(StatusCode.DEPT_SCORE_GROUP_ALREADY_EXISTS_VALUE);
        assertThat(activeGroups()).isEmpty();
    }

    @Test
    void batchAppendsInRequestOrderAndRejectsAlreadyActiveCodeAtomically() {
        assertThat(addGroup(FIRST_CODE).getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(addGroups(SECOND_CODE, FIRST_CODE).getRt().getCode())
            .isEqualTo(StatusCode.DEPT_SCORE_GROUP_ALREADY_EXISTS_VALUE);
        assertThat(activeGroups()).hasSize(1);

        AddDeptScoreGroupsResp response = addGroups(SECOND_CODE, THIRD_CODE);
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(activeGroups()).extracting(DeptScoreGroup::getScoreGroupCode)
            .containsExactly(FIRST_CODE, SECOND_CODE, THIRD_CODE);
        assertThat(activeGroups()).extracting(DeptScoreGroup::getDisplayOrder)
            .containsExactly(1, 2, 3);
        assertThat(response.getIdList()).containsExactly(
            activeGroups().get(1).getId(), activeGroups().get(2).getId());
    }

    @Test
    void invalidReorderLeavesEarlierValidGroupUnchanged() {
        AddDeptScoreGroupsResp added = addGroups(FIRST_CODE, SECOND_CODE);
        assertThat(added.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        DeptScoreGroup before = repository.findById(added.getId(0)).orElseThrow();
        String request = ProtoUtils.protoToJson(ReorderDeptScoreGroupsReq.newBuilder()
            .setDeptId(DEPT_ID)
            .addGroupOrder(order(added.getId(0), 50))
            .addGroupOrder(order(Integer.MAX_VALUE, 60)).build());

        GenericResp response = service.reorderDeptScoreGroups(request);

        assertThat(response.getRt().getCode())
            .isEqualTo(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND_VALUE);
        assertThat(repository.findById(before.getId()).orElseThrow()).isEqualTo(before);
        assertThat(activeGroups()).extracting(DeptScoreGroup::getDisplayOrder)
            .containsExactly(1, 2);
    }

    @Test
    void reorderReplansAfterTargetIsDeletedWhileWaitingAndDoesNotPartiallyUpdate()
            throws Exception {
        AddDeptScoreGroupsResp added = addGroups(FIRST_CODE, SECOND_CODE);
        assertThat(added.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        DeptScoreGroup unchanged = repository.findById(added.getId(0)).orElseThrow();
        int deletedId = added.getId(1);
        String request = ProtoUtils.protoToJson(ReorderDeptScoreGroupsReq.newBuilder()
            .setDeptId(DEPT_ID).addGroupOrder(order(unchanged.getId(), 50))
            .addGroupOrder(order(deletedId, 60)).build());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch deleteAfterPreRead = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> deletion = pool.submit(() -> executor.execute(List.of(
                LockKeys.deptScoreGroup(DEPT_ID), LockKeys.deptScoreGroup(DEPT_ID, SECOND_CODE)),
                () -> {
                    locked.countDown();
                    try {
                        assertTrue(deleteAfterPreRead.await(30, TimeUnit.SECONDS),
                            "等待重排请求完成预读超时");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    // 已持有同一科室和评分组的锁，在当前事务内模拟并发软删除。
                    jdbcTemplate.update("""
                        UPDATE dept_score_groups SET is_deleted = TRUE,
                            deleted_by = ?, deleted_at = CURRENT_TIMESTAMP WHERE id = ?
                        """, ACCOUNT, deletedId);
                    return null;
                }));
            assertTrue(locked.await(10, TimeUnit.SECONDS), "未取得科室业务锁");
            Future<GenericResp> reordered = pool.submit(
                () -> authenticated(() -> service.reorderDeptScoreGroups(request)));
            assertTrue(waitForBlockedRequests(1), "重排应在预读目标后等待科室评分业务锁");
            deleteAfterPreRead.countDown();
            deletion.get(15, TimeUnit.SECONDS);

            assertThat(reordered.get(15, TimeUnit.SECONDS).getRt().getCode())
                .isEqualTo(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND_VALUE);
            assertThat(repository.findById(unchanged.getId()).orElseThrow()).isEqualTo(unchanged);
            assertThat(repository.findById(deletedId).orElseThrow().getIsDeleted()).isTrue();
        } finally {
            deleteAfterPreRead.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void reorderRejectsDuplicateIdsAndAcceptsPartialListsWithArbitraryOrders() {
        AddDeptScoreGroupsResp added = addGroups(FIRST_CODE, SECOND_CODE);
        assertThat(added.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        String duplicateRequest = ProtoUtils.protoToJson(ReorderDeptScoreGroupsReq.newBuilder()
            .setDeptId(DEPT_ID)
            .addGroupOrder(order(added.getId(0), 5))
            .addGroupOrder(order(added.getId(0), 6)).build());
        assertThat(service.reorderDeptScoreGroups(duplicateRequest).getRt().getCode())
            .isEqualTo(StatusCode.INVALID_PARAM_VALUE_VALUE);
        assertThat(activeGroups()).extracting(DeptScoreGroup::getDisplayOrder)
            .containsExactly(1, 2);

        assertOk(service.reorderDeptScoreGroups(ProtoUtils.protoToJson(
            ReorderDeptScoreGroupsReq.newBuilder().setDeptId(DEPT_ID)
                .addGroupOrder(order(added.getId(1), -3)).build())));
        GetScoreGroupMetaResp actual = service.getScoreGroupMeta(ProtoUtils.protoToJson(
            GetScoreGroupMetaReq.newBuilder().setDeptId(DEPT_ID).build()));
        assertThat(actual.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(actual.getScoreGroupMetaList()).extracting(ScoreGroupMetaPB::getCode)
            .containsExactly(SECOND_CODE, FIRST_CODE);
        assertThat(actual.getScoreGroupMetaList()).extracting(ScoreGroupMetaPB::getDisplayOrder)
            .containsExactly(-3, 1);
    }

    @Test
    void itemOptionAndMeasureEditsPreserveOtherMetadataAndSupportImplicitCreation() {
        ScoreGroupMetaPB original = readMeta(FIRST_CODE);
        String itemCode = original.getItem(0).getCode();
        String optionCode = original.getItem(0).getOption(0).getCode();
        assertOk(service.updateScoreItemName(ProtoUtils.protoToJson(
            UpdateScoreItemNameReq.newBuilder().setDeptId(DEPT_ID)
                .setScoreGroupCode(FIRST_CODE).setScoreItemCode(itemCode)
                .setName("评分项别名").build())));
        assertOk(service.updateScoreOptionName(ProtoUtils.protoToJson(
            UpdateScoreOptionNameReq.newBuilder().setDeptId(DEPT_ID)
                .setScoreGroupCode(FIRST_CODE).setScoreItemCode(itemCode)
                .setScoreOptionCode(optionCode).setName("评分选项别名").build())));
        assertOk(service.updateScoreGroupMeta(ProtoUtils.protoToJson(
            UpdateScoreGroupMetaReq.newBuilder().setDeptId(DEPT_ID)
                .setScoreGroupCode(FIRST_CODE).setUpdateMeasureMultiSelection(true)
                .setMeasureMultiSelection(true).build())));

        String addMeasureRequest = ProtoUtils.protoToJson(AddScoreGroupMeasureMetaReq.newBuilder()
            .setDeptId(DEPT_ID).setScoreGroupCode(FIRST_CODE)
            .setMeasure(ScoreGroupMeasureMetaPB.newBuilder().setName("观察").setMeasureType(1))
            .build());
        assertOk(service.addScoreGroupMeasureMeta(addMeasureRequest));
        assertThat(service.addScoreGroupMeasureMeta(addMeasureRequest).getRt().getCode())
            .isEqualTo(StatusCode.SCORE_GROUP_MEASURE_ALREADY_EXISTS_VALUE);
        ScoreGroupMeasureMetaPB measure = readMeta(FIRST_CODE).getMeasure(0);
        assertThat(measure.getId()).isPositive();
        assertOk(service.updateScoreGroupMeasureMeta(ProtoUtils.protoToJson(
            UpdateScoreGroupMeasureMetaReq.newBuilder().setDeptId(DEPT_ID)
                .setScoreGroupCode(FIRST_CODE)
                .setMeasure(measure.toBuilder().setName("加强观察").setMeasureType(2)).build())));
        ScoreGroupMetaPB actual = readMeta(FIRST_CODE);
        assertThat(actual.getMeasure(0).getName()).isEqualTo("加强观察");
        assertThat(actual.getMeasure(0).getMeasureType()).isEqualTo(2);
        assertThat(actual.getMeasureMultiSelection()).isTrue();
        assertThat(actual.getItem(0).getName()).isEqualTo("评分项别名");
        assertThat(actual.getItem(0).getOption(0).getName()).isEqualTo("评分选项别名");
        assertThat(actual.getItem(1)).isEqualTo(original.getItem(1));
        assertThat(actual.getValueMeta()).isEqualTo(original.getValueMeta());

        String deleteMeasureRequest = ProtoUtils.protoToJson(
            DeleteScoreGroupMeasureMetaReq.newBuilder().setDeptId(DEPT_ID)
                .setScoreGroupCode(FIRST_CODE).setMeasureName("加强观察").build());
        assertOk(service.deleteScoreGroupMeasureMeta(deleteMeasureRequest));
        assertOk(service.deleteScoreGroupMeasureMeta(deleteMeasureRequest));
        assertThat(readMeta(FIRST_CODE).getMeasureList()).isEmpty();
        assertThat(activeGroups()).hasSize(1);
    }

    @Test
    void invalidParametersAndMissingNestedMetadataDoNotCreateGroups() {
        assertThat(service.addDeptScoreGroup(ProtoUtils.protoToJson(
            AddDeptScoreGroupReq.newBuilder().setDeptId(" ")
                .setScoreGroupCode(FIRST_CODE).build())).getRt().getCode())
            .isEqualTo(StatusCode.INVALID_PARAM_VALUE_VALUE);
        assertThat(service.updateScoreGroupMeta(ProtoUtils.protoToJson(
            UpdateScoreGroupMetaReq.newBuilder().setDeptId(DEPT_ID)
                .setScoreGroupCode(" ").setUpdateName(true).setName("无效").build()))
            .getRt().getCode()).isEqualTo(StatusCode.INVALID_PARAM_VALUE_VALUE);
        assertThat(service.updateScoreItemName(ProtoUtils.protoToJson(
            UpdateScoreItemNameReq.newBuilder().setDeptId(DEPT_ID)
                .setScoreGroupCode(FIRST_CODE).setScoreItemCode("missing").build()))
            .getRt().getCode()).isEqualTo(StatusCode.SCORE_ITEM_NOT_FOUND_VALUE);
        assertThat(service.updateScoreOptionName(ProtoUtils.protoToJson(
            UpdateScoreOptionNameReq.newBuilder().setDeptId(DEPT_ID)
                .setScoreGroupCode(FIRST_CODE).setScoreItemCode("missing")
                .setScoreOptionCode("missing").build())).getRt().getCode())
            .isEqualTo(StatusCode.SCORE_OPTION_NOT_FOUND_VALUE);
        assertThat(service.updateScoreGroupMeasureMeta(ProtoUtils.protoToJson(
            UpdateScoreGroupMeasureMetaReq.newBuilder().setDeptId(DEPT_ID)
                .setScoreGroupCode(FIRST_CODE)
                .setMeasure(ScoreGroupMeasureMetaPB.newBuilder().setId(999)).build()))
            .getRt().getCode()).isEqualTo(StatusCode.SCORE_GROUP_MEASURE_NOT_FOUND_VALUE);
        assertThat(activeGroups()).isEmpty();
    }

    private AddDeptScoreGroupResp addGroup(String code) {
        return service.addDeptScoreGroup(ProtoUtils.protoToJson(AddDeptScoreGroupReq.newBuilder()
            .setDeptId(DEPT_ID).setScoreGroupCode(code).setDisplayOrder(99).build()));
    }

    private AddDeptScoreGroupsResp addGroups(String... codes) {
        AddDeptScoreGroupsReq.Builder request = AddDeptScoreGroupsReq.newBuilder()
            .setDeptId(DEPT_ID);
        for (String code : codes) {
            request.addGroup(DeptScoreGroupPB.newBuilder().setScoreGroupCode(code)
                .setDisplayOrder(99));
        }
        return service.addDeptScoreGroups(ProtoUtils.protoToJson(request.build()));
    }

    private int firstWrite(String code, boolean implicit) {
        if (!implicit) return addGroup(code).getRt().getCode();
        return service.updateScoreGroupMeta(ProtoUtils.protoToJson(
            UpdateScoreGroupMetaReq.newBuilder().setDeptId(DEPT_ID).setScoreGroupCode(code)
                .setUpdateNotes(true).setNotes("首次保存").build())).getRt().getCode();
    }

    private ScoreGroupMetaPB readMeta(String code) {
        GetOneScoreGroupMetaResp response = service.getOneScoreGroupMeta(ProtoUtils.protoToJson(
            GetOneScoreGroupMetaReq.newBuilder().setDeptId(DEPT_ID).setCode(code).build()));
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        return response.getScoreGroupMeta();
    }

    private ScoreGroupOrder order(int id, int displayOrder) {
        return ScoreGroupOrder.newBuilder().setId(id).setDisplayOrder(displayOrder).build();
    }

    private List<DeptScoreGroup> activeGroups() {
        return repository.findByDeptIdAndIsDeletedFalse(DEPT_ID).stream()
            .sorted(java.util.Comparator.comparing(DeptScoreGroup::getDisplayOrder)).toList();
    }

    private void assertOk(GenericResp response) {
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
    }

    private <T> List<T> runContending(Supplier<T> first, Supplier<T> second) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(
                List.of(LockKeys.deptScoreGroup(DEPT_ID)), () -> {
                    locked.countDown();
                    try {
                        assertTrue(release.await(30, TimeUnit.SECONDS), "等待释放业务锁超时");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return null;
                }));
            assertTrue(locked.await(10, TimeUnit.SECONDS), "未取得科室业务锁");
            Future<T> firstResult = pool.submit(() -> authenticated(first));
            Future<T> secondResult = pool.submit(() -> authenticated(second));
            // 确认两个请求都已经进入锁等待，避免依赖线程调度制造竞争。
            assertTrue(waitForBlockedRequests(2), "两个请求应同时等待科室评分业务锁");
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            return List.of(firstResult.get(15, TimeUnit.SECONDS),
                secondResult.get(15, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private boolean waitForBlockedRequests(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock'
                  AND query LIKE '%app_transaction_locks%'
                """, Integer.class);
            if (waiting != null && waiting >= expected) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private <T> T authenticated(Supplier<T> action) {
        authenticate();
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(ACCOUNT, "", List.of()));
    }

    private int lockCount(String key) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_transaction_locks WHERE resource_key = ?",
            Integer.class, key);
    }

    private void cleanDepartment() {
        jdbcTemplate.update("DELETE FROM dept_score_groups WHERE dept_id = ?", DEPT_ID);
        jdbcTemplate.update("DELETE FROM rbac_departments WHERE dept_id = ?", DEPT_ID);
        jdbcTemplate.update("DELETE FROM rbac_accounts WHERE account_id = ?", ACCOUNT);
        jdbcTemplate.update("""
            DELETE FROM app_transaction_locks WHERE resource_key = ? OR resource_key LIKE ?
            """, LockKeys.deptScoreGroup(DEPT_ID), LockKeys.deptScoreGroup(DEPT_ID) + ":%");
    }

    private static final String DEPT_ID = "score-meta-tests";
    private static final String ACCOUNT = "score-meta-tests-user";
    private static final String FIRST_CODE = "test_score_1";
    private static final String SECOND_CODE = "test_score_2";
    private static final String THIRD_CODE = "test_score_3";

    @Autowired private ScoreMetaService service;
    @Autowired private ConfigProtoService protoService;
    @Autowired private DeptScoreGroupRepository repository;
    @Autowired private LockExecutor executor;
    @Autowired private JdbcTemplate jdbcTemplate;
}
