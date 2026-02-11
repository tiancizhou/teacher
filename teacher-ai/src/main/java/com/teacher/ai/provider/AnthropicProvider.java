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
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Anthropic Claude 3.5 Sonnet 视觉能力实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnthropicProvider implements AiProvider {

    private final OkHttpClient aiHttpClient;
    private final AiProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    @Override
    public String analyzeImage(String imageBase64, String prompt, String apiKey) {
        AiProperties.AnthropicConfig config = properties.getAnthropic();
        String url = config.getBaseUrl() + "/messages";

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", config.getModel());
            root.put("max_tokens", config.getMaxTokens());
            root.put("temperature", config.getTemperature());

            ArrayNode messages = root.putArray("messages");

            // 用户消息
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            ArrayNode content = userMsg.putArray("content");

            // 图片部分（Anthropic 格式）
            ObjectNode imagePart = content.addObject();
            imagePart.put("type", "image");
            ObjectNode source = imagePart.putObject("source");
            source.put("type", "base64");
            source.put("media_type", "image/png");
            source.put("data", imageBase64);

            // 文字部分
            ObjectNode textPart = content.addObject();
            textPart.put("type", "text");
            textPart.put("text", prompt);

            String requestBody = objectMapper.writeValueAsString(root);

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, JSON_MEDIA))
                    .build();

            try (Response response = aiHttpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    log.error("Anthropic API 调用失败: {} - {}", response.code(), body);
                    throw new AiServiceException("Anthropic API 返回错误: " + response.code());
                }

                JsonNode json = objectMapper.readTree(body);
                JsonNode contentArray = json.path("content");
                StringBuilder result = new StringBuilder();
                if (contentArray.isArray()) {
                    for (JsonNode block : contentArray) {
                        if ("text".equals(block.path("type").asText())) {
                            result.append(block.path("text").asText());
                        }
                    }
                }

                if (result.isEmpty()) {
                    throw new AiServiceException("Anthropic API 返回空内容");
                }

                log.debug("Anthropic 响应长度: {} 字符", result.length());
                return result.toString();
            }

        } catch (AiServiceException e) {
            throw e;
        } catch (IOException e) {
            throw new AiServiceException("调用 Anthropic API 时发生网络错误", e);
        }
    }

    @Override
    public String getProviderName() {
        return "anthropic";
    }
}
