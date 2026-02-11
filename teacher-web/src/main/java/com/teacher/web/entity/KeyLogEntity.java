package com.teacher.web.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * API Key 调用日志 —— 监控算力健康度，防刷审计。
 */
@Table("t_key_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyLogEntity {

    @Id
    private Long id;

    private Long userId;
    private String taskId;
    private String apiKeyHash;
    private String provider;
    private String model;
    private Integer charCount;
    private Integer tokensUsed;
    private Long latencyMs;

    @Builder.Default
    private Boolean success = true;

    private String errorMessage;

    @Builder.Default
    private Integer cacheHits = 0;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
