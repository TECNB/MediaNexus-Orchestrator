package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import org.junit.jupiter.api.Test;

class SeriesReleaseAliasesTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void enrichesOnlyMatchingCatalogIdentityAndUniqueSeason() throws Exception {
        var tmdb = mock(TmdbClient.class);
        var local = json.readTree("""
                {"id":293610,"name":"Doctor X：白色黑手党时代","original_name":"닥터X: 하얀 마피아의 시대",
                 "seasons":[{"season_number":1}]}
                """);
        var english = json.readTree("""
                {"id":293610,"name":"Doctor X: Mafia in White","original_name":"닥터X: 하얀 마피아의 시대",
                 "seasons":[{"season_number":1}]}
                """);
        when(tmdb.getTvDetails(293610,"zh-CN")).thenReturn(local);
        when(tmdb.getTvDetails(293610,"en-US")).thenReturn(english);
        var aliases = new SeriesReleaseAliases(tmdb);
        var target = ReleaseTarget.of("Doctor X：白色黑手党时代",null,null,null,1,"1080p");
        assertThat(aliases.enrich(293610,target).matches("Doctor.X.Mafia.in.White.S01.1080p")).isTrue();
        var unrelated = ReleaseTarget.of("Another Show",null,null,null,1,"1080p");
        assertThat(aliases.enrich(293610,unrelated)).isSameAs(unrelated);
        var wrongSeason = ReleaseTarget.of(target.titles().get(0),null,null,null,2,"1080p");
        assertThat(aliases.enrich(293610,wrongSeason)).isSameAs(wrongSeason);
        ((com.fasterxml.jackson.databind.node.ObjectNode)english).put("id",99);
        assertThat(new SeriesReleaseAliases(tmdb).enrich(293610,target)).isSameAs(target);
        ((com.fasterxml.jackson.databind.node.ObjectNode)english).put("id",293610);
        ((com.fasterxml.jackson.databind.node.ArrayNode)local.path("seasons")).addObject().put("season_number",1);
        assertThat(new SeriesReleaseAliases(tmdb).enrich(293610,target)).isSameAs(target);
    }
}
