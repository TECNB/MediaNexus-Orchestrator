package com.medianexus.orchestrator.service;

public record QasTaskPlan(
        String taskName,
        String sourceUrl,
        String savePath,
        String pattern,
        String replace,
        String versionLabel
) {
}
