package com.teacher.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teacher.ai.config.AiProperties;
import com.teacher.ai.prompt.PromptTemplates;
import com.teacher.ai.provider.AiProvider;
import com.teacher.ai.provider.AiProviderFactory;
import com.teacher.common.dto.BatchResult;
import com.teacher.common.dto.CharAnalysis;
import com.teacher.common.util.IdGenerator;
import com.teacher.common.util.ImageUtils;
import com.teacher.dispatcher.pool.ApiKeyPool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 书法作业批改核心服务。
 * <p>
 * 整页模式（推荐）：将整张作业图片发送给 AI，一次调用完成全部分析。
 * AI 会自动识别所有字，挑出写得不好的 3~5 个字重点点评。
 * <p>
 * 速度对比：
 * - 旧方案：30 个字 × 每字一次调用 = 30 次 API 调用（单 Key 需 25 分钟）
 * - 新方案：1 次 API 调用（约 30~60 秒）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomeworkGradingService {

    private final AiProviderFactory providerFactory;
    private final AiProperties aiProperties;
    private final ApiKeyPool keyPool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 整页批改：将完整作业图片直接发给 AI，一次调用搞定。
     *
     * @param imageBytes 原始作业图片字节数组
     * @return 批改结果
     */
    public BatchResult gradeWholePageImage(byte[] imageBytes) {
        long startTime = System.currentTimeMillis();
        String taskId = IdGenerator.withPrefix("task");

        log.info("开始整页批改任务 {}", taskId);

        // 1. 图片转 Base64
        String imageBase64 = ImageUtils.toBase64(imageBytes);

        // 2. 借一个 Key
        String apiKey = keyPool.borrowKey();

        try {
            // 3. 发送整图 + Prompt 给 AI
            AiProvider provider = providerFactory.getProvider();
            String response = provider.analyzeImage(
                    imageBase64, PromptTemplates.WHOLE_PAGE_ANALYSIS, apiKey);

            log.info("AI 响应长度: {} 字符", response.length());
            log.debug("AI 原始响应:\n{}", response);

            // 4. 归还 Key
            keyPool.returnKey(apiKey);
            apiKey = null; // 标记已归还

            // 5. 解析响应
            BatchResult result = parseWholePageResponse(response, taskId);
            result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            result.setCreatedAt(LocalDateTime.now());

            log.info("整页批改任务 {} 完成, 耗时 {}ms, 识别 {} 字, 问题字 {} 个, 综合分 {}",
                    taskId, result.getProcessingTimeMs(),
                    result.getTotalCharacters(),
                    result.getAnalyses() != null ? result.getAnalyses().size() : 0,
                    String.format("%.1f", result.getAvgOverallScore()));

            return result;

        } catch (Exception e) {
            // Key 异常时标记失败
            if (apiKey != null) {
                keyPool.markFailed(apiKey);
            }
            throw new RuntimeException("整页批改失败: " + e.getMessage(), e);
        }
    }

    /**
     * 整页批改（流式回调）：边收 AI 响应边通过回调推送给调用方。
     * <p>
     * 不依赖 Spring Web 的 SseEmitter，纯回调方式，调用方（Controller）
     * 自行决定如何将 token 推送到前端。
     *
     * @param imageBytes  原始作业图片字节数组
     * @param onToken     每收到 AI 增量 token 时的回调
     * @param onResult    AI 完成后的结构化结果回调
     * @param onError     出错时的回调
     */
    public void gradeWholePageStream(byte[] imageBytes,
                                     Consumer<String> onToken,
                                     Consumer<BatchResult> onResult,
                                     Consumer<String> onError) {
        long startTime = System.currentTimeMillis();
        String taskId = IdGenerator.withPrefix("task");

        log.info("开始流式批改任务 {}", taskId);

        String imageBase64 = ImageUtils.toBase64(imageBytes);
        String apiKey = keyPool.borrowKey();

        try {
            AiProvider provider = providerFactory.getProvider();

            // AI 完成回调：解析完整文本 → BatchResult
            Consumer<String> onComplete = (fullText) -> {
                log.info("流式 AI 响应完成，长度: {} 字符", fullText.length());
                log.debug("流式 AI 完整响应:\n{}", fullText);

                // 归还 Key
                keyPool.returnKey(apiKey);

                // 解析完整响应
                BatchResult result = parseWholePageResponse(fullText, taskId);
                result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
                result.setCreatedAt(LocalDateTime.now());

                log.info("流式批改任务 {} 完成, 耗时 {}ms, 识别 {} 字, 综合分 {}",
                        taskId, result.getProcessingTimeMs(),
                        result.getTotalCharacters(),
                        String.format("%.1f", result.getAvgOverallScore()));

                onResult.accept(result);
            };

            provider.analyzeImageStream(imageBase64, PromptTemplates.WHOLE_PAGE_ANALYSIS, apiKey,
                    onToken, onComplete);

        } catch (Exception e) {
            log.error("流式批改失败", e);
            keyPool.markFailed(apiKey);
            onError.accept("批改失败: " + e.getMessage());
        }
    }

    /**
     * 解析整页批改的 AI 响应 JSON。
     * 支持处理被截断的 JSON（先尝试完整解析，失败后尝试修复后解析，最后用正则兜底提取）。
     */
    private BatchResult parseWholePageResponse(String response, String taskId) {
        String cleaned = cleanJsonResponse(response);
        log.debug("清理后 JSON ({}字符):\n{}", cleaned.length(), cleaned);

        // 第一步：尝试直接解析完整 JSON
        JsonNode json = tryParseJson(cleaned);

        // 第二步：如果失败，尝试修复截断的 JSON 再解析
        if (json == null) {
            log.info("JSON 不完整，尝试修复截断的响应...");
            String repaired = repairTruncatedJson(cleaned);
            log.debug("修复后 JSON ({}字符):\n{}", repaired.length(), repaired);
            json = tryParseJson(repaired);
        }

        // 第三步：如果修复后仍然失败，用正则提取关键字段
        if (json == null) {
            log.info("JSON 修复后仍无法解析，使用正则兜底提取");
            return extractWithRegex(cleaned, taskId);
        }

        // 正常解析路径
        return parseFromJsonNode(json, taskId);
    }

    /**
     * 尝试解析 JSON，失败返回 null（不抛异常）。
     */
    private JsonNode tryParseJson(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 修复被截断的 JSON：关闭未闭合的字符串、数组、对象。
     * 使用栈追踪括号的开闭顺序，确保补全时按正确顺序关闭。
     */
    private String repairTruncatedJson(String json) {
        if (json == null || json.isEmpty()) return "{}";

        StringBuilder sb = new StringBuilder(json);

        // 1. 如果结尾在字符串中间（没有闭合引号），加上引号
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') { inString = !inString; }
        }
        if (inString) {
            sb.append('"');
        }

        // 2. 清理尾部不完整的属性模式
        String work = sb.toString();
        // 移除悬空的 "key" 或 "key": （没有值）
        work = work.replaceAll(",\\s*\"[^\"]*\"\\s*:?\\s*$", "");
        // 清理残留的尾部逗号、冒号、空白
        work = work.replaceAll("[,:\\s]+$", "");
        sb = new StringBuilder(work);

        // 3. 用栈追踪未闭合的括号（保留开闭顺序）
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();
        inString = false;
        escaped = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') stack.push('}');
            else if (c == '[') stack.push(']');
            else if (c == '}' || c == ']') {
                if (!stack.isEmpty()) stack.pop();
            }
        }

        // 4. 按栈顺序（后进先出）补全：先关内层，再关外层
        while (!stack.isEmpty()) {
            sb.append(stack.pop());
        }

        return sb.toString();
    }

    /**
     * 从解析好的 JsonNode 中提取数据。
     */
    private BatchResult parseFromJsonNode(JsonNode json, String taskId) {
        int totalChars = json.path("totalCharCount").asInt(0);
        String recognizedChars = json.path("recognizedChars").asText("");
        int overallStructure = json.path("overallStructureScore").asInt(60);
        int overallStroke = json.path("overallStrokeScore").asInt(60);
        int overallScore = json.path("overallScore").asInt(60);
        String summaryComment = json.path("summaryComment").asText("继续加油练习！");

        List<CharAnalysis> problemChars = new ArrayList<>();
        JsonNode problemArray = json.path("problemChars");
        if (problemArray.isArray()) {
            int index = 0;
            for (JsonNode charNode : problemArray) {
                // 跳过不完整的条目（至少要有 char 字段）
                String charName = charNode.path("char").asText(null);
                if (charName == null || charName.isEmpty()) continue;

                CharAnalysis analysis = CharAnalysis.builder()
                        .charIndex(index++)
                        .recognizedChar(charName)
                        .structureScore(charNode.path("structureScore").asInt(60))
                        .structureComment(charNode.path("structureComment").asText("暂无详细分析"))
                        .strokeScore(charNode.path("strokeScore").asInt(60))
                        .strokeComment(charNode.path("strokeComment").asText("暂无详细分析"))
                        .overallScore(charNode.path("overallScore").asInt(60))
                        .overallComment(charNode.path("overallComment").asText("继续加油"))
                        .suggestion(charNode.path("suggestion").asText("多加练习"))
                        .build();
                problemChars.add(analysis);
            }
        }

        log.info("解析成功: 识别 {} 字 ({}), 问题字 {} 个",
                totalChars, recognizedChars, problemChars.size());

        return BatchResult.builder()
                .taskId(taskId)
                .totalCharacters(totalChars)
                .analyses(problemChars)
                .avgStructureScore(overallStructure)
                .avgStrokeScore(overallStroke)
                .avgOverallScore(overallScore)
                .summaryComment(summaryComment)
                .build();
    }

    /**
     * 正则兜底提取：当 JSON 完全无法修复时，用正则从原始文本中提取关键数据。
     */
    private BatchResult extractWithRegex(String text, String taskId) {
        int totalChars = extractInt(text, "totalCharCount", 0);
        int structure = extractInt(text, "overallStructureScore", 60);
        int stroke = extractInt(text, "overallStrokeScore", 60);
        int overall = extractInt(text, "overallScore", 60);
        String summary = extractString(text, "summaryComment", "AI 分析结果不完整，请重新提交。");
        String recognizedChars = extractString(text, "recognizedChars", "");

        log.info("正则兜底提取: 总字数={}, 综合分={}, 识别={}", totalChars, overall, recognizedChars);

        // 尝试提取 problemChars 中的字名
        List<CharAnalysis> problemChars = new ArrayList<>();
        java.util.regex.Pattern charPattern = java.util.regex.Pattern.compile("\"char\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher matcher = charPattern.matcher(text);
        int idx = 0;
        while (matcher.find()) {
            problemChars.add(CharAnalysis.builder()
                    .charIndex(idx++)
                    .recognizedChar(matcher.group(1))
                    .structureScore(structure)
                    .structureComment("AI 输出被截断，暂无详细分析")
                    .strokeScore(stroke)
                    .strokeComment("AI 输出被截断，暂无详细分析")
                    .overallScore(overall)
                    .overallComment("此字需要重点练习")
                    .suggestion("建议对照字帖仔细观察后重新书写")
                    .build());
        }

        return BatchResult.builder()
                .taskId(taskId)
                .totalCharacters(totalChars)
                .analyses(problemChars)
                .avgStructureScore(structure)
                .avgStrokeScore(stroke)
                .avgOverallScore(overall)
                .summaryComment(summary)
                .build();
    }

    private int extractInt(String text, String key, int defaultValue) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)");
        java.util.regex.Matcher m = p.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : defaultValue;
    }

    private String extractString(String text, String key, String defaultValue) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : defaultValue;
    }

    /**
     * 清理 JSON 响应：去除 markdown 代码块包裹，提取 JSON 部分。
     * 支持：```json\n{...}\n```、截断的 ```json\n{...（无结尾）、以及纯 JSON。
     */
    private String cleanJsonResponse(String response) {
        if (response == null) return "{}";
        String cleaned = response.trim();

        // 去除开头的 ```json 或 ``` 标记
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            } else {
                cleaned = cleaned.substring(3);
            }
        }

        // 去除结尾的 ``` 标记（如果有）
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }

        cleaned = cleaned.trim();

        // 如果经过上面处理后仍然不以 { 开头，尝试找到第一个 {
        if (!cleaned.startsWith("{") && !cleaned.startsWith("[")) {
            int braceIdx = cleaned.indexOf('{');
            if (braceIdx >= 0) {
                cleaned = cleaned.substring(braceIdx);
            }
        }

        return cleaned.isEmpty() ? "{}" : cleaned;
    }

    /**
     * AI 解析失败时的兜底结果。
     */
    private BatchResult buildFallbackResult(String taskId, String rawResponse) {
        CharAnalysis fallback = CharAnalysis.builder()
                .charIndex(0)
                .structureScore(60)
                .structureComment("AI 分析暂时无法解读")
                .strokeScore(60)
                .strokeComment("AI 分析暂时无法解读")
                .overallScore(60)
                .overallComment("小朋友写得不错，继续加油练习！")
                .suggestion("建议多对照字帖练习。")
                .build();

        return BatchResult.builder()
                .taskId(taskId)
                .totalCharacters(0)
                .analyses(List.of(fallback))
                .avgStructureScore(60)
                .avgStrokeScore(60)
                .avgOverallScore(60)
                .summaryComment("AI 分析暂时出现问题，请重新提交试试。")
                .build();
    }
}
