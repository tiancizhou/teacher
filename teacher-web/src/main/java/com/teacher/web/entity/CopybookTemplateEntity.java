package com.teacher.web.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * 字帖模板表 —— 预定义字帖的网格布局参数。
 */
@Table("t_copybook_template")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopybookTemplateEntity {

    @Id
    private Long id;

    private String name;
    private String gridType;
    private Integer gridRows;
    private Integer gridCols;

    @Builder.Default
    private Double headerRatio = 0.0;

    private String description;

    /** SQLite TEXT 格式，避免 LocalDateTime 解析问题 */
    private String createdAt;
}
