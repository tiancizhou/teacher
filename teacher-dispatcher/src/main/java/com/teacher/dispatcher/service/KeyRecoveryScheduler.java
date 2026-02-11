package com.teacher.dispatcher.service;

import com.teacher.dispatcher.config.DispatcherProperties;
import com.teacher.dispatcher.pool.ApiKeyPool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时任务：将失败队列中冷却完毕的 Key 恢复到可用池。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeyRecoveryScheduler {

    private final ApiKeyPool keyPool;
    private final DispatcherProperties properties;

    /**
     * 每隔一定时间（等于冷却时间）执行一次 Key 恢复。
     */
    @Scheduled(fixedDelayString = "${teacher.dispatcher.key-cooldown-seconds:60}000")
    public void recoverKeys() {
        long failedCount = keyPool.failedCount();
        if (failedCount > 0) {
            log.info("开始恢复失败 Key，当前失败队列大小: {}", failedCount);
            int recovered = keyPool.recoverFailedKeys();
            log.info("Key 恢复完成，恢复数量: {}, 当前可用: {}", recovered, keyPool.availableCount());
        }
    }
}
