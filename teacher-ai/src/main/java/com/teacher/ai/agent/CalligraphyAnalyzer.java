package com.teacher.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teacher.ai.config.AiProperties;
import com.teacher.ai.prompt.PromptTemplates;
import com.teacher.common.dto.CharAnalysis;
import com.teacher.common.util.ImageUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.util.List;

/**
 * 书法分析器：根据配置选择单 Agent 或多 Agent 模式来分析单个汉字。
 * <p>
 * 底层通过 Spring AI ChatModel 调用大模型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalligraphyAnalyzer {

    private final ChatModel chatModel;
    private final AiProperties properties;
    private final PromptTemplates promptTemplates;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 分析单个汉字图片。
     *
     * @param imageBase64 单字图片的 Base64 编码
     * @param charIndex   字符在整页中的序号
     * @return 单字分析结果
     */
    public CharAnalysis analyze(String imageBase64, int charIndex) {
        if (properties.isMultiAgentEnabled()) {
            return multiAgentAnalysis(imageBase64, charIndex);
        } else {
            return unifiedAnalysis(imageBase64, charIndex);
        }
    }

    /**
     * 单一 Prompt 综合分析（默认模式，节省 API 调用次数）。
     */
    private CharAnalysis unifiedAnalysis(String imageBase64, int charIndex) {
        log.debug("使用统一分析模式，字符序号: {}", charIndex);

        String response = callVisionModel(imageBase64, promptTemplates.getUnifiedAnalysis());
        return parseUnifiedResponse(response, charIndex);
    }

    /**
     * 多 Agent 分析模式：A(结构) + B(笔画) + C(综合评语)。
     */
    private CharAnalysis multiAgentAnalysis(String imageBase64, int charIndex) {
        log.debug("使用多Agent分析模式，字符序号: {}", charIndex);

        // Agent A: 结构分析
        String structureResponse = callVisionModel(imageBase64, promptTemplates.getStructureAnalysis());

        // Agent B: 笔画分析
        String strokeResponse = callVisionModel(imageBase64, promptTemplates.getStrokeAnalysis());

        // Agent C: 综合评语
        String commentPrompt = promptTemplates.getCommentGenerator(structureResponse, strokeResponse);
        String commentResponse = callVisionModel(imageBase64, commentPrompt);

        return parseMultiAgentResponse(structureResponse, strokeResponse, commentResponse, charIndex);
    }

    /**
     * 调用大模型 Vision API。
     */
    private String callVisionModel(String imageBase64, String promptText) {
        byte[] imageBytes = ImageUtils.fromBase64(imageBase64);
        Media imageMedia = new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(imageBytes));
        UserMessage userMessage = UserMessage.builder()
                .text(promptText)
                .media(imageMedia)
                .build();

        ChatResponse response = chatModel.call(new Prompt(List.of(userMessage)));
        return response.getResult().getOutput().getText();
    }

    /**
     * 解析统一分析的 JSON 响应。
     */
    private CharAnalysis parseUnifiedResponse(String response, int charIndex) {
        try {
            String cleaned = cleanJsonResponse(response);
            JsonNode json = objectMapper.readTree(cleaned);

            return CharAnalysis.builder()
                    .charIndex(charIndex)
                    .recognizedChar(getTextOrNull(json, "recognizedChar"))
                    .structureScore(json.path("structureScore").asInt(60))
                    .structureComment(json.path("structureComment").asText("暂无评价"))
                    .strokeScore(json.path("strokeScore").asInt(60))
                    .strokeComment(json.path("strokeComment").asText("暂无评价"))
                    .overallScore(json.path("overallScore").asInt(60))
                    .overallComment(json.path("overallComment").asText("继续加油！"))
                    .suggestion(json.path("suggestion").asText("多加练习"))
                    .build();

        } catch (Exception e) {
            log.warn("解析AI响应失败，返回默认结果。原始响应: {}", response, e);
            return buildFallbackResult(charIndex);
        }
    }

    /**
     * 解析多 Agent 的各个 JSON 响应并整合。
     */
    private CharAnalysis parseMultiAgentResponse(String structureResp, String strokeResp,
                                                  String commentResp, int charIndex) {
        try {
            JsonNode structureJson = objectMapper.readTree(cleanJsonResponse(structureResp));
            JsonNode strokeJson = objectMapper.readTree(cleanJsonResponse(strokeResp));
            JsonNode commentJson = objectMapper.readTree(cleanJsonResponse(commentResp));

            return CharAnalysis.builder()
                    .charIndex(charIndex)
                    .structureScore(structureJson.path("structureScore").asInt(60))
                    .structureComment(structureJson.path("structureComment").asText("暂无评价"))
                    .strokeScore(strokeJson.path("strokeScore").asInt(60))
                    .strokeComment(strokeJson.path("strokeComment").asText("暂无评价"))
                    .overallScore(commentJson.path("overallScore").asInt(60))
                    .overallComment(commentJson.path("overallComment").asText("继续加油！"))
                    .suggestion(commentJson.path("suggestion").asText("多加练习"))
                    .build();

        } catch (Exception e) {
            log.warn("解析多Agent响应失败，返回默认结果", e);
            return buildFallbackResult(charIndex);
        }
    }

    private String cleanJsonResponse(String response) {
        if (response == null) return "{}";
        String cleaned = response.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
        }
        return cleaned.trim();
    }

    private String getTextOrNull(JsonNode json, String field) {
        JsonNode node = json.path(field);
        if (node.isMissingNode() || node.isNull() || "null".equals(node.asText())) {
            return null;
        }
        return node.asText();
    }

    private CharAnalysis buildFallbackResult(int charIndex) {
        return CharAnalysis.builder()
                .charIndex(charIndex)
                .structureScore(60)
                .structureComment("AI分析暂时无法解读，请重试")
                .strokeScore(60)
                .strokeComment("AI分析暂时无法解读，请重试")
                .overallScore(60)
                .overallComment("小朋友写得不错，继续加油练习！")
                .suggestion("建议多对照字帖练习，注意笔画的起收。")
                .build();
    }
}
