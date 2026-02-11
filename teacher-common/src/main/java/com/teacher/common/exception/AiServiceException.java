package com.teacher.common.exception;

/**
 * AI 服务调用异常（API 调用失败、Key 不可用等）。
 */
public class AiServiceException extends TeacherException {

    public AiServiceException(String message) {
        super("AI_ERROR", message);
    }

    public AiServiceException(String message, Throwable cause) {
        super("AI_ERROR", message, cause);
    }
}
