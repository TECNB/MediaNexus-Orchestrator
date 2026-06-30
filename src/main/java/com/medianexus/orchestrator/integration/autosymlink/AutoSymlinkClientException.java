package com.medianexus.orchestrator.integration.autosymlink;

public class AutoSymlinkClientException extends RuntimeException {

    public AutoSymlinkClientException(String message) {
        super(message);
    }

    public AutoSymlinkClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
