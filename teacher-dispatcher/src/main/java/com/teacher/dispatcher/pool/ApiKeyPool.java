package com.teacher.dispatcher.pool;

import java.util.List;

/**
 * API Key 轮询池接口。
 * <p>
 * 提供两种实现：
 * - {@link InMemoryApiKeyPool}：内存实现，适合轻量单机部署
 * - {@link RedisApiKeyPool}：Redis 实现，适合分布式多实例部署
 */
public interface ApiKeyPool {

    /** 从池中借出一个可用 Key */
    String borrowKey();

    /** 归还 Key 到池尾部 */
    void returnKey(String key);

    /** 标记 Key 为失败状态 */
    void markFailed(String key);

    /** 向池中添加一个 Key */
    void addKey(String key);

    /** 批量添加 Key */
    void addKeys(List<String> keys);

    /** 获取可用 Key 数量 */
    long availableCount();

    /** 获取失败 Key 数量 */
    long failedCount();

    /** 恢复失败的 Key 到可用池 */
    int recoverFailedKeys();
}
