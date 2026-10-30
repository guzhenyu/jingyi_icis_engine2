package com.jingyicare.jingyi_icis_engine.service.locks;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;

class LockServiceTests extends TestsBase {
    @BeforeEach
    void prepare() {
        cleanUp();
        for (int functionId : List.of(FIRST_FUNCTION_ID, SECOND_FUNCTION_ID)) {
            jdbcTemplate.update("""
                INSERT INTO system_settings(
                    function_id, function_name, settings_pb, modified_at, modified_by
                )
                VALUES (?, 'before', '14', CURRENT_TIMESTAMP, 'LockServiceTests')
                """, functionId);
        }
    }

    @AfterEach
    void cleanUp() {
        // 仅清理本测试的记录，保留应用启动时初始化的配置。
        jdbcTemplate.update(
            "DELETE FROM system_settings WHERE function_id IN (?, ?)",
            FIRST_FUNCTION_ID, SECOND_FUNCTION_ID
        );
        jdbcTemplate.update(
            "DELETE FROM app_transaction_locks WHERE resource_key LIKE ?",
            KEY_PREFIX + "%"
        );
    }

    @Test
    void shouldCommitBusinessChangesAndCreateLock() {
        String result = executor.execute(List.of(FIRST_KEY, FIRST_KEY), () -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, lockCount(FIRST_KEY));
            updateFunctionName("after");
            return "committed";
        });

        assertEquals("committed", result);
        assertEquals("after", readFunctionName());
        assertEquals(1, lockCount(FIRST_KEY));
    }

    @Test
    void shouldRollbackBusinessChangesAndNewLockWhenActionFails() {
        IllegalArgumentException failure = new IllegalArgumentException("业务执行失败");

        IllegalArgumentException actual = assertThrows(IllegalArgumentException.class,
            () -> executor.execute(List.of(FIRST_KEY), () -> {
                updateFunctionName("after");
                throw failure;
            })
        );

        assertSame(failure, actual);
        assertEquals("before", readFunctionName());
        assertEquals(0, lockCount(FIRST_KEY));
    }

    @Test
    void rejectNestedExecutorAndRollback() {
        assertThrows(IllegalStateException.class,
            () -> executor.execute(List.of(FIRST_KEY), () -> {
                updateFunctionName("after");
                // 内层执行器被拒绝时，外层已经执行的 JDBC 更新也应回滚。
                executor.execute(List.of(SECOND_KEY), () -> null);
                return null;
            })
        );

        assertEquals("before", readFunctionName());
        assertEquals(0, lockCount(FIRST_KEY));
        assertEquals(0, lockCount(SECOND_KEY));
    }

    @Test
    void shouldReplanWhenSettingsChanged() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger businessRuns = new AtomicInteger();

        String result = executor.executePlanned(
            () -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                // 首次模拟过期的预读取结果；重试时读取数据库中的真实值。
                return attempts.incrementAndGet() == 1 ? "12" : readSettings();
            },
            source -> List.of(FIRST_KEY, KEY_PREFIX + "plan:" + source),
            source -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                return readSettings().equals(source);
            },
            source -> {
                businessRuns.incrementAndGet();
                updateFunctionName("after");
                return "source=" + source;
            }
        );

        assertEquals("source=14", result);
        assertEquals(2, attempts.get());
        assertEquals(1, businessRuns.get());
        assertEquals("after", readFunctionName());
        assertEquals(0, lockCount(KEY_PREFIX + "plan:12"));
        assertEquals(1, lockCount(KEY_PREFIX + "plan:14"));
    }

    @Test
    void shouldStopAfterThreeInvalidPlansWithoutRunningBusinessAction() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger businessRuns = new AtomicInteger();

        assertThrows(IllegalStateException.class,
            () -> executor.executePlanned(
                attempts::incrementAndGet,
                plan -> List.of(FIRST_KEY, KEY_PREFIX + "plan:" + plan),
                plan -> false,
                plan -> {
                    businessRuns.incrementAndGet();
                    updateFunctionName("after");
                    return null;
                }
            )
        );

        assertEquals(3, attempts.get());
        assertEquals(0, businessRuns.get());
        assertEquals("before", readFunctionName());
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_transaction_locks WHERE resource_key LIKE ?",
            Integer.class, KEY_PREFIX + "%"
        ));
    }

    @ParameterizedTest(name = "相同资源并发执行，锁记录已存在：{0}")
    @ValueSource(booleans = {false, true})
    void concurrentRequestsShouldSerialize(boolean locksAlreadyExist) throws Exception {
        if (locksAlreadyExist) {
            for (String key : List.of(FIRST_KEY, SECOND_KEY)) {
                jdbcTemplate.update(
                    "INSERT INTO app_transaction_locks(resource_key) VALUES (?)", key
                );
            }
        }

        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();
        AtomicInteger secondPid = new AtomicInteger();
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            // 反向传入资源并包含重复键，两个请求最终应按统一顺序获取锁。
            Future<?> first = pool.submit(() -> executor.execute(
                List.of(SECOND_KEY, FIRST_KEY, FIRST_KEY),
                () -> {
                    firstPid.set(jdbcTemplate.queryForObject(
                        "SELECT pg_backend_pid()", Integer.class
                    ));
                    String name = readFunctionName();
                    events.add("first-enter");
                    firstHasLock.countDown();
                    awaitRelease(releaseFirst);
                    // 在确认第二个请求被阻塞之前不更新业务表，避免业务行锁干扰判断。
                    updateFunctionName(name + "-first");
                    events.add("first-exit");
                    return null;
                }
            ));

            assertTrue(firstHasLock.await(10, TimeUnit.SECONDS), "第一个请求未取得锁");

            Future<?> second = pool.submit(() -> executor.execute(
                List.of(FIRST_KEY, SECOND_KEY),
                () -> {
                    secondPid.set(jdbcTemplate.queryForObject(
                        "SELECT pg_backend_pid()", Integer.class
                    ));
                    events.add("second-enter");
                    updateFunctionName(readFunctionName() + "-second");
                    return null;
                }
            ));

            assertTrue(waitForBlocker(firstPid.get()), "第二个请求应在数据库中等待业务锁");
            assertEquals(List.of("first-enter"), List.copyOf(events));

            releaseFirst.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);

            assertEquals(
                List.of("first-enter", "first-exit", "second-enter"), List.copyOf(events)
            );
            assertNotEquals(firstPid.get(), secondPid.get());
            assertEquals("before-first-second", readFunctionName());
            assertEquals(1, lockCount(FIRST_KEY));
            assertEquals(1, lockCount(SECOND_KEY));
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "并发测试线程未结束");
        }
    }

    private boolean waitForBlocker(int blockerPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_stat_activity a
                WHERE a.datname = current_database()
                  AND a.wait_event_type = 'Lock'
                  AND ? = ANY(pg_blocking_pids(a.pid))
                """, Integer.class, blockerPid);
            if (waiting != null && waiting > 0) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待释放业务锁超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待释放业务锁时被中断", e);
        }
    }

    private void updateFunctionName(String name) {
        assertEquals(1, jdbcTemplate.update("""
            UPDATE system_settings
            SET function_name = ?, modified_at = CURRENT_TIMESTAMP
            WHERE function_id = ?
            """, name, FIRST_FUNCTION_ID));
    }

    private String readFunctionName() {
        return jdbcTemplate.queryForObject(
            "SELECT function_name FROM system_settings WHERE function_id = ?",
            String.class, FIRST_FUNCTION_ID
        );
    }

    private String readSettings() {
        return jdbcTemplate.queryForObject(
            "SELECT settings_pb FROM system_settings WHERE function_id = ?",
            String.class, FIRST_FUNCTION_ID
        );
    }

    private int lockCount(String key) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_transaction_locks WHERE resource_key = ?",
            Integer.class, key
        );
    }

    private static final int FIRST_FUNCTION_ID = 900001;
    private static final int SECOND_FUNCTION_ID = 900002;
    private static final String KEY_PREFIX = "lock-service-tests:system_settings:";
    private static final String FIRST_KEY = KEY_PREFIX + FIRST_FUNCTION_ID;
    private static final String SECOND_KEY = KEY_PREFIX + SECOND_FUNCTION_ID;

    @Autowired
    private LockExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;
}
