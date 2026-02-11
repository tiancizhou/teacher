package com.teacher.web.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 用户表 —— 绑定微信 OpenID，实现用户资产化。
 */
@Table("t_user")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserEntity {

    @Id
    private Long id;

    private String openId;
    private String nickname;
    private String avatarUrl;

    @Builder.Default
    private String role = "PARENT";

    @Builder.Default
    private Integer totalAnalyses = 0;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    private LocalDateTime lastActiveAt = LocalDateTime.now();
}
