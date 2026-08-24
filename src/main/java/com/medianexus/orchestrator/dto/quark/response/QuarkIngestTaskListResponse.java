package com.medianexus.orchestrator.dto.quark.response;

import java.util.List;

public record QuarkIngestTaskListResponse(List<QuarkIngestTaskResponse> items, int total) {
}
