package com.teacher.ai.agent;

import com.teacher.common.dto.BatchResult;
import com.teacher.common.dto.CalligraphyImage;
import com.teacher.common.dto.CharAnalysis;
import com.teacher.common.util.IdGenerator;
import com.teacher.dispatcher.service.DispatcherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 书法作业批改核心服务：编排整个批改流程。
 * <p>
 * 流程: 接收切分好的字 -> 并发调度 AI 分析 -> 聚合结果 -> 生成总评
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomeworkGradingService {

    private final DispatcherService dispatcherService;
    private final CalligraphyAnalyzer analyzer;

    /**
     * 批改整页书法作业。
     *
     * @param characters 切分后的单字图片列表
     * @return 批改结果
     */
    public BatchResult gradeHomework(List<CalligraphyImage.SingleCharImage> characters) {
        long startTime = System.currentTimeMillis();
        String taskId = IdGenerator.withPrefix("task");

        log.info("开始批改任务 {}, 共 {} 个字", taskId, characters.size());

        // 利用调度中心并发分析所有字
        List<CharAnalysis> analyses = dispatcherService.dispatchAll(
                characters,
                (charImage, apiKey) -> analyzer.analyze(
                        charImage.getBase64(),
                        charImage.getIndex(),
                        apiKey
                )
        );

        // 过滤掉分析失败的结果
        List<CharAnalysis> validAnalyses = analyses.stream()
                .filter(a -> a != null)
                .collect(Collectors.toList());

        long elapsed = System.currentTimeMillis() - startTime;

        // 组装批改结果
        BatchResult result = BatchResult.builder()
                .taskId(taskId)
                .analyses(validAnalyses)
                .processingTimeMs(elapsed)
                .createdAt(LocalDateTime.now())
                .build();

        // 计算汇总数据
        result.computeSummary();

        // 生成总评语
        result.setSummaryComment(generateSummaryComment(result));

        log.info("批改任务 {} 完成, 耗时 {}ms, 成功 {}/{} 个字, 平均分 {}",
                taskId, elapsed, validAnalyses.size(), characters.size(),
                String.format("%.1f", result.getAvgOverallScore()));

        return result;
    }

    /**
     * 根据汇总数据生成整页总评语。
     */
    private String generateSummaryComment(BatchResult result) {
        double avg = result.getAvgOverallScore();
        int total = result.getTotalCharacters();

        if (avg >= 90) {
            return String.format("太棒了！%d个字整体表现非常优秀，结构扎实，笔画流畅。继续保持这份功力！", total);
        } else if (avg >= 75) {
            return String.format("写得很好！%d个字大部分表现不错，有些小瑕疵可以改进。多加练习一定能更上一层楼！", total);
        } else if (avg >= 60) {
            return String.format("有进步！%d个字中能看到你的努力和用心。建议多对照字帖练习，注意结构的匀称和笔画的起收。加油！", total);
        } else {
            return String.format("继续加油！%d个字虽然还有较大提升空间，但每一次练习都是在进步。建议放慢速度，一笔一画认真写，一定会越来越好！", total);
        }
    }
}
