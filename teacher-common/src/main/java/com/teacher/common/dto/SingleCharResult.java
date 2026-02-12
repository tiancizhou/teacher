package com.teacher.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单字精批结果 DTO：对单个字进行深度分析的返回结构。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SingleCharResult {

    /** 批改任务ID */
    private String taskId;

    /** 识别出的汉字 */
    private String recognizedChar;

    /** 结构评分 (0-100) */
    private int structureScore;

    /** 结构分析详情 */
    private String structureDetail;

    /** 笔画评分 (0-100) */
    private int strokeScore;

    /** 笔画分析详情 */
    private String strokeDetail;

    /** 重心平衡评分 (0-100) */
    private int balanceScore;

    /** 重心平衡分析详情 */
    private String balanceDetail;

    /** 间架布局评分 (0-100) */
    private int spacingScore;

    /** 间架布局分析详情 */
    private String spacingDetail;

    /** 综合评分 (0-100) */
    private int overallScore;

    /** 综合评语 */
    private String overallComment;

    /** 改进建议（具体可操作） */
    private String suggestion;

    /** 字符图片 Base64（可选） */
    private String charImageBase64;

    /** 处理耗时（毫秒） */
    private long processingTimeMs;

    /** 批改时间（SQLite TEXT 格式，可选） */
    private String createdAt;
}
