package com.teacher.config;

import com.teacher.dispatcher.pool.ApiKeyPool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 应用启动时，从配置文件加载 API Keys 到 Redis 池中。
 * <p>
 * 配置方式（在 application.yml 中）：
 * teacher.api-keys=sk-key1,sk-key2,sk-key3
 * <p>
 * 或通过环境变量：TEACHER_API_KEYS=sk-key1,sk-key2,sk-key3
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyInitializer implements CommandLineRunner {

    private final ApiKeyPool keyPool;

    @Value("${teacher.api-keys:}")
    private String apiKeysConfig;

    @Override
    public void run(String... args) {
        if (apiKeysConfig == null || apiKeysConfig.isBlank()) {
            log.warn("==============================================");
            log.warn("  未配置 API Keys！");
            log.warn("  请在 application.yml 中设置:");
            log.warn("  teacher.api-keys: sk-your-key1,sk-your-key2");
            log.warn("  或通过环境变量: TEACHER_API_KEYS");
            log.warn("==============================================");
            return;
        }

        List<String> keys = Arrays.stream(apiKeysConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (keys.isEmpty()) {
            log.warn("API Keys 配置为空");
            return;
        }

        // 仅在池为空时添加（避免重启时重复添加）
        long existing = keyPool.availableCount();
        if (existing == 0) {
            keyPool.addKeys(keys);
            log.info("已加载 {} 个 API Keys 到调度池", keys.size());
        } else {
            log.info("Key 池中已有 {} 个 Key，跳过初始化加载", existing);
        }
    }
}
