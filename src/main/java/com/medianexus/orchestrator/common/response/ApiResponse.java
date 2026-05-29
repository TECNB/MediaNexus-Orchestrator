package com.medianexus.orchestrator.common.response;

import com.medianexus.orchestrator.common.exception.ErrorCode;

public record ApiResponse<T>(Integer code, String message, T data) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> failed(ErrorCode errorCode) {
        return failed(errorCode.getCode(), errorCode.getMessage());
    }

    public static <T> ApiResponse<T> failed(Integer code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
