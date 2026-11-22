package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Catalog-owned series names shared by discovery and the final submission boundary. */
@Component
public class SeriesReleaseAliases {
    private static final Logger log = LoggerFactory.getLogger(SeriesReleaseAliases.class);
    private final TmdbClient tmdb;
    private final Cache<Integer, CatalogNames> cache = CacheBuilder.newBuilder().maximumSize(512)
            .expireAfterWrite(2, TimeUnit.HOURS).build();

    public SeriesReleaseAliases(TmdbClient tmdb) { this.tmdb = tmdb; }

    public ReleaseTarget enrich(Integer tmdbId, ReleaseTarget target) {
        if (tmdbId == null || target.season() == null) return target;
        try {
            CatalogNames names = cache.getIfPresent(tmdbId);
            if (names == null) {
                JsonNode local = tmdb.getTvDetails(tmdbId, "zh-CN");
                JsonNode english = tmdb.getTvDetails(tmdbId, "en-US");
                if (local.path("id").asInt(-1) != tmdbId || english.path("id").asInt(-1) != tmdbId)
                    return target;
                LinkedHashSet<String> titles = new LinkedHashSet<>();
                add(titles, english.path("name").asText(""));
                for (JsonNode details : List.of(local, english)) {
                    add(titles, details.path("name").asText(""));
                    add(titles, details.path("original_name").asText(""));
                }
                names = new CatalogNames(List.copyOf(titles), local, english);
                cache.put(tmdbId, names);
            }
            if (!uniqueSeason(names.local(), target.season()) || !uniqueSeason(names.english(), target.season())) return target;
            CatalogNames verified = names;
            if (target.titles().stream().noneMatch(title -> verified.titles().stream()
                    .anyMatch(alias -> ReleaseTarget.comparable(title).equals(ReleaseTarget.comparable(alias))))) return target;
            LinkedHashSet<String> titles = new LinkedHashSet<>();
            titles.add(target.titles().get(0));
            titles.addAll(names.titles());
            titles.addAll(target.titles());
            return new ReleaseTarget(List.copyOf(titles), target.year(), target.season(), target.quality());
        } catch (RuntimeException failure) {
            log.warn("Series release aliases unavailable tmdbId={}; retaining original identity", tmdbId);
            return target;
        }
    }

    private static boolean uniqueSeason(JsonNode details, int season) {
        int matches = 0;
        for (JsonNode node : details.path("seasons"))
            if (node.path("season_number").isIntegralNumber() && node.path("season_number").intValue() == season) matches++;
        return matches == 1;
    }
    private static void add(LinkedHashSet<String> titles, String title) {
        if (!title.isBlank() && title.length() <= 255) titles.add(title.strip());
    }
    private record CatalogNames(List<String> titles, JsonNode local, JsonNode english) { }
}
