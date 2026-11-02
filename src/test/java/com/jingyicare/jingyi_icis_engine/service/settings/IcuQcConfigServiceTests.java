package com.jingyicare.jingyi_icis_engine.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.jingyicare.jingyi_icis_engine.entity.settings.SystemSettings;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisQualityControl.IcuQcConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.SystemSettingFunctionId;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.repository.settings.SystemSettingsRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class IcuQcConfigServiceTests extends TestsBase {
    @BeforeEach
    void prepare() {
        previousSettings = settingsRepo.findById(FUNCTION_ID).orElse(null);
        previousConfig = service.getIcuQcConfigPb();
        previousLockCount = lockCount();
        jdbcTemplate.update(
            "INSERT INTO rbac_accounts(account_id, account_name) VALUES (?, ?)", ACCOUNT, ACCOUNT);
        authenticate();
    }

    @AfterEach
    void cleanUp() {
        try {
            // 保留测试前已经存在的配置和锁。
            settingsRepo.deleteById(FUNCTION_ID);
            if (previousSettings != null) settingsRepo.save(previousSettings);
            if (previousLockCount == 0) {
                jdbcTemplate.update(
                    "DELETE FROM app_transaction_locks WHERE resource_key = ?", KEY);
            }
            jdbcTemplate.update("DELETE FROM rbac_accounts WHERE account_id = ?", ACCOUNT);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void readsCurrentConfigAndRejectsInvalidUpdatesWithoutLocks() {
        var response = service.getIqcConfig("{}");
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(response.getIqcConfig()).isEqualTo(previousConfig);
        assertThat(service.getIqcConfig("{").getRt().getCode())
            .isEqualTo(StatusCode.PARSE_JSON_FAILED_VALUE);
        for (String payload : List.of("{", "{}")) {
            assertThat(service.updateIqcConfig(payload).getRt().getCode())
                .isEqualTo(StatusCode.PARSE_JSON_FAILED_VALUE);
        }
        SecurityContextHolder.clearContext();
        assertThat(service.updateIqcConfig(request(changedConfig(35))).getRt().getCode())
            .isEqualTo(StatusCode.ACCOUNT_NOT_FOUND_VALUE);
        assertThat(service.getIcuQcConfigPb()).isEqualTo(previousConfig);
        assertThat(lockCount()).isEqualTo(previousLockCount);
    }

    @Test
    void savesGlobalConfigAndMakesItAvailableToExistingServiceInstances() {
        IcuQcConfigService secondService = newService(executor);
        assertThat(secondService.getIcuQcConfigPb()).isEqualTo(previousConfig);
        IcuQcConfigPB config = changedConfig(35);

        assertOk(service.updateIqcConfig(request(config)));

        var stored = settingsRepo.findById(FUNCTION_ID).orElseThrow();
        assertThat(ProtoUtils.decodeIcuQcConfig(stored.getSettingsPb())).isEqualTo(config);
        assertThat(stored.getFunctionName()).isEqualTo("GET_IQC_CONFIG");
        assertThat(stored.getModifiedBy()).isEqualTo(ACCOUNT);
        assertThat(stored.getModifiedAt()).isNotNull();
        assertThat(service.getIqcConfig("{}").getIqcConfig()).isEqualTo(config);
        assertThat(secondService.getIcuQcConfigPb()).isEqualTo(config);
        assertThat(secondService.getIqcConfig("{}").getIqcConfig()).isEqualTo(config);
        assertThat(lockCount()).isEqualTo(1);
    }

    @Test
    void readsExternalChangesAndFallsBackWhenStoredConfigIsInvalidOrMissing() {
        settingsRepo.deleteById(FUNCTION_ID);
        IcuQcConfigPB defaults = service.getIcuQcConfigPb();
        IcuQcConfigPB config = defaults.toBuilder().setSepsisSepticShockDiagnosis(
            defaults.getSepsisSepticShockDiagnosis().toBuilder().setAgeLowerBound(53)).build();
        var stored = SystemSettings.builder().functionId(FUNCTION_ID)
            .functionName("GET_IQC_CONFIG").settingsPb(ProtoUtils.encodeIcuQcConfig(config))
            .modifiedAt(LocalDateTime.now()).modifiedBy(ACCOUNT).build();
        settingsRepo.save(stored);
        assertThat(service.getIcuQcConfigPb()).isEqualTo(config);

        stored.setSettingsPb("invalid!");
        settingsRepo.save(stored);
        assertThat(service.getIcuQcConfigPb()).isEqualTo(defaults);
        assertThat(service.getIqcConfig("{}").getIqcConfig()).isEqualTo(defaults);

        settingsRepo.deleteById(FUNCTION_ID);
        assertThat(service.getIqcConfig("{}").getIqcConfig()).isEqualTo(defaults);
        assertThat(lockCount()).isEqualTo(previousLockCount);
    }

    @Test
    void concurrentFirstUpdatesAcrossInstancesWaitForSameDatabaseLock() throws Exception {
        settingsRepo.deleteById(FUNCTION_ID);
        IcuQcConfigService secondService = newService(executor);
        IcuQcConfigPB firstConfig = changedConfig(37);
        IcuQcConfigPB secondConfig = changedConfig(38);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(List.of(KEY), () -> {
                locked.countDown();
                await(release);
                return null;
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<GenericResp> first = pool.submit(
                () -> authenticated(() -> service.updateIqcConfig(request(firstConfig))));
            Future<GenericResp> second = pool.submit(
                () -> authenticated(() -> secondService.updateIqcConfig(request(secondConfig))));
            assertTrue(waitUntil(() -> jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock'
                  AND query LIKE '%app_transaction_locks%'
                """, Integer.class) == 2), "两个实例应等待同一个数据库业务锁");
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            assertOk(first.get(15, TimeUnit.SECONDS));
            assertOk(second.get(15, TimeUnit.SECONDS));

            var stored = settingsRepo.findById(FUNCTION_ID).orElseThrow();
            var actual = ProtoUtils.decodeIcuQcConfig(stored.getSettingsPb());
            assertThat(actual).isIn(firstConfig, secondConfig);
            assertThat(service.getIcuQcConfigPb()).isEqualTo(actual);
            assertThat(secondService.getIcuQcConfigPb()).isEqualTo(actual);
            assertThat(secondService.getIqcConfig("{}").getIqcConfig()).isEqualTo(actual);
            assertThat(lockCount()).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedCommitKeepsPreviousCommittedConfig() {
        LockExecutor failingExecutor = new LockExecutor(transactionManager, jdbcTemplate) {
            @Override
            public <T> T execute(Collection<String> keys, Supplier<T> action) {
                return super.execute(keys, () -> {
                    T result = action.get();
                    TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void beforeCommit(boolean readOnly) {
                                throw new IllegalStateException("提交配置失败");
                            }
                        });
                    return result;
                });
            }
        };
        var failingService = newService(failingExecutor);
        IcuQcConfigPB original = failingService.getIcuQcConfigPb();

        assertThrows(IllegalStateException.class,
            () -> failingService.updateIqcConfig(request(changedConfig(39))));

        assertThat(failingService.getIcuQcConfigPb()).isEqualTo(original);
        assertThat(newService(executor).getIcuQcConfigPb()).isEqualTo(original);
        assertThat(settingsRepo.findById(FUNCTION_ID).orElse(null)).isEqualTo(previousSettings);
        assertThat(lockCount()).isEqualTo(previousLockCount);
    }

    @Test
    void olderRequestReturningAfterNewerCommitDoesNotRevertConfig() throws Exception {
        CountDownLatch firstCommitted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger commits = new AtomicInteger();
        LockExecutor pausingExecutor = new LockExecutor(transactionManager, jdbcTemplate) {
            @Override
            public <T> T execute(Collection<String> keys, Supplier<T> action) {
                T result = super.execute(keys, action);
                if (commits.incrementAndGet() == 1) {
                    firstCommitted.countDown();
                    await(releaseFirst);
                }
                return result;
            }
        };
        var localService = newService(pausingExecutor);
        IcuQcConfigPB firstConfig = changedConfig(41);
        IcuQcConfigPB secondConfig = changedConfig(43);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<GenericResp> first = pool.submit(
                () -> authenticated(() -> localService.updateIqcConfig(request(firstConfig))));
            assertTrue(firstCommitted.await(10, TimeUnit.SECONDS));
            Future<GenericResp> second = pool.submit(
                () -> authenticated(() -> localService.updateIqcConfig(request(secondConfig))));
            // 第一个请求已提交但尚未返回，第二个请求仍可提交并立即被读取。
            assertOk(second.get(15, TimeUnit.SECONDS));
            assertThat(localService.getIcuQcConfigPb()).isEqualTo(secondConfig);
            releaseFirst.countDown();
            assertOk(first.get(15, TimeUnit.SECONDS));

            assertThat(localService.getIcuQcConfigPb()).isEqualTo(secondConfig);
            assertThat(newService(executor).getIcuQcConfigPb()).isEqualTo(secondConfig);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private IcuQcConfigService newService(LockExecutor lockExecutor) {
        return new IcuQcConfigService(
            configResource, charsetName, protoService, settingsRepo, users, lockExecutor);
    }

    private IcuQcConfigPB changedConfig(int age) {
        return previousConfig.toBuilder().setSepsisSepticShockDiagnosis(
            previousConfig.getSepsisSepticShockDiagnosis().toBuilder().setAgeLowerBound(age))
            .build();
    }

    private String request(IcuQcConfigPB config) {
        return ProtoUtils.protoToJson(UpdateIqcConfigReq.newBuilder().setIqcConfig(config).build());
    }

    private int lockCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_transaction_locks WHERE resource_key = ?",
            Integer.class, KEY);
    }

    private void assertOk(GenericResp response) {
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
    }

    private GenericResp authenticated(Supplier<GenericResp> action) {
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

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS), "等待释放配置更新超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private boolean waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static final int FUNCTION_ID = SystemSettingFunctionId.GET_IQC_CONFIG_VALUE;
    private static final String KEY = LockKeys.systemSettings(FUNCTION_ID);
    private static final String ACCOUNT = "icu-qc-config-tests-user";
    private SystemSettings previousSettings;
    private IcuQcConfigPB previousConfig;
    private int previousLockCount;

    @Autowired private IcuQcConfigService service;
    @Autowired private LockExecutor executor;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ConfigProtoService protoService;
    @Autowired private SystemSettingsRepository settingsRepo;
    @Autowired private UserService users;
    @Value("${jingyi.textresources.icis_qc_config}") private Resource configResource;
    @Value("${jingyi.textresources.charset}") private String charsetName;
}
