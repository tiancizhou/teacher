package com.teacher.dispatcher.ratelimit;

import com.teacher.dispatcher.config.DispatcherProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * 基于 Redis Sorted Set 的滑动窗口限流器。
 * <p>
 * 适用于多实例分布式部署，所有实例共享限流计数。
 */
@Slf4j
public class RedisRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final DispatcherProperties properties;

    private static final String RATE_LIMIT_PREFIX = "ratelimit:";

    public RedisRateLimiter(StringRedisTemplate redisTemplate, DispatcherProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public boolean tryAcquire(String apiKey) {
        String redisKey = RATE_LIMIT_PREFIX + apiKey.hashCode();
        long now = Instant.now().toEpochMilli();
        long windowStart = now - properties.getRateLimitWindowSeconds() * 1000L;

        redisTemplate.opsForZSet().removeRangeByScore(redisKey, 0, windowStart);

        Long count = redisTemplate.opsForZSet().zCard(redisKey);
        if (count != null && count >= properties.getRateLimitMaxRequests()) {
            log.debug("Key {}... 已达速率限制 ({}/{})",
                    apiKey.substring(0, Math.min(8, apiKey.length())),
                    count, properties.getRateLimitMaxRequests());
            return false;
        }

        String member = now + ":" + Thread.currentThread().threadId();
        redisTemplate.opsForZSet().add(redisKey, member, now);
        redisTemplate.expire(redisKey, Duration.ofSeconds(properties.getRateLimitWindowSeconds() + 10));

        return true;
    }

    @Override
    public long remainingQuota(String apiKey) {
        String redisKey = RATE_LIMIT_PREFIX + apiKey.hashCode();
        long windowStart = Instant.now().toEpochMilli() - properties.getRateLimitWindowSeconds() * 1000L;
        redisTemplate.opsForZSet().removeRangeByScore(redisKey, 0, windowStart);
        Long count = redisTemplate.opsForZSet().zCard(redisKey);
        long used = count != null ? count : 0;
        return Math.max(0, properties.getRateLimitMaxRequests() - used);
    }
}
