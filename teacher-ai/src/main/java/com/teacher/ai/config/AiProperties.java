package com.teacher.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 模块业务配置项。
 * <p>
 * 模型相关配置（base-url、api-key、model、temperature 等）
 * 已迁移至 Spring AI 自动配置：spring.ai.openai.*
 */
@Data
@ConfigurationProperties(prefix = "teacher.ai")
public class AiProperties {

    /** 是否启用多 Agent 模式（关闭时使用单一综合 Prompt） */
    private boolean multiAgentEnabled = false;

    /** 图片最大尺寸（发送前缩放，减少 Token 消耗） */
    private int maxImageSize = 512;
}
