package com.medianexus.orchestrator.integration.openlist;

public class OpenListDirectoryPrepareException extends OpenListClientException {

    public enum Reason {
        ROOT_NOT_FOUND,
        PATH_OUTSIDE_ROOT,
        TARGET_CREATE_FAILED
    }

    private final Reason reason;

    public OpenListDirectoryPrepareException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public OpenListDirectoryPrepareException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
