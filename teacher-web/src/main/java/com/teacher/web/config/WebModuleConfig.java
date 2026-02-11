package com.teacher.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.relational.core.dialect.Dialect;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 模块配置。
 */
@Configuration
@ComponentScan(basePackages = "com.teacher.web")
public class WebModuleConfig implements WebMvcConfigurer {

    /**
     * 注册 SQLite 方言 —— Spring Data JDBC 内置不认识 SQLite，需手动提供。
     */
    @Bean
    public Dialect jdbcDialect() {
        return SqliteDialect.INSTANCE;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/static/**")
                .addResourceLocations("classpath:/static/");
    }
}
