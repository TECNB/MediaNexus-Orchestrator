package com.medianexus.orchestrator.dto.quark.response;

import java.util.List;

public record QuarkIngestTaskLogListResponse(List<QuarkIngestTaskLogResponse> items, int total) {
}
