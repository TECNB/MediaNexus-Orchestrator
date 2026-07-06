package com.medianexus.orchestrator.integration.emby;

public class EmbyClientException extends RuntimeException {

    public EmbyClientException(String message) {
        super(message);
    }

    public EmbyClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
