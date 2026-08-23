package com.medianexus.orchestrator.integration.pansou;

public record PanSouLinkCheckResult(
        String url,
        String normalizedUrl,
        String state,
        String summary
) {
}
