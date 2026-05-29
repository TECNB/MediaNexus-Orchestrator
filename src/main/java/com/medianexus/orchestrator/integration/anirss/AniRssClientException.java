package com.medianexus.orchestrator.integration.anirss;

public class AniRssClientException extends RuntimeException {

    public AniRssClientException(String message) {
        super(message);
    }

    public AniRssClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
