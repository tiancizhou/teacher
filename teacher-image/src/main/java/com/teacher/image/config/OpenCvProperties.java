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

    /** 最小轮廓面积（低于此值的轮廓被过滤掉，去除噪点） */
    private int minContourArea = 800;

    /** 最大轮廓面积占比（超过图片总面积该比例的轮廓被过滤，去除整页边框） */
    private double maxContourAreaRatio = 0.25;

    /** 自适应阈值的块大小（必须为奇数） */
    private int adaptiveBlockSize = 15;

    /** 自适应阈值的常量 C */
    private double adaptiveC = 10;

    /** 高斯模糊核大小（必须为奇数） */
    private int gaussianKernelSize = 5;

    /** 是否启用透视矫正 */
    private boolean perspectiveCorrectionEnabled = true;

    /** 膨胀迭代次数（用于连接同一个字的笔画碎片） */
    private int dilateIterations = 1;

    /** 字符之间的最小间距（像素），低于此间距的轮廓合并 */
    private int minCharGap = 5;

    /** 是否启用网格线去除（适用于格子练字纸） */
    private boolean gridRemovalEnabled = true;

    /** 网格线检测最小长度比例（相对于图片宽/高的比例） */
    private double gridLineMinRatio = 0.25;

    /** 字符宽高比最大值（过滤掉细长条形的非字轮廓） */
    private double maxAspectRatio = 3.0;

    /** 切分时每个字周围的留白边距（像素） */
    private int charPadding = 8;

    /** 小图阈值（像素）：宽和高都小于此值时视为单字图，跳过切分 */
    private int smallImageThreshold = 300;

    /**
     * 最小轮廓面积占图片总面积的比例。
     * 用于动态计算 minContourArea（在小/中图上替代固定值）。
     * 例如 0.005 表示轮廓面积至少占图片的 0.5%。
     */
    private double minContourAreaRatio = 0.005;
}
