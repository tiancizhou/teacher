package com.teacher.common.exception;

/**
 * 图像处理异常（切分失败、矫正失败等）。
 */
public class ImageProcessingException extends TeacherException {

    public ImageProcessingException(String message) {
        super("IMG_ERROR", message);
    }

    public ImageProcessingException(String message, Throwable cause) {
        super("IMG_ERROR", message, cause);
    }
}
