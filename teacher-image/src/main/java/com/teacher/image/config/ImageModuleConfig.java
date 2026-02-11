package com.teacher.image.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 图像模块自动配置。
 */
@Configuration
@ComponentScan(basePackages = "com.teacher.image")
@EnableConfigurationProperties(OpenCvProperties.class)
public class ImageModuleConfig {
}
