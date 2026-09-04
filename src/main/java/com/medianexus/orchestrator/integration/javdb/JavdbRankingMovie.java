package com.medianexus.orchestrator.integration.javdb;

public record JavdbRankingMovie(
        String code,
        String title,
        String detailUrl,
        String releaseDate,
        String period,
        int rank,
        boolean hasMagnetBadge
) {
}
