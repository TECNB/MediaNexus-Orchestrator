package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Identity;
import com.medianexus.orchestrator.dto.douban.DoubanChartContract.*;
import com.medianexus.orchestrator.integration.douban.DoubanChartClient;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.mapper.DoubanChartMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 公开补证操作 + 真实 Store JSON 往返；不调用真实来源或入库网关。 */
class DoubanChartProgressTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final Map<String,String> rows = new ConcurrentHashMap<>();
    final DoubanChartMapper mapper = mock(DoubanChartMapper.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final DoubanChartClient douban = mock(DoubanChartClient.class);
    final TmdbClient tmdb = mock(TmdbClient.class);
    final DoubanChartEligibility eligibility = new DoubanChartEligibility(tmdb, mock(ReleaseFileTables.class), new MovieSeriesFileRenameService());
    final Instant now = Instant.parse("2026-11-25T04:00:00Z");
    final Clock clock = Clock.fixed(now, DoubanChartStore.ZONE);
    DoubanChartStore store;
    DoubanChartProgress progress;

    @BeforeEach void setup() throws Exception {
        when(transactions.getTransaction(any())).thenAnswer(c -> new SimpleTransactionStatus());
        when(mapper.insert(anyString(), anyString(), nullable(String.class), anyString())).thenAnswer(c -> rows.putIfAbsent(c.getArgument(0),c.getArgument(3)) == null ? 1 : 0);
        when(mapper.select(anyString())).thenAnswer(c -> rows.get(c.getArgument(0)));
        when(mapper.lock(anyString())).thenAnswer(c -> rows.get(c.getArgument(0)));
        doAnswer(c -> { rows.put(c.getArgument(0),c.getArgument(1)); return null; }).when(mapper).update(anyString(),anyString());
        store = new DoubanChartStore(mapper,json,transactions);
        progress = new DoubanChartProgress(store,douban,eligibility,tmdb,clock);
        when(douban.refreshSubject(any())).thenAnswer(c -> c.getArgument(0));
        catalog("Returning Series", "2026-11-24", "2026-11-26", null);
    }
    @AfterEach void close() { progress.close(); }

    @Test void roundIsBoundedRotatesAndGetNeverCallsSources() {
        board(5,null);
        assertEquals("PENDING", visible(1).doubanStatus());
        verifyNoInteractions(douban,tmdb);
        progress.refreshCurrentRound();
        verify(douban,times(3)).refreshSubject(any());
        assertEquals("NOT_PROVIDED",visible(1).doubanStatus());
        assertEquals("AIRING",visible(1).catalog().seasonStatus());
        assertEquals("PENDING",visible(4).doubanStatus());
        progress.refreshCurrentRound();
        verify(douban,times(5)).refreshSubject(any());
        progress.refreshCurrentRound();
        verify(douban,times(5)).refreshSubject(any());
        assertEquals("SUCCESS",store.board(ChartId.tv_hot).refreshStatus());
        DoubanChartStore reload = new DoubanChartStore(mapper,json,transactions);
        try (DoubanChartProgress restarted = new DoubanChartProgress(reload,douban,eligibility,tmdb,clock)) {
            restarted.refreshCurrentRound();
            verify(douban,times(5)).refreshSubject(any());
        }
    }

    @Test void failuresAreIndependentAndDoNotReplaceRawProgressOrHistory() {
        board(3,null);
        when(douban.refreshSubject(argThat(s -> s.id() == 1))).thenThrow(new IllegalStateException("timeout"));
        when(tmdb.getTvDetails(2,"zh-CN")).thenThrow(new IllegalStateException("catalog"));
        progress.refreshCurrentRound();
        assertEquals("FAILED",visible(1).doubanStatus());
        assertEquals("SUCCEEDED",visible(1).catalog().status());
        assertEquals("NOT_PROVIDED",visible(2).doubanStatus());
        assertEquals("FAILED",visible(2).catalog().status());
        assertEquals("SUCCEEDED",visible(3).catalog().status());
        assertNull(store.board(ChartId.tv_hot).candidates().get(0).subject().progress());
        assertNull(store.board(ChartId.tv_hot).candidates().get(0).observations().get(0).progress());
        progress.refreshCurrentRound();
        verify(douban,times(3)).refreshSubject(any());
    }

    @Test void suppliedDetailsAreSeparateAndNewSnapshotNeverReusesOldCompletion() {
        board(1,null);
        when(douban.refreshSubject(any())).thenAnswer(c -> subject(1,"2集全",now));
        progress.refreshCurrentRound();
        assertEquals("SUPPLIED",visible(1).doubanStatus());
        assertEquals("2集全",visible(1).doubanProgress());
        assertEquals("CONFLICT",visible(1).catalog().seasonStatus());
        boardAt(1,null,now.plusSeconds(1));
        assertEquals("PENDING",visible(1).doubanStatus());
        assertNull(visible(1).catalog());
        when(douban.refreshSubject(any())).thenAnswer(c -> c.getArgument(0));
        progress.refreshCurrentRound();
        assertEquals("NOT_PROVIDED",visible(1).doubanStatus());
        assertNull(visible(1).doubanProgress());
    }

    @Test void currentSeasonDatesAndEndEvidenceAreConservative() throws Exception {
        board(1,"更新至1集");
        catalog("Ended","2026-11-24","2026-11-26",null);
        progress.refreshCurrentRound();
        assertEquals("NOT_NEEDED",visible(1).doubanStatus());
        assertEquals("AIRING",visible(1).catalog().seasonStatus());
        assertEquals(2,visible(1).catalog().nextEpisodeNumber());
        assertEquals("2026-11-26",visible(1).catalog().nextAirDate());
        verifyNoInteractions(douban);
        checkSeason("Ended","2026-11-24","2026-11-25",null,"UNKNOWN");
        checkSeason("Ended","2026-11-24","2026-11-24",null,"COMPLETE");
        checkSeason("Returning Series","2026-11-23","2026-11-24",null,"UNKNOWN");
        checkSeason("Returning Series","2026-11-23","2026-11-24","finale","COMPLETE");
        checkSeason("Ended","2026-11-26","2026-11-27",null,"NOT_AIRED");
        checkSeason("Ended","2026-02-30","2026-11-24",null,"UNKNOWN");
    }

    @Test void identityChangesAndMidFlightRefreshDiscardOldResults() {
        board(1,null);
        when(douban.refreshSubject(any())).thenAnswer(c -> {
            store.identity(1,new Identity("SERIES",2,1,"作品","Original",2026));
            return c.getArgument(0);
        });
        progress.refreshCurrentRound();
        assertEquals("PENDING",visible(1).doubanStatus());
        when(douban.refreshSubject(any())).thenAnswer(c -> c.getArgument(0));
        progress.refreshCurrentRound();
        assertEquals(2,visible(1).catalog().identity().tmdbId());
        store.identity(1,new Identity("SERIES",3,1,"作品","Original",2026));
        assertNull(visible(1).catalog());
        when(douban.refreshSubject(any())).thenAnswer(c -> { boardAt(1,null,now.plusSeconds(10)); return c.getArgument(0); });
        progress.refreshCurrentRound();
        assertEquals("PENDING",visible(1).doubanStatus());
    }

    @Test void missingIdentityIsNotCatalogFailureAndMultiSeasonIsNotGuessed() throws Exception {
        board(1,null); store.identity(1,null);
        when(tmdb.searchChartTitles("作品","SERIES")).thenReturn(json.readTree("[{\"id\":1,\"name\":\"作品\",\"original_name\":\"Original\"}]"));
        when(tmdb.getTvDetails(1,"zh-CN")).thenReturn(json.readTree("{\"id\":1,\"seasons\":[{\"season_number\":1,\"air_date\":\"2025-01-01\"},{\"season_number\":2,\"air_date\":\"2026-01-01\"}]}"));
        progress.refreshCurrentRound();
        assertEquals("IDENTITY_REQUIRED",visible(1).catalog().status());
        verify(tmdb,never()).getTvSeasonDetails(anyInt(),anyInt(),anyString());
    }

    @Test void expiredFailuresRetryIndependentlyOfSuccessfulCatalogAndSuccessExpiresAt24Hours() {
        board(1,null);
        when(douban.refreshSubject(any())).thenThrow(new IllegalStateException("timeout"));
        progress.refreshCurrentRound();
        int catalogCalls = mockingDetails(tmdb).getInvocations().size();
        doAnswer(c -> c.getArgument(0)).when(douban).refreshSubject(any());
        try (DoubanChartProgress later = new DoubanChartProgress(store,douban,eligibility,tmdb,Clock.fixed(now.plusSeconds(1800),DoubanChartStore.ZONE))) {
            later.refreshCurrentRound();
            verify(douban,times(2)).refreshSubject(any());
            assertEquals(catalogCalls,mockingDetails(tmdb).getInvocations().size());
        }
        Candidate c = store.board(ChartId.tv_hot).candidates().get(0);
        assertNull(DoubanChartProgress.visible(store.progress(ChartId.tv_hot,1),c.subject(),store.identity(1),now.plus(Duration.ofHours(24))).catalog());
    }

    @Test void persistedPendingAttemptSurvivesRestartButExpiresWithoutRunningForever() {
        board(1,null);
        Subject s = store.board(ChartId.tv_hot).candidates().get(0).subject();
        store.saveProgress(ChartId.tv_hot,new DoubanChartStore.ProgressRecord(s,store.identity(1),
                new ProgressEvidence(s.observedAt(),now,"PENDING",null,now,"等待补查",null)));
        progress.refreshCurrentRound();
        verifyNoInteractions(douban,tmdb);
        try (DoubanChartProgress later = new DoubanChartProgress(store,douban,eligibility,tmdb,Clock.fixed(now.plusSeconds(1800),DoubanChartStore.ZONE))) {
            later.refreshCurrentRound();
            verify(douban).refreshSubject(any());
        }
    }

    @Test void duplicateEpisodesAndWrongSeasonNextEpisodeRemainUnknownOrHidden() throws Exception {
        board(1,null);
        var duplicate = json.readTree("{\"id\":10,\"season_number\":1,\"episodes\":[{\"episode_number\":1},{\"episode_number\":1}]}");
        when(tmdb.getTvSeasonDetails(1,1,"zh-CN")).thenReturn(duplicate);
        progress.refreshCurrentRound();
        assertEquals("SUCCEEDED",visible(1).catalog().status());
        assertEquals("UNKNOWN",visible(1).catalog().seasonStatus());
        assertNull(visible(1).catalog().pastEpisodes());
        boardAt(1,null,now.plusSeconds(1));
        catalog("Returning Series","2026-11-24","2026-11-26",null);
        when(tmdb.getTvDetails(1,"zh-CN")).thenReturn(json.readTree("{\"id\":1,\"name\":\"作品\",\"original_name\":\"Original\",\"seasons\":[{\"season_number\":1,\"episode_count\":2}],\"next_episode_to_air\":{\"season_number\":2,\"episode_number\":2,\"air_date\":\"2026-11-26\"}}"));
        progress.refreshCurrentRound();
        assertEquals("AIRING",visible(1).catalog().seasonStatus());
        assertNull(visible(1).catalog().nextEpisodeNumber());
    }

    @Test void sourceAiringConflictsWithCatalogCompletionAndCountsAreNotGuessed() throws Exception {
        board(1,"更新至1集");
        catalog("Ended","2026-11-23","2026-11-24",null);
        progress.refreshCurrentRound();
        assertEquals("CONFLICT",visible(1).catalog().seasonStatus());
        assertEquals(2,visible(1).catalog().pastEpisodes());
        assertEquals(0,visible(1).catalog().todayEpisodes());
        assertEquals(0,visible(1).catalog().undatedEpisodes());
    }

    @Test void legacyCandidateJsonAndPublicBoardGetAreCompatibleAndNetworkFree() throws Exception {
        board(1,null);
        Candidate c = store.board(ChartId.tv_hot).candidates().get(0);
        var tree = json.valueToTree(c);
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree).remove("progressEvidence");
        assertNull(json.treeToValue(tree,Candidate.class).progressEvidence());
        AuthService auth = mock(AuthService.class);
        DoubanChartService service = new DoubanChartService(store,douban,eligibility,mock(BgmWishlistTaskGateway.class),
                mock(BgmWishlistSessionLocks.class),auth,mock(com.medianexus.orchestrator.mapper.UserMapper.class));
        try {
            assertEquals("PENDING",service.board(ChartId.tv_hot).candidates().get(0).progressEvidence().doubanStatus());
            service.overview();
        } finally { service.close(); }
        verifyNoInteractions(douban,tmdb);
    }

    @Test void conflictIsProjectedFromCurrentSourcesAndNeverStoredInCatalogCache() {
        board(1, null);
        when(douban.refreshSubject(any())).thenAnswer(c -> subject(1, "2集全", now));
        progress.refreshCurrentRound();
        assertEquals("CONFLICT", visible(1).catalog().seasonStatus());
        assertEquals("AIRING", store.progress(ChartId.tv_hot, 1).evidence().catalog().seasonStatus());
        var saved = store.progress(ChartId.tv_hot, 1);
        var e = saved.evidence();
        store.saveProgress(ChartId.tv_hot, new DoubanChartStore.ProgressRecord(saved.subject(), saved.correctedIdentity(),
                new ProgressEvidence(e.sourceObservedAt(), e.checkedAt(), "NOT_PROVIDED", null, e.doubanCheckedAt(), null, e.catalog())));
        assertEquals("AIRING", visible(1).catalog().seasonStatus());
    }

    @Test void explicitSourceEpisodeCountsConflictWithKnownTargetSeasonTotals() throws Exception {
        board(1, "12集全");
        catalog("Ended", "2026-11-23", "2026-11-24", "finale");
        progress.refreshCurrentRound();
        assertEquals("CONFLICT", visible(1).catalog().seasonStatus());
        boardAt(1, "更新至3集", now.plusSeconds(1));
        catalog("Returning Series", "2026-11-24", "2026-11-26", null);
        progress.refreshCurrentRound();
        assertEquals("CONFLICT", visible(1).catalog().seasonStatus());
    }

    @Test void expiredEvidenceInterruptedDuringRefreshUsesPendingLeaseAfterRestart() {
        board(1, null);
        progress.refreshCurrentRound();
        Clock laterClock = Clock.fixed(now.plus(Duration.ofHours(24)), DoubanChartStore.ZONE);
        when(douban.refreshSubject(any())).thenThrow(new AssertionError("simulated process interruption"));
        try (DoubanChartProgress later = new DoubanChartProgress(store, douban, eligibility, tmdb, laterClock)) {
            assertThrows(AssertionError.class, later::refreshCurrentRound);
        }
        clearInvocations(douban, tmdb);
        try (DoubanChartProgress restarted = new DoubanChartProgress(store, douban, eligibility, tmdb, laterClock)) {
            restarted.refreshCurrentRound();
            verifyNoInteractions(douban, tmdb);
        }
        Subject s = store.board(ChartId.tv_hot).candidates().get(0).subject();
        assertEquals("PENDING", DoubanChartProgress.visible(store.progress(ChartId.tv_hot, 1), s, store.identity(1), laterClock.instant()).doubanStatus());
        assertNull(DoubanChartProgress.visible(store.progress(ChartId.tv_hot, 1), s, store.identity(1), laterClock.instant()).catalog());
    }

    private void checkSeason(String series,String first,String second,String finale,String expected) throws Exception {
        boardAt(1,null,store.board(ChartId.tv_hot).candidates().get(0).subject().observedAt().plusSeconds(1));
        catalog(series,first,second,finale); progress.refreshCurrentRound();
        assertEquals(expected,visible(1).catalog().seasonStatus());
    }
    private ProgressEvidence visible(long id) {
        Candidate c = store.board(ChartId.tv_hot).candidates().stream().filter(v -> v.subject().id() == id).findFirst().orElseThrow();
        return DoubanChartProgress.visible(store.progress(ChartId.tv_hot,id),c.subject(),store.identity(id),clock.instant());
    }
    private Subject subject(long id,String raw,Instant observed) { return new Subject(id,"作品","Original",null,2026,"SERIES",null,null,(int)id,raw,observed); }
    private void board(int count,String raw) { boardAt(count,raw,now); }
    private void boardAt(int count,String raw,Instant observed) {
        List<Candidate> candidates = new ArrayList<>();
        for (int i=1;i<=count;i++) {
            Subject s=subject(i,raw,observed);
            candidates.add(new Candidate(s,true,now,now,i,false,List.of(new Observation(now,raw)),null,null));
            if (store.identity(i) == null) store.identity(i,new Identity("SERIES",i,1,"作品","Original",2026));
        }
        store.updateBoard(ChartId.tv_hot,b -> new Board(b.id(),b.label(),false,null,"SUCCESS",null,now,now,null,candidates));
    }
    private void catalog(String status,String first,String second,String finale) throws Exception {
        when(tmdb.getTvDetails(anyInt(),eq("zh-CN"))).thenAnswer(c -> json.readTree("{\"id\":"+c.getArgument(0)+",\"name\":\"作品\",\"original_name\":\"Original\",\"status\":\""+status+"\",\"next_episode_to_air\":{\"season_number\":1,\"episode_number\":2,\"air_date\":\""+second+"\"},\"seasons\":[{\"season_number\":1,\"episode_count\":2,\"air_date\":\"2026-01-01\"}]}"));
        when(tmdb.getTvSeasonDetails(anyInt(),eq(1),eq("zh-CN"))).thenReturn(json.readTree("{\"id\":10,\"season_number\":1,\"episodes\":[{\"episode_number\":1,\"season_number\":1,\"air_date\":\""+first+"\"},{\"episode_number\":2,\"season_number\":1,\"air_date\":\""+second+"\",\"episode_type\":\""+finale+"\"}]}"));
    }
}
