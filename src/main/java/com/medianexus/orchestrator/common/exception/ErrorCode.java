package com.medianexus.orchestrator.common.exception;

public enum ErrorCode {

    SUCCESS(200, "success"),
    BAD_REQUEST(400, "bad request"),
    UNAUTHORIZED(401, "unauthorized"),
    FORBIDDEN(403, "forbidden"),
    NOT_FOUND(404, "not found"),
    CONFLICT(409, "conflict"),
    TOO_MANY_REQUESTS(429, "too many requests"),
    BAD_GATEWAY(502, "bad gateway"),
    SERVICE_UNAVAILABLE(503, "service unavailable"),
    INTERNAL_ERROR(500, "internal server error");

    private final Integer code;
    private final String message;

    ErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
