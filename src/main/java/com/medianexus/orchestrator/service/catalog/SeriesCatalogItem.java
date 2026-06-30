package com.medianexus.orchestrator.service.catalog;

public record SeriesCatalogItem(
        String id,
        String title,
        String originalTitle,
        Integer year,
        String overview,
        String poster,
        Integer tvdbId,
        String imdbId,
        Integer tmdbId,
        String status,
        String network,
        String seriesType
) {
}
