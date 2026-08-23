package com.medianexus.orchestrator.dto.quark.response;

import java.util.List;

public record QuarkSharePreviewNodeResponse(
        String name,
        boolean directory,
        long size,
        List<QuarkSharePreviewNodeResponse> children
) {

    public QuarkSharePreviewNodeResponse {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
