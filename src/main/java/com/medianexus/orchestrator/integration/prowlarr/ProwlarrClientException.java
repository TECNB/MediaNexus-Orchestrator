package com.medianexus.orchestrator.integration.prowlarr;

public class ProwlarrClientException extends RuntimeException {

    public enum Reason {
        CONFIGURATION,
        UPSTREAM,
        /** 已知身份、路径、结构或体积安全失败；不得由其他证据来源洗白。 */
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
