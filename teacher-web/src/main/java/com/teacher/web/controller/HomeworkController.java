package com.teacher.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teacher.ai.agent.HomeworkGradingService;
import com.teacher.common.dto.*;
import com.teacher.common.util.IdGenerator;
import com.teacher.web.entity.HomeworkEntity;
import com.teacher.web.service.HomeworkDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 书法作业批改 REST API 控制器。
 * <p>
 * 支持三种模式：
 * 1. 整页批改：AI 分析全页，返回文字点评 + 行列位置
 * 2. 单字精批：上传单个字的图片，多维度深度分析
 */
@Slf4j
@RestController
@RequestMapping("/api/homework")
@RequiredArgsConstructor
public class HomeworkController {

    private final HomeworkGradingService gradingService;
    private final HomeworkDataService dataService;
    private final ObjectMapper objectMapper;

    // ======================== 整页批改（阻塞式） ========================

    /**
     * 上传书法作业图片进行 AI 批改（整页模式）。
     *
     * @param file       书法作业图片文件
     * @param userId     用户ID（可选，匿名不传）
     * @param copyBookId 临摹字帖ID（可选，传了可启用缓存命中）
     */
    @PostMapping("/analyze")
    public ApiResponse<BatchResult> analyzeHomework(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "copyBookId", required = false) String copyBookId) {

        log.info("收到批改请求, 文件名: {}, 大小: {} bytes, userId: {}",
                file.getOriginalFilename(), file.getSize(), userId);

        // 防刷检查：同一用户 5 分钟内最多 20 次
        if (userId != null && dataService.countRecentCalls(userId, 5) >= 20) {
            return ApiResponse.error("RATE_LIMITED", "操作过于频繁，请 5 分钟后再试");
        }

        long startTime = System.currentTimeMillis();
        try {
            byte[] imageBytes = file.getBytes();

            // 整页模式：直接把完整图片发给 AI，一次调用搞定
            BatchResult result = gradingService.gradeWholePageImage(imageBytes);
            result.setImageId(IdGenerator.withPrefix("img"));

            // 持久化到数据库
            dataService.saveResult(result, file.getOriginalFilename(), userId, copyBookId);

            // 记录调用日志
            long elapsed = System.currentTimeMillis() - startTime;
            dataService.logKeyUsage(
                    result.getTaskId(), userId, "openai", "whole-page",
                    result.getTotalCharacters(), elapsed, true, null, 0);

            return ApiResponse.ok(result, "批改完成");

        } catch (Exception e) {
            log.error("批改失败", e);
            long elapsed = System.currentTimeMillis() - startTime;
            dataService.logKeyUsage(
                    null, userId, "openai", "whole-page",
                    0, elapsed, false, e.getMessage(), 0);
            return ApiResponse.error("ANALYZE_FAILED", "批改失败: " + e.getMessage());
        }
    }

    // ======================== 整页批改（SSE 流式） ========================

    /**
     * 上传书法作业图片进行 AI 批改（SSE 流式模式）。
     * <p>
     * 前端通过 fetch + ReadableStream 接收 SSE 事件：
     * - event: start   → 任务开始
     * - event: token   → AI 增量输出文本
     * - event: result  → 完整结构化批改结果 (BatchResult JSON)
     * - event: error   → 出错信息
     */
    @PostMapping(value = "/analyze-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeStream(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "copyBookId", required = false) String copyBookId) {

        log.info("收到流式批改请求, 文件名: {}, 大小: {} bytes, userId: {}",
                file.getOriginalFilename(), file.getSize(), userId);

        // 3 分钟超时，覆盖最慢场景
        SseEmitter emitter = new SseEmitter(180_000L);

        // 防刷检查
        if (userId != null && dataService.countRecentCalls(userId, 5) >= 20) {
            try {
                emitter.send(SseEmitter.event().name("error").data("操作过于频繁，请 5 分钟后再试"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        // 在虚拟线程中异步执行流式批改
        Thread.startVirtualThread(() -> {
            try {
                byte[] imageBytes = file.getBytes();

                // 发送 start 事件
                sendSse(emitter, "start", "{}");

                // ---- 思考中心跳：在 AI 思考期间每 3 秒发送一条提示 ----
                AtomicBoolean firstTokenReceived = new AtomicBoolean(false);
                String[] thinkingMessages = {
                        "正在上传图片到 AI 模型...",
                        "AI 正在观察作业整体布局...",
                        "正在分析字的间架结构...",
                        "正在评估笔画力度与走势...",
                        "正在识别每个字的特征...",
                        "正在对比标准字帖...",
                        "正在撰写专业点评...",
                        "AI 思考中，大型模型需要更多时间...",
                        "即将完成，请再稍等片刻..."
                };

                Thread heartbeat = Thread.startVirtualThread(() -> {
                    int idx = 0;
                    while (!firstTokenReceived.get()) {
                        try {
                            Thread.sleep(3000);
                            if (!firstTokenReceived.get()) {
                                sendSse(emitter, "thinking",
                                        thinkingMessages[Math.min(idx, thinkingMessages.length - 1)]);
                                idx++;
                            }
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                });

                // ---- 流式批改 ----
                final String origFileName = file.getOriginalFilename();
                final Long uid = userId;
                final String cbId = copyBookId;

                gradingService.gradeWholePageStream(imageBytes,
                        // onToken: 第一个 token 到达时停止心跳
                        (token) -> {
                            firstTokenReceived.set(true);
                            sendSse(emitter, "token", token);
                        },
                        // onResult: 附加字符截图 → 持久化 → 发送
                        (result) -> {
                            firstTokenReceived.set(true);
                            try {
                                // 持久化到数据库
                                try {
                                    result.setImageId(IdGenerator.withPrefix("img"));
                                    dataService.saveResult(result, origFileName, uid, cbId);
                                    dataService.logKeyUsage(
                                            result.getTaskId(), uid, "openai", "whole-page",
                                            result.getTotalCharacters(), result.getProcessingTimeMs(),
                                            true, null, 0);
                                } catch (Exception dbEx) {
                                    log.warn("流式批改结果持久化失败（不影响返回）: {}", dbEx.getMessage());
                                }

                                String json = objectMapper.writeValueAsString(result);
                                sendSse(emitter, "result", json);
                            } catch (Exception e) {
                                log.error("结果处理失败", e);
                                sendSse(emitter, "error", "结果处理失败");
                            }
                            emitter.complete();
                        },
                        // onError
                        (errorMsg) -> {
                            firstTokenReceived.set(true);
                            sendSse(emitter, "error", errorMsg);
                            emitter.complete();
                        }
                );

                // 确保心跳线程结束
                heartbeat.interrupt();

            } catch (Exception e) {
                log.error("流式批改异常", e);
                sendSse(emitter, "error", "批改失败: " + e.getMessage());
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    // ======================== 单字精批 ========================

    /**
     * 单字精批（阻塞式）。
     */
    @PostMapping("/analyze-single")
    public ApiResponse<SingleCharResult> analyzeSingleChar(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "userId", required = false) Long userId) {

        log.info("收到单字精批请求, 文件名: {}, 大小: {} bytes", file.getOriginalFilename(), file.getSize());

        if (userId != null && dataService.countRecentCalls(userId, 5) >= 20) {
            return ApiResponse.error("RATE_LIMITED", "操作过于频繁，请 5 分钟后再试");
        }

        try {
            byte[] imageBytes = file.getBytes();
            SingleCharResult result = gradingService.gradeSingleCharImage(imageBytes);

            // 持久化
            try {
                dataService.saveSingleResult(result, userId);
                dataService.logKeyUsage(result.getTaskId(), userId, "openai", "single-char",
                        1, result.getProcessingTimeMs(), true, null, 0);
            } catch (Exception dbEx) {
                log.warn("单字精批结果持久化失败（不影响返回）: {}", dbEx.getMessage());
            }

            return ApiResponse.ok(result, "单字精批完成");
        } catch (Exception e) {
            log.error("单字精批失败", e);
            return ApiResponse.error("ANALYZE_FAILED", "单字精批失败: " + e.getMessage());
        }
    }

    /**
     * 单字精批（SSE 流式模式）。
     */
    @PostMapping(value = "/analyze-single-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeSingleCharStream(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "userId", required = false) Long userId) {

        log.info("收到流式单字精批请求, 文件名: {}, 大小: {} bytes", file.getOriginalFilename(), file.getSize());

        SseEmitter emitter = new SseEmitter(180_000L);

        if (userId != null && dataService.countRecentCalls(userId, 5) >= 20) {
            try {
                emitter.send(SseEmitter.event().name("error").data("操作过于频繁，请 5 分钟后再试"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        Thread.startVirtualThread(() -> {
            try {
                byte[] imageBytes = file.getBytes();
                sendSse(emitter, "start", "{}");

                AtomicBoolean firstTokenReceived = new AtomicBoolean(false);
                String[] thinkingMessages = {
                        "正在上传图片到 AI 模型...",
                        "AI 正在细察这个字的每一笔...",
                        "正在分析结构比例...",
                        "正在评估笔画力度...",
                        "正在分析重心与间架...",
                        "正在撰写深度点评...",
                        "AI 思考中，请稍等..."
                };

                Thread heartbeat = Thread.startVirtualThread(() -> {
                    int idx = 0;
                    while (!firstTokenReceived.get()) {
                        try {
                            Thread.sleep(3000);
                            if (!firstTokenReceived.get()) {
                                sendSse(emitter, "thinking",
                                        thinkingMessages[Math.min(idx, thinkingMessages.length - 1)]);
                                idx++;
                            }
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                });

                final Long uid = userId;
                gradingService.gradeSingleCharStream(imageBytes,
                        (token) -> {
                            firstTokenReceived.set(true);
                            sendSse(emitter, "token", token);
                        },
                        (result) -> {
                            firstTokenReceived.set(true);
                            try {
                                // 持久化
                                try {
                                    dataService.saveSingleResult(result, uid);
                                    dataService.logKeyUsage(result.getTaskId(), uid, "openai", "single-char",
                                            1, result.getProcessingTimeMs(), true, null, 0);
                                } catch (Exception dbEx) {
                                    log.warn("流式单字精批持久化失败: {}", dbEx.getMessage());
                                }

                                String json = objectMapper.writeValueAsString(result);
                                sendSse(emitter, "result", json);
                            } catch (Exception e) {
                                log.error("结果处理失败", e);
                                sendSse(emitter, "error", "结果处理失败");
                            }
                            emitter.complete();
                        },
                        (errorMsg) -> {
                            firstTokenReceived.set(true);
                            sendSse(emitter, "error", errorMsg);
                            emitter.complete();
                        }
                );

                heartbeat.interrupt();

            } catch (Exception e) {
                log.error("流式单字精批异常", e);
                sendSse(emitter, "error", "单字精批失败: " + e.getMessage());
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    // ======================== 查询接口 ========================

    /**
     * 查询批改结果（从数据库读取）。
     */
    @GetMapping("/{taskId}")
    public ApiResponse<BatchResult> getResult(@PathVariable String taskId) {
        Optional<BatchResult> result = dataService.findByTaskId(taskId);
        return result
                .map(r -> ApiResponse.ok(r))
                .orElse(ApiResponse.error("NOT_FOUND", "未找到批改记录: " + taskId));
    }

    /**
     * 查询用户的作业历史。
     */
    @GetMapping("/history/{userId}")
    public ApiResponse<List<HomeworkEntity>> getHistory(@PathVariable Long userId) {
        List<HomeworkEntity> list = dataService.findRecentHomeworks(userId);
        return ApiResponse.ok(list);
    }

    /**
     * 查询成长曲线：某用户某个字的历史评分趋势。
     */
    @GetMapping("/growth/{userId}/{charName}")
    public ApiResponse<List<CharAnalysis>> getGrowthCurve(
            @PathVariable Long userId,
            @PathVariable String charName) {
        List<CharAnalysis> curve = dataService.getGrowthCurve(userId, charName);
        return ApiResponse.ok(curve);
    }

    // ======================== SSE 工具 ========================

    /**
     * 安全发送 SSE 事件（吞掉 IOException，避免客户端断开导致崩溃）。
     */
    private void sendSse(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            log.warn("SSE 发送失败 (客户端可能已断开): event={}", eventName);
        }
    }
}
