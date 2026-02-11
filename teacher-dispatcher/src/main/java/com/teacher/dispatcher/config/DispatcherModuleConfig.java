package com.teacher.dispatcher.config;

import com.teacher.dispatcher.pool.ApiKeyPool;
import com.teacher.dispatcher.pool.InMemoryApiKeyPool;
import com.teacher.dispatcher.pool.RedisApiKeyPool;
import com.teacher.dispatcher.ratelimit.InMemoryRateLimiter;
import com.teacher.dispatcher.ratelimit.RateLimiter;
import com.teacher.dispatcher.ratelimit.RedisRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 调度模块自动配置。
 * <p>
 * 通过 {@code teacher.dispatcher.storage-type} 切换存储实现：
 * <ul>
 *   <li>{@code memory}（默认）：纯内存，零外部依赖，适合轻量单机部署</li>
 *   <li>{@code redis}：Redis 实现，适合分布式多实例部署</li>
 * </ul>
 */
@Slf4j
@Configuration
@ComponentScan(basePackages = "com.teacher.dispatcher")
@EnableConfigurationProperties(DispatcherProperties.class)
public class DispatcherModuleConfig {

    // ==================== 内存实现（默认） ====================

    @Bean
    @ConditionalOnProperty(name = "teacher.dispatcher.storage-type", havingValue = "memory", matchIfMissing = true)
    public ApiKeyPool inMemoryApiKeyPool(DispatcherProperties properties) {
        log.info("使用内存 Key 池（轻量模式，无需 Redis）");
        return new InMemoryApiKeyPool(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "teacher.dispatcher.storage-type", havingValue = "memory", matchIfMissing = true)
    public RateLimiter inMemoryRateLimiter(DispatcherProperties properties) {
        log.info("使用内存限流器（轻量模式，无需 Redis）");
        return new InMemoryRateLimiter(properties);
    }

    // ==================== Redis 实现 ====================

    @Bean
    @ConditionalOnProperty(name = "teacher.dispatcher.storage-type", havingValue = "redis")
    public ApiKeyPool redisApiKeyPool(StringRedisTemplate redisTemplate, DispatcherProperties properties) {
        log.info("使用 Redis Key 池（分布式模式）");
        return new RedisApiKeyPool(redisTemplate, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "teacher.dispatcher.storage-type", havingValue = "redis")
    public RateLimiter redisRateLimiter(StringRedisTemplate redisTemplate, DispatcherProperties properties) {
        log.info("使用 Redis 限流器（分布式模式）");
        return new RedisRateLimiter(redisTemplate, properties);
    }
}
