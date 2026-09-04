package com.medianexus.orchestrator.integration.javdb;

import java.util.List;

public record JavdbMovieDetail(
        String code,
        String title,
        String detailUrl,
        List<JavdbMagnet> magnets
) {
}
