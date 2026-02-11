package com.teacher.common.util;

import java.util.Base64;

/**
 * 图片编解码工具类。
 */
public final class ImageUtils {

    private ImageUtils() {
    }

    /**
     * 字节数组转 Base64 字符串。
     */
    public static String toBase64(byte[] imageBytes) {
        return Base64.getEncoder().encodeToString(imageBytes);
    }

    /**
     * Base64 字符串转字节数组。
     */
    public static byte[] fromBase64(String base64) {
        return Base64.getDecoder().decode(base64);
    }

    /**
     * 根据文件扩展名推断 MIME 类型。
     */
    public static String getMimeType(String fileName) {
        if (fileName == null) return "image/jpeg";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    /**
     * 构建 data URI（用于 AI 视觉 API 调用）。
     */
    public static String toDataUri(byte[] imageBytes, String mimeType) {
        return "data:" + mimeType + ";base64," + toBase64(imageBytes);
    }
}
