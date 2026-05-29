package com.medianexus.orchestrator.common.exception;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {

    private final Integer code;
    private final HttpStatus httpStatus;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, HttpStatus.BAD_REQUEST);
    }

    public BusinessException(ErrorCode errorCode, String message, HttpStatus httpStatus) {
        super(message);
        this.code = errorCode.getCode();
        this.httpStatus = httpStatus;
    }

    public Integer getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
