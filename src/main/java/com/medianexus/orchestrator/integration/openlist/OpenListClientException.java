package com.medianexus.orchestrator.integration.openlist;

public class OpenListClientException extends RuntimeException {

    public OpenListClientException(String message) {
        super(message);
    }

    public OpenListClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
