package com.medianexus.orchestrator.integration.qas;

public class QasClientException extends RuntimeException {

    public enum Reason {
        CONFIGURATION,
        AUTHENTICATION,
        UPSTREAM,
        INVALID_RESPONSE
    }

    private final Reason reason;

    public QasClientException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public QasClientException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
