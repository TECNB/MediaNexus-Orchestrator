package com.medianexus.orchestrator.integration.qas;

import com.fasterxml.jackson.databind.JsonNode;

/** A small, credential-free view of a task returned by QAS /data. */
public record QasExistingTask(
        String taskName,
        String shareUrl,
        JsonNode document
) {
}
