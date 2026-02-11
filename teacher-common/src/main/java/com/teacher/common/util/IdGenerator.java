package com.teacher.common.util;

import java.util.UUID;

/**
 * ID 生成器工具类。
 */
public final class IdGenerator {

    private IdGenerator() {
    }

    /**
     * 生成短 UUID（去掉连字符）。
     */
    public static String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 生成带前缀的 ID，如 "task-xxxx", "img-xxxx"。
     */
    public static String withPrefix(String prefix) {
        return prefix + "-" + shortUuid().substring(0, 12);
    }
}
