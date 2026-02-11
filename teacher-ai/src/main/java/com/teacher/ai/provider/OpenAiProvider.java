package com.teacher.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.teacher.ai.config.AiProperties;
import com.teacher.common.exception.AiServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.BufferedSource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * OpenAI 兼容 API 实现，支持阻塞式和 SSE 流式两种调用方式。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiProvider implements AiProvider {

    private final OkHttpClient aiHttpClient;
    private final AiProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    // ======================== 阻塞式调用 ========================

    @Override
    public String analyzeImage(String imageBase64, String prompt, String apiKey) {
        AiProperties.OpenAiConfig config = properties.getOpenai();
        String url = config.getBaseUrl() + "/chat/completions";

        try {
            String requestBody = buildRequestBody(config, imageBase64, prompt, false);

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, JSON_MEDIA))
                    .build();

            try (Response response = aiHttpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    log.error("OpenAI API 调用失败: {} - {}", response.code(), body);
                    throw new AiServiceException("OpenAI API 返回错误: " + response.code());
                }

                JsonNode json = objectMapper.readTree(body);
                String result = json.path("choices").path(0).path("message").path("content").asText();

                if (result.isEmpty()) {
                    throw new AiServiceException("OpenAI API 返回空内容");
                }

                log.info("OpenAI 响应长度: {} 字符", result.length());
                return result;
            }

        } catch (AiServiceException e) {
            throw e;
        } catch (IOException e) {
            throw new AiServiceException("调用 OpenAI API 时发生网络错误", e);
        }
    }

    // ======================== SSE 流式调用 ========================

    @Override
    public void analyzeImageStream(String imageBase64, String prompt, String apiKey,
                                   Consumer<String> onToken, Consumer<String> onComplete) {
        AiProperties.OpenAiConfig config = properties.getOpenai();
        String url = config.getBaseUrl() + "/chat/completions";

        try {
            String requestBody = buildRequestBody(config, imageBase64, prompt, true);

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                    .post(RequestBody.create(requestBody, JSON_MEDIA))
                    .build();

            try (Response response = aiHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    log.error("OpenAI SSE 调用失败: {} - {}", response.code(), errorBody);
                    throw new AiServiceException("OpenAI API 返回错误: " + response.code());
                }

                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    throw new AiServiceException("OpenAI SSE 响应体为空");
                }

                StringBuilder fullContent = new StringBuilder();
                BufferedSource source = responseBody.source();

                while (!source.exhausted()) {
                    String line = source.readUtf8Line();
                    if (line == null) continue;

                    // SSE 格式：每行以 "data: " 开头
                    if (!line.startsWith("data: ")) continue;

                    String data = line.substring(6).trim();

                    // 流结束标记
                    if ("[DONE]".equals(data)) {
                        log.info("SSE 流结束，总长度: {} 字符", fullContent.length());
                        break;
                    }

                    // 解析 delta.content
                    try {
                        JsonNode chunk = objectMapper.readTree(data);
                        String deltaContent = chunk.path("choices").path(0)
                                .path("delta").path("content").asText("");

                        if (!deltaContent.isEmpty()) {
                            fullContent.append(deltaContent);
                            onToken.accept(deltaContent);
                        }
                    } catch (Exception e) {
                        // 某些 chunk 可能不含 content（如 role chunk），忽略
                        log.trace("跳过无内容的 SSE chunk: {}", data);
                    }
                }

                // 流结束，回调完整文本
                String fullText = fullContent.toString();
                if (fullText.isEmpty()) {
                    throw new AiServiceException("OpenAI SSE 返回空内容");
                }
                onComplete.accept(fullText);
            }

        } catch (AiServiceException e) {
            throw e;
        } catch (IOException e) {
            throw new AiServiceException("SSE 流式调用时发生网络错误", e);
        }
    }

    // ======================== 公共方法 ========================

    /**
     * 构建 OpenAI Chat Completions 请求体。
     */
    private String buildRequestBody(AiProperties.OpenAiConfig config,
                                    String imageBase64, String prompt, boolean stream) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", config.getModel());
            root.put("max_tokens", config.getMaxTokens());
            root.put("temperature", config.getTemperature());
            if (stream) {
                root.put("stream", true);
            }

            ArrayNode messages = root.putArray("messages");

            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            ArrayNode content = userMsg.putArray("content");

            // 文字部分
            ObjectNode textPart = content.addObject();
            textPart.put("type", "text");
            textPart.put("text", prompt);

            // 图片部分
            ObjectNode imagePart = content.addObject();
            imagePart.put("type", "image_url");
            ObjectNode imageUrl = imagePart.putObject("image_url");
            imageUrl.put("url", "data:image/png;base64," + imageBase64);
            imageUrl.put("detail", "high");

            return objectMapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new AiServiceException("构建请求体失败", e);
        }
    }

    @Override
    public String getProviderName() {
        return "openai";
    }
}
