package com.medianexus.orchestrator.service.catalog;

public class MediaCatalogSearchException extends RuntimeException {

    private final Reason reason;

    public MediaCatalogSearchException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public MediaCatalogSearchException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        CONFIGURATION,
        UPSTREAM,
        UNSUPPORTED_IDENTITY
    }
}
