package com.teacher.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * API Key 元数据，描述一个 AI 服务的访问密钥。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKeyInfo {

    /** Key 唯一标识 */
    private String keyId;

    /** 实际的 API Key 值 */
    private String apiKey;

    /** 提供商: openai / anthropic */
    private String provider;

    /** 每分钟请求上限 (RPM) */
    private int rateLimitRpm;

    /** 每分钟 Token 上限 (TPM) */
    private int rateLimitTpm;

    /** Key 状态: active / cooldown / disabled */
    private String status;

    /** 自定义 base URL（用于反代中转） */
    private String baseUrl;

    public enum Status {
        ACTIVE, COOLDOWN, DISABLED
    }
}
