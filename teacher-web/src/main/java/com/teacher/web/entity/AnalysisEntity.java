package com.teacher.web.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 分析结果表 —— 存储每个字的 AI 批改详情，支撑成长曲线和缓存命中。
 */
@Table("t_analysis")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisEntity {

    @Id
    private Long id;

    private Long homeworkId;
    private Integer charIndex;
    private String recognizedChar;
    private Integer structureScore;
    private Integer strokeScore;
    private Integer overallScore;
    private String resultJson;
    private String overallComment;
    private String suggestion;

    /** 缓存键：字帖ID:汉字 */
    private String cacheKey;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
