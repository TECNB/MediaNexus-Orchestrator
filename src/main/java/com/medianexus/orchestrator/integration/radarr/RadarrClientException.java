package com.medianexus.orchestrator.integration.radarr;

public class RadarrClientException extends RuntimeException {

    public enum Reason {
        CONFIGURATION,
        UPSTREAM,
        INVALID_RESPONSE
    }

    private final Reason reason;

    public RadarrClientException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RadarrClientException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
