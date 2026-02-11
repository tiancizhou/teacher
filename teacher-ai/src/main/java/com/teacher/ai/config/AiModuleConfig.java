package com.teacher.ai.config;

import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * AI 模块自动配置。
 */
@Configuration
@ComponentScan(basePackages = "com.teacher.ai")
@EnableConfigurationProperties(AiProperties.class)
public class AiModuleConfig {

    @Bean
    public OkHttpClient aiHttpClient(AiProperties properties) {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .writeTimeout(Duration.ofSeconds(10))
                .build();
    }
}
