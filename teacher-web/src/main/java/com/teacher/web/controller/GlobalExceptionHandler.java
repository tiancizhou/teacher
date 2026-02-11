package com.teacher.web.controller;

import com.teacher.common.dto.ApiResponse;
import com.teacher.common.exception.TeacherException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TeacherException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleTeacherException(TeacherException e) {
        log.warn("业务异常: [{}] {}", e.getErrorCode(), e.getMessage());
        return ApiResponse.error(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleFileSizeException(MaxUploadSizeExceededException e) {
        return ApiResponse.error("FILE_TOO_LARGE", "上传文件过大，请压缩后重试（最大 10MB）");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoResourceFound(NoResourceFoundException e) {
        // favicon.ico 等静态资源找不到，不打 ERROR 日志
        log.debug("静态资源未找到: {}", e.getResourcePath());
        return ApiResponse.error("NOT_FOUND", "资源不存在");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGenericException(Exception e) {
        log.error("系统异常", e);
        return ApiResponse.error("SYSTEM_ERROR", "系统内部错误，请稍后重试");
    }
}
