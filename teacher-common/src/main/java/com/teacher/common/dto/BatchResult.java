package com.teacher.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 整页书法作业的批改结果聚合。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchResult {

    /** 批改任务ID */
    private String taskId;

    /** 原始图片ID */
    private String imageId;

    /** 总字数 */
    private int totalCharacters;

    /** 各字的分析结果 */
    private List<CharAnalysis> analyses;

    /** 整页平均结构评分 */
    private double avgStructureScore;

    /** 整页平均笔画评分 */
    private double avgStrokeScore;

    /** 整页综合评分 */
    private double avgOverallScore;

    /** 整页总评语 */
    private String summaryComment;

    /** 处理耗时（毫秒） */
    private long processingTimeMs;

    /** 批改时间 */
    private LocalDateTime createdAt;

    /**
     * 根据各字分析结果计算汇总数据。
     */
    public void computeSummary() {
        if (analyses == null || analyses.isEmpty()) {
            return;
        }
        this.totalCharacters = analyses.size();
        this.avgStructureScore = analyses.stream()
                .mapToInt(CharAnalysis::getStructureScore)
                .average()
                .orElse(0);
        this.avgStrokeScore = analyses.stream()
                .mapToInt(CharAnalysis::getStrokeScore)
                .average()
                .orElse(0);
        this.avgOverallScore = analyses.stream()
                .mapToInt(CharAnalysis::getOverallScore)
                .average()
                .orElse(0);
    }
}
