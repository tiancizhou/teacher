package com.teacher.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * AI 模块自动配置。
 * <p>
 * 模型调用由 Spring AI 自动配置的 ChatModel Bean 提供，
 * 支持 OpenAI 兼容协议（通过 CLIProxyAPI 网关代理所有大模型）。
 */
@Configuration
@ComponentScan(basePackages = "com.teacher.ai")
@EnableConfigurationProperties(AiProperties.class)
public class AiModuleConfig {
    // Spring AI 自动配置 ChatModel，无需手动创建 OkHttpClient
}
