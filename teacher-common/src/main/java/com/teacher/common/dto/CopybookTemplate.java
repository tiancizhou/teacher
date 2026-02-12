package com.teacher.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 字帖模板 DTO：描述字帖的网格布局。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopybookTemplate {

    /** 模板ID */
    private Long id;

    /** 模板名称，如"田字格 10x10" */
    private String name;

    /** 格线类型：TIAN=田字格 / MI=米字格 / HUI=回宫格 / PLAIN=无格线 */
    private String gridType;

    /** 网格行数 */
    private int gridRows;

    /** 网格列数 */
    private int gridCols;

    /** 顶部非书写区占比（0~0.3） */
    private double headerRatio;

    /** 补充描述 */
    private String description;
}
