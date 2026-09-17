package com.medianexus.orchestrator.integration.telegram;

public class TelegramWorkerClientException extends RuntimeException {

    private final boolean retryable;
    private final Integer retryAfterSeconds;

    public TelegramWorkerClientException(String message, boolean retryable, Integer retryAfterSeconds) {
        super(message);
        this.retryable = retryable;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public TelegramWorkerClientException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
        this.retryAfterSeconds = null;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
