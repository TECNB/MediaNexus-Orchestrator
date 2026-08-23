package com.medianexus.orchestrator.service;

import java.util.List;

public record QasIngestPlan(List<QasTaskPlan> tasks, List<String> warnings) {

    public QasIngestPlan {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
