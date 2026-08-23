package com.medianexus.orchestrator.integration.qas;

import java.util.List;

public record QasShareTree(String sourceUrl, List<QasShareNode> entries) {

    public QasShareTree {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
