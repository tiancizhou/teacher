package com.teacher.web.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * 作业表 —— 记录每次上传的书法作业和批改状态。
 */
@Table("t_homework")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HomeworkEntity {

    @Id
    private Long id;

    private String taskId;
    private Long userId;
    private String originalFileName;
    private String imagePath;
    private String copyBookId;
    private Integer charCount;
    private Double avgScore;

    @Builder.Default
    private String status = "PENDING";

    private Long processingTimeMs;

    /** SQLite TEXT 格式（yyyy-MM-dd HH:mm:ss），由数据库默认值填充 */
    private String createdAt;
}
