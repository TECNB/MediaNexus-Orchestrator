package com.medianexus.orchestrator.integration.prowlarr;

public class ProwlarrClientException extends RuntimeException {

    public enum Reason {
        CONFIGURATION,
        UPSTREAM,
        INVALID_RESPONSE,
        MAGNET_RESOLUTION
    }

    private final Reason reason;

    public ProwlarrClientException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ProwlarrClientException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
