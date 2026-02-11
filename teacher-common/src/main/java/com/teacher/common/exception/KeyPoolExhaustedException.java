package com.teacher.common.exception;

/**
 * Key 池耗尽异常，所有 Key 均不可用时抛出。
 */
public class KeyPoolExhaustedException extends TeacherException {

    public KeyPoolExhaustedException(String message) {
        super("KEY_EXHAUSTED", message);
    }
}
