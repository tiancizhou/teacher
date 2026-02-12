package com.teacher.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单字分析结果，包含结构评分、笔画评分和综合评语。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CharAnalysis {

    /** 对应的字符序号 */
    private int charIndex;

    /** 识别出的汉字（如果能识别） */
    private String recognizedChar;

    /** 在作业网格中的行号（从1开始，从上往下） */
    private int row;

    /** 在作业网格中的列号（从1开始，从左往右） */
    private int column;

    /** 结构评分 (0-100)：重心、间架、比例 */
    private int structureScore;

    /** 结构分析详情 */
    private String structureComment;

    /** 笔画评分 (0-100)：起笔、行笔、收笔 */
    private int strokeScore;

    /** 笔画分析详情 */
    private String strokeComment;

    /** 综合评分 (0-100) */
    private int overallScore;

    /** 综合评语（温情鼓励风格） */
    private String overallComment;

    /** 改进建议 */
    private String suggestion;

    /** 从原图中裁剪出的字符图片 Base64（由后端字符切分器提供） */
    private String charImageBase64;
}
