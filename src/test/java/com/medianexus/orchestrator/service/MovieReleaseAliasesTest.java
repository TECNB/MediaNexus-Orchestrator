package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MovieReleaseAliasesTest {
    @Test void usesOnlySameCatalogIdentityAndYearAndRejectsWorkingTitles() throws Exception {
        var json = new ObjectMapper();
        var tmdb = mock(TmdbClient.class);
        when(tmdb.getMovieDetails(123, "zh-CN")).thenReturn(json.readTree("""
                {"id":123,"title":"突如其来","original_title":"急に具合が悪くなる","release_date":"2026-06-19"}
                """));
        when(tmdb.getMovieDetails(123, "en-US")).thenReturn(json.readTree("""
                {"id":123,"title":"All of a Sudden","original_title":"急に具合が悪くなる"}
                """));
        when(tmdb.getMovieAlternativeTitles(123)).thenReturn(json.readTree("""
                {"id":123,"titles":[{"iso_3166_1":"FR","title":"Soudain","type":""},
                {"iso_3166_1":"US","title":"Old Project","type":"working title"}]}
                """));
        var aliases = new MovieReleaseAliases(tmdb);
        var target = ReleaseTarget.of("突如其来", "急に具合が悪くなる", null, 2026, null, "1080p");
        assertThat(aliases.enrich(123, target).titles()).contains("All of a Sudden", "Soudain").doesNotContain("Old Project");
        var wrongYear = ReleaseTarget.of("突如其来", null, null, 2020, null, null);
        assertThat(aliases.enrich(123, wrongYear)).isEqualTo(wrongYear);
        var wrongMovie = ReleaseTarget.of("另一电影", null, null, 2026, null, null);
        assertThat(aliases.enrich(123, wrongMovie)).isEqualTo(wrongMovie);
    }

    @Test void keepsVerifiedEnglishNameAheadOfOriginalWhenAlternativeLookupFails() throws Exception {
        var json = new ObjectMapper();
        var tmdb = mock(TmdbClient.class);
        when(tmdb.getMovieDetails(123, "zh-CN")).thenReturn(json.readTree("""
                {"id":123,"title":"非常治疗师","original_title":"Lo dejamos acá","release_date":"2026-06-19"}
                """));
        when(tmdb.getMovieDetails(123, "en-US")).thenReturn(json.readTree("""
                {"id":123,"title":"Therapy Done Right","original_title":"Lo dejamos acá"}
                """));
        when(tmdb.getMovieAlternativeTitles(123)).thenThrow(new IllegalStateException("unavailable"));
        var target = ReleaseTarget.of("非常治疗师", "Lo dejamos acá", null, 2026, null, "1080p");
        assertThat(new MovieReleaseAliases(tmdb).enrich(123, target).titles())
                .containsExactly("非常治疗师", "Therapy Done Right", "Lo dejamos acá");
    }
}
