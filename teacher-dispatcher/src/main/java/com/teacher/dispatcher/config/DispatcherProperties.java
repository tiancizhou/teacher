package com.teacher.dispatcher.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 调度中心配置项。
 */
@Data
@ConfigurationProperties(prefix = "teacher.dispatcher")
public class DispatcherProperties {

    /** 存储类型: memory（内存，轻量部署） / redis（分布式） */
    private String storageType = "memory";

    /** 最大并发数 */
    private int maxConcurrent = 15;

    /** 单 Key 失败后的重试次数 */
    private int retryCount = 3;

    /** Key 池在 Redis 中的 key 名 */
    private String keyPoolName = "ai:key:pool";

    /** 失败 Key 队列在 Redis 中的 key 名 */
    private String failedKeyPoolName = "ai:key:failed";

    /** Key 冷却时间（秒），失败后等待多久恢复 */
    private int keyCooldownSeconds = 60;

    /** 滑动窗口限流的窗口大小（秒） */
    private int rateLimitWindowSeconds = 60;

    /** 每个 Key 在窗口内的最大请求数 */
    private int rateLimitMaxRequests = 50;

    /** 借用 Key 的超时时间（秒），单 Key 场景需足够长以等待前一个任务完成 */
    private int keyBorrowTimeoutSeconds = 120;

    /** 单次批改的最大字符数（超出部分截断，避免请求过长） */
    private int maxCharactersPerBatch = 30;
}
