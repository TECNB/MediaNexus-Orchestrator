package com.medianexus.orchestrator.integration.qas;

public record QasTaskCreateCommand(
        String taskName,
        String shareUrl,
        String savePath,
        String pattern,
        String replace
) {
}
