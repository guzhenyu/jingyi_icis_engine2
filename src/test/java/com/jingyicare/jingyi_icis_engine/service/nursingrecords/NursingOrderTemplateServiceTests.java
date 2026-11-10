package com.jingyicare.jingyi_icis_engine.service.nursingrecords;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.*;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;

class NursingOrderTemplateServiceTests extends TestsBase {
    @BeforeEach
    void prepare() {
        cleanUp();
        for (String deptId : DEPT_IDS) {
            jdbcTemplate.update(
                "INSERT INTO rbac_departments(dept_id, dept_name) VALUES (?, ?)", deptId, deptId);
        }
        protoService = mock(ConfigProtoService.class);
        when(protoService.getConfig()).thenReturn(Config.newBuilder()
            .setNursingRecord(NursingRecordConfigPB.newBuilder()
                .addDefaultDeptGroup(NursingRecordTemplateGroupPB.newBuilder()
                    .setName("first-group").setDisplayOrder(9)
                    .addNursingRecordTemplate(NursingRecordTemplatePB.newBuilder()
                        .setName("first-template").setContent("护理内容一")
                        .setDisplayOrder(9).setIsCommon(1))
                    .addNursingRecordTemplate(NursingRecordTemplatePB.newBuilder()
                        .setName("second-template").setContent("护理内容二").setIsCommon(0)))
                .addDefaultDeptGroup(NursingRecordTemplateGroupPB.newBuilder()
                    .setName("empty-group")))
            .build());
        service = new NursingOrderTemplateService(
            protoService, userService, templateGroupRepo, templateRepo, deptRepo,
            lockExecutor);
    }

    @AfterEach
    void cleanUp() {
        for (String deptId : DEPT_IDS) {
            jdbcTemplate.update(
                "DELETE FROM nursing_record_templates WHERE entity_id = ?", deptId);
            jdbcTemplate.update(
                "DELETE FROM nursing_record_template_groups WHERE entity_id = ?", deptId);
            jdbcTemplate.update(
                "DELETE FROM rbac_departments WHERE dept_id = ?", deptId);
            for (String groupName : List.of("first-group", "empty-group")) {
                jdbcTemplate.update(
                    "DELETE FROM app_transaction_locks WHERE resource_key = ?",
                    LockKeys.nursingRecordTemplateGroup(1, deptId, groupName));
            }
        }
    }

    @Test
    void shouldInitializeAllDepartmentsAndRemainIdempotent() {
        service.initDeptTemplateGroups();

        for (String deptId : DEPT_IDS) {
            List<NursingRecordTemplateGroup> groups = activeGroups(deptId);
            assertThat(groups).extracting(NursingRecordTemplateGroup::getName)
                .containsExactlyInAnyOrder("first-group", "empty-group");
            NursingRecordTemplateGroup firstGroup = groups.stream()
                .filter(group -> group.getName().equals("first-group")).findFirst().orElseThrow();
            assertThat(firstGroup.getDisplayOrder()).isZero();
            assertThat(firstGroup.getModifiedBy()).isEqualTo("system");
            assertThat(firstGroup.getModifiedAt()).isNotNull();
            assertThat(groups.stream().filter(group -> group.getName().equals("empty-group"))
                .findFirst().orElseThrow().getDisplayOrder()).isEqualTo(1);

            List<NursingRecordTemplate> templates = templateRepo
                .findByEntityTypeAndEntityIdAndIsDeletedFalse(1, deptId);
            assertThat(templates).hasSize(2);
            for (NursingRecordTemplate template : templates) {
                boolean first = template.getName().equals("first-template");
                assertThat(template.getContent()).isEqualTo(first ? "护理内容一" : "护理内容二");
                assertThat(template.getDisplayOrder()).isEqualTo(first ? 0 : 1);
                assertThat(template.getIsCommon()).isEqualTo(first ? 1 : 0);
                assertThat(template.getGroupId()).isEqualTo(firstGroup.getId());
                assertThat(template.getModifiedBy()).isEqualTo("system");
                assertThat(template.getModifiedAt()).isEqualTo(firstGroup.getModifiedAt());
            }

            service.initDeptTemplateGroups();
            assertThat(activeGroups(deptId)).extracting(NursingRecordTemplateGroup::getId)
                .containsExactlyInAnyOrderElementsOf(groups.stream()
                    .map(NursingRecordTemplateGroup::getId).toList());
            assertThat(templateRepo.findByEntityTypeAndEntityIdAndIsDeletedFalse(1, deptId))
                .extracting(NursingRecordTemplate::getId)
                .containsExactlyInAnyOrderElementsOf(templates.stream()
                    .map(NursingRecordTemplate::getId).toList());
            assertThat(lockCount(deptId)).isEqualTo(2);
        }
    }

    @Test
    void shouldSkipDepartmentWithAnyActiveGroup() {
        NursingRecordTemplateGroup existing = saveGroup(1, "custom-group", false);

        service.initDeptTemplateGroups();

        assertThat(activeGroups(DEPT_IDS.getFirst())).extracting(NursingRecordTemplateGroup::getId)
            .containsExactly(existing.getId());
        assertThat(lockCount(DEPT_IDS.getFirst())).isZero();
        assertThat(activeGroups(DEPT_IDS.getLast())).hasSize(2);
    }

    @Test
    void shouldIgnoreDeletedAndAccountGroupsWhenCheckingInitialization() {
        saveGroup(1, "first-group", true);
        saveGroup(0, "first-group", false);

        service.initDeptTemplateGroups();

        assertThat(activeGroups(DEPT_IDS.getFirst())).hasSize(2);
        assertThat(templateGroupRepo.findByEntityTypeAndEntityIdAndIsDeletedFalse(
            0, DEPT_IDS.getFirst())).hasSize(1);
    }

    @Test
    void shouldDoNothingWithoutDefaultGroups() {
        when(protoService.getConfig()).thenReturn(Config.getDefaultInstance());

        service.initDeptTemplateGroups();

        for (String deptId : DEPT_IDS) {
            assertThat(activeGroups(deptId)).isEmpty();
            assertThat(lockCount(deptId)).isZero();
        }
    }

    @Test
    void shouldRollbackTemplatesGroupsAndLocksOnFailure() {
        NursingRecordTemplateRepository failingRepo = mock(NursingRecordTemplateRepository.class);
        AtomicInteger saves = new AtomicInteger();
        when(failingRepo.save(any(NursingRecordTemplate.class))).thenAnswer(invocation -> {
            if (saves.incrementAndGet() == 2) throw new IllegalStateException("保存模板失败");
            return templateRepo.save(invocation.getArgument(0));
        });
        service = new NursingOrderTemplateService(
            protoService, userService, templateGroupRepo, failingRepo, deptRepo,
            lockExecutor);

        assertThrows(IllegalStateException.class, service::initDeptTemplateGroups);

        for (String deptId : DEPT_IDS) {
            assertThat(activeGroups(deptId)).isEmpty();
            assertThat(templateRepo.findByEntityTypeAndEntityIdAndIsDeletedFalse(1, deptId))
                .isEmpty();
            assertThat(lockCount(deptId)).isZero();
        }
    }

    @Test
    void shouldSerializeConcurrentInitializationsAfterSamePreRead() throws Exception {
        CountDownLatch planned = new CountDownLatch(2);
        LockExecutor concurrentExecutor = new LockExecutor(transactionManager, jdbcTemplate) {
            @Override
            public <T> T execute(Collection<String> resourceKeys, Supplier<T> action) {
                planned.countDown();
                try {
                    assertTrue(planned.await(10, TimeUnit.SECONDS), "两个请求应在拿锁前完成预读取");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return super.execute(resourceKeys, action);
            }
        };
        service = new NursingOrderTemplateService(
            protoService, userService, templateGroupRepo, templateRepo, deptRepo,
            concurrentExecutor);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(service::initDeptTemplateGroups);
            Future<?> second = pool.submit(service::initDeptTemplateGroups);
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);

            for (String deptId : DEPT_IDS) {
                assertThat(activeGroups(deptId)).hasSize(2);
                assertThat(templateRepo.findByEntityTypeAndEntityIdAndIsDeletedFalse(1, deptId))
                    .hasSize(2);
                assertThat(lockCount(deptId)).isEqualTo(2);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private NursingRecordTemplateGroup saveGroup(int entityType, String name, boolean deleted) {
        return templateGroupRepo.save(NursingRecordTemplateGroup.builder()
            .entityType(entityType).entityId(DEPT_IDS.getFirst()).name(name)
            .displayOrder(0).isDeleted(deleted).build());
    }

    private List<NursingRecordTemplateGroup> activeGroups(String deptId) {
        return templateGroupRepo.findByEntityTypeAndEntityIdAndIsDeletedFalse(1, deptId);
    }

    private int lockCount(String deptId) {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM app_transaction_locks WHERE resource_key IN (?, ?)
            """, Integer.class,
            LockKeys.nursingRecordTemplateGroup(1, deptId, "first-group"),
            LockKeys.nursingRecordTemplateGroup(1, deptId, "empty-group"));
    }

    private static final List<String> DEPT_IDS = List.of(
        "nursing-template-init-tests-1", "nursing-template-init-tests-2");

    private ConfigProtoService protoService;
    private NursingOrderTemplateService service;

    @Autowired
    private UserService userService;

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
