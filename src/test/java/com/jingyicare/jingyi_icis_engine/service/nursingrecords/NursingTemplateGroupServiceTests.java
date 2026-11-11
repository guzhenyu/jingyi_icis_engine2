package com.jingyicare.jingyi_icis_engine.service.nursingrecords;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class NursingTemplateGroupServiceTests extends TestsBase {
    @BeforeEach
    void prepare() {
        cleanUp();
        jdbcTemplate.update(
            "INSERT INTO rbac_departments(dept_id, dept_name) VALUES (?, ?)", DEPT_ID, DEPT_ID);
        userQuery = mock(UserQuery.class);
        when(userQuery.getAccountWithAutoId()).thenReturn(new Pair<>(ACCOUNT_ID, "测试用户"));
        service = newService(lockExecutor);
    }

    @AfterEach
    void cleanUp() {
        for (String entityId : List.of(DEPT_ID, ACCOUNT_ID)) {
            jdbcTemplate.update(
                "DELETE FROM nursing_record_templates WHERE entity_id = ?", entityId);
            jdbcTemplate.update(
                "DELETE FROM nursing_record_template_groups WHERE entity_id = ?", entityId);
        }
        jdbcTemplate.update("DELETE FROM rbac_departments WHERE dept_id = ?", DEPT_ID);
        jdbcTemplate.update("""
            DELETE FROM app_transaction_locks
            WHERE resource_key LIKE ? OR resource_key LIKE ?
            """, "nursing_record_template_groups:1:" + DEPT_ID + ":%",
            "nursing_record_template_groups:0:" + ACCOUNT_ID + ":%");
    }

    @Test
    void shouldCreateDepartmentAndAccountGroupsWithSeparateLocks() {
        int deptGroupId = createGroup("same-name");
        AddNursingRecordTemplateGroupResp personal = service.addNursingRecordTemplateGroup(
            addRequest("", "same-name"));

        assertThat(personal.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        NursingRecordTemplateGroup deptGroup = group(deptGroupId);
        NursingRecordTemplateGroup personalGroup = group(personal.getId());
        assertThat(deptGroup.getEntityType()).isEqualTo(1);
        assertThat(deptGroup.getEntityId()).isEqualTo(DEPT_ID);
        assertThat(personalGroup.getEntityType()).isZero();
        assertThat(personalGroup.getEntityId()).isEqualTo(ACCOUNT_ID);
        assertThat(deptGroup.getModifiedBy()).isEqualTo(ACCOUNT_ID);
        assertThat(personalGroup.getModifiedAt()).isNotNull();
        assertThat(lockCount(1, DEPT_ID, "same-name")).isEqualTo(1);
        assertThat(lockCount(0, ACCOUNT_ID, "same-name")).isEqualTo(1);
    }

    @Test
    void shouldLockOriginalAndTargetNamesTogetherWhenRenaming() {
        int id = createGroup("original");
        service = serviceBeforeLock(keys -> assertThat(keys).containsExactlyInAnyOrder(
            LockKeys.nursingRecordTemplateGroup(1, DEPT_ID, "original"),
            LockKeys.nursingRecordTemplateGroup(1, DEPT_ID, "target")));

        GenericResp response = service.updateNursingRecordTemplateGroups(
            updateRequest(groupPB(id, "target", 4)));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(group(id).getName()).isEqualTo("target");
        assertThat(group(id).getDisplayOrder()).isEqualTo(4);
        assertThat(group(id).getModifiedBy()).isEqualTo(ACCOUNT_ID);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRejectNameChangedBetweenPreReadAndLock(boolean delete) {
        int id = createGroup("original");
        service = serviceBeforeLock(keys -> jdbcTemplate.update(
            "UPDATE nursing_record_template_groups SET name = ? WHERE id = ?", "changed", id));

        GenericResp response = delete
            ? service.deleteNursingRecordTemplateGroup(deleteRequest(id))
            : service.updateNursingRecordTemplateGroups(updateRequest(groupPB(id, "target", 4)));

        assertThat(response.getRt().getCode())
            .isEqualTo(StatusCode.INVALID_PARAM_VALUE.getNumber());
        assertThat(group(id).getName()).isEqualTo("changed");
        assertThat(group(id).getIsDeleted()).isFalse();
        assertThat(group(id).getDisplayOrder()).isZero();
    }

    @Test
    void shouldValidateWholeBatchBeforeChangingAnyGroup() {
        int first = createGroup("first");
        int second = createGroup("second");

        GenericResp response = service.updateNursingRecordTemplateGroups(updateRequest(
            groupPB(first, "renamed", 3), groupPB(second, "first", 4)));
        assertThat(response.getRt().getCode()).isEqualTo(
            StatusCode.NURSING_RECORD_TEMPLATE_GROUP_ALREADY_EXISTS.getNumber());
        assertThat(group(first).getName()).isEqualTo("first");
        assertThat(group(first).getDisplayOrder()).isZero();

        response = service.updateNursingRecordTemplateGroups(updateRequest(
            groupPB(first, "shared", 3), groupPB(second, "shared", 4)));
        assertThat(response.getRt().getCode()).isEqualTo(
            StatusCode.NURSING_RECORD_TEMPLATE_GROUP_ALREADY_EXISTS.getNumber());
        assertThat(group(first).getName()).isEqualTo("first");
        assertThat(group(second).getName()).isEqualTo("second");
    }

    @Test
    void shouldCheckTemplatesBeforeSoftDeletingAndAllowRepeatedDeletion() {
        int id = createGroup("with-template");
        NursingRecordTemplate template = templateRepo.save(NursingRecordTemplate.builder()
            .entityType(1).entityId(DEPT_ID).groupId(id).name("template").content("内容")
            .displayOrder(0).isCommon(0).isDeleted(false).build());

        GenericResp response = service.deleteNursingRecordTemplateGroup(deleteRequest(id));
        assertThat(response.getRt().getCode()).isEqualTo(
            StatusCode.NURSING_RECORD_TEMPLATE_GROUP_HAS_TEMPLATES.getNumber());
        assertThat(group(id).getIsDeleted()).isFalse();

        template.setIsDeleted(true);
        templateRepo.save(template);
        response = service.deleteNursingRecordTemplateGroup(deleteRequest(id));
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(group(id).getIsDeleted()).isTrue();
        assertThat(group(id).getDeletedBy()).isEqualTo(ACCOUNT_ID);
        assertThat(group(id).getDeletedAt()).isNotNull();
        assertThat(service.deleteNursingRecordTemplateGroup(deleteRequest(id)).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        assertThat(createGroup("with-template")).isNotEqualTo(id);
    }

    @Test
    void shouldSerializeConcurrentCreatesWithSameName() throws Exception {
        CountDownLatch planned = new CountDownLatch(2);
        service = serviceBeforeLock(keys -> awaitPlans(planned));

        List<Integer> codes = concurrentCodes(
            () -> service.addNursingRecordTemplateGroup(addRequest(DEPT_ID, "shared"))
                .getRt().getCode(),
            () -> service.addNursingRecordTemplateGroup(addRequest(DEPT_ID, "shared"))
                .getRt().getCode());

        assertThat(codes).containsExactlyInAnyOrder(StatusCode.OK.getNumber(),
            StatusCode.NURSING_RECORD_TEMPLATE_GROUP_ALREADY_EXISTS.getNumber());
        assertThat(templateGroupRepo.findByEntityTypeAndEntityIdAndIsDeletedFalse(1, DEPT_ID))
            .hasSize(1);
    }

    @Test
    void shouldSerializeConcurrentRenamesToSameTarget() throws Exception {
        int first = createGroup("first");
        int second = createGroup("second");
        CountDownLatch planned = new CountDownLatch(2);
        service = serviceBeforeLock(keys -> awaitPlans(planned));

        List<Integer> codes = concurrentCodes(
            () -> service.updateNursingRecordTemplateGroups(
                updateRequest(groupPB(first, "shared", 1))).getRt().getCode(),
            () -> service.updateNursingRecordTemplateGroups(
                updateRequest(groupPB(second, "shared", 1))).getRt().getCode());

        assertThat(codes).containsExactlyInAnyOrder(StatusCode.OK.getNumber(),
            StatusCode.NURSING_RECORD_TEMPLATE_GROUP_ALREADY_EXISTS.getNumber());
        assertThat(templateGroupRepo.findByEntityTypeAndEntityIdAndIsDeletedFalse(1, DEPT_ID))
            .extracting(NursingRecordTemplateGroup::getName).contains("shared").hasSize(2);
    }

    @Test
    void shouldHandleInvalidRequestsAndEmptyUpdatesWithoutTakingLocks() {
        service = serviceBeforeLock(keys -> {
            throw new AssertionError("无效请求和空更新不应加锁");
        });
        assertThat(service.addNursingRecordTemplateGroup("{").getRt().getCode())
            .isEqualTo(StatusCode.PARSE_JSON_FAILED.getNumber());
        assertThat(service.updateNursingRecordTemplateGroups("{").getRt().getCode())
            .isEqualTo(StatusCode.PARSE_JSON_FAILED.getNumber());
        assertThat(service.deleteNursingRecordTemplateGroup("{").getRt().getCode())
            .isEqualTo(StatusCode.PARSE_JSON_FAILED.getNumber());
        assertThat(service.updateNursingRecordTemplateGroups(updateRequest()).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        assertThat(service.addNursingRecordTemplateGroup(addRequest(DEPT_ID, " "))
            .getRt().getCode())
            .isEqualTo(StatusCode.INVALID_PARAM_VALUE.getNumber());

        when(userQuery.getAccountWithAutoId()).thenReturn(null);
        assertThat(service.addNursingRecordTemplateGroup(addRequest(DEPT_ID, "name"))
            .getRt().getCode()).isEqualTo(StatusCode.ACCOUNT_NOT_FOUND.getNumber());
        assertThat(service.updateNursingRecordTemplateGroups(updateRequest()).getRt().getCode())
            .isEqualTo(StatusCode.ACCOUNT_NOT_FOUND.getNumber());
        assertThat(service.deleteNursingRecordTemplateGroup(deleteRequest(0)).getRt().getCode())
            .isEqualTo(StatusCode.ACCOUNT_NOT_FOUND.getNumber());
    }

    private NursingOrderTemplateService serviceBeforeLock(Consumer<Collection<String>> beforeLock) {
        return newService(new LockExecutor(transactionManager, jdbcTemplate) {
            @Override
            public <T> T execute(Collection<String> keys, Supplier<T> action) {
                beforeLock.accept(keys);
                return super.execute(keys, action);
            }
        });
    }

    private NursingOrderTemplateService newService(LockExecutor executor) {
        return new NursingOrderTemplateService(protoService, userQuery,
            templateGroupRepo, templateRepo, deptRepo, executor);
    }

    private void awaitPlans(CountDownLatch planned) {
        planned.countDown();
        try {
            assertTrue(planned.await(10, TimeUnit.SECONDS), "两个请求应在拿锁前完成预读取");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private List<Integer> concurrentCodes(
        Supplier<Integer> first, Supplier<Integer> second
    ) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> firstResult = pool.submit(first::get);
            Future<Integer> secondResult = pool.submit(second::get);
            return List.of(firstResult.get(15, TimeUnit.SECONDS),
                secondResult.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private int createGroup(String name) {
        AddNursingRecordTemplateGroupResp response = service.addNursingRecordTemplateGroup(
            addRequest(DEPT_ID, name));
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        return response.getId();
    }

    private String addRequest(String deptId, String name) {
        return ProtoUtils.protoToJson(AddNursingRecordTemplateGroupReq.newBuilder()
            .setDeptId(deptId).setGroup(NursingRecordTemplateGroupPB.newBuilder().setName(name))
            .build());
    }

    private String updateRequest(NursingRecordTemplateGroupPB... groups) {
        return ProtoUtils.protoToJson(UpdateNursingRecordTemplateGroupsReq.newBuilder()
            .setDeptId(DEPT_ID).addAllGroup(List.of(groups)).build());
    }

    private NursingRecordTemplateGroupPB groupPB(int id, String name, int order) {
        return NursingRecordTemplateGroupPB.newBuilder().setId(id).setName(name)
            .setDisplayOrder(order).build();
    }

    private String deleteRequest(int id) {
        return ProtoUtils.protoToJson(DeleteNursingRecordTemplateGroupReq.newBuilder()
            .setId(id).build());
    }

    private NursingRecordTemplateGroup group(int id) {
        return templateGroupRepo.findById(id).orElseThrow();
    }

    private int lockCount(int entityType, String entityId, String name) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_transaction_locks WHERE resource_key = ?", Integer.class,
            LockKeys.nursingRecordTemplateGroup(entityType, entityId, name));
    }

    private static final String DEPT_ID = "nursing-template-group-tests-dept";
    private static final String ACCOUNT_ID = "nursing-template-group-tests-account";

    private UserQuery userQuery;
    private NursingOrderTemplateService service;

    @Autowired
    private ConfigProtoService protoService;

    @Autowired
    private NursingRecordTemplateGroupRepository templateGroupRepo;

    @Autowired
    private NursingRecordTemplateRepository templateRepo;

    @Autowired
    private RbacDepartmentRepository deptRepo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LockExecutor lockExecutor;

    @Autowired
    private PlatformTransactionManager transactionManager;
}
