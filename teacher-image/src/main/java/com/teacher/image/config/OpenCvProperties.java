package com.teacher.image.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenCV 图像处理相关配置。
 */
@Data
@ConfigurationProperties(prefix = "teacher.opencv")
public class OpenCvProperties {

    /** 二值化阈值 (0-255) */
    private int binaryThreshold = 127;

    /** 最小轮廓面积（低于此值的轮廓被过滤掉） */
    private int minContourArea = 500;

    /** 自适应阈值的块大小（必须为奇数） */
    private int adaptiveBlockSize = 15;

    /** 自适应阈值的常量 C */
    private double adaptiveC = 10;

    /** 高斯模糊核大小（必须为奇数） */
    private int gaussianKernelSize = 5;

    /** 是否启用透视矫正 */
    private boolean perspectiveCorrectionEnabled = true;

    /** 膨胀迭代次数（用于连接相近笔画） */
    private int dilateIterations = 2;

    /** 字符之间的最小间距（像素），用于合并过近的轮廓 */
    private int minCharGap = 10;
}
