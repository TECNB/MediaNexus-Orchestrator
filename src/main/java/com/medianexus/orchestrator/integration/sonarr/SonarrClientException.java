package com.medianexus.orchestrator.integration.sonarr;

public class SonarrClientException extends RuntimeException {

    public enum Reason {
        CONFIGURATION,
        UPSTREAM,
        INVALID_RESPONSE
    }

    private final Reason reason;

    public SonarrClientException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SonarrClientException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
