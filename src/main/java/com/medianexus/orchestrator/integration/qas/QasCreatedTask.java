package com.medianexus.orchestrator.integration.qas;

import com.fasterxml.jackson.databind.JsonNode;

public record QasCreatedTask(
        String taskName,
        String savePath,
        JsonNode document
) {
}
