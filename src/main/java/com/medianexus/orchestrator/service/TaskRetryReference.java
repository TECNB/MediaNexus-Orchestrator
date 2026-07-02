package com.medianexus.orchestrator.service;

public record TaskRetryReference(
        String attemptGroupId,
        String taskType,
        String taskId
) {
}
