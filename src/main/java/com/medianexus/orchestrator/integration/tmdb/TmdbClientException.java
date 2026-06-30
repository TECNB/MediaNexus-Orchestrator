package com.medianexus.orchestrator.integration.tmdb;

public class TmdbClientException extends RuntimeException {

    public enum Reason {
        CONFIGURATION,
        UPSTREAM,
        INVALID_RESPONSE
    }

    private final Reason reason;

    public TmdbClientException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public TmdbClientException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
