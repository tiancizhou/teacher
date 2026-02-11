package com.teacher.dispatcher.ratelimit;

/**
 * 速率限制器接口。
 * <p>
 * 提供两种实现：
 * - {@link InMemoryRateLimiter}：内存实现，适合轻量单机部署
 * - {@link RedisRateLimiter}：Redis 实现，适合分布式多实例部署
 */
public interface RateLimiter {

    /**
     * 尝试获取请求许可。
     *
     * @param apiKey 要检查的 API Key
     * @return true 表示允许请求，false 表示已达速率限制
     */
    boolean tryAcquire(String apiKey);

    /**
     * 获取某个 Key 在当前窗口内剩余的可用请求数。
     */
    long remainingQuota(String apiKey);
}
