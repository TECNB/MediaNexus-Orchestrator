package com.medianexus.orchestrator.service.catalog;

import com.medianexus.orchestrator.config.TmdbProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Uses TMDB as the primary TV catalog source while preserving Sonarr as the
 * configured compatibility path. TMDB success is returned as-is so fallback
 * data cannot overwrite localized catalog fields.
 */
@Primary
@Component
public class TmdbFirstMediaCatalogSearch implements MediaCatalogSearch {

    private static final Logger log = LoggerFactory.getLogger(TmdbFirstMediaCatalogSearch.class);

    private final TmdbSeriesCatalogSearch tmdbSeriesCatalogSearch;
    private final SonarrMediaCatalogSearch sonarrMediaCatalogSearch;
    private final TmdbProperties tmdbProperties;

    public TmdbFirstMediaCatalogSearch(
            TmdbSeriesCatalogSearch tmdbSeriesCatalogSearch,
            SonarrMediaCatalogSearch sonarrMediaCatalogSearch,
            TmdbProperties tmdbProperties
    ) {
        this.tmdbSeriesCatalogSearch = tmdbSeriesCatalogSearch;
        this.sonarrMediaCatalogSearch = sonarrMediaCatalogSearch;
        this.tmdbProperties = tmdbProperties;
    }

    @Override
    public List<SeriesCatalogItem> searchSeries(String term) {
        try {
            return tmdbSeriesCatalogSearch.searchSeries(term);
        } catch (MediaCatalogSearchException exception) {
            if (!tmdbProperties.isFallbackToSonarr()) {
                throw exception;
            }
            log.warn(
                    "TMDB series catalog search failed, falling back to Sonarr term={} reason={}",
                    logValue(term),
                    exception.getMessage()
            );
            return sonarrMediaCatalogSearch.searchSeries(term);
        }
    }

    @Override
    public SeriesCatalogSeasons getSeriesSeasons(SeriesCatalogIdentity identity) {
        if (identity.tmdbId() == null || identity.tmdbId() <= 0) {
            return sonarrMediaCatalogSearch.getSeriesSeasons(identity);
        }

        try {
            return tmdbSeriesCatalogSearch.getSeriesSeasons(identity);
        } catch (MediaCatalogSearchException exception) {
            if (!tmdbProperties.isFallbackToSonarr()
                    || identity.tvdbId() == null
                    || identity.tvdbId() <= 0) {
                throw exception;
            }
            log.warn(
                    "TMDB series seasons lookup failed, falling back to Sonarr tmdbId={} tvdbId={} reason={}",
                    identity.tmdbId(),
                    identity.tvdbId(),
                    exception.getMessage()
            );
            return sonarrMediaCatalogSearch.getSeriesSeasons(identity);
        }
    }

    private String logValue(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return cleaned.length() <= 80 ? cleaned : cleaned.substring(0, 80);
    }
}
