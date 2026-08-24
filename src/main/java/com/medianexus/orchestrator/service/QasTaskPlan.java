package com.medianexus.orchestrator.service;

import java.util.List;

public record QasTaskPlan(
        String taskName,
        String sourceUrl,
        String savePath,
        String pattern,
        String replace,
        String versionLabel,
        String renameRule,
        int matchedFileCount,
        List<QasRenameSample> renameSamples
) {

    public QasTaskPlan {
        renameSamples = renameSamples == null ? List.of() : List.copyOf(renameSamples);
    }

    public QasTaskPlan(
            String taskName,
            String sourceUrl,
            String savePath,
            String pattern,
            String replace,
            String versionLabel
    ) {
        this(taskName, sourceUrl, savePath, pattern, replace, versionLabel, null, 0, List.of());
    }
}
