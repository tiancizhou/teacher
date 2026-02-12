package com.teacher.ai.agent;

import com.teacher.ai.config.AiProperties;
import com.teacher.ai.prompt.PromptTemplates;
import com.teacher.common.dto.BatchResult;
import com.teacher.common.dto.CharAnalysis;
import com.teacher.common.dto.SingleCharResult;
import com.teacher.common.util.IdGenerator;
import com.teacher.dispatcher.pool.ApiKeyPool;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 书法作业批改核心服务。
 * <p>
 * 整页模式：将整张作业图片发送给 AI，一次调用完成全部分析。
 * AI 输出人类可读的结构化文字（可流式展示），后端用正则提取评分数据。
 * <p>
 * 性能优化：
 * - 图片发送前压缩到 maxImageSize 并转 JPEG，减少 Vision token
 * - Prompt 只要求输出可读文字，不再要求 JSON，输出 token 减少约 50%
 * - 流式直接推送所有内容，无需分隔符检测
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomeworkGradingService {

    private final ChatModel chatModel;
    private final ApiKeyPool keyPool;
    private final AiProperties aiProperties;
    private final PromptTemplates promptTemplates;

    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String configuredModel;

    @Value("${spring.ai.openai.base-url:unknown}")
    private String baseUrl;

    @PostConstruct
    void logConfig() {
        log.info("========== AI 批改服务启动 ==========");
        log.info("模型: {}", configuredModel);
        log.info("网关: {}", baseUrl);
        log.info("图片最大尺寸: {}px", aiProperties.getMaxImageSize());
        log.info("====================================");
    }

    // ======================== 正则模式（预编译） ========================

    /** 总览：共识别 20 个汉字（4 行 5 列）：飞,流,直,... */
    private static final Pattern P_OVERVIEW = Pattern.compile(
            "共识别\\s*(\\d+)\\s*个汉字[（(]\\s*(\\d+)\\s*行\\s*(\\d+)\\s*列\\s*[）)][：:]\\s*(.+)");

    /** 总览（兜底，不含行列信息）：共识别 20 个汉字：飞,流,直,... */
    private static final Pattern P_OVERVIEW_FALLBACK = Pattern.compile(
            "共识别\\s*(\\d+)\\s*个汉字[：:]\\s*(.+)");

    /** 整体评分：结构：73 分 | 笔画：71 分 | 综合：73 分 */
    private static final Pattern P_SCORES = Pattern.compile(
            "结构[：:]\\s*(\\d+)\\s*分\\s*[|｜]\\s*笔画[：:]\\s*(\\d+)\\s*分\\s*[|｜]\\s*综合[：:]\\s*(\\d+)\\s*分");

    /** 问题字标题：1.「疑」（第3行第2列，综合 61 分） */
    private static final Pattern P_CHAR_HEADER = Pattern.compile(
            "\\d+[.．、]\\s*「(.+?)」[（(]第(\\d+)行第(\\d+)列[，,]\\s*综合\\s*(\\d+)\\s*分[）)]");

    /** 问题字标题（兜底，不含位置信息）：1.「疑」（综合 61 分） */
    private static final Pattern P_CHAR_HEADER_FALLBACK = Pattern.compile(
            "\\d+[.．、]\\s*「(.+?)」.*综合\\s*(\\d+)\\s*分");

    /** 结构评分：结构（62 分）：描述 */
    private static final Pattern P_STRUCTURE = Pattern.compile(
            "结构（(\\d+)\\s*分）[：:]\\s*(.+)");

    /** 笔画评分：笔画（60 分）：描述 */
    private static final Pattern P_STROKE = Pattern.compile(
            "笔画（(\\d+)\\s*分）[：:]\\s*(.+)");

    /** 建议：具体建议 */
    private static final Pattern P_SUGGESTION = Pattern.compile(
            "建议[：:]\\s*(.+)");

    // ---- 单字精批正则 ----

    /** 单字识别：字：X */
    private static final Pattern P_SINGLE_CHAR = Pattern.compile(
            "字[：:]\\s*(.+)");

    /** 单字评分：结构：XX 分 | 笔画：XX 分 | 重心：XX 分 | 间架：XX 分 | 综合：XX 分 */
    private static final Pattern P_SINGLE_SCORES = Pattern.compile(
            "结构[：:]\\s*(\\d+)\\s*分\\s*[|｜]\\s*笔画[：:]\\s*(\\d+)\\s*分\\s*[|｜]\\s*重心[：:]\\s*(\\d+)\\s*分\\s*[|｜]\\s*间架[：:]\\s*(\\d+)\\s*分\\s*[|｜]\\s*综合[：:]\\s*(\\d+)\\s*分");

    // ======================== 阻塞式调用 ========================

    /**
     * 整页批改（阻塞式）。
     */
    public BatchResult gradeWholePageImage(byte[] imageBytes) {
        long startTime = System.currentTimeMillis();
        String taskId = IdGenerator.withPrefix("task");

        log.info("开始整页批改任务 {}", taskId);

        String apiKey = keyPool.borrowKey();

        try {
            byte[] compressed = compressImageForAi(imageBytes);
            UserMessage userMessage = buildVisionMessage(compressed, promptTemplates.getWholePageAnalysis());

            ChatResponse response = chatModel.call(new Prompt(List.of(userMessage)));
            String responseText = response.getResult().getOutput().getText();

            log.info("AI 响应长度: {} 字符", responseText.length());
            log.debug("AI 原始响应:\n{}", responseText);

            keyPool.returnKey(apiKey);
            apiKey = null;

            BatchResult result = parseReadableResponse(responseText, taskId);
            result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            result.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            log.info("整页批改任务 {} 完成, 耗时 {}ms, 识别 {} 字, 问题字 {} 个, 综合分 {}",
                    taskId, result.getProcessingTimeMs(),
                    result.getTotalCharacters(),
                    result.getAnalyses() != null ? result.getAnalyses().size() : 0,
                    String.format("%.1f", result.getAvgOverallScore()));

            return result;

        } catch (Exception e) {
            if (apiKey != null) {
                keyPool.markFailed(apiKey);
            }
            throw new RuntimeException("整页批改失败: " + e.getMessage(), e);
        }
    }

    // ======================== 流式调用 ========================

    /**
     * 整页批改（流式）：所有 AI 输出直接推送到前端，结束后正则提取结构化数据。
     */
    public void gradeWholePageStream(byte[] imageBytes,
                                     Consumer<String> onToken,
                                     Consumer<BatchResult> onResult,
                                     Consumer<String> onError) {
        long startTime = System.currentTimeMillis();
        String taskId = IdGenerator.withPrefix("task");

        log.info("开始流式批改任务 {}", taskId);

        String apiKey = keyPool.borrowKey();

        try {
            byte[] compressed = compressImageForAi(imageBytes);
            UserMessage userMessage = buildVisionMessage(compressed, promptTemplates.getWholePageAnalysis());

            Flux<ChatResponse> flux = chatModel.stream(new Prompt(List.of(userMessage)));

            // 流式直推：所有内容都是可读文字，直接推送前端
            StringBuilder fullContent = new StringBuilder();
            long[] firstTokenTime = {0};
            int[] chunkCount = {0};

            flux.toStream().forEach(chatResponse -> {
                if (chatResponse.getResult() != null
                        && chatResponse.getResult().getOutput() != null) {
                    String text = chatResponse.getResult().getOutput().getText();
                    if (text != null && !text.isEmpty()) {
                        if (firstTokenTime[0] == 0) {
                            firstTokenTime[0] = System.currentTimeMillis();
                            log.info("任务 {} 首 token 到达，TTFT: {}ms", taskId,
                                    firstTokenTime[0] - startTime);
                        }
                        chunkCount[0]++;
                        fullContent.append(text);
                        onToken.accept(text);
                    }
                }
            });

            log.info("任务 {} 流式统计: {} 个 chunk, TTFT {}ms, 总耗时 {}ms",
                    taskId, chunkCount[0],
                    firstTokenTime[0] > 0 ? firstTokenTime[0] - startTime : -1,
                    System.currentTimeMillis() - startTime);

            keyPool.returnKey(apiKey);
            apiKey = null;

            String fullText = fullContent.toString();
            if (fullText.isEmpty()) {
                throw new RuntimeException("AI 返回空内容");
            }

            log.debug("流式 AI 完整响应:\n{}", fullText);

            BatchResult result = parseReadableResponse(fullText, taskId);
            result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            result.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            log.info("流式批改任务 {} 完成, 耗时 {}ms, 识别 {} 字, 综合分 {}",
                    taskId, result.getProcessingTimeMs(),
                    result.getTotalCharacters(),
                    String.format("%.1f", result.getAvgOverallScore()));

            onResult.accept(result);

        } catch (Exception e) {
            if (apiKey != null) {
                keyPool.markFailed(apiKey);
            }
            log.error("流式批改失败", e);
            onError.accept("批改失败: " + e.getMessage());
        }
    }

    // ======================== 图片压缩 ========================

    /**
     * 压缩图片：缩放到 maxImageSize 并转为 JPEG quality=85。
     * 减少 Vision API 的 image token 消耗，加速模型处理。
     */
    private byte[] compressImageForAi(byte[] originalBytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(originalBytes));
            if (img == null) {
                log.warn("无法解码图片，使用原始字节");
                return originalBytes;
            }

            int maxSize = aiProperties.getMaxImageSize();
            int w = img.getWidth();
            int h = img.getHeight();

            log.info("原始图片: {}x{}, {} bytes", w, h, originalBytes.length);

            // 缩放（保持比例）
            if (w > maxSize || h > maxSize) {
                double scale = (double) maxSize / Math.max(w, h);
                int newW = (int) (w * scale);
                int newH = (int) (h * scale);

                BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = resized.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, newW, newH); // 白底（避免 PNG 透明通道变黑）
                g.drawImage(img, 0, 0, newW, newH, null);
                g.dispose();
                img = resized;

                log.info("图片缩放: {}x{} -> {}x{}", w, h, newW, newH);
            }

            // 转 JPEG quality=85
            byte[] jpegBytes = toJpeg(img, 0.85f);
            long ratio = Math.round((1.0 - (double) jpegBytes.length / originalBytes.length) * 100);
            log.info("压缩后: {} bytes (压缩率 {}%)", jpegBytes.length, ratio);
            return jpegBytes;

        } catch (IOException e) {
            log.warn("图片压缩失败，使用原始字节: {}", e.getMessage());
            return originalBytes;
        }
    }

    private byte[] toJpeg(BufferedImage img, float quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    // ======================== 构建消息 ========================

    private UserMessage buildVisionMessage(byte[] imageBytes, String promptText) {
        Media imageMedia = new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(imageBytes));
        return UserMessage.builder()
                .text(promptText)
                .media(imageMedia)
                .build();
    }

    // ======================== 正则解析可读文字 ========================

    // ======================== 单字精批 ========================

    /**
     * 单字精批（阻塞式）。
     */
    public SingleCharResult gradeSingleCharImage(byte[] imageBytes) {
        long startTime = System.currentTimeMillis();
        String taskId = IdGenerator.withPrefix("single");

        log.info("开始单字精批任务 {}", taskId);

        String apiKey = keyPool.borrowKey();

        try {
            byte[] compressed = compressImageForAi(imageBytes);
            UserMessage userMessage = buildVisionMessage(compressed, promptTemplates.getSingleCharAnalysis());

            ChatResponse response = chatModel.call(new Prompt(List.of(userMessage)));
            String responseText = response.getResult().getOutput().getText();

            log.info("单字精批 AI 响应长度: {} 字符", responseText.length());
            log.debug("单字精批 AI 原始响应:\n{}", responseText);

            keyPool.returnKey(apiKey);
            apiKey = null;

            SingleCharResult result = parseSingleCharResponse(responseText, taskId);
            result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            result.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            return result;

        } catch (Exception e) {
            if (apiKey != null) {
                keyPool.markFailed(apiKey);
            }
            throw new RuntimeException("单字精批失败: " + e.getMessage(), e);
        }
    }

    /**
     * 单字精批（流式）。
     */
    public void gradeSingleCharStream(byte[] imageBytes,
                                      Consumer<String> onToken,
                                      Consumer<SingleCharResult> onResult,
                                      Consumer<String> onError) {
        long startTime = System.currentTimeMillis();
        String taskId = IdGenerator.withPrefix("single");

        log.info("开始流式单字精批任务 {}", taskId);

        String apiKey = keyPool.borrowKey();

        try {
            byte[] compressed = compressImageForAi(imageBytes);
            UserMessage userMessage = buildVisionMessage(compressed, promptTemplates.getSingleCharAnalysis());

            Flux<ChatResponse> flux = chatModel.stream(new Prompt(List.of(userMessage)));

            StringBuilder fullContent = new StringBuilder();
            long[] firstTokenTime = {0};
            int[] chunkCount = {0};

            flux.toStream().forEach(chatResponse -> {
                if (chatResponse.getResult() != null
                        && chatResponse.getResult().getOutput() != null) {
                    String text = chatResponse.getResult().getOutput().getText();
                    if (text != null && !text.isEmpty()) {
                        if (firstTokenTime[0] == 0) {
                            firstTokenTime[0] = System.currentTimeMillis();
                            log.info("单字精批任务 {} 首 token 到达，TTFT: {}ms", taskId,
                                    firstTokenTime[0] - startTime);
                        }
                        chunkCount[0]++;
                        fullContent.append(text);
                        onToken.accept(text);
                    }
                }
            });

            log.info("单字精批任务 {} 流式统计: {} 个 chunk, TTFT {}ms, 总耗时 {}ms",
                    taskId, chunkCount[0],
                    firstTokenTime[0] > 0 ? firstTokenTime[0] - startTime : -1,
                    System.currentTimeMillis() - startTime);

            keyPool.returnKey(apiKey);
            apiKey = null;

            String fullText = fullContent.toString();
            if (fullText.isEmpty()) {
                throw new RuntimeException("AI 返回空内容");
            }

            SingleCharResult result = parseSingleCharResponse(fullText, taskId);
            result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            result.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            onResult.accept(result);

        } catch (Exception e) {
            if (apiKey != null) {
                keyPool.markFailed(apiKey);
            }
            log.error("流式单字精批失败", e);
            onError.accept("单字精批失败: " + e.getMessage());
        }
    }

    /**
     * 解析单字精批 AI 响应。
     */
    private SingleCharResult parseSingleCharResponse(String text, String taskId) {
        log.debug("开始解析单字精批响应");

        // 识别的字
        String recognizedChar = "?";
        Matcher mChar = P_SINGLE_CHAR.matcher(text);
        if (mChar.find()) {
            recognizedChar = mChar.group(1).trim();
        }

        // 评分
        int structureScore = 60, strokeScore = 60, balanceScore = 60, spacingScore = 60, overallScore = 60;
        Matcher mScores = P_SINGLE_SCORES.matcher(text);
        if (mScores.find()) {
            structureScore = Integer.parseInt(mScores.group(1));
            strokeScore = Integer.parseInt(mScores.group(2));
            balanceScore = Integer.parseInt(mScores.group(3));
            spacingScore = Integer.parseInt(mScores.group(4));
            overallScore = Integer.parseInt(mScores.group(5));
        }

        // 各维度分析
        String structureDetail = extractSection(text, "【结构分析】");
        String strokeDetail = extractSection(text, "【笔画分析】");
        String balanceDetail = extractSection(text, "【重心分析】");
        String spacingDetail = extractSection(text, "【间架分析】");
        String overallComment = extractSection(text, "【总评】");
        String suggestion = extractSection(text, "【练习建议】");

        log.info("单字精批解析完成: 字={}, 结构={}, 笔画={}, 重心={}, 间架={}, 综合={}",
                recognizedChar, structureScore, strokeScore, balanceScore, spacingScore, overallScore);

        return SingleCharResult.builder()
                .taskId(taskId)
                .recognizedChar(recognizedChar)
                .structureScore(structureScore)
                .structureDetail(structureDetail)
                .strokeScore(strokeScore)
                .strokeDetail(strokeDetail)
                .balanceScore(balanceScore)
                .balanceDetail(balanceDetail)
                .spacingScore(spacingScore)
                .spacingDetail(spacingDetail)
                .overallScore(overallScore)
                .overallComment(overallComment)
                .suggestion(suggestion)
                .build();
    }

    /**
     * 提取文本中【标题】到下一个【】或文末之间的内容。
     */
    private String extractSection(String text, String sectionTitle) {
        int start = text.indexOf(sectionTitle);
        if (start < 0) return "";
        start += sectionTitle.length();
        // 找下一个【...】标记
        int nextSection = text.indexOf("【", start);
        String content;
        if (nextSection > 0) {
            content = text.substring(start, nextSection).trim();
        } else {
            content = text.substring(start).trim();
        }
        // 限制长度
        if (content.length() > 500) {
            content = content.substring(0, 500);
        }
        return content;
    }

    // ======================== 正则解析可读文字（整页模式） ========================

    /**
     * 从 AI 输出的结构化可读文字中提取评分和评语。
     * 格式约定见 {@link PromptTemplates#WHOLE_PAGE_ANALYSIS}。
     */
    private BatchResult parseReadableResponse(String text, String taskId) {
        log.debug("开始正则解析可读文字响应");

        // 1. 提取总字数、网格布局和识别的汉字
        int totalChars = 0;
        int gridRows = 0, gridCols = 0;
        String recognizedChars = "";
        Matcher mOverview = P_OVERVIEW.matcher(text);
        if (mOverview.find()) {
            totalChars = Integer.parseInt(mOverview.group(1));
            gridRows = Integer.parseInt(mOverview.group(2));
            gridCols = Integer.parseInt(mOverview.group(3));
            recognizedChars = mOverview.group(4).trim();
        } else {
            // 兜底：没有行列信息
            Matcher mFallback = P_OVERVIEW_FALLBACK.matcher(text);
            if (mFallback.find()) {
                totalChars = Integer.parseInt(mFallback.group(1));
                recognizedChars = mFallback.group(2).trim();
            }
        }

        // 2. 提取整体评分
        int structureScore = 60, strokeScore = 60, overallScore = 60;
        Matcher mScores = P_SCORES.matcher(text);
        if (mScores.find()) {
            structureScore = Integer.parseInt(mScores.group(1));
            strokeScore = Integer.parseInt(mScores.group(2));
            overallScore = Integer.parseInt(mScores.group(3));
        }

        // 3. 提取总评
        String summaryComment = "继续加油练习！";
        int summaryIdx = text.indexOf("【总评】");
        if (summaryIdx >= 0) {
            summaryComment = text.substring(summaryIdx + "【总评】".length()).trim();
            // 截取到文末或下一个【】段落
            int nextSection = summaryComment.indexOf("【");
            if (nextSection > 0) {
                summaryComment = summaryComment.substring(0, nextSection).trim();
            }
            // 限制长度
            if (summaryComment.length() > 200) {
                summaryComment = summaryComment.substring(0, 200);
            }
        }

        // 4. 提取问题字（在【重点点评】和【总评】之间）
        List<CharAnalysis> problemChars = new ArrayList<>();
        int reviewStart = text.indexOf("【重点点评】");
        int reviewEnd = summaryIdx >= 0 ? summaryIdx : text.length();
        if (reviewStart >= 0) {
            String reviewSection = text.substring(reviewStart, reviewEnd);
            // 按问题字标题分段（优先匹配带位置信息的格式）
            Matcher mHeader = P_CHAR_HEADER.matcher(reviewSection);
            List<int[]> charPositions = new ArrayList<>(); // [matchStart, charOverallScore, row, col]
            List<String> charNames = new ArrayList<>();
            boolean hasPosition = false;

            while (mHeader.find()) {
                int row = Integer.parseInt(mHeader.group(2));
                int col = Integer.parseInt(mHeader.group(3));
                int score = Integer.parseInt(mHeader.group(4));
                charPositions.add(new int[]{mHeader.start(), score, row, col});
                charNames.add(mHeader.group(1));
                hasPosition = true;
            }

            // 兜底：如果没有匹配到带位置的格式，用旧格式
            if (!hasPosition) {
                Matcher mFallback = P_CHAR_HEADER_FALLBACK.matcher(reviewSection);
                while (mFallback.find()) {
                    charPositions.add(new int[]{mFallback.start(), Integer.parseInt(mFallback.group(2)), 0, 0});
                    charNames.add(mFallback.group(1));
                }
            }

            for (int i = 0; i < charPositions.size(); i++) {
                int blockStart = charPositions.get(i)[0];
                int blockEnd = (i + 1 < charPositions.size())
                        ? charPositions.get(i + 1)[0]
                        : reviewSection.length();
                String block = reviewSection.substring(blockStart, blockEnd);

                String charName = charNames.get(i);
                int charOverall = charPositions.get(i)[1];

                // 提取结构、笔画、建议
                int charStructScore = 60;
                String charStructComment = "暂无分析";
                Matcher mStruct = P_STRUCTURE.matcher(block);
                if (mStruct.find()) {
                    charStructScore = Integer.parseInt(mStruct.group(1));
                    charStructComment = mStruct.group(2).trim();
                }

                int charStrokeScore = 60;
                String charStrokeComment = "暂无分析";
                Matcher mStroke = P_STROKE.matcher(block);
                if (mStroke.find()) {
                    charStrokeScore = Integer.parseInt(mStroke.group(1));
                    charStrokeComment = mStroke.group(2).trim();
                }

                String suggestion = "多加练习";
                Matcher mSugg = P_SUGGESTION.matcher(block);
                if (mSugg.find()) {
                    suggestion = mSugg.group(1).trim();
                }

                int charRow = charPositions.get(i)[2];
                int charCol = charPositions.get(i)[3];

                problemChars.add(CharAnalysis.builder()
                        .charIndex(i)
                        .recognizedChar(charName)
                        .row(charRow)
                        .column(charCol)
                        .structureScore(charStructScore)
                        .structureComment(charStructComment)
                        .strokeScore(charStrokeScore)
                        .strokeComment(charStrokeComment)
                        .overallScore(charOverall)
                        .overallComment(charName + "字需要重点练习")
                        .suggestion(suggestion)
                        .build());
            }
        }

        log.info("正则解析完成: 识别 {} 字 ({}), 布局 {}行{}列, 问题字 {} 个, 综合分 {}",
                totalChars, recognizedChars, gridRows, gridCols, problemChars.size(), overallScore);

        return BatchResult.builder()
                .taskId(taskId)
                .totalCharacters(totalChars)
                .gridRows(gridRows)
                .gridCols(gridCols)
                .analyses(problemChars)
                .avgStructureScore(structureScore)
                .avgStrokeScore(strokeScore)
                .avgOverallScore(overallScore)
                .summaryComment(summaryComment)
                .build();
    }
}
