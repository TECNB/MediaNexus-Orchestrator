package com.medianexus.orchestrator.service;

public class QuarkIngestPlanningException extends RuntimeException {

    public enum Reason {
        UNSAFE_STRUCTURE,
        DATE_MAPPING_REQUIRED
    }

    private final Reason reason;

    public QuarkIngestPlanningException(String message) {
        this(Reason.UNSAFE_STRUCTURE, message);
    }

    public QuarkIngestPlanningException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
