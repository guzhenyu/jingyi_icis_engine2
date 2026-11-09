package com.jingyicare.jingyi_icis_engine.service.monitorings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParamId;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParamHistory;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.MonitoringParamHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.MonitoringParamRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.WebApiService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

class MonitoringMetaServiceTests extends TestsBase {
    @BeforeEach
    void prepare() {
        cleanParams();
        userService = mock(UserService.class);
        when(userService.getAccountWithAutoId()).thenReturn(new Pair<>(ACCOUNT, ACCOUNT));
        serviceExecutor = spy(executor);
        service = configuredService(List.of(param(FIRST_CODE), param(SECOND_CODE)), historyRepo);
    }

    @AfterEach
    void cleanUp() {
        cleanParams();
        monitoringMetaService.initMonitoringParams();
    }

    @Test
    void concurrentInitializationIsIdempotentAndPreservesExistingParams() throws Exception {
        List<String> originalCodes = orderedParamCodes();
        Supplier<Integer> initialize = () -> {
            service.initMonitoringParams();
            return StatusCode.OK_VALUE;
        };
        assertThat(runContending(initialize, initialize))
            .containsExactly(StatusCode.OK_VALUE, StatusCode.OK_VALUE);
        verify(serviceExecutor, times(2)).execute(eq(List.of(LockKeys.monitoringParam())), any());
        assertThat(repository.findByCode(FIRST_CODE).orElseThrow().getDisplayOrder())
            .isEqualTo(originalCodes.size() + 1);
        assertThat(repository.findByCode(SECOND_CODE).orElseThrow().getDisplayOrder())
            .isEqualTo(originalCodes.size() + 2);
        List<String> expectedCodes = new ArrayList<>(originalCodes);
        expectedCodes.addAll(List.of(FIRST_CODE, SECOND_CODE));
        assertThat(orderedParamCodes()).containsExactlyElementsOf(expectedCodes);
        assertContinuousDisplayOrders();
        assertThat(historyCount()).isZero();
        assertThat(monitoringLockKeys()).containsExactly(LockKeys.monitoringParam());

        MonitoringParam first = repository.findByCode(FIRST_CODE).orElseThrow();
        first.setName("已修改的名称");
        repository.saveAndFlush(first);
        service.initMonitoringParams();
        verify(serviceExecutor, times(3)).execute(eq(List.of(LockKeys.monitoringParam())), any());
        assertThat(repository.findByCode(FIRST_CODE).orElseThrow()).isEqualTo(first);
    }

    @Test
    void initializationReadsOnceAndBatchSavesOnlyNewOrReorderedParams() {
        monitoringMetaService.initMonitoringParams();
        int originalCount = Math.toIntExact(repository.count());
        MonitoringParam existing = MonitoringPbUtils.toMonitoringParamEntity(
            param(FIRST_CODE).toBuilder().setName("已存在参数").build(), originalCount + 1);
        repository.saveAndFlush(existing);
        MonitoringParamRepository batchRepository =
            mock(MonitoringParamRepository.class, delegatesTo(repository));
        service = configuredService(List.of(
            param(SECOND_CODE),
            param(FIRST_CODE),
            param(SECOND_CODE).toBuilder().setName("重复配置").build()), historyRepo, batchRepository);

        service.initMonitoringParams();

        verify(batchRepository).findAll();
        verify(batchRepository).saveAll(argThat(params ->
            StreamSupport.stream(params.spliterator(), false)
                .map(MonitoringParam::getCode).toList().equals(List.of(SECOND_CODE))));
        verifyNoMoreInteractions(batchRepository);
        assertThat(repository.findByCode(FIRST_CODE).orElseThrow()).isEqualTo(existing);
        MonitoringParam added = repository.findByCode(SECOND_CODE).orElseThrow();
        assertThat(added.getName()).isEqualTo("初始化参数");
        assertThat(added.getDisplayOrder()).isEqualTo(originalCount + 2);
        assertContinuousDisplayOrders();
        assertThat(historyCount()).isZero();

        clearInvocations(batchRepository);
        service.initMonitoringParams();
        verify(batchRepository).findAll();
        verifyNoMoreInteractions(batchRepository);
    }

    @Test
    void initializationCompactsOrdersWithoutChangingRelativeOrderOrMetadata() {
        MonitoringParam first = MonitoringPbUtils.toMonitoringParamEntity(
            param(FIRST_CODE).toBuilder().setName("已修改的名称")
                .setNameEn("Custom parameter").setCategory(3)
                .setUiModalCode("custom-modal").build(),
            1000);
        MonitoringParam second =
            MonitoringPbUtils.toMonitoringParamEntity(param(SECOND_CODE), 1000);
        repository.saveAllAndFlush(List.of(first, second));
        List<String> originalCodes = orderedParamCodes();
        service = configuredService(List.of(param(SECOND_CODE), param(FIRST_CODE)), historyRepo);

        service.initMonitoringParams();

        MonitoringParam reorderedFirst = repository.findByCode(FIRST_CODE).orElseThrow();
        assertThat(reorderedFirst).usingRecursiveComparison().ignoringFields("displayOrder")
            .isEqualTo(first);
        assertThat(reorderedFirst.getDisplayOrder())
            .isEqualTo(originalCodes.indexOf(FIRST_CODE) + 1);
        assertThat(repository.findByCode(SECOND_CODE).orElseThrow().getDisplayOrder())
            .isEqualTo(originalCodes.indexOf(SECOND_CODE) + 1);
        assertThat(orderedParamCodes()).containsExactlyElementsOf(originalCodes);
        assertContinuousDisplayOrders();
        assertThat(historyCount()).isZero();
    }

    @Test
    void concurrentAddsOfSameCodeCreateOneParamAndOneHistoryRecord() throws Exception {
        List<Integer> statuses = runContending(
            () -> add(FIRST_CODE).getRt().getCode(),
            () -> add(FIRST_CODE).getRt().getCode());

        assertThat(statuses).containsExactlyInAnyOrder(
            StatusCode.OK_VALUE, StatusCode.MONITORING_PARAM_CODE_EXIST_VALUE);
        assertThat(repository.findByCodeIn(TEST_CODES)).hasSize(1);
        assertThat(historyCount()).isEqualTo(1);
        MonitoringParam saved = repository.findByCode(FIRST_CODE).orElseThrow();
        assertThat(saved.getName()).isEqualTo("接口新增");
        assertThat(saved.getNameEn()).isEqualTo("Custom parameter");
        assertThat(saved.getCategory()).isEqualTo(3);
        assertThat(saved.getUiModalCode()).isEqualTo("custom-modal");
        assertThat(monitoringLockKeys()).containsExactly(LockKeys.monitoringParam());
    }

    @Test
    void concurrentAddsOfDifferentCodesAllocateDistinctOrders() throws Exception {
        int previousOrder = maxDisplayOrder();
        assertThat(runContending(
            () -> add(FIRST_CODE).getRt().getCode(),
            () -> add(SECOND_CODE).getRt().getCode()))
            .containsExactly(StatusCode.OK_VALUE, StatusCode.OK_VALUE);

        assertThat(repository.findByCodeIn(TEST_CODES)).extracting(MonitoringParam::getDisplayOrder)
            .containsExactlyInAnyOrder(previousOrder + 1, previousOrder + 2);
        assertThat(historyCount()).isEqualTo(2);
        assertThat(monitoringLockKeys()).containsExactly(LockKeys.monitoringParam());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializationAndApiAddCoordinateCodesAndOrders(boolean sameCode) throws Exception {
        service = configuredService(List.of(param(FIRST_CODE)), historyRepo);
        int originalCount = Math.toIntExact(repository.count());
        String addedCode = sameCode ? FIRST_CODE : SECOND_CODE;
        List<Integer> statuses = runContending(() -> {
            service.initMonitoringParams();
            return StatusCode.OK_VALUE;
        }, () -> add(addedCode).getRt().getCode());

        assertThat(statuses.getFirst()).isEqualTo(StatusCode.OK_VALUE);
        if (sameCode) {
            assertThat(statuses.get(1)).isIn(
                StatusCode.OK_VALUE, StatusCode.MONITORING_PARAM_CODE_EXIST_VALUE);
            assertThat(repository.findByCodeIn(TEST_CODES)).hasSize(1);
            boolean addedByApi = statuses.get(1) == StatusCode.OK_VALUE;
            assertThat(repository.findByCode(FIRST_CODE).orElseThrow().getName())
                .isEqualTo(addedByApi ? "接口新增" : "初始化参数");
            assertThat(historyCount()).isEqualTo(addedByApi ? 1 : 0);
        } else {
            assertThat(statuses.get(1)).isEqualTo(StatusCode.OK_VALUE);
            assertThat(repository.findByCodeIn(TEST_CODES))
                .extracting(MonitoringParam::getDisplayOrder)
                .containsExactlyInAnyOrder(originalCount + 1, originalCount + 2);
            assertThat(historyCount()).isEqualTo(1);
        }
        assertContinuousDisplayOrders();
        assertThat(monitoringLockKeys()).containsExactly(LockKeys.monitoringParam());
    }

    @Test
    void historyFailureRollsBackParamAndHistoryBeforeReturningError() {
        MonitoringParamHistoryRepository failingHistoryRepo =
            mock(MonitoringParamHistoryRepository.class);
        doAnswer(invocation -> {
            MonitoringParamHistory history = invocation.getArgument(0);
            historyRepo.saveAndFlush(history);
            repository.flush();
            throw new IllegalStateException("模拟历史记录写入失败");
        }).when(failingHistoryRepo).save(any(MonitoringParamHistory.class));
        service = configuredService(List.of(), failingHistoryRepo);

        assertThat(add(FIRST_CODE).getRt().getCode())
            .isEqualTo(StatusCode.INTERNAL_EXCEPTION_VALUE);
        assertThat(repository.findByCode(FIRST_CODE)).isEmpty();
        assertThat(historyCount()).isZero();
        assertThat(monitoringLockKeys()).isEmpty();

        service = configuredService(List.of(), historyRepo);
        assertThat(add(FIRST_CODE).getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
    }

    @Test
    void initializationFailureRollsBackWholeBatch() {
        int previousOrder = maxDisplayOrder();
        service = configuredService(List.of(
            param(FIRST_CODE),
            param(SECOND_CODE).toBuilder().setName("x".repeat(256)).build()), historyRepo);

        assertThatThrownBy(service::initMonitoringParams)
            .isInstanceOf(DataIntegrityViolationException.class);

        verify(serviceExecutor).execute(eq(List.of(LockKeys.monitoringParam())), any());
        assertThat(repository.findByCodeIn(TEST_CODES)).isEmpty();
        assertThat(maxDisplayOrder()).isEqualTo(previousOrder);
        assertThat(monitoringLockKeys()).isEmpty();
        assertThat(historyCount()).isZero();
    }

    @Test
    void updatePreservesOrderAndRecordsOldAndNewSnapshots() {
        MonitoringParam original = seedParam();
        MonitoringParamPB updated = param(FIRST_CODE).toBuilder()
            .setName("修改后的参数").setNameEn("Updated parameter")
            .setBalanceType(protoService.getConfig().getMonitoring()
                .getEnums().getBalanceIn().getId())
            .setCategory(3).setUiModalCode("updated-modal").setChartSign("updated-sign")
            .setValueMeta(ValueMetaPB.newBuilder().setValueType(TypeEnumPB.FLOAT)
                .setDecimalPlaces(2).setUnit("ml")).build();

        assertThat(service.updateMonitoringParam(request(updated)).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);

        MonitoringParam saved = repository.findByCode(FIRST_CODE).orElseThrow();
        assertThat(saved.getDisplayOrder()).isEqualTo(original.getDisplayOrder());
        assertThat(saved.getName()).isEqualTo(updated.getName());
        assertThat(saved.getNameEn()).isEqualTo(updated.getNameEn());
        assertThat(saved.getTypePb()).isEqualTo(ProtoUtils.encodeValueMeta(updated.getValueMeta()));
        assertThat(saved.getBalanceType()).isEqualTo(updated.getBalanceType());
        assertThat(saved.getCategory()).isEqualTo(updated.getCategory());
        assertThat(saved.getUiModalCode()).isEqualTo(updated.getUiModalCode());
        assertThat(saved.getChartSign()).isEqualTo(updated.getChartSign());
        List<MonitoringParamHistory> histories = histories();
        assertThat(histories).hasSize(2);
        MonitoringParamHistory before = histories.getFirst();
        assertThat(before.getName()).isEqualTo(original.getName());
        assertThat(before.getTypePb()).isEqualTo(original.getTypePb());
        assertThat(before.getBalanceType()).isEqualTo(original.getBalanceType());
        assertThat(before.getIsDeleted()).isTrue();
        assertThat(before.getDeletedBy()).isEqualTo(ACCOUNT);
        assertThat(before.getDeletedAt()).isNotNull();
        assertThat(before.getModifiedBy()).isNull();
        assertThat(before.getModifiedAt()).isNull();
        MonitoringParamHistory after = histories.getLast();
        assertThat(after.getName()).isEqualTo(saved.getName());
        assertThat(after.getTypePb()).isEqualTo(saved.getTypePb());
        assertThat(after.getBalanceType()).isEqualTo(saved.getBalanceType());
        assertThat(after.getIsDeleted()).isFalse();
        assertThat(after.getModifiedBy()).isEqualTo(ACCOUNT);
        assertThat(after.getModifiedAt()).isNotNull();
        assertThat(after.getDeletedBy()).isNull();
        assertThat(after.getDeletedAt()).isNull();
        verify(serviceExecutor).execute(eq(List.of(LockKeys.monitoringParam())), any());
    }

    @Test
    void concurrentUpdateAndDeleteUseTheSameListLock() throws Exception {
        seedParam();
        String update = request(param(FIRST_CODE).toBuilder().setName("并发更新").build());
        List<Integer> statuses = runContending(
            () -> service.updateMonitoringParam(update).getRt().getCode(),
            () -> service.deleteMonitoringParam(deleteRequest(false)).getRt().getCode());

        assertThat(statuses.getFirst()).isIn(
            StatusCode.OK_VALUE, StatusCode.MONITORING_PARAM_CODE_NOT_EXIST_VALUE);
        assertThat(statuses.getLast()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(repository.findByCode(FIRST_CODE)).isEmpty();
        boolean updatedFirst = statuses.getFirst() == StatusCode.OK_VALUE;
        assertThat(historyCount()).isEqualTo(updatedFirst ? 3 : 1);
        assertThat(histories().getLast().getName())
            .isEqualTo(updatedFirst ? "并发更新" : "初始化参数");
        verify(serviceExecutor, times(2)).execute(eq(List.of(LockKeys.monitoringParam())), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentDeletesPreserveExpectedHistory(boolean department) throws Exception {
        MonitoringParam original = seedParam();
        DeptMonitoringParam deptParam = department ? seedDeptParam(original) : null;
        Supplier<Integer> delete = () -> service.deleteMonitoringParam(deleteRequest(department))
            .getRt().getCode();
        assertThat(runContending(delete, delete))
            .containsExactly(StatusCode.OK_VALUE, StatusCode.OK_VALUE);

        assertThat(historyCount()).isEqualTo(department ? 0 : 1);
        if (department) {
            assertThat(deptRepository.findById(deptParam.getId())).isEmpty();
            assertThat(repository.findByCode(FIRST_CODE)).contains(original);
        } else {
            assertThat(repository.findByCode(FIRST_CODE)).isEmpty();
            MonitoringParamHistory history = histories().getFirst();
            assertThat(history.getIsDeleted()).isTrue();
            assertThat(history.getDeletedBy()).isEqualTo(ACCOUNT);
            assertThat(history.getDeletedAt()).isNotNull();
            assertThat(history.getDeptId()).isNull();
            assertThat(history.getTypePb()).isEqualTo(original.getTypePb());
            assertThat(history.getName()).isEqualTo(original.getName());
        }
        verify(serviceExecutor, times(2)).execute(eq(List.of(LockKeys.monitoringParam())), any());
    }

    @Test
    void globalDeleteRequiresRemovingDeptConfigFirst() {
        MonitoringParam original = seedParam();
        DeptMonitoringParam deptParam = seedDeptParam(original);
        assertThat(service.deleteMonitoringParam(deleteRequest(false)).getRt().getCode())
            .isEqualTo(StatusCode.MONITORING_PARAM_HAS_DEPT_CONFIG_VALUE);
        assertThat(repository.findByCode(FIRST_CODE)).contains(original);
        assertThat(deptRepository.findById(deptParam.getId())).contains(deptParam);
        assertThat(historyCount()).isZero();

        assertThat(service.deleteMonitoringParam(deleteRequest(true)).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        assertThat(deptRepository.findById(deptParam.getId())).isEmpty();
        assertThat(historyCount()).isZero();
        assertThat(repository.findByCode(FIRST_CODE)).contains(original);
        assertThat(service.deleteMonitoringParam(deleteRequest(false)).getRt().getCode())
            .isEqualTo(StatusCode.OK_VALUE);
        assertThat(repository.findByCode(FIRST_CODE)).isEmpty();
        assertThat(historyCount()).isEqualTo(1);
    }

    @Test
    void updateHistoryFailureRollsBackFlushedParamAndBothSnapshots() {
        MonitoringParam original = seedParam();
        MonitoringParamHistoryRepository failingHistoryRepo =
            mock(MonitoringParamHistoryRepository.class);
        doAnswer(invocation -> {
            MonitoringParamHistory history = invocation.getArgument(0);
            historyRepo.saveAndFlush(history);
            if (!history.getIsDeleted()) {
                repository.flush();
                throw new IllegalStateException("模拟更新后历史记录写入失败");
            }
            return history;
        }).when(failingHistoryRepo).save(any(MonitoringParamHistory.class));
        service = configuredService(List.of(), failingHistoryRepo);

        assertThat(service.updateMonitoringParam(request(param(FIRST_CODE).toBuilder()
            .setName("应回滚的名称").build())).getRt().getCode())
            .isEqualTo(StatusCode.INTERNAL_EXCEPTION_VALUE);
        assertThat(repository.findByCode(FIRST_CODE)).contains(original);
        assertThat(historyCount()).isZero();
        assertThat(monitoringLockKeys()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deleteFailureRollsBackFlushedDeletion(boolean department) {
        MonitoringParam original = seedParam();
        DeptMonitoringParam deptParam = department ? seedDeptParam(original) : null;
        MonitoringParamRepository trackedParams =
            mock(MonitoringParamRepository.class, delegatesTo(repository));
        DeptMonitoringParamRepository trackedDeptParams =
            mock(DeptMonitoringParamRepository.class, delegatesTo(deptRepository));
        if (department) {
            doAnswer(invocation -> {
                deptRepository.delete(invocation.getArgument(0));
                deptRepository.flush();
                throw new IllegalStateException("模拟科室参数删除失败");
            }).when(trackedDeptParams).delete(any(DeptMonitoringParam.class));
        } else {
            doAnswer(invocation -> {
                repository.delete(invocation.getArgument(0));
                repository.flush();
                throw new IllegalStateException("模拟全局参数删除失败");
            }).when(trackedParams).delete(any(MonitoringParam.class));
        }
        service = new MonitoringMetaService(protoService, userService, trackedParams,
            trackedDeptParams, historyRepo, serviceExecutor);

        assertThat(service.deleteMonitoringParam(deleteRequest(department)).getRt().getCode())
            .isEqualTo(StatusCode.INTERNAL_EXCEPTION_VALUE);
        assertThat(repository.findByCode(FIRST_CODE)).contains(original);
        if (department) {
            assertThat(deptRepository.findById(deptParam.getId())).contains(deptParam);
        }
        assertThat(historyCount()).isZero();
        assertThat(monitoringLockKeys()).isEmpty();
    }

    @Test
    void updatingMissingParamReturnsNotFoundWithoutCreatingHistory() {
        assertThat(service.updateMonitoringParam(request(param(FIRST_CODE))).getRt().getCode())
            .isEqualTo(StatusCode.MONITORING_PARAM_CODE_NOT_EXIST_VALUE);
        assertThat(repository.findByCode(FIRST_CODE)).isEmpty();
        assertThat(historyCount()).isZero();
        verify(serviceExecutor).execute(eq(List.of(LockKeys.monitoringParam())), any());
    }

    @Test
    void invalidRequestsPreserveReturnCodesAndDoNotWriteParams() {
        assertThat(service.addMonitoringParam("invalid-json").getRt().getCode())
            .isEqualTo(StatusCode.PARSE_JSON_FAILED_VALUE);
        assertThat(service.addMonitoringParam(request(MonitoringParamPB.getDefaultInstance()))
            .getRt().getCode())
            .isEqualTo(StatusCode.MONITORING_PARAM_CODE_EMPTY_VALUE);
        MonitoringParamPB invalidBalance = param(FIRST_CODE).toBuilder()
            .setBalanceType(protoService.getConfig().getMonitoring()
                .getEnums().getBalanceIn().getId())
            .setValueMeta(ValueMetaPB.newBuilder().setValueType(TypeEnumPB.BOOL)).build();
        assertThat(service.addMonitoringParam(request(invalidBalance)).getRt().getCode())
            .isEqualTo(StatusCode.MONITORING_PARAM_BALANCE_TYPE_VALUE_TYPE_NOT_MATCH_VALUE);
        assertThat(service.addMonitoringParam(request(invalidBalance.toBuilder()
            .setValueMeta(ValueMetaPB.newBuilder().setIsMultipleSelection(true)).build()))
            .getRt().getCode())
            .isEqualTo(StatusCode.MONITORING_PARAM_MULTI_SELECTION_NOT_ALLOWED_VALUE);
        assertThat(service.updateMonitoringParam("invalid-json").getRt().getCode())
            .isEqualTo(StatusCode.PARSE_JSON_FAILED_VALUE);
        assertThat(service.updateMonitoringParam(request(MonitoringParamPB.getDefaultInstance()))
            .getRt().getCode()).isEqualTo(StatusCode.MONITORING_PARAM_CODE_EMPTY_VALUE);
        assertThat(service.updateMonitoringParam(request(invalidBalance)).getRt().getCode())
            .isEqualTo(StatusCode.MONITORING_PARAM_BALANCE_TYPE_VALUE_TYPE_NOT_MATCH_VALUE);
        assertThat(service.deleteMonitoringParam("invalid-json").getRt().getCode())
            .isEqualTo(StatusCode.PARSE_JSON_FAILED_VALUE);
        assertThat(service.deleteMonitoringParam("{}").getRt().getCode())
            .isEqualTo(StatusCode.MONITORING_PARAM_CODE_EMPTY_VALUE);
        SecurityContextHolder.clearContext();
        GenericResp unauthenticated =
            webApiService.addMonitoringParam(request(param(FIRST_CODE)));
        assertThat(unauthenticated.getRt().getCode())
            .isEqualTo(StatusCode.ACCOUNT_NOT_FOUND_VALUE);
        assertThat(webApiService.updateMonitoringParam(request(param(FIRST_CODE)))
            .getRt().getCode()).isEqualTo(StatusCode.ACCOUNT_NOT_FOUND_VALUE);
        assertThat(webApiService.deleteMonitoringParam(deleteRequest(false)).getRt().getCode())
            .isEqualTo(StatusCode.ACCOUNT_NOT_FOUND_VALUE);
        assertThat(repository.findByCodeIn(TEST_CODES)).isEmpty();
        assertThat(historyCount()).isZero();
        assertThat(monitoringLockKeys()).isEmpty();
        verifyNoMoreInteractions(serviceExecutor);
    }

    private MonitoringMetaService configuredService(
        List<MonitoringParamPB> params, MonitoringParamHistoryRepository historyRepository
    ) {
        return configuredService(params, historyRepository, repository);
    }

    private MonitoringMetaService configuredService(
        List<MonitoringParamPB> params, MonitoringParamHistoryRepository historyRepository,
        MonitoringParamRepository paramRepository
    ) {
        ConfigProtoService configured = spy(protoService);
        doReturn(protoService.getConfig().toBuilder().setMonitoring(
            protoService.getConfig().getMonitoring().toBuilder()
                .clearMonitoringParam().addAllMonitoringParam(params)).build())
            .when(configured).getConfig();
        return new MonitoringMetaService(
            configured, userService, paramRepository, deptRepository, historyRepository,
            serviceExecutor);
    }

    private MonitoringParam seedParam() {
        return repository.saveAndFlush(MonitoringPbUtils.toMonitoringParamEntity(
            param(FIRST_CODE), maxDisplayOrder() + 1));
    }

    private DeptMonitoringParam seedDeptParam(MonitoringParam param) {
        return deptRepository.saveAndFlush(DeptMonitoringParam.builder()
            .id(new DeptMonitoringParamId(param.getCode(), DEPT_ID))
            .typePb(param.getTypePb()).build());
    }

    private List<MonitoringParamHistory> histories() {
        return historyRepo.findAll().stream().filter(h -> TEST_CODES.contains(h.getCode()))
            .sorted(Comparator.comparing(MonitoringParamHistory::getId)).toList();
    }

    private String deleteRequest(boolean department) {
        return ProtoUtils.protoToJson(DeleteMonitoringParamReq.newBuilder().setCode(FIRST_CODE)
            .setDepartmentId(department ? DEPT_ID : "").build());
    }

    private MonitoringParamPB param(String code) {
        return MonitoringParamPB.newBuilder().setCode(code).setName("初始化参数")
            .setValueMeta(ValueMetaPB.newBuilder().setValueType(TypeEnumPB.FLOAT)).build();
    }

    private GenericResp add(String code) {
        return service.addMonitoringParam(request(param(code).toBuilder().setName("接口新增")
            .setNameEn("Custom parameter").setCategory(3).setUiModalCode("custom-modal").build()));
    }

    private String request(MonitoringParamPB param) {
        return ProtoUtils.protoToJson(AddMonitoringParamReq.newBuilder().setParam(param).build());
    }

    private <T> List<T> runContending(Supplier<T> first, Supplier<T> second) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(
                List.of(LockKeys.monitoringParam()), () -> {
                    locked.countDown();
                    try {
                        assertTrue(release.await(30, TimeUnit.SECONDS), "等待释放业务锁超时");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return null;
                }));
            assertTrue(locked.await(10, TimeUnit.SECONDS), "未取得观察参数业务锁");
            Future<T> firstResult = pool.submit(first::get);
            Future<T> secondResult = pool.submit(second::get);
            assertTrue(waitForBlockedRequests(), "两个请求应同时等待观察参数业务锁");
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

    private boolean waitForBlockedRequests() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock'
                  AND query LIKE '%app_transaction_locks%'
                """, Integer.class);
            if (waiting != null && waiting >= 2) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private int maxDisplayOrder() {
        return repository.findTopByOrderByDisplayOrderDesc()
            .map(MonitoringParam::getDisplayOrder).orElse(0);
    }

    private List<String> orderedParamCodes() {
        return repository.findAll().stream()
            .sorted(Comparator.comparing(MonitoringParam::getDisplayOrder)
                .thenComparing(MonitoringParam::getCode))
            .map(MonitoringParam::getCode).toList();
    }

    private void assertContinuousDisplayOrders() {
        List<Integer> orders = jdbcTemplate.queryForList(
            "SELECT display_order FROM monitoring_params ORDER BY display_order", Integer.class);
        assertThat(orders).containsExactlyElementsOf(
            IntStream.rangeClosed(1, orders.size()).boxed().toList());
    }

    private int historyCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM monitoring_params_history WHERE code IN (?, ?)",
            Integer.class, FIRST_CODE, SECOND_CODE);
    }

    private List<String> monitoringLockKeys() {
        return jdbcTemplate.queryForList("""
            SELECT resource_key FROM app_transaction_locks
            WHERE resource_key = ? OR resource_key LIKE ? ORDER BY resource_key
            """, String.class, LockKeys.monitoringParam(), LockKeys.monitoringParam() + ":%");
    }

    private void cleanParams() {
        jdbcTemplate.update("DELETE FROM dept_monitoring_params WHERE code IN (?, ?)",
            FIRST_CODE, SECOND_CODE);
        jdbcTemplate.update("DELETE FROM monitoring_params_history WHERE code IN (?, ?)",
            FIRST_CODE, SECOND_CODE);
        jdbcTemplate.update("DELETE FROM monitoring_params WHERE code IN (?, ?)",
            FIRST_CODE, SECOND_CODE);
        jdbcTemplate.update("""
            DELETE FROM app_transaction_locks WHERE resource_key = ? OR resource_key LIKE ?
            """, LockKeys.monitoringParam(), LockKeys.monitoringParam() + ":%");
    }

    private static final String ACCOUNT = "monitoring-meta-tests-user";
    private static final String DEPT_ID = "monitoring-meta-tests-dept";
    private static final String FIRST_CODE = "monitoring-meta-tests-first";
    private static final String SECOND_CODE = "monitoring-meta-tests-second";
    private static final Set<String> TEST_CODES = Set.of(FIRST_CODE, SECOND_CODE);

    private MonitoringMetaService service;
    private UserService userService;
    private LockExecutor serviceExecutor;
    @Autowired private WebApiService webApiService;
    @Autowired private MonitoringMetaService monitoringMetaService;
    @Autowired private ConfigProtoService protoService;
    @Autowired private MonitoringParamRepository repository;
    @Autowired private DeptMonitoringParamRepository deptRepository;
    @Autowired private MonitoringParamHistoryRepository historyRepo;
    @Autowired private LockExecutor executor;
    @Autowired private JdbcTemplate jdbcTemplate;
}
