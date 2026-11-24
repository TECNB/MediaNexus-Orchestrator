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

    @Test void missingSourceProgressRequiresStrictPastSeasonAndEndingEvidence() throws Exception {
        var clock = java.time.Clock.fixed(Instant.parse("2026-11-22T04:00:00Z"), DoubanChartStore.ZONE);
        var checked = new DoubanChartEligibility(tmdb, tables, new MovieSeriesFileRenameService(), clock);
        var series = json.readTree("""
                {"id":123,"name":"测试剧","original_name":"Series","status":"Ended","seasons":[{"season_number":2,"episode_count":2,"air_date":"2025-01-01"}]}
                """);
        var season = json.readTree("""
                {"id":22,"season_number":2,"episodes":[{"episode_number":1,"season_number":2,"air_date":"2026-11-20"},{"episode_number":2,"season_number":2,"air_date":"2026-11-21"}]}
                """);
        when(tmdb.getTvDetails(123,"zh-CN")).thenReturn(series);
        when(tmdb.getTvSeasonDetails(123,2,"zh-CN")).thenReturn(season);
        assertNull(checked.assess(subject(null), identity).reason());
        assertEquals(List.of(1,2), checked.assess(subject(null), identity).expectedEpisodes());
        assertEquals("AIRING", checked.assess(subject("更新至1集"), identity).reason().code());
        for (String date : List.of("2026-11-22", "2026-11-23", "", "2026-02-30")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode)season.path("episodes").get(1)).put("air_date", date);
            assertNotNull(checked.assess(subject(null), identity).reason(), date);
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode)season.path("episodes").get(1)).put("air_date", "2026-11-21");
        ((com.fasterxml.jackson.databind.node.ObjectNode)series).put("status", "Returning Series");
        assertNotNull(checked.assess(subject(null), identity).reason());
        ((com.fasterxml.jackson.databind.node.ObjectNode)season.path("episodes").get(1)).put("episode_type", "finale");
        assertNull(checked.assess(subject(null), identity).reason());
        ((com.fasterxml.jackson.databind.node.ObjectNode)season.path("episodes").get(1)).put("episode_number", 1);
        assertNotNull(checked.assess(subject(null), identity).reason());
    }

    @Test void explicitCompletionAndPastSeasonCannotOverrideConflictingFutureSchedule() throws Exception {
        var checked = new DoubanChartEligibility(tmdb, tables, new MovieSeriesFileRenameService(),
                java.time.Clock.fixed(Instant.parse("2026-11-22T04:00:00Z"), DoubanChartStore.ZONE));
        when(tmdb.getTvDetails(123,"zh-CN")).thenReturn(json.readTree("""
                {"id":123,"name":"测试剧","original_name":"Series","status":"Ended","seasons":[{"season_number":2,"episode_count":1}],
                 "next_episode_to_air":{"season_number":2,"episode_number":2,"air_date":"2026-11-23"}}
                """));
        when(tmdb.getTvSeasonDetails(123,2,"zh-CN")).thenReturn(json.readTree("""
                {"id":22,"season_number":2,"episodes":[{"episode_number":1,"season_number":2,"air_date":"2026-11-20"}]}
                """));
        assertEquals("PROGRESS_CONFLICT", checked.assess(subject(null), identity).reason().code());
        assertEquals("PROGRESS_CONFLICT", checked.assess(subject("1集全"), identity).reason().code());
    }

    @Test void catalogAiringIsReportedAsAiringAndSameDayFinaleRemainsPending() throws Exception {
        var checked = new DoubanChartEligibility(tmdb, tables, new MovieSeriesFileRenameService(),
                java.time.Clock.fixed(Instant.parse("2026-11-22T04:00:00Z"), DoubanChartStore.ZONE));
        var series = json.readTree("""
                {"id":123,"name":"测试剧","original_name":"Series","status":"Returning Series",
                 "seasons":[{"season_number":2,"episode_count":2}]}
                """);
        var season = json.readTree("""
                {"id":22,"season_number":2,"episodes":[
                 {"episode_number":1,"season_number":2,"air_date":"2026-11-21"},
                 {"episode_number":2,"season_number":2,"air_date":"2026-11-23","episode_type":"finale"}]}
                """);
        when(tmdb.getTvDetails(123,"zh-CN")).thenReturn(series);
        when(tmdb.getTvSeasonDetails(123,2,"zh-CN")).thenReturn(season);
        assertEquals("AIRING", checked.assess(subject(null), identity).reason().code());
        var first = (com.fasterxml.jackson.databind.node.ObjectNode) season.path("episodes").get(0);
        first.remove("air_date");
        assertEquals("AIRING", checked.assess(subject(null), identity).reason().code());
        assertEquals("PROGRESS_CONFLICT", checked.assess(subject("2集全"), identity).reason().code());
        first.put("air_date", "2026-11-21");
        var last = (com.fasterxml.jackson.databind.node.ObjectNode) season.path("episodes").get(1);
        last.put("air_date", "2026-11-22");
        var pending = checked.assess(subject(null), identity).reason();
        assertNotNull(pending);
        assertTrue(pending.message().contains("今日"), pending.message());
        assertNotNull(checked.assess(subject("2集全"), identity).reason(), "同日目录日期不能证明已播出");
        last.put("air_date", "2026-11-21");
        assertNull(checked.assess(subject(null), identity).reason(), "续订系列的目标季可已完结");
        last.remove("air_date");
        assertEquals("PROGRESS_UNKNOWN", checked.assess(subject(null), identity).reason().code());
    }

    @Test void movieOriginalTitleIsOptionalButCatalogTitleYearAndIdentityConflictsAreNot() throws Exception {
        var subject = new Subject(1,"罗斯",null,null,2026,"MOVIE",null,null,1,null,Instant.now());
        when(tmdb.searchChartTitles("罗斯","MOVIE",2026)).thenReturn(json.readTree("""
                [{"id":123,"title":"罗斯","release_date":"2026-04-17"}]
                """));
        var details = json.readTree("{\"id\":123,\"title\":\"罗斯\",\"release_date\":\"2026-04-17\"}");
        when(tmdb.getMovieDetails(123,"zh-CN")).thenReturn(details);
        when(tmdb.getMovieReleaseDates(123)).thenReturn(json.readTree("""
                {"results":[{"release_dates":[{"type":4,"release_date":"2020-01-01T00:00:00Z"}]}]}
                """));
        assertNull(eligibility.assess(subject,null).reason());
        var node = (com.fasterxml.jackson.databind.node.ObjectNode)details;
        node.put("release_date","2025-04-17");
        assertNotNull(eligibility.assess(subject,null).reason());
        node.put("release_date","2026-04-17");
        node.put("title","不同作品");
        assertNotNull(eligibility.assess(subject,null).reason());
        node.put("title","罗斯");
        node.put("id",124);
        assertNotNull(eligibility.assess(subject,null).reason());
        node.put("id",123);
        node.remove("release_date");
        assertNotNull(eligibility.assess(subject,null).reason());
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

    @Test void fullSeasonKeepsExactCoverageWhenExplicitSmallAdvertisementsArePresent() {
        var assessed = new DoubanChartEligibility.Assessment(identity, BgmWishlistService.target(identity), List.of(1,2), null);
        when(tables.knownMagnet(null, magnet)).thenReturn(Optional.of(magnet));
        var files = List.of(new TorrentFileTable.Entry("Show.S02E01.1080p.mkv", 200000000),
                new TorrentFileTable.Entry("Show.S02E02.1080p.mkv", 200000000),
                new TorrentFileTable.Entry("【更多高品质资源请访问 www.Butailing.com】.MP4", 296080));
        var table = new TorrentFileTable(PanSouMagnetIdentity.fromUrl(magnet).orElseThrow(), "Show", files, 400296080);
        when(tables.cached(magnet)).thenReturn(Optional.of(new ReleaseFileTables.Evidence(table, "fixture")));
        assertTrue(eligibility.completeRelease(assessed, release()));
        var incomplete = new DoubanChartEligibility.Assessment(identity, BgmWishlistService.target(identity), List.of(1,2,3), null);
        assertFalse(eligibility.completeRelease(incomplete, release()));
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

    @Test void numberedMovieSubtitleCanResolveOnlyToOneSameYearCatalogIdentity() throws Exception {
        when(tmdb.searchChartTitles("老千4：别西卜之歌", "MOVIE", 2026)).thenReturn(json.readTree("[]"));
        when(tmdb.searchChartTitles("老千：别西卜之歌", "MOVIE", 2026)).thenReturn(json.readTree("""
                [{"id":1394740,"title":"老千：别西卜之歌","original_title":"타짜: 벨제붑의 노래","release_date":"2026-09-23"}]
                """));
        when(tmdb.getMovieDetails(1394740, "zh-CN")).thenReturn(json.readTree("""
                {"id":1394740,"title":"老千：别西卜之歌","original_title":"타짜: 벨제붑의 노래","release_date":"2026-09-23"}
                """));
        var subject = new Subject(37134880,"老千4：别西卜之歌",null,null,2026,"MOVIE",null,null,1,null,Instant.now());
        assertEquals(1394740, eligibility.resolveProgressIdentity(subject, null).tmdbId());
        when(tmdb.searchChartTitles("老千：别西卜之歌", "MOVIE", 2026)).thenReturn(json.readTree("""
                [{"id":1,"title":"老千：别西卜之歌","original_title":"Other","release_date":"2006-09-23"}]
                """));
        assertNull(eligibility.resolveProgressIdentity(subject, null));
    }

    @Test void doubanMovieYearNarrowsSearchButReturnedYearAndUniqueIdentityRemainRequired() throws Exception {
        var subject = new Subject(35322132,"罗斯","Rose",null,2026,"MOVIE",null,null,19,null,Instant.now());
        when(tmdb.searchChartTitles("罗斯", "MOVIE", 2026)).thenReturn(json.readTree("""
                [{"id":1176962,"title":"罗斯","original_title":"Rose","release_date":"2026-04-17"},
                 {"id":2,"title":"罗斯","original_title":"Rose","release_date":"2016-01-01"}]
                """));
        when(tmdb.getMovieDetails(1176962,"zh-CN")).thenReturn(json.readTree("""
                {"id":1176962,"title":"罗斯","original_title":"Rose","release_date":"2026-04-17"}
                """));
        assertEquals(1176962, eligibility.resolveProgressIdentity(subject, null).tmdbId());
        verify(tmdb, never()).searchChartTitles("罗斯", "MOVIE");
        when(tmdb.searchChartTitles("罗斯", "MOVIE", 2026)).thenReturn(json.readTree("""
                [{"id":1176962,"title":"罗斯","original_title":"Rose","release_date":"2026-04-17"},
                 {"id":3,"title":"罗斯","original_title":"Rose","release_date":"2026-01-01"}]
                """));
        assertNull(eligibility.resolveProgressIdentity(subject, null));
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
