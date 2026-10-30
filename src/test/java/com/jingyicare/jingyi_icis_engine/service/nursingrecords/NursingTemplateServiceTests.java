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
import com.jingyicare.jingyi_icis_engine.service.users.UserBasicOperator;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class NursingTemplateServiceTests extends TestsBase {
    @BeforeEach
    void prepare() {
        cleanUp();
        for (String deptId : List.of(DEPT_ID, OTHER_DEPT_ID)) {
            jdbcTemplate.update(
                "INSERT INTO rbac_departments(dept_id, dept_name) VALUES (?, ?)", deptId, deptId);
        }
        userService = mock(UserService.class);
        when(userService.getAccountWithAutoId()).thenReturn(new Pair<>(ACCOUNT_ID, "测试用户"));
        service = newService(lockExecutor);
    }

    @AfterEach
    void cleanUp() {
        for (String entityId : List.of(DEPT_ID, OTHER_DEPT_ID, ACCOUNT_ID)) {
            jdbcTemplate.update(
                "DELETE FROM nursing_record_templates WHERE entity_id = ?", entityId);
            jdbcTemplate.update(
                "DELETE FROM nursing_record_template_groups WHERE entity_id = ?", entityId);
            jdbcTemplate.update("""
                DELETE FROM app_transaction_locks
                WHERE resource_key LIKE ? OR resource_key LIKE ?
                """, "nursing_record_template_groups:%:" + entityId + ":%",
                "nursing_record_templates:%:" + entityId + ":%");
        }
        for (String deptId : List.of(DEPT_ID, OTHER_DEPT_ID)) {
            jdbcTemplate.update("DELETE FROM rbac_departments WHERE dept_id = ?", deptId);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void shouldInheritGroupScopeWhenCreating(int entityType) {
        String entityId = entityType == 0 ? ACCOUNT_ID : DEPT_ID;
        int groupId = createGroup(entityType, entityId, "group");
        service = serviceBeforeLock(keys -> assertThat(keys).containsExactlyInAnyOrder(
            LockKeys.nursingRecordTemplateGroup(entityType, entityId, "group"),
            LockKeys.nursingRecordTemplate(entityType, entityId, "template")));

        int id = createTemplate(groupId, "template");

        NursingRecordTemplate template = template(id);
        assertThat(template.getEntityType()).isEqualTo(entityType);
        assertThat(template.getEntityId()).isEqualTo(entityId);
        assertThat(template.getGroupId()).isEqualTo(groupId);
        assertThat(template.getContent()).isEqualTo("原内容");
        assertThat(template.getIsCommon()).isEqualTo(1);
        assertThat(template.getModifiedBy()).isEqualTo(ACCOUNT_ID);
        assertThat(template.getModifiedAt()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void shouldLockOldAndNewResourcesAndInheritTargetGroupScope(int targetType) {
        int source = createGroup(1, DEPT_ID, "source");
        String targetEntityId = targetType == 0 ? ACCOUNT_ID : OTHER_DEPT_ID;
        int target = createGroup(targetType, targetEntityId, "target");
        int id = createTemplate(source, "old-name");
        service = serviceBeforeLock(keys -> assertThat(keys).containsExactlyInAnyOrder(
            LockKeys.nursingRecordTemplateGroup(1, DEPT_ID, "source"),
            LockKeys.nursingRecordTemplate(1, DEPT_ID, "old-name"),
            LockKeys.nursingRecordTemplateGroup(targetType, targetEntityId, "target"),
            LockKeys.nursingRecordTemplate(targetType, targetEntityId, "new-name")));

        GenericResp response = service.updateNursingRecordTemplate(
            updateRequest(id, target, "new-name"));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        NursingRecordTemplate updated = template(id);
        assertThat(updated.getEntityType()).isEqualTo(targetType);
        assertThat(updated.getEntityId()).isEqualTo(targetEntityId);
        assertThat(updated.getGroupId()).isEqualTo(target);
        assertThat(updated.getName()).isEqualTo("new-name");
        assertThat(updated.getContent()).isEqualTo("更新内容");
        assertThat(updated.getDisplayOrder()).isEqualTo(2);
        assertThat(updated.getIsCommon()).isZero();
    }

    @Test
    void shouldCheckDuplicateNameInTargetEntityBeforeChangingTemplate() {
        int source = createGroup(1, DEPT_ID, "source");
        int target = createGroup(0, ACCOUNT_ID, "target");
        int id = createTemplate(source, "original");
        createTemplate(target, "occupied");

        GenericResp response = service.updateNursingRecordTemplate(
            updateRequest(id, target, "occupied"));

        assertThat(response.getRt().getCode()).isEqualTo(
            StatusCode.NURSING_RECORD_TEMPLATE_GROUP_ALREADY_EXISTS.getNumber());
        assertThat(template(id).getName()).isEqualTo("original");
        assertThat(template(id).getEntityType()).isEqualTo(1);
        assertThat(template(id).getEntityId()).isEqualTo(DEPT_ID);
        assertThat(template(id).getGroupId()).isEqualTo(source);
        assertThat(template(id).getContent()).isEqualTo("原内容");
    }

    @Test
    void shouldKeepNameForBlankUpdateAndNeverReviveDeletedTemplate() {
        int groupId = createGroup(1, DEPT_ID, "group");
        int id = createTemplate(groupId, "original");

        GenericResp response = service.updateNursingRecordTemplate(updateRequest(id, groupId, ""));
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(template(id).getName()).isEqualTo("original");
        assertThat(template(id).getContent()).isEqualTo("更新内容");
        response = service.deleteNursingRecordTemplate(deleteRequest(id));
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(template(id).getDeletedBy()).isEqualTo(ACCOUNT_ID);
        assertThat(template(id).getDeletedAt()).isNotNull();
        assertThat(service.deleteNursingRecordTemplate(deleteRequest(id)).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        assertThat(service.updateNursingRecordTemplate(updateRequest(id, groupId, "revived"))
            .getRt().getCode())
            .isEqualTo(StatusCode.NURSING_RECORD_TEMPLATE_NOT_EXISTS.getNumber());
        assertThat(template(id).getIsDeleted()).isTrue();

        jdbcTemplate.update(
            "UPDATE nursing_record_template_groups SET is_deleted = true WHERE id = ?", groupId);
        assertThat(service.addNursingRecordTemplate(addRequest(groupId, "new")).getRt().getCode())
            .isEqualTo(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_NOT_EXISTS.getNumber());
    }

    @ParameterizedTest
    @ValueSource(strings = {"add", "update", "delete"})
    void shouldRejectGroupRenamedBetweenPreReadAndLock(String operation) {
        int groupId = createGroup(1, DEPT_ID, "original-group");
        int id = createTemplate(groupId, "original");
        service = serviceBeforeLock(keys -> jdbcTemplate.update(
            "UPDATE nursing_record_template_groups SET name = ? WHERE id = ?",
            "changed-group", groupId));

        int code = switch (operation) {
            case "add" -> service.addNursingRecordTemplate(addRequest(groupId, "new"))
                .getRt().getCode();
            case "update" -> service.updateNursingRecordTemplate(
                updateRequest(id, groupId, "new")).getRt().getCode();
            default -> service.deleteNursingRecordTemplate(deleteRequest(id)).getRt().getCode();
        };

        assertThat(code).isEqualTo(StatusCode.INVALID_PARAM_VALUE.getNumber());
        assertThat(template(id).getName()).isEqualTo("original");
        assertThat(template(id).getContent()).isEqualTo("原内容");
        assertThat(template(id).getIsDeleted()).isFalse();
        assertThat(templateRepo.findByEntityTypeAndEntityIdAndIsDeletedFalse(1, DEPT_ID))
            .hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRejectTemplateRenamedBetweenPreReadAndLock(boolean delete) {
        int groupId = createGroup(1, DEPT_ID, "group");
        int id = createTemplate(groupId, "original");
        service = serviceBeforeLock(keys -> jdbcTemplate.update(
            "UPDATE nursing_record_templates SET name = ? WHERE id = ?", "changed", id));

        GenericResp response = delete
            ? service.deleteNursingRecordTemplate(deleteRequest(id))
            : service.updateNursingRecordTemplate(updateRequest(id, groupId, "new"));

        assertThat(response.getRt().getCode())
            .isEqualTo(StatusCode.INVALID_PARAM_VALUE.getNumber());
        assertThat(template(id).getName()).isEqualTo("changed");
        assertThat(template(id).getContent()).isEqualTo("原内容");
        assertThat(template(id).getIsDeleted()).isFalse();
    }

    @Test
    void shouldSerializeConcurrentCreatesWithSameNameAcrossGroups() throws Exception {
        int first = createGroup(1, DEPT_ID, "first");
        int second = createGroup(1, DEPT_ID, "second");
        CountDownLatch planned = new CountDownLatch(2);
        service = serviceBeforeLock(keys -> awaitPlans(planned));

        List<Integer> codes = concurrentCodes(
            () -> service.addNursingRecordTemplate(addRequest(first, "shared")).getRt().getCode(),
            () -> service.addNursingRecordTemplate(addRequest(second, "shared")).getRt().getCode());

        assertThat(codes).containsExactlyInAnyOrder(StatusCode.OK.getNumber(),
            StatusCode.NURSING_RECORD_TEMPLATE_ALREADY_EXISTS.getNumber());
        assertThat(templateRepo.findByEntityTypeAndEntityIdAndIsDeletedFalse(1, DEPT_ID))
            .hasSize(1);
    }

    @Test
    void shouldSerializeConcurrentRenamesWithSameTargetAcrossGroups() throws Exception {
        int firstGroup = createGroup(1, DEPT_ID, "first-group");
        int secondGroup = createGroup(1, DEPT_ID, "second-group");
        int first = createTemplate(firstGroup, "first");
        int second = createTemplate(secondGroup, "second");
        CountDownLatch planned = new CountDownLatch(2);
        service = serviceBeforeLock(keys -> awaitPlans(planned));

        List<Integer> codes = concurrentCodes(
            () -> service.updateNursingRecordTemplate(updateRequest(first, firstGroup, "shared"))
                .getRt().getCode(),
            () -> service.updateNursingRecordTemplate(updateRequest(second, secondGroup, "shared"))
                .getRt().getCode());

        assertThat(codes).containsExactlyInAnyOrder(StatusCode.OK.getNumber(),
            StatusCode.NURSING_RECORD_TEMPLATE_GROUP_ALREADY_EXISTS.getNumber());
        assertThat(templateRepo.findByEntityTypeAndEntityIdAndIsDeletedFalse(1, DEPT_ID))
            .extracting(NursingRecordTemplate::getName).contains("shared").hasSize(2);
    }

    @Test
    void shouldSerializeTemplateCreationAndGroupDeletion() throws Exception {
        int groupId = createGroup(1, DEPT_ID, "group");
        CountDownLatch planned = new CountDownLatch(2);
        service = serviceBeforeLock(keys -> awaitPlans(planned));

        List<Integer> codes = concurrentCodes(
            () -> service.addNursingRecordTemplate(addRequest(groupId, "template"))
                .getRt().getCode(),
            () -> service.deleteNursingRecordTemplateGroup(ProtoUtils.protoToJson(
                DeleteNursingRecordTemplateGroupReq.newBuilder().setId(groupId).build()))
                .getRt().getCode());

        boolean deleted = templateGroupRepo.findById(groupId).orElseThrow().getIsDeleted();
        if (deleted) {
            assertThat(codes).containsExactly(
                StatusCode.NURSING_RECORD_TEMPLATE_GROUP_NOT_EXISTS.getNumber(),
                StatusCode.OK.getNumber());
            assertThat(templateRepo.findByEntityTypeAndEntityIdAndIsDeletedFalse(1, DEPT_ID))
                .isEmpty();
        } else {
            assertThat(codes).containsExactly(StatusCode.OK.getNumber(),
                StatusCode.NURSING_RECORD_TEMPLATE_GROUP_HAS_TEMPLATES.getNumber());
            assertThat(templateRepo.findByEntityTypeAndEntityIdAndIsDeletedFalse(1, DEPT_ID))
                .hasSize(1);
        }
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
        return new NursingOrderTemplateService(protoService, userBasicOp, userService,
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

    private int createGroup(int entityType, String entityId, String name) {
        return templateGroupRepo.save(NursingRecordTemplateGroup.builder()
            .entityType(entityType).entityId(entityId).name(name)
            .displayOrder(0).isDeleted(false).build()).getId();
    }

    private int createTemplate(int groupId, String name) {
        AddNursingRecordTemplateResp response = service.addNursingRecordTemplate(
            addRequest(groupId, name));
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        return response.getId();
    }

    private String addRequest(int groupId, String name) {
        return ProtoUtils.protoToJson(AddNursingRecordTemplateReq.newBuilder()
            .setDeptId("ignored-request-dept")
            .setTemplate(NursingRecordTemplatePB.newBuilder().setGroupId(groupId).setName(name)
                .setContent("原内容").setDisplayOrder(1).setIsCommon(1)).build());
    }

    private String updateRequest(int id, int groupId, String name) {
        return ProtoUtils.protoToJson(UpdateNursingRecordTemplateReq.newBuilder()
            .setTemplate(NursingRecordTemplatePB.newBuilder().setId(id).setGroupId(groupId)
                .setName(name).setContent("更新内容").setDisplayOrder(2).setIsCommon(0)).build());
    }

    private String deleteRequest(int id) {
        return ProtoUtils.protoToJson(DeleteNursingRecordTemplateReq.newBuilder()
            .setId(id).build());
    }

    private NursingRecordTemplate template(int id) {
        return templateRepo.findById(id).orElseThrow();
    }

    private static final String DEPT_ID = "template-item-tests-dept";
    private static final String OTHER_DEPT_ID = "template-item-tests-other-dept";
    private static final String ACCOUNT_ID = "template-item-tests-account";

    private UserService userService;
    private NursingOrderTemplateService service;

    @Autowired
    private ConfigProtoService protoService;

    @Autowired
    private UserBasicOperator userBasicOp;

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
