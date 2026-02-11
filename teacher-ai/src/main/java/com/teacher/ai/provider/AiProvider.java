package com.teacher.ai.provider;

/**
 * AI 视觉分析提供商接口。
 * 通过适配器模式支持不同的 AI 服务商（OpenAI、Anthropic 等）。
 */
public interface AiProvider {

    /**
     * 对单张图片发送带视觉的 Chat 请求。
     *
     * @param imageBase64 图片的 Base64 编码
     * @param prompt      系统/用户提示词
     * @param apiKey      API Key
     * @return AI 返回的文本响应
     */
    String analyzeImage(String imageBase64, String prompt, String apiKey);

    /**
     * 获取提供商名称。
     */
    String getProviderName();
}
