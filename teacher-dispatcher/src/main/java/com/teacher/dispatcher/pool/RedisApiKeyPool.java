package com.teacher.dispatcher.pool;

import com.teacher.common.exception.KeyPoolExhaustedException;
import com.teacher.dispatcher.config.DispatcherProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

/**
 * 基于 Redis List 的 API Key 轮询池。
 * <p>
 * 适用于多实例分布式部署，所有实例共享同一个 Key 池。
 */
@Slf4j
public class RedisApiKeyPool implements ApiKeyPool {

    private final StringRedisTemplate redisTemplate;
    private final DispatcherProperties properties;

    public RedisApiKeyPool(StringRedisTemplate redisTemplate, DispatcherProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public String borrowKey() {
        String key = redisTemplate.opsForList().leftPop(
                properties.getKeyPoolName(),
                Duration.ofSeconds(properties.getKeyBorrowTimeoutSeconds())
        );
        if (key == null) {
            throw new KeyPoolExhaustedException("API Key 池已耗尽，请稍后重试或添加更多 Key");
        }
        log.debug("借出 Key: {}...", maskKey(key));
        return key;
    }

    @Override
    public void returnKey(String key) {
        redisTemplate.opsForList().rightPush(properties.getKeyPoolName(), key);
        log.debug("归还 Key: {}...", maskKey(key));
    }

    @Override
    public void markFailed(String key) {
        redisTemplate.opsForList().rightPush(properties.getFailedKeyPoolName(), key);
        log.warn("Key 标记为失败: {}...", maskKey(key));
    }

    @Override
    public void addKey(String key) {
        redisTemplate.opsForList().rightPush(properties.getKeyPoolName(), key);
        log.info("添加新 Key 到池: {}...", maskKey(key));
    }

    @Override
    public void addKeys(List<String> keys) {
        for (String k : keys) {
            addKey(k);
        }
        log.info("批量添加 {} 个 Key", keys.size());
    }

    @Override
    public long availableCount() {
        Long size = redisTemplate.opsForList().size(properties.getKeyPoolName());
        return size != null ? size : 0;
    }

    @Override
    public long failedCount() {
        Long size = redisTemplate.opsForList().size(properties.getFailedKeyPoolName());
        return size != null ? size : 0;
    }

    @Override
    public int recoverFailedKeys() {
        int recovered = 0;
        String key;
        while ((key = redisTemplate.opsForList().leftPop(properties.getFailedKeyPoolName())) != null) {
            redisTemplate.opsForList().rightPush(properties.getKeyPoolName(), key);
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
