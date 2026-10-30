package com.jingyicare.jingyi_icis_engine.service.locks;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class LockExecutor {
    public LockExecutor(
        PlatformTransactionManager transactionManager,
        JdbcTemplate jdbcTemplate
    ) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.jdbcTemplate = jdbcTemplate;
    }

    public <T> T execute(Collection<String> resourceKeys, Supplier<T> businessAction) {
        Objects.requireNonNull(businessAction);
        Objects.requireNonNull(resourceKeys);

        // 1. 禁止已经存在外层事务
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            threwIllegalState("LockExecutor 不允许在已有事务中调用");
        }

        // 2. 参数检查和统一排序
        if (resourceKeys == null || resourceKeys.isEmpty()) {
            threwIllegalState("锁资源不能为空");
        }

        List<String> sortedKeys = resourceKeys.stream()
            .map(key -> {
                if (key == null || key.isBlank()) {
                    threwIllegalState("无效的锁资源");
                }
                return key;
            })
            .distinct()
            .sorted()
            .toList();

        // 3. 开始物理事务
        return transactionTemplate.execute(status -> {

            // 4. 获取所有业务锁
            for (String key : sortedKeys) {
                log.info("请求锁：{}", key);

                jdbcTemplate.update(
                    """
                    INSERT INTO app_transaction_locks(resource_key)
                    VALUES (?)
                    ON CONFLICT (resource_key) DO NOTHING
                    """,
                    key
                );

                String lockedKey = jdbcTemplate.queryForObject(
                    """
                    SELECT resource_key
                    FROM app_transaction_locks
                    WHERE resource_key = ?
                    FOR UPDATE
                    """,
                    String.class,
                    key
                );

                if (!key.equals(lockedKey)) {
                    threwIllegalState(
                        String.format("业务锁获取异常，期望锁：%s，实际锁：%s", key, lockedKey)
                    );
                }

                log.info("已取得锁：{}", key);
            }

            // 5. 所有锁获得后才执行业务
            return businessAction.get();

            // COMMIT / ROLLBACK
        });
    }

    // 只表示“锁计划已经过期”，与数据库死锁异常不同
    private static final class LockPlanChangedException
            extends RuntimeException {
    }

    public <P, R> R executePlanned(
            Supplier<P> planSupplier,
            Function<P, Collection<String>> resourceResolver,
            Predicate<P> validateAfterLock,
            Function<P, R> businessAction
    ) {
        Objects.requireNonNull(planSupplier);
        Objects.requireNonNull(resourceResolver);
        Objects.requireNonNull(validateAfterLock);
        Objects.requireNonNull(businessAction);

        // 必须从事务外发起整个动态锁流程
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            threwIllegalState("动态业务锁执行器不能在已有事务中调用");
        }

        for (int attempt = 1; attempt <= MAX_PLAN_ATTEMPTS; attempt++) {
            // 1. 事务外预读取，产生一份候选计划
            P plan = Objects.requireNonNull(planSupplier.get());
            Collection<String> keys = resourceResolver.apply(plan);

            try {
                // 2. 调用已有 execute(), 它负责开启事务、排序、取得全部锁
                return execute(keys, () -> {
                    // 3. 所有锁取得后，重新验证候选计划
                    if (!validateAfterLock.test(plan)) {
                        throw new LockPlanChangedException();
                    }

                    // 4. 计划有效，才执行业务
                    return businessAction.apply(plan);
                });

            } catch (LockPlanChangedException e) {
                // execute() 的事务已经回滚并结束
                if (attempt == MAX_PLAN_ATTEMPTS) {
                    threwIllegalState("锁资源连续变化，已达到最大重新规划次数");
                }

                // 下一轮重新执行 planSupplier
                // 不复用旧计划
            }
        }

        threwIllegalState("无法完成锁资源规划");
        return null; // 这一行实际上不会被执行，因为上面已经抛出异常
    }

    private void threwIllegalState(String message) {
        log.error(message);
        throw new IllegalStateException(message);
    }

    private static final int MAX_PLAN_ATTEMPTS = 3;

    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
}