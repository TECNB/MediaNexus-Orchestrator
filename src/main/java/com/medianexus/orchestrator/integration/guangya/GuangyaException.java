package com.medianexus.orchestrator.integration.guangya;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** 失败保持“未取得”；调用方不得转换成零文件、缺集或死种。消息不含上游原文或凭据。 */
public final class GuangyaException extends BusinessException {
    public enum Reason { NOT_CONFIGURED, BUDGET_EXHAUSTED, AUTHENTICATION, ACCOUNT_BLOCKED, INVALID_RESPONSE, UNAVAILABLE, PERSISTENCE_FAILED }
    private final Reason reason;
    public GuangyaException(Reason reason, String message) {
        super(ErrorCode.SERVICE_UNAVAILABLE, message, HttpStatus.SERVICE_UNAVAILABLE);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
