package com.teacher.dispatcher.ratelimit;

import com.teacher.dispatcher.config.DispatcherProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.Map;

/**
 * 基于内存的滑动窗口限流器。
 * <p>
 * 每个 API Key 维护一个时间戳队列，
 * 通过清理过期记录并计数来判断是否超限。
 * 适用于单机轻量部署，无需 Redis。
 */
@Slf4j
public class InMemoryRateLimiter implements RateLimiter {

    private final DispatcherProperties properties;

    /** key -> 请求时间戳队列 */
    private final Map<String, ConcurrentLinkedDeque<Long>> windows = new ConcurrentHashMap<>();

    public InMemoryRateLimiter(DispatcherProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean tryAcquire(String apiKey) {
        String slotKey = String.valueOf(apiKey.hashCode());
        ConcurrentLinkedDeque<Long> timestamps = windows.computeIfAbsent(slotKey,
                k -> new ConcurrentLinkedDeque<>());

        long now = System.currentTimeMillis();
        long windowStart = now - properties.getRateLimitWindowSeconds() * 1000L;

        // 清理过期记录
        while (!timestamps.isEmpty() && timestamps.peekFirst() != null && timestamps.peekFirst() < windowStart) {
            timestamps.pollFirst();
        }

        // 检查是否超限
        if (timestamps.size() >= properties.getRateLimitMaxRequests()) {
            log.debug("Key {}... 已达速率限制 ({}/{})",
                    apiKey.substring(0, Math.min(8, apiKey.length())),
                    timestamps.size(), properties.getRateLimitMaxRequests());
            return false;
        }

        // 记录本次请求
        timestamps.addLast(now);
        return true;
    }

    @Override
    public long remainingQuota(String apiKey) {
        String slotKey = String.valueOf(apiKey.hashCode());
        ConcurrentLinkedDeque<Long> timestamps = windows.get(slotKey);
        if (timestamps == null) {
            return properties.getRateLimitMaxRequests();
        }

        long windowStart = System.currentTimeMillis() - properties.getRateLimitWindowSeconds() * 1000L;
        while (!timestamps.isEmpty() && timestamps.peekFirst() != null && timestamps.peekFirst() < windowStart) {
            timestamps.pollFirst();
        }

        return Math.max(0, properties.getRateLimitMaxRequests() - timestamps.size());
    }
}
