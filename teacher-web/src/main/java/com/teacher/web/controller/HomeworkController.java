package com.teacher.web.controller;

import com.teacher.ai.agent.HomeworkGradingService;
import com.teacher.common.dto.ApiResponse;
import com.teacher.common.dto.BatchResult;
import com.teacher.common.dto.CalligraphyImage;
import com.teacher.common.dto.CharAnalysis;
import com.teacher.common.util.IdGenerator;
import com.teacher.image.service.CharacterSegmenter;
import com.teacher.web.entity.HomeworkEntity;
import com.teacher.web.service.HomeworkDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

/**
 * 书法作业批改 REST API 控制器。
 */
@Slf4j
@RestController
@RequestMapping("/api/homework")
@RequiredArgsConstructor
public class HomeworkController {

    private final CharacterSegmenter segmenter;
    private final HomeworkGradingService gradingService;
    private final HomeworkDataService dataService;

    /**
     * 上传书法作业图片进行 AI 批改。
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

        log.info("收到批改请求, 文件名: {}, 大小: {} bytes, userId: {}, copyBookId: {}",
                file.getOriginalFilename(), file.getSize(), userId, copyBookId);

        // 防刷检查：同一用户 5 分钟内最多 20 次
        if (userId != null && dataService.countRecentCalls(userId, 5) >= 20) {
            return ApiResponse.error("RATE_LIMITED", "操作过于频繁，请 5 分钟后再试");
        }

        long startTime = System.currentTimeMillis();
        try {
            // 1. 图像预处理与字符切分
            byte[] imageBytes = file.getBytes();
            List<CalligraphyImage.SingleCharImage> characters = segmenter.segment(imageBytes);
            log.info("字符切分完成, 检测到 {} 个字", characters.size());

            // 2. AI 并发批改
            BatchResult result = gradingService.gradeHomework(characters);
            result.setImageId(IdGenerator.withPrefix("img"));

            // 3. 持久化到数据库
            dataService.saveResult(result, file.getOriginalFilename(), userId, copyBookId);

            // 4. 记录调用日志
            long elapsed = System.currentTimeMillis() - startTime;
            dataService.logKeyUsage(
                    result.getTaskId(), userId, "openai", "gpt-4o",
                    characters.size(), elapsed, true, null, 0);

            return ApiResponse.ok(result, "批改完成");

        } catch (Exception e) {
            log.error("批改失败", e);
            long elapsed = System.currentTimeMillis() - startTime;
            dataService.logKeyUsage(
                    null, userId, "openai", "gpt-4o",
                    0, elapsed, false, e.getMessage(), 0);
            return ApiResponse.error("ANALYZE_FAILED", "批改失败: " + e.getMessage());
        }
    }

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
}
