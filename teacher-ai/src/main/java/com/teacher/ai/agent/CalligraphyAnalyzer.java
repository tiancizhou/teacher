package com.teacher.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teacher.ai.config.AiProperties;
import com.teacher.ai.prompt.PromptTemplates;
import com.teacher.ai.provider.AiProvider;
import com.teacher.ai.provider.AiProviderFactory;
import com.teacher.common.dto.CharAnalysis;
import com.teacher.common.exception.AiServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 书法分析器：根据配置选择单 Agent 或多 Agent 模式来分析单个汉字。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalligraphyAnalyzer {

    private final AiProviderFactory providerFactory;
    private final AiProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 分析单个汉字图片。
     *
     * @param imageBase64 单字图片的 Base64 编码
     * @param charIndex   字符在整页中的序号
     * @param apiKey      API Key
     * @return 单字分析结果
     */
    public CharAnalysis analyze(String imageBase64, int charIndex, String apiKey) {
        AiProvider provider = providerFactory.getProvider();

        if (properties.isMultiAgentEnabled()) {
            return multiAgentAnalysis(provider, imageBase64, charIndex, apiKey);
        } else {
            return unifiedAnalysis(provider, imageBase64, charIndex, apiKey);
        }
    }

    /**
     * 单一 Prompt 综合分析（默认模式，节省 API 调用次数）。
     */
    private CharAnalysis unifiedAnalysis(AiProvider provider, String imageBase64,
                                         int charIndex, String apiKey) {
        log.debug("使用统一分析模式，字符序号: {}", charIndex);

        String response = provider.analyzeImage(imageBase64, PromptTemplates.UNIFIED_ANALYSIS, apiKey);
        return parseUnifiedResponse(response, charIndex);
    }

    /**
     * 多 Agent 分析模式：A(结构) + B(笔画) + C(综合评语)。
     */
    private CharAnalysis multiAgentAnalysis(AiProvider provider, String imageBase64,
                                            int charIndex, String apiKey) {
        log.debug("使用多Agent分析模式，字符序号: {}", charIndex);

        // Agent A: 结构分析
        String structureResponse = provider.analyzeImage(
                imageBase64, PromptTemplates.STRUCTURE_ANALYSIS, apiKey);

        // Agent B: 笔画分析
        String strokeResponse = provider.analyzeImage(
                imageBase64, PromptTemplates.STROKE_ANALYSIS, apiKey);

        // Agent C: 综合评语
        String commentPrompt = String.format(
                PromptTemplates.COMMENT_GENERATOR, structureResponse, strokeResponse);
        String commentResponse = provider.analyzeImage(
                imageBase64, commentPrompt, apiKey);

        return parseMultiAgentResponse(structureResponse, strokeResponse, commentResponse, charIndex);
    }

    /**
     * 解析统一分析的 JSON 响应。
     */
    private CharAnalysis parseUnifiedResponse(String response, int charIndex) {
        try {
            // 清理可能的 markdown 代码块标记
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
            return buildFallbackResult(charIndex, response);
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
            return buildFallbackResult(charIndex, structureResp);
        }
    }

    /**
     * 清理 JSON 响应中可能的 markdown 标记。
     */
    private String cleanJsonResponse(String response) {
        if (response == null) return "{}";
        String cleaned = response.trim();
        // 去除 ```json ... ``` 包裹
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

    /**
     * AI 解析失败时的兜底结果。
     */
    private CharAnalysis buildFallbackResult(int charIndex, String rawResponse) {
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
