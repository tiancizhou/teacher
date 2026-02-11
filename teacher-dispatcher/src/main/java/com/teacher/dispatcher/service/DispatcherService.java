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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

/**
 * 并发调度服务：利用 Virtual Threads 和 Key 池实现高并发 AI API 调用。
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
     * 并发执行一批任务，每个任务自动获取/归还 Key，并带有重试机制。
     *
     * @param items      待处理的数据列表
     * @param taskRunner 实际的任务逻辑 (item, apiKey) -> result
     * @param <T>        输入类型
     * @param <R>        输出类型
     * @return 结果列表（与输入顺序一致）
     */
    public <T, R> List<R> dispatchAll(List<T> items, BiFunction<T, String, R> taskRunner) {
        log.info("开始并发调度 {} 个任务, Key池可用: {}", items.size(), keyPool.availableCount());

        List<CompletableFuture<R>> futures = new ArrayList<>();

        for (T item : items) {
            CompletableFuture<R> future = CompletableFuture.supplyAsync(
                    () -> executeWithRetry(item, taskRunner), virtualThreadPool
            );
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
                log.error("获取任务结果失败", e);
                results.add(null);
            }
        }

        log.info("并发调度完成, 成功: {}/{}", results.stream().filter(r -> r != null).count(), items.size());
        return results;
    }

    /**
     * 带重试的任务执行。
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
                log.warn("Key 池耗尽，第 {} 次重试", attempt + 1);
                lastException = e;
                sleep(1000L * (attempt + 1)); // 指数退避

            } catch (Exception e) {
                log.warn("任务执行失败 (尝试 {}/{}): {}", attempt + 1, maxRetries + 1, e.getMessage());
                lastException = e;
                if (key != null) {
                    keyPool.markFailed(key);
                }
                sleep(500L * (attempt + 1));
            }
        }

        throw new AiServiceException("任务在 " + (maxRetries + 1) + " 次尝试后仍然失败",
                lastException);
    }

    /**
     * 借出 Key 并确保未超过速率限制。
     */
    private String borrowKeyWithRateLimit() {
        int maxAttempts = 5;
        for (int i = 0; i < maxAttempts; i++) {
            String key = keyPool.borrowKey();
            if (rateLimiter.tryAcquire(key)) {
                return key;
            }
            // Key 已达限流，归还并重试另一个
            keyPool.returnKey(key);
            log.debug("Key 已达限流，尝试下一个");
        }
        throw new KeyPoolExhaustedException("所有可用 Key 均已达速率限制");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
