package com.medianexus.orchestrator.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.*;
import com.medianexus.orchestrator.dto.douban.DoubanChartContract.Subject;
import com.medianexus.orchestrator.dto.resources.response.*;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.integration.guangya.TorrentFileTable;
import com.medianexus.orchestrator.integration.pansou.PanSouMagnetIdentity;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class DoubanChartEligibilityTest {
    private final ObjectMapper json = new ObjectMapper();
    private final TmdbClient tmdb = mock(TmdbClient.class);
    private final ReleaseFileTables tables = mock(ReleaseFileTables.class);
    private final DoubanChartEligibility eligibility = new DoubanChartEligibility(tmdb, tables, new MovieSeriesFileRenameService());
    private final Identity identity = new Identity("SERIES", 123, 2, "测试剧", "Series", 2025);
    private final String magnet = "magnet:?xt=urn:btih:" + "a".repeat(40);

    @Test void explicitSourceCompletionAllowsMissingDatesButNotCountConflictOrAiring() throws Exception {
        when(tmdb.getTvDetails(123,"zh-CN")).thenReturn(json.readTree("""
                {"id":123,"name":"测试剧","original_name":"Series","seasons":[{"season_number":2,"episode_count":3,"air_date":"2025-01-01"}]}
                """));
        when(tmdb.getTvSeasonDetails(123,2,"zh-CN")).thenReturn(json.readTree("""
                {"id":22,"season_number":2,"episodes":[{"episode_number":1},{"episode_number":2},{"episode_number":3}]}
                """));
        assertNull(eligibility.assess(subject("3集全"), identity).reason());
        assertEquals(List.of(1,2,3),eligibility.assess(subject("3集全"), identity).expectedEpisodes());
        assertEquals("EPISODE_CONFLICT",eligibility.assess(subject("4集全"), identity).reason().code());
        assertEquals("AIRING",eligibility.assess(subject("更新至2集"), identity).reason().code());
        assertEquals("PROGRESS_UNKNOWN",eligibility.assess(subject(null), identity).reason().code());
    }

    @Test void fullSeasonRequiresDistinctExactEpisodeSetAndSafeKnownFileTable() {
        var assessed = new DoubanChartEligibility.Assessment(identity,
                BgmWishlistService.target(identity), List.of(1,2,3), null);
        when(tables.knownMagnet(null, magnet)).thenReturn(Optional.of(magnet));
        for (var names : List.of(List.of("Show.S02E01.mkv", "Show.S02E02.mkv"),
                List.of("Show.S02E01.mkv", "Show.S02E01.1080p.mkv", "Show.S02E03.mkv"),
                List.of("Show.S02E01.mkv", "Show.S02E03.mkv", "Show.S03E02.mkv"),
                List.of("Show.S02E01.mkv", "unknown.mkv", "Show.S02E03.mkv"))) {
            cache(names); assertFalse(eligibility.completeRelease(assessed, release()));
        }
        cache(List.of("Show.S02E01.mkv", "Show.S02E02.mkv", "Show.S02E03.mkv"));
        assertTrue(eligibility.completeRelease(assessed,release()));
        cache(List.of("Show.S02E01.mkv", "Show.S02E02.mkv", "Show.S02E03.mkv", "Extras/interview.mkv", "Show.sample.mkv"));
        assertTrue(eligibility.completeRelease(assessed,release()));
        when(tables.cached(magnet)).thenReturn(Optional.empty());
        assertFalse(eligibility.completeRelease(assessed,release()));
    }

    @Test void ambiguousMultiSeasonMatchNeverDefaultsToFirstOrLatestSeason() throws Exception {
        when(tmdb.searchChartTitles("测试剧", "SERIES")).thenReturn(json.readTree("""
                [{"id":123,"name":"测试剧","original_name":"Series","first_air_date":"2020-01-01"}]
                """));
        when(tmdb.getTvDetails(123,"zh-CN")).thenReturn(json.readTree("""
                {"id":123,"name":"测试剧","original_name":"Series","seasons":[
                  {"season_number":1,"episode_count":3,"air_date":"2020-01-01"},
                  {"season_number":2,"episode_count":3,"air_date":"2025-01-01"}]}
                """));
        assertEquals("IDENTITY_AMBIGUOUS", eligibility.assess(subject("3集全"),null).reason().code());
        assertThrows(RuntimeException.class, () -> eligibility.correct(subject("3集全"), new IdentityInput("SERIES",123,9)));
    }

    private Subject subject(String progress) { return new Subject(1,"测试剧",null,null,2025,"SERIES",null,null,1,progress,Instant.now()); }
    private void cache(List<String> names) {
        var table = new TorrentFileTable(PanSouMagnetIdentity.fromUrl(magnet).orElseThrow(),"Show",
                names.stream().map(name -> new TorrentFileTable.Entry(name,100)).toList(),names.size()*100L);
        when(tables.cached(magnet)).thenReturn(Optional.of(new ReleaseFileTables.Evidence(table,"fixture")));
    }
    private ProwlarrReleaseItemResponse release() {
        return new ProwlarrReleaseItemResponse("测试剧 S02 Complete",300L,null,null,null,"fixture",null,null,magnet,
                List.of("1080p"),List.of(),List.of("S02"),null,null,List.of(),
                new ReleaseFileEvidence("RECOMMENDABLE","safe",300L,300L,3,false,false));
    }
}
