package com.medianexus.orchestrator.integration.clouddrive;

import io.grpc.Status;

public class CloudDrive2ClientException extends RuntimeException {

    private final Status.Code statusCode;

    public CloudDrive2ClientException(String message) {
        this(message, Status.Code.UNKNOWN, null);
    }

    public CloudDrive2ClientException(String message, Status.Code statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public Status.Code getStatusCode() {
        return statusCode;
    }
}
