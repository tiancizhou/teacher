package com.teacher.common.exception;

/**
 * 系统基础异常，所有业务异常的父类。
 */
public class TeacherException extends RuntimeException {

    private final String errorCode;

    public TeacherException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TeacherException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
