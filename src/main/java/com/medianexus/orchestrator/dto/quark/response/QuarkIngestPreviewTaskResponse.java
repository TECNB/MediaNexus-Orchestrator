package com.medianexus.orchestrator.dto.quark.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QuarkIngestPreviewTaskResponse(
        @JsonProperty("task_name")
        String taskName,
        @JsonProperty("version_label")
        String versionLabel,
        @JsonProperty("rename_enabled")
        boolean renameEnabled
) {
}
