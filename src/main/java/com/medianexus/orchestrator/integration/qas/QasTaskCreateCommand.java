package com.medianexus.orchestrator.integration.qas;

import java.util.List;

public record QasTaskCreateCommand(
        String taskName,
        String shareUrl,
        String savePath,
        String pattern,
        String replace,
        List<Integer> runWeek,
        String endDate
) {

    public QasTaskCreateCommand(
            String taskName,
            String shareUrl,
            String savePath,
            String pattern,
            String replace
    ) {
        this(taskName, shareUrl, savePath, pattern, replace, null, null);
    }

    public QasTaskCreateCommand {
        runWeek = runWeek == null ? null : List.copyOf(runWeek);
    }
}
