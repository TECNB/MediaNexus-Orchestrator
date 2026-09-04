package com.medianexus.orchestrator.integration.javdb;

/**
 * Describes a JAVDB request failure without retaining the request cookie or
 * upstream response body.
 */
public class JavdbClientException extends RuntimeException {

    private final Reason reason;

    public JavdbClientException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public JavdbClientException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        AUTHENTICATION,
        NOT_FOUND,
        UPSTREAM,
        PARSE
    }
}
