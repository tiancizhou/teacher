package com.teacher.web.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * 单字精批结果表 —— 每次单字精批一条记录，五维度深度分析。
 */
@Table("t_single_analysis")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SingleAnalysisEntity {

    @Id
    private Long id;

    private String taskId;
    private Long userId;
    private String recognizedChar;

    private Integer structureScore;
    private String structureDetail;
    private Integer strokeScore;
    private String strokeDetail;
    private Integer balanceScore;
    private String balanceDetail;
    private Integer spacingScore;
    private String spacingDetail;
    private Integer overallScore;
    private String overallComment;
    private String suggestion;

    private Long processingTimeMs;

    /** SQLite TEXT 格式，由数据库默认值填充 */
    private String createdAt;
}
