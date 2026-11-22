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

/** Catalog-owned release names for one verified movie identity; shared by comparison and submission. */
@Component
public class MovieReleaseAliases {
    private static final Logger log = LoggerFactory.getLogger(MovieReleaseAliases.class);
    private final TmdbClient tmdb;
    private final Cache<Integer, CatalogNames> cache = CacheBuilder.newBuilder().maximumSize(512)
            .expireAfterWrite(2, TimeUnit.HOURS).build();

    public MovieReleaseAliases(TmdbClient tmdb) { this.tmdb = tmdb; }

    public ReleaseTarget enrich(Integer tmdbId, ReleaseTarget target) {
        if (tmdbId == null || target.season() != null) return target;
        try {
            CatalogNames names = cache.getIfPresent(tmdbId);
            if (names == null) {
                JsonNode local = tmdb.getMovieDetails(tmdbId, "zh-CN");
                JsonNode english = tmdb.getMovieDetails(tmdbId, "en-US");
                if (local.path("id").asInt(-1) != tmdbId || english.path("id").asInt(-1) != tmdbId)
                    throw new IllegalStateException("电影目录身份不一致");
                String date = local.path("release_date").asText("");
                if (!date.matches("[12][0-9]{3}-[0-9]{2}-[0-9]{2}")) return target;
                LinkedHashSet<String> titles = new LinkedHashSet<>();
                add(titles, english.path("title").asText(""));
                for (JsonNode details : List.of(local, english)) {
                    add(titles, details.path("title").asText(""));
                    add(titles, details.path("original_title").asText(""));
                }
                try {
                    JsonNode alternatives = tmdb.getMovieAlternativeTitles(tmdbId);
                    if (alternatives.path("id").asInt(-1) == tmdbId) for (JsonNode alias : alternatives.path("titles")) {
                        String type = alias.path("type").asText("").toLowerCase(java.util.Locale.ROOT);
                        if (type.contains("working") || type.contains("production") || type.contains("literal")) continue;
                        if (List.of("US", "GB", "FR", "CN", "TW", "HK", "KR", "JP").contains(alias.path("iso_3166_1").asText()))
                            add(titles, alias.path("title").asText(""));
                    }
                } catch (RuntimeException failure) {
                    log.warn("Movie alternative titles unavailable tmdbId={}; retaining verified catalog names", tmdbId);
                }
                names = new CatalogNames(Integer.parseInt(date.substring(0, 4)), List.copyOf(titles));
                cache.put(tmdbId, names);
            }
            CatalogNames verified = names;
            if (!java.util.Objects.equals(target.year(), verified.year())
                    || target.titles().stream().noneMatch(title -> verified.titles().stream()
                    .anyMatch(alias -> ReleaseTarget.comparable(title).equals(ReleaseTarget.comparable(alias))))) return target;
            LinkedHashSet<String> titles = new LinkedHashSet<>();
            titles.add(target.titles().get(0));
            titles.addAll(names.titles());
            titles.addAll(target.titles());
            return new ReleaseTarget(List.copyOf(titles), target.year(), null, target.quality());
        } catch (RuntimeException failure) {
            log.warn("Movie release aliases unavailable tmdbId={}; retaining original identity", tmdbId);
            return target;
        }
    }

    private static void add(LinkedHashSet<String> titles, String title) {
        if (!title.isBlank() && title.length() <= 255 && titles.size() < 16) titles.add(title.strip());
    }
    private record CatalogNames(Integer year, List<String> titles) { }
}
