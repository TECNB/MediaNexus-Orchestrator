package com.medianexus.orchestrator.service.catalog;

public record SeriesCatalogIdentity(
        Integer tvdbId,
        Integer tmdbId,
        String imdbId
) {

    public static SeriesCatalogIdentity tvdb(Integer tvdbId) {
        return new SeriesCatalogIdentity(tvdbId, null, null);
    }

    public static SeriesCatalogIdentity tmdb(Integer tmdbId) {
        return new SeriesCatalogIdentity(null, tmdbId, null);
    }
}
