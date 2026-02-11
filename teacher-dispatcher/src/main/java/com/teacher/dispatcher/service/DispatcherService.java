package com.teacher.dispatcher.service;

import com.teacher.common.exception.AiServiceException;
import com.teacher.common.exception.KeyPoolExhaustedException;
import com.teacher.dispatcher.config.DispatcherProperties;
import com.teacher.dispatcher.pool.ApiKeyPool;
import com.teacher.dispatcher.ratelimit.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * 并发调度服务：利用 Virtual Threads 和 Key 池实现高并发 AI API 调用。
 * <p>
 * 核心策略：
 * - 用 Semaphore 控制并发数，确保同一时刻运行的任务数 <= 可用 Key 数
 * - 任务排队等待而非抢占失败，适配单 Key / 少 Key 场景
 * - 带进度日志，方便跟踪长任务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatcherService {

    private final ApiKeyPool keyPool;
    private final RateLimiter rateLimiter;
    private final DispatcherProperties properties;

    /** Virtual Thread 执行器 */
    private final ExecutorService virtualThreadPool =
            Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 并发执行一批任务，用 Semaphore 控制并发度，每个任务自动获取/归还 Key。
     *
     * @param items      待处理的数据列表
     * @param taskRunner 实际的任务逻辑 (item, apiKey) -> result
     * @param <T>        输入类型
     * @param <R>        输出类型
     * @return 结果列表（与输入顺序一致，失败的为 null）
     */
    public <T, R> List<R> dispatchAll(List<T> items, BiFunction<T, String, R> taskRunner) {
        int totalTasks = items.size();
        // 并发度 = min(可用Key数, 配置的最大并发数, 任务数)
        int keyCount = Math.max(1, (int) keyPool.availableCount());
        int concurrency = Math.min(keyCount, Math.min(properties.getMaxConcurrent(), totalTasks));

        log.info("开始并发调度 {} 个任务, Key池可用: {}, 并发度: {}", totalTasks, keyCount, concurrency);

        // Semaphore 控制同一时刻最多 concurrency 个任务在执行
        Semaphore semaphore = new Semaphore(concurrency);
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger succeeded = new AtomicInteger(0);

        List<CompletableFuture<R>> futures = new ArrayList<>();

        for (int idx = 0; idx < totalTasks; idx++) {
            final T item = items.get(idx);
            final int taskIndex = idx;

            CompletableFuture<R> future = CompletableFuture.supplyAsync(() -> {
                try {
                    // 排队等待信号量（不会立即失败，会耐心等待）
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AiServiceException("等待调度时被中断");
                }

                try {
                    R result = executeWithRetry(item, taskRunner);
                    succeeded.incrementAndGet();
                    return result;
                } catch (Exception e) {
                    log.warn("任务 #{} 最终失败: {}", taskIndex, e.getMessage());
                    return null;
                } finally {
                    semaphore.release();
                    int done = completed.incrementAndGet();
                    // 每完成一定数量或最后一个时打印进度
                    if (done % 5 == 0 || done == totalTasks) {
                        log.info("批改进度: {}/{} (成功 {})", done, totalTasks, succeeded.get());
                    }
                }
            }, virtualThreadPool);

            futures.add(future);
        }

        // 等待所有任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 收集结果
        List<R> results = new ArrayList<>();
        for (CompletableFuture<R> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception e) {
                log.error("获取任务结果异常", e);
                results.add(null);
            }
        }

        log.info("并发调度完成, 成功: {}/{}", succeeded.get(), totalTasks);
        return results;
    }

    /**
     * 带重试的任务执行。
     * 由于 Semaphore 已经控制了并发，这里的重试主要应对 AI 接口临时错误。
     */
    private <T, R> R executeWithRetry(T item, BiFunction<T, String, R> taskRunner) {
        int maxRetries = properties.getRetryCount();
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            String key = null;
            try {
                key = borrowKeyWithRateLimit();
                R result = taskRunner.apply(item, key);
                keyPool.returnKey(key);
                return result;

            } catch (KeyPoolExhaustedException e) {
                // Semaphore 控制并发后，这种情况很少发生
                // 如果发生说明限流或短暂竞争，等一会再试
                log.debug("Key 暂时不可用，等待后重试 (第 {} 次)", attempt + 1);
                lastException = e;
                sleep(2000L * (attempt + 1));

            } catch (Exception e) {
                log.warn("AI 调用失败 (尝试 {}/{}): {}", attempt + 1, maxRetries + 1, e.getMessage());
                lastException = e;
                if (key != null) {
                    // API 调用失败，标记 Key 有问题
                    keyPool.markFailed(key);
                }
                sleep(1000L * (attempt + 1));
            }
        }

        throw new AiServiceException("任务在 " + (maxRetries + 1) + " 次尝试后仍然失败",
                lastException);
    }

    /**
     * 借出 Key 并确保未超过速率限制。
     */
    private String borrowKeyWithRateLimit() {
        int maxAttempts = 3;
        for (int i = 0; i < maxAttempts; i++) {
            String key = keyPool.borrowKey();
            if (rateLimiter.tryAcquire(key)) {
                return key;
            }
            // Key 已达限流，归还并等一下
            keyPool.returnKey(key);
            log.debug("Key 已达限流，等待后重试");
            sleep(1000);
        }
        throw new KeyPoolExhaustedException("Key 当前速率限制中，请稍后重试");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
