package com.teacher.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 模块配置项。
 */
@Data
@ConfigurationProperties(prefix = "teacher.ai")
public class AiProperties {

    /** 当前使用的 AI 提供商: openai / anthropic */
    private String provider = "openai";

    /** OpenAI 配置 */
    private OpenAiConfig openai = new OpenAiConfig();

    /** Anthropic 配置 */
    private AnthropicConfig anthropic = new AnthropicConfig();

    /** 是否启用多 Agent 模式（关闭时使用单一综合 Prompt） */
    private boolean multiAgentEnabled = false;

    /** 单次 API 调用的超时时间（秒） */
    private int requestTimeoutSeconds = 30;

    /** 图片最大尺寸（发送前缩放，减少 Token 消耗） */
    private int maxImageSize = 512;

    @Data
    public static class OpenAiConfig {
        private String baseUrl = "https://api.openai.com/v1";
        private String model = "gpt-4o";
        private double temperature = 0.3;
        private int maxTokens = 1024;
    }

    @Data
    public static class AnthropicConfig {
        private String baseUrl = "https://api.anthropic.com/v1";
        private String model = "claude-3-5-sonnet-20241022";
        private double temperature = 0.3;
        private int maxTokens = 1024;
    }
}
