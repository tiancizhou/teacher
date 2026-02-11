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
 * OpenAI GPT-4o 视觉能力实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiProvider implements AiProvider {

    private final OkHttpClient aiHttpClient;
    private final AiProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    @Override
    public String analyzeImage(String imageBase64, String prompt, String apiKey) {
        AiProperties.OpenAiConfig config = properties.getOpenai();
        String url = config.getBaseUrl() + "/chat/completions";

        try {
            // 构建请求体
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", config.getModel());
            root.put("max_tokens", config.getMaxTokens());
            root.put("temperature", config.getTemperature());

            ArrayNode messages = root.putArray("messages");

            // 用户消息（包含图片和文字）
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

            String requestBody = objectMapper.writeValueAsString(root);

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

                log.debug("OpenAI 响应长度: {} 字符", result.length());
                return result;
            }

        } catch (AiServiceException e) {
            throw e;
        } catch (IOException e) {
            throw new AiServiceException("调用 OpenAI API 时发生网络错误", e);
        }
    }

    @Override
    public String getProviderName() {
        return "openai";
    }
}
