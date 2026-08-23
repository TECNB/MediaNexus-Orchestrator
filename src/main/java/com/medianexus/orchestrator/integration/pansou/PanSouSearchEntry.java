package com.medianexus.orchestrator.integration.pansou;

public record PanSouSearchEntry(
        String url,
        String password,
        String note,
        String datetime,
        String source
) {
}
