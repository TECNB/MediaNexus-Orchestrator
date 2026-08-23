package com.medianexus.orchestrator.integration.pansou;

import java.util.List;

public record PanSouSearchResult(List<PanSouSearchEntry> entries) {

    public PanSouSearchResult {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
