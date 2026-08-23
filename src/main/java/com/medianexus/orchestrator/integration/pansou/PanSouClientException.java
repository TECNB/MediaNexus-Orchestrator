package com.medianexus.orchestrator.integration.pansou;

public class PanSouClientException extends RuntimeException {

    public enum Reason {
        CONFIGURATION,
        AUTHENTICATION,
        TIMEOUT,
        UPSTREAM,
        INVALID_RESPONSE
    }

    private final Reason reason;

    public PanSouClientException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public PanSouClientException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
