package com.teacher.ai.provider;

import java.util.function.Consumer;

/**
 * AI 视觉分析提供商接口。
 * 通过适配器模式支持不同的 AI 服务商（OpenAI、Anthropic 等）。
 */
public interface AiProvider {

    /**
     * 对单张图片发送带视觉的 Chat 请求（阻塞式，等待完整响应）。
     *
     * @param imageBase64 图片的 Base64 编码
     * @param prompt      系统/用户提示词
     * @param apiKey      API Key
     * @return AI 返回的文本响应
     */
    String analyzeImage(String imageBase64, String prompt, String apiKey);

    /**
     * 对单张图片发送带视觉的 Chat 请求（流式 SSE，逐 token 回调）。
     * <p>
     * 默认实现：降级为阻塞式调用，完成后一次性回调全部文本。
     * 支持流式的提供商（如 OpenAI）应覆盖此方法。
     *
     * @param imageBase64 图片的 Base64 编码
     * @param prompt      系统/用户提示词
     * @param apiKey      API Key
     * @param onToken     每收到一个增量 token 时的回调
     * @param onComplete  AI 输出完成时的回调，参数为拼接好的完整文本
     */
    default void analyzeImageStream(String imageBase64, String prompt, String apiKey,
                                    Consumer<String> onToken, Consumer<String> onComplete) {
        // 降级：阻塞式调用，完成后一次性推送
        String result = analyzeImage(imageBase64, prompt, apiKey);
        onToken.accept(result);
        onComplete.accept(result);
    }

    /**
     * 获取提供商名称。
     */
    String getProviderName();
}
