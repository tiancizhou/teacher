package com.teacher.dispatcher.pool;

import com.teacher.common.exception.KeyPoolExhaustedException;
import com.teacher.dispatcher.config.DispatcherProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 基于内存的 API Key 轮询池。
 * <p>
 * 使用 {@link LinkedBlockingQueue} 实现线程安全的借/还操作，
 * 适用于单机轻量部署，无需 Redis。
 */
@Slf4j
public class InMemoryApiKeyPool implements ApiKeyPool {

    private final BlockingQueue<String> available = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> failed = new LinkedBlockingQueue<>();
    private final DispatcherProperties properties;

    public InMemoryApiKeyPool(DispatcherProperties properties) {
        this.properties = properties;
    }

    @Override
    public String borrowKey() {
        try {
            String key = available.poll(properties.getKeyBorrowTimeoutSeconds(), TimeUnit.SECONDS);
            if (key == null) {
                throw new KeyPoolExhaustedException("API Key 池已耗尽，请稍后重试或添加更多 Key");
            }
            log.debug("借出 Key: {}...", maskKey(key));
            return key;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KeyPoolExhaustedException("等待借用 Key 时被中断");
        }
    }

    @Override
    public void returnKey(String key) {
        available.offer(key);
        log.debug("归还 Key: {}...", maskKey(key));
    }

    @Override
    public void markFailed(String key) {
        failed.offer(key);
        log.warn("Key 标记为失败: {}...", maskKey(key));
    }

    @Override
    public void addKey(String key) {
        available.offer(key);
        log.info("添加新 Key 到池: {}...", maskKey(key));
    }

    @Override
    public void addKeys(List<String> keys) {
        for (String key : keys) {
            available.offer(key);
        }
        log.info("批量添加 {} 个 Key", keys.size());
    }

    @Override
    public long availableCount() {
        return available.size();
    }

    @Override
    public long failedCount() {
        return failed.size();
    }

    @Override
    public int recoverFailedKeys() {
        int recovered = 0;
        String key;
        while ((key = failed.poll()) != null) {
            available.offer(key);
            recovered++;
        }
        if (recovered > 0) {
            log.info("恢复了 {} 个失败的 Key", recovered);
        }
        return recovered;
    }

    private String maskKey(String key) {
        if (key == null || key.length() <= 8) return "***";
        return key.substring(0, 8) + "***";
    }
}
