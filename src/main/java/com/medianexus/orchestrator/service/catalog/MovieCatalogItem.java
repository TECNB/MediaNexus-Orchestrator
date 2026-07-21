package com.medianexus.orchestrator.service.catalog;

import java.util.List;

public record MovieCatalogItem(
        String id,
        String title,
        String originalTitle,
        Integer year,
        String overview,
        String poster,
        Integer tmdbId,
        String imdbId,
        List<String> alternateTitles,
        String status
) {
}
