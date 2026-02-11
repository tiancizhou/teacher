package com.teacher.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 书法图片封装，包含原始整页图片及切分后的单字图片列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalligraphyImage {

    /** 唯一标识 */
    private String id;

    /** 原始图片的 Base64 编码 */
    private String originalBase64;

    /** 原始图片文件名 */
    private String fileName;

    /** 图片宽度（像素） */
    private int width;

    /** 图片高度（像素） */
    private int height;

    /** 经过预处理（矫正、二值化）后的图片 Base64 */
    private String preprocessedBase64;

    /** 切分出的单字图片列表 */
    private List<SingleCharImage> characters;

    /**
     * 单个汉字的切分图片信息。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SingleCharImage {

        /** 在整页图片中的序号（从左到右、从上到下） */
        private int index;

        /** 切分后单字图片的 Base64 编码 */
        private String base64;

        /** 在原图中的 X 坐标 */
        private int x;

        /** 在原图中的 Y 坐标 */
        private int y;

        /** 切分区域宽度 */
        private int width;

        /** 切分区域高度 */
        private int height;
    }
}
