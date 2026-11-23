package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.medianexus.orchestrator.dto.resources.request.*;
import com.medianexus.orchestrator.dto.resources.response.*;
import com.medianexus.orchestrator.integration.guangya.*;
import com.medianexus.orchestrator.integration.pansou.*;
import com.medianexus.orchestrator.integration.prowlarr.ProwlarrClient;
import com.medianexus.orchestrator.model.User;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReleaseCandidateComparisonTest {
    private final PanSouClient client = mock(PanSouClient.class);
    private final GuangyaClient guangya = mock(GuangyaClient.class);
    private final AuthService auth = mock(AuthService.class);
    private final ReleaseTitleTagParser tags = new ReleaseTitleTagParser();
    private final SettingsService settings = mock(SettingsService.class);
    private final ProwlarrReleaseSearchCache prowlarr = mock(ProwlarrReleaseSearchCache.class);
    private final ProwlarrClient originalClient = mock(ProwlarrClient.class);
    private final ReleaseFileTables tables = new ReleaseFileTables(guangya, originalClient);
    private final ReleaseCandidateComparison search = new ReleaseCandidateComparison(
            new ReleaseCandidateDiscovery(client, prowlarr, settings, tags, tables), tags, tables,
            new ReleaseFileAssessment(new MovieSeriesFileRenameService(), tags), auth);
    private final SeriesReleaseSearchRequest request = new SeriesReleaseSearchRequest(null, null, null, "Show", "Original", 1, "1080p");

    @BeforeEach void setup() {
        when(settings.usesPanSouForMagnetSearch()).thenReturn(true);
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of()));
        User user = new User(); user.setId(1L); when(auth.requireCurrentUser()).thenReturn(user);
        when(guangya.resolve(anyString(), any())).thenAnswer(call -> table(call.getArgument(0), "Show Season 1 1080p", "Show.S01E01.1080p.mkv"));
        when(originalClient.resolveTorrent(any(), any())).thenThrow(new com.medianexus.orchestrator.integration.prowlarr.ProwlarrClientException(
                com.medianexus.orchestrator.integration.prowlarr.ProwlarrClientException.Reason.MAGNET_RESOLUTION, "无原始文件表"));
    }
    @AfterEach void close() { search.close(); }

    @Test void verifiedSeriesEnglishNameReachesBoundedSearchAndFinalSubmission() throws Exception {
        var tmdb = mock(com.medianexus.orchestrator.integration.tmdb.TmdbClient.class);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        when(tmdb.getTvDetails(293610, "zh-CN")).thenReturn(json.readTree("""
                {"id":293610,"name":"Doctor X：白色黑手党时代","original_name":"닥터X: 하얀 마피아의 시대","seasons":[{"season_number":1}]}
                """));
        when(tmdb.getTvDetails(293610, "en-US")).thenReturn(json.readTree("""
                {"id":293610,"name":"Doctor X: Mafia in White","original_name":"닥터X: 하얀 마피아의 시대","seasons":[{"season_number":1}]}
                """));
        var aliases = new SeriesReleaseAliases(tmdb);
        org.springframework.test.util.ReflectionTestUtils.setField(search, "seriesAliases", aliases);
        var safety = new ReleaseSubmissionSafety(tables, new ReleaseFileAssessment(new MovieSeriesFileRenameService(), tags));
        org.springframework.test.util.ReflectionTestUtils.setField(safety, "seriesAliases", aliases);
        search.shareSubmissionEvidence(safety);
        var release = new com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease(
                "Doctor X Mafia in White S01 1080p", 200000000L, 5, null, null, "Indexer", null, 7, "guid", magnet(2));
        when(prowlarr.search("Doctor X: Mafia in White S01")).thenReturn(List.of(release));
        when(guangya.resolve(eq(magnet(2)), any())).thenReturn(table(magnet(2), "Doctor X Mafia in White S01 1080p",
                "Doctor.X.Mafia.in.White.S01E01.1080p.mkv", "Doctor.X.Mafia.in.White.S01E02.1080p.mkv"));
        var progress = new java.util.concurrent.CopyOnWriteArrayList<ProwlarrReleaseIngestService.ReleaseSearchProgress>();
        var result = search.recommendSeries(new SeriesReleaseRecommendationRequest(null, 293610, null,
                "Doctor X：白色黑手党时代", "닥터X: 하얀 마피아의 시대", 1, "1080p"), progress::add);
        assertThat(result.item().downloadRef()).isEqualTo(magnet(2));
        assertThat(result.comparison().searchQueries()).isLessThanOrEqualTo(4);
        assertThat(progress.stream().filter(p -> p.query().equals("Doctor X: Mafia in White S01")).toList())
                .extracting(ProwlarrReleaseIngestService.ReleaseSearchProgress::status).containsExactly("RUNNING", "SUCCEEDED");
        assertThat(progress.stream().filter(p -> p.query().equals("Doctor X: Mafia in White S01")).toList())
                .allSatisfy(p -> assertThat(p.source()).isEqualTo("补充来源"));
        var target = ReleaseTarget.of("Doctor X：白色黑手党时代", "닥터X: 하얀 마피아의 시대", null, null, 1, "1080p");
        assertThat(safety.checkSeries(293610, magnet(2), target, ReleaseIngestMetadata.manual())).contains("RECOMMENDABLE");
        safety.rememberSourceTitles(magnet(2), List.of("Doctor X Mafia in White S02 1080p"));
        assertThatThrownBy(() -> safety.checkSeries(293610, magnet(2), target, ReleaseIngestMetadata.manual()))
                .hasMessageContaining("季编号");
    }

    @Test void panSouYearDeclarationsSurviveRecommendationAndFinalSubmission() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(new PanSouSearchEntry(
                magnet(1), "", "电影", null, "plugin:dygang", null, false,
                List.of("电影 - 1080p.HD中字.mp4 (2026)")))));
        when(guangya.resolve(eq(magnet(1)), any())).thenReturn(table(magnet(1), "电影.1080p", "电影.1080p.mkv"));
        var safety = new ReleaseSubmissionSafety(tables, new ReleaseFileAssessment(new MovieSeriesFileRenameService(), tags));
        search.shareSubmissionEvidence(safety);
        var result = search.recommendMovie(new MovieReleaseRecommendationRequest(null, null, "电影", null, 2026, "1080p"));
        assertThat(result.item().evidence().status()).isEqualTo("RECOMMENDABLE");
        assertThat(safety.check(magnet(1), ReleaseTarget.of("电影", null, null, 2026, null, null), ReleaseIngestMetadata.manual()))
                .contains("RECOMMENDABLE");
        verifyNoInteractions(prowlarr);
    }

    @Test void movieSearchSupplementsMissingPreferredSourceWithinFourQueries() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of()));
        var release = new com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease(
                "Arrival 2016 1080p", 100L, 5, null, null, "Indexer", null, 7, "guid", magnet(2));
        when(prowlarr.search("Arrival 2016")).thenReturn(List.of(release));
        when(guangya.resolve(eq(magnet(2)), any())).thenAnswer(call -> {
            ResolveBudget budget = call.getArgument(1);
            assertThat(java.time.Duration.between(java.time.Instant.now(), budget.totalDeadline()).toSeconds()).isBetween(40L, 45L);
            return table(magnet(2), "Arrival 2016 1080p", "Arrival.2016.1080p.mkv");
        });
        var result = search.recommendMovie(new MovieReleaseRecommendationRequest(null, null, "降临", "Arrival", 2016, "1080p"));
        assertThat(result.item().downloadRef()).isEqualTo(magnet(2));
        assertThat(result.comparison().searchQueries()).isLessThanOrEqualTo(4);
        verify(prowlarr).search("Arrival 2016");
    }

    @Test void moviePrimaryUsesPlainPanSouTitleButKeepsYearForSupplementAndIdentity() {
        when(client.searchMagnets(any())).thenAnswer(call -> new PanSouSearchResult(
                call.<PanSouSearchCommand>getArgument(0).keyword().equals("狗狗的完美圣诞节")
                        ? List.of(entry(1, "狗狗的完美圣诞节 2026 1080p", null)) : List.of()));
        when(guangya.resolve(eq(magnet(1)), any())).thenReturn(table(magnet(1),
                "狗狗的完美圣诞节 2026 1080p", "狗狗的完美圣诞节.2026.1080p.mkv"));
        var result = search.recommendMovie(new MovieReleaseRecommendationRequest(null, null,
                "狗狗的完美圣诞节", "A Dog's Perfect Christmas", 2026, "1080p"));
        assertThat(result.item().evidence().status()).isEqualTo("RECOMMENDABLE");
        verify(client).searchMagnets(new PanSouSearchCommand("狗狗的完美圣诞节", false));
        verifyNoInteractions(prowlarr);
        assertThat(result.comparison().searchQueries()).isLessThanOrEqualTo(4);
    }

    @Test void verifiedMoviePrimaryDoesNotWaitForUnneededSupplement() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(entry(1, "Arrival 2016 1080p", null))));
        when(guangya.resolve(eq(magnet(1)), any())).thenReturn(table(magnet(1), "Arrival 2016 1080p", "Arrival.2016.1080p.mkv"));
        var result = search.recommendMovie(new MovieReleaseRecommendationRequest(null, null, "Arrival", null, 2016, "1080p"));
        assertThat(result.item().evidence().status()).isEqualTo("RECOMMENDABLE");
        verifyNoInteractions(prowlarr);
    }

    @Test void emptyMoviePrimaryDoesNotSpendResolutionBudgetDuringSupplementSearch() {
        var supplementFinished = new java.util.concurrent.atomic.AtomicReference<java.time.Instant>();
        var release = new com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease(
                "Arrival 2016 1080p", 100L, 5, null, null, "Indexer", null, 7, "guid", magnet(2));
        when(prowlarr.search(anyString())).thenAnswer(call -> {
            Thread.sleep(150);
            supplementFinished.set(java.time.Instant.now());
            return List.of(release);
        });
        when(guangya.resolve(eq(magnet(2)), any())).thenAnswer(call -> {
            ResolveBudget budget = call.getArgument(1);
            assertThat(budget.resolveDeadline()).isAfter(supplementFinished.get().plusMillis(29950));
            assertThat(java.time.Duration.between(java.time.Instant.now(), budget.totalDeadline()).toSeconds()).isBetween(40L, 45L);
            return table(magnet(2), "Arrival 2016 1080p", "Arrival.2016.1080p.mkv");
        });
        var result = search.recommendMovie(new MovieReleaseRecommendationRequest(null, null, "降临", "Arrival", 2016, "1080p"));
        assertThat(result.item().evidence().status()).isEqualTo("RECOMMENDABLE");
        assertThat(result.comparison().searchQueries()).isLessThanOrEqualTo(4);
    }

    @Test void seriesResolutionBudgetStartsAfterSupplementSearch() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(entry(1, "Show S01 1080p", null))));
        var supplementFinished = new java.util.concurrent.atomic.AtomicReference<java.time.Instant>();
        when(prowlarr.search(anyString())).thenAnswer(call -> {
            Thread.sleep(150);
            supplementFinished.set(java.time.Instant.now());
            return List.of();
        });
        when(guangya.resolve(eq(magnet(1)), any())).thenAnswer(call -> {
            ResolveBudget budget = call.getArgument(1);
            assertThat(java.time.Duration.between(java.time.Instant.now(), budget.totalDeadline()).toSeconds()).isBetween(70L, 75L);
            assertThat(budget.resolveDeadline()).isAfter(supplementFinished.get().plusMillis(29950));
            return table(magnet(1), "Show S01 1080p", "Show.S01E01.1080p.mkv");
        });
        var result = search.recommendSeries(new SeriesReleaseRecommendationRequest(null, null, null, "Show", null, 1, "1080p"));
        assertThat(result.item().evidence().status()).isEqualTo("RECOMMENDABLE");
        assertThat(result.comparison().searchQueries()).isLessThanOrEqualTo(4);
    }

    @Test void cachedPartialSeriesStillSearchesSupplement() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(entry(1, "Show S01 1080p", null))));
        when(guangya.cached(magnet(1))).thenReturn(Optional.of(table(magnet(1), "Show S01 1080p", "Show.S01E01.mkv")));
        var release = new com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease(
                "Show S01 Complete 1080p", 200000000L, 5, null, null, "Indexer", null, 7, "guid", magnet(2));
        when(prowlarr.search("Show S01")).thenReturn(List.of(release));
        var result = search.recommendSeries(new SeriesReleaseRecommendationRequest(null, null, null, "Show", "Original", 1, "1080p"));
        assertThat(result.items()).extracting(ProwlarrReleaseItemResponse::downloadRef).contains(magnet(2));
    }

    @Test void seriesRetainsNormalizedTitleSearchWithinFourQueries() {
        when(client.searchMagnets(any())).thenAnswer(call -> new PanSouSearchResult(
                call.<PanSouSearchCommand>getArgument(0).keyword().equals("AxB") ? List.of(entry(1, "A×B S01 1080p", null)) : List.of()));
        when(guangya.resolve(eq(magnet(1)), any())).thenReturn(table(magnet(1), "A×B S01 1080p", "A×B.S01E01.mkv"));
        var result = search.recommendSeries(new SeriesReleaseRecommendationRequest(null, null, null, "A×B", null, 1, "1080p"));
        assertThat(result.item().downloadRef()).isEqualTo(magnet(1));
        assertThat(result.comparison().searchQueries()).isLessThanOrEqualTo(4);
    }

    @Test void seriesSupplementIncludesFullPackWhenPreferredSourceOnlyHasPartialEpisodes() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(entry(1, "Show S01 1080p", null))));
        when(guangya.resolve(eq(magnet(1)), any())).thenReturn(table(magnet(1), "Show S01 1080p", "Show.S01E01.1080p.mkv"));
        var release = new com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease(
                "Show S01 Complete 1080p", 200000000L, 5, null, null, "Indexer", null, 7, "guid", magnet(2));
        when(prowlarr.search("Show S01")).thenReturn(List.of(release));
        when(guangya.resolve(eq(magnet(2)), any())).thenReturn(table(magnet(2), "Show S01 1080p",
                "Show.S01E01.1080p.mkv", "Show.S01E02.1080p.mkv"));
        var result = search.recommendSeries(new SeriesReleaseRecommendationRequest(null, null, null, "Show", "Original", 1, "1080p"));
        assertThat(result.items()).anySatisfy(item -> {
            assertThat(item.downloadRef()).isEqualTo(magnet(2));
            assertThat(item.evidence().targetFiles()).isEqualTo(2);
        });
        assertThat(result.comparison().searchQueries()).isLessThanOrEqualTo(4);
    }

    @Test void disabledSupplementRemainsNormalNoRelease() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of()));
        when(prowlarr.search(anyString())).thenThrow(new com.medianexus.orchestrator.integration.prowlarr.ProwlarrClientException(
                com.medianexus.orchestrator.integration.prowlarr.ProwlarrClientException.Reason.CONFIGURATION, "disabled"));
        assertThatThrownBy(() -> search.recommendMovie(new MovieReleaseRecommendationRequest(null, null, "电影", null, 2026, "1080p")))
                .isInstanceOfSatisfying(ProwlarrReleaseIngestService.NoMatchingReleaseException.class,
                        failure -> assertThat(failure.reasonCode()).isEqualTo("NO_RELEASE"));
    }

    @Test void refreshVerifiesUsableMoviePrimaryWithoutUnneededSupplement() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(entry(1, "电影 2026 1080p", null))));
        when(prowlarr.refreshQueries(anyList())).thenAnswer(call -> Map.of(((List<String>)call.getArgument(0)).get(0), List.of()));
        when(guangya.resolve(eq(magnet(1)), any())).thenReturn(table(magnet(1), "电影 2026 1080p", "电影.2026.1080p.mkv"));
        var result = search.refreshRecommendMovie(new MovieReleaseRecommendationRequest(null, null, "电影", null, 2026, "1080p"));
        assertThat(result.item().evidence().status()).isEqualTo("RECOMMENDABLE");
        verify(guangya).resolve(eq(magnet(1)), any());
        verifyNoInteractions(prowlarr);
    }

    @Test void sameHashConflictingSourceYearStillPreventsAutomaticMovieSubmission() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(new PanSouSearchEntry(
                magnet(1), "", "电影", null, "plugin:dygang", null, false,
                List.of("电影 2026 1080p", "电影 2001 1080p")))));
        when(prowlarr.search(anyString())).thenReturn(List.of());
        assertThatThrownBy(() -> search.recommendMovie(new MovieReleaseRecommendationRequest(null, null, "电影", null, 2026, "1080p")))
                .isInstanceOf(ProwlarrReleaseIngestService.NoMatchingReleaseException.class);
        verify(guangya, never()).resolve(eq(magnet(1)), any());
    }

    @Test void waitsForAllDiscoveredCandidatesAndNormalVersionOutranksPreviewBeforeQuality() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(
                entry(1, "Show S01 Complete 1080p 尝鲜版", 999L),
                entry(2, "Show S01 2160p", 500L),
                entry(3, "Show S01 1080p", 100L))));
        when(guangya.resolve(eq(magnet(1)), any())).thenReturn(table(magnet(1), "Show Season 1 1080p", "Show.S01E01.1080p.尝鲜版.mkv"));
        when(guangya.resolve(eq(magnet(2)), any())).thenReturn(table(magnet(2), "Show Season 1 2160p", "Show.S01E01.2160p.mkv"));
        var recommendation = search.recommendSeries(new SeriesReleaseRecommendationRequest(null, null, null, "Show", "Original", 1, "1080p"));
        assertThat(recommendation.items()).extracting(ProwlarrReleaseItemResponse::downloadRef).containsExactly(magnet(3), magnet(2), magnet(1));
        verify(guangya, times(3)).resolve(anyString(), any());
        assertThat(recommendation.comparison().status()).isEqualTo("COMPLETE");
        assertThat(recommendation.comparison().pending()).isZero();
    }

    @Test void searchReturnsBeforeFileResolutionAndPublishesProgressWithoutAutoContinuation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(entry(1, "Show", null))));
        doAnswer(call -> {
            entered.countDown(); release.await(2, TimeUnit.SECONDS);
            return table(magnet(1), "Show Season 1 HD1080P", "Show.S01E01.mkv");
        }).when(guangya).resolve(anyString(), any());
        try {
            var initial = search.searchSeries(request);
            assertThat(initial.comparison().status()).isEqualTo("RUNNING");
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var progress = search.comparison(initial.comparison().id());
            assertThat(progress.items()).hasSize(1);
            assertThat(progress.items().get(0).evidence().targetFiles()).isNull();
            release.countDown();
            assertThat(await(initial.comparison().id()).items().get(0).evidence().status()).isEqualTo("RECOMMENDABLE");
        } finally { release.countDown(); }
    }

    @Test void movieContinuationResetsExpiredDeadlineToFortyFiveSeconds() throws Exception {
        var initial = search.searchMovie(new MovieReleaseSearchRequest(null, null, "Arrival", null, 2016, "1080p"));
        await(initial.comparison().id());
        var cache = (com.google.common.cache.Cache<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(search, "comparisons");
        Object comparison = cache.getIfPresent(initial.comparison().id());
        org.springframework.test.util.ReflectionTestUtils.setField(comparison, "status", "PAUSED");
        org.springframework.test.util.ReflectionTestUtils.setField(comparison, "deadline", java.time.Instant.EPOCH);
        search.continueComparison(initial.comparison().id());
        java.time.Instant deadline = (java.time.Instant) org.springframework.test.util.ReflectionTestUtils.getField(comparison, "deadline");
        assertThat(java.time.Duration.between(java.time.Instant.now(), deadline).toSeconds()).isBetween(40L, 45L);
        await(initial.comparison().id());
    }

    @Test void budgetPauseRetainsCurrentChoiceAndOnlyExplicitContinueEvaluatesRemaining() throws Exception {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(entry(1, "Show Complete 1080p", 100L), entry(2, "Show", null), entry(3, "Show S1-S4", null))));
        when(guangya.resolve(eq(magnet(2)), any())).thenThrow(new GuangyaException(GuangyaException.Reason.BUDGET_EXHAUSTED, "预算暂停"));
        var initial = search.searchSeries(request);
        var paused = await(initial.comparison().id());
        assertThat(paused.comparison().status()).isEqualTo("PAUSED");
        assertThat(paused.comparison().recommendedRef()).isEqualTo(magnet(1));
        assertThat(paused.comparison().pending()).isEqualTo(2);
        verify(guangya, never()).resolve(eq(magnet(3)), any());
        search.comparison(initial.comparison().id());
        verify(guangya, never()).resolve(eq(magnet(3)), any());
        when(guangya.resolve(eq(magnet(2)), any())).thenAnswer(call -> {
            ResolveBudget budget = call.getArgument(1);
            assertThat(java.time.Duration.between(java.time.Instant.now(), budget.totalDeadline()).toSeconds()).isBetween(70L, 75L);
            return table(magnet(2), "Show Season 1 1080p", "Show.S01E01.mkv");
        });
        var cache = (com.google.common.cache.Cache<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(search, "comparisons");
        org.springframework.test.util.ReflectionTestUtils.setField(cache.getIfPresent(initial.comparison().id()), "deadline", java.time.Instant.EPOCH);
        search.continueComparison(initial.comparison().id());
        assertThat(await(initial.comparison().id()).comparison().status()).isEqualTo("COMPLETE");
        verify(guangya, times(1)).resolve(eq(magnet(1)), any());
        verify(guangya, times(1)).resolve(eq(magnet(3)), any());
    }

    @Test void automationNeverReturnsCandidateWhileQueueIsPaused() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(entry(1, "Show", null))));
        doThrow(new GuangyaException(GuangyaException.Reason.BUDGET_EXHAUSTED, "预算暂停")).when(guangya).resolve(anyString(), any());
        assertThatThrownBy(() -> search.recommendSeries(new SeriesReleaseRecommendationRequest(null, null, null, "Show", null, 1, "1080p")))
                .isInstanceOfSatisfying(ProwlarrReleaseIngestService.NoMatchingReleaseException.class, failure -> {
                    assertThat(failure.reasonCode()).isEqualTo("EVIDENCE_BUDGET_EXHAUSTED");
                    assertThat(failure.candidates().get(0).evidence().targetFiles()).isNull();
                });
    }

    @Test void automationCanUseVerifiedReleaseWhenOtherCandidatesReachLimit() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(
                entry(1, "Show S01 1080p", 100L), entry(2, "Show S01 1080p", 200L))));
        when(guangya.resolve(eq(magnet(2)), any())).thenThrow(new GuangyaException(GuangyaException.Reason.BUDGET_EXHAUSTED, "本轮解析次数达到上限"));
        var recommendation = search.recommendSeries(new SeriesReleaseRecommendationRequest(null, null, null, "Show", null, 1, "1080p"));
        assertThat(recommendation.item().downloadRef()).isEqualTo(magnet(1));
        assertThat(recommendation.item().evidence().status()).isEqualTo("RECOMMENDABLE");
        assertThat(recommendation.comparison().status()).isEqualTo("PAUSED");
        assertThat(recommendation.comparison().pending()).isPositive();
    }

    @Test void failedSearchPublishesTerminalProgressAndDoesNotMasqueradeAsResolutionLimit() {
        var progress = new java.util.concurrent.CopyOnWriteArrayList<ProwlarrReleaseIngestService.ReleaseSearchProgress>();
        when(client.searchMagnets(any())).thenAnswer(call -> {
            if (call.<PanSouSearchCommand>getArgument(0).keyword().equals("Original")) throw new IllegalStateException("offline");
            return new PanSouSearchResult(List.of());
        });
        assertThatThrownBy(() -> search.recommendSeries(new SeriesReleaseRecommendationRequest(null, null, null, "Show", "Original", 1, "1080p"), progress::add))
                .isInstanceOfSatisfying(ProwlarrReleaseIngestService.NoMatchingReleaseException.class,
                        failure -> assertThat(failure.reasonCode()).isEqualTo("SEARCH_UNAVAILABLE"));
        assertThat(progress).anySatisfy(item -> {
            assertThat(item.query()).isEqualTo("Original");
            assertThat(item.status()).isEqualTo("FAILED");
        });
        assertThat(progress.stream().filter(item -> item.query().equals("Original")).reduce((a, b) -> b).orElseThrow().status()).isEqualTo("FAILED");
    }

    @Test void missingFileMetadataConfigurationIsExplicitAndHasNoDeadSnapshotInstruction() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(entry(1, "Show", null))));
        doThrow(new GuangyaException(GuangyaException.Reason.NOT_CONFIGURED, "文件清单解析服务尚未配置")).when(guangya).resolve(anyString(), any());
        assertThatThrownBy(() -> search.recommendSeries(new SeriesReleaseRecommendationRequest(null, null, null, "Show", null, 1, "1080p")))
                .isInstanceOfSatisfying(ProwlarrReleaseIngestService.NoMatchingReleaseException.class, failure -> {
                    assertThat(failure.reasonCode()).isEqualTo("EVIDENCE_NOT_CONFIGURED");
                    assertThat(failure.getMessage()).contains("尚未配置").doesNotContain("比较快照=", "继续比较");
                });
    }

    @Test void usableTorrentFallbackSurvivesAnotherCandidateMissingMetadataConfiguration() {
        when(settings.usesPanSouForMagnetSearch()).thenReturn(false);
        var missing = new com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease("Show S01 1080p", 100L, 5, null, null, "Indexer", null, 7, "missing", magnet(1));
        var usable = new com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease("Show S01 1080p", 100L, 10, null, null, "Indexer", null, 7, "usable", magnet(2));
        when(prowlarr.search(anyString())).thenReturn(List.of(missing, usable));
        doThrow(new GuangyaException(GuangyaException.Reason.NOT_CONFIGURED, "文件清单解析服务尚未配置")).when(guangya).resolve(anyString(), any());
        doReturn(new com.medianexus.orchestrator.integration.prowlarr.ResolvedTorrent(magnet(1), Optional.empty())).when(originalClient).resolveTorrent(eq(missing), any());
        doReturn(new com.medianexus.orchestrator.integration.prowlarr.ResolvedTorrent(magnet(2), Optional.of(table(magnet(2), "Show S01 1080p", "Show.S01E01.mkv")))).when(originalClient).resolveTorrent(eq(usable), any());
        var result = search.recommendSeries(new SeriesReleaseRecommendationRequest(null, null, null, "Show", null, 1, "1080p"));
        assertThat(result.item().downloadRef()).isEqualTo(magnet(2));
        assertThat(result.item().seeders()).isPositive();
    }

    @Test void verifiedMovieSurvivesEarlierFailedCandidateAndPassesCommonSubmissionSafety() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(
                entry(1, "Arrival 2016 1080p", 100L), entry(2, "Arrival 2016 1080p", 200L))));
        doThrow(new GuangyaException(GuangyaException.Reason.BUDGET_EXHAUSTED, "本轮文件核验达到上限")).when(guangya).resolve(eq(magnet(1)), any());
        doReturn(table(magnet(2), "Arrival 2016 1080p", "Arrival.2016.1080p.mkv")).when(guangya).resolve(eq(magnet(2)), any());
        var safety = new ReleaseSubmissionSafety(tables, new ReleaseFileAssessment(new MovieSeriesFileRenameService(), tags));
        search.shareSubmissionEvidence(safety);
        var result = search.recommendMovie(new MovieReleaseRecommendationRequest(null, null, "Arrival", null, 2016, "1080p"));
        assertThat(result.item().downloadRef()).isEqualTo(magnet(2));
        assertThat(result.comparison().status()).isEqualTo("PAUSED");
        assertThat(safety.check(magnet(2), ReleaseTarget.of("Arrival", null, null, 2016, null, "1080p"), ReleaseIngestMetadata.manual()))
                .contains("RECOMMENDABLE");
    }

    @Test void mergesHashesPreservesConflictingSourcesAndCannotWashKnownMovieIdentity() throws Exception {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(
                entry(1, "Show 1080p", 100L), entry(1, "Show Movie 1080p", 500L))));
        var initial = search.searchSeries(request);
        var complete = await(initial.comparison().id());
        assertThat(complete.items()).singleElement().satisfies(item -> {
            assertThat(item.evidence().status()).isEqualTo("UNSUITABLE");
            assertThat(item.matchSource()).contains("Show Movie", "体积冲突");
            assertThat(item.size()).isNull();
        });
        verifyNoInteractions(guangya);
    }

    @Test void titlePollutionCanBeSupplementedButNotAcceptedWithoutMatchingFileIdentity() throws Exception {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(entry(1, "63-gatsu no Lion 2nd Season 1080p", null))));
        doReturn(table(magnet(1), "3-gatsu no Lion 2nd Season 1080p", "3-gatsu no Lion.S02E01.mkv")).when(guangya).resolve(anyString(), any());
        var initial = search.searchSeries(new SeriesReleaseSearchRequest(null, null, null, "三月的狮子", "3-gatsu no Lion", 2, "1080p"));
        assertThat(await(initial.comparison().id()).items()).singleElement().satisfies(item -> assertThat(item.evidence().status()).isEqualTo("RECOMMENDABLE"));
    }

    @Test void rejectedOnlyCandidateTriggersBoundedAliasSupplementAndUnknownIsNotZero() throws Exception {
        Set<String> queries = ConcurrentHashMap.newKeySet();
        when(client.searchMagnets(any())).thenAnswer(call -> {
            var command = call.<PanSouSearchCommand>getArgument(0); queries.add(command.keyword());
            return new PanSouSearchResult(command.keyword().equals("发行名") ? List.of(entry(2, "发行名 1080p", 100L)) : List.of(entry(1, "Show Movie 1080p", null)));
        });
        when(guangya.resolve(eq(magnet(2)), any())).thenReturn(table(magnet(2), "发行名 Season 1 1080p", "发行名.S01E01.mkv"));
        var initial = search.searchSeries(new SeriesReleaseSearchRequest(null, null, null, "Show", "Original", 1, "1080p", "发行名"));
        var complete = await(initial.comparison().id());
        assertThat(complete.comparison().recommendedRef()).isEqualTo(magnet(2));
        assertThat(queries).contains("发行名").hasSizeLessThanOrEqualTo(4);
    }

    @Test void snapshotIsIsolatedByUser() {
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of()));
        var initial = search.searchSeries(request);
        User other = new User(); other.setId(2L); when(auth.requireCurrentUser()).thenReturn(other);
        assertThatThrownBy(() -> search.comparison(initial.comparison().id())).isInstanceOf(ProwlarrReleaseIngestService.NoMatchingReleaseException.class);
    }

    @Test void explicitContinueRetriesOnlyFailedSearchAndCanRecover() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        when(client.searchMagnets(any())).thenAnswer(call -> {
            String query = call.<PanSouSearchCommand>getArgument(0).keyword();
            if (query.equals("Original") && failures.getAndIncrement() == 0) throw new IllegalStateException("temporary search outage");
            return new PanSouSearchResult(List.of(entry(1, "Show 1080p", 100L)));
        });
        var initial = search.searchSeries(request);
        assertThat(await(initial.comparison().id()).comparison().status()).isEqualTo("BLOCKED");
        search.continueComparison(initial.comparison().id());
        assertThat(await(initial.comparison().id()).comparison().status()).isEqualTo("COMPLETE");
        verify(client, times(1)).searchMagnets(new PanSouSearchCommand("Show", false));
        verify(client, times(2)).searchMagnets(new PanSouSearchCommand("Original", false));
    }

    @Test void manualRefreshPreservesPausedSnapshotAndUnsafeEvidenceReachesRawSubmission() throws Exception {
        var safety = new ReleaseSubmissionSafety(tables, new ReleaseFileAssessment(new MovieSeriesFileRenameService(), tags));
        search.shareSubmissionEvidence(safety);
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(entry(1, "Show 1080p", 100L))));
        doThrow(new GuangyaException(GuangyaException.Reason.INVALID_RESPONSE, "unsafe path")).when(guangya).resolve(anyString(), any());
        var refreshed = search.refreshRecommendSeries(new SeriesReleaseRecommendationRequest(null, null, null, "Show", null, 1, "1080p"));
        assertThat(refreshed.item()).isNull();
        assertThat(refreshed.items()).hasSize(1);
        doThrow(new GuangyaException(GuangyaException.Reason.UNAVAILABLE, "offline")).when(guangya).resolve(anyString(), any());
        assertThatThrownBy(() -> safety.check(magnet(1), ReleaseTarget.of("Show", null, null, null, 1, null),
                ReleaseIngestMetadata.manual().withUnverifiedRisk(true))).hasMessageContaining("安全校验失败");
        doThrow(new GuangyaException(GuangyaException.Reason.BUDGET_EXHAUSTED, "pause")).when(guangya).resolve(anyString(), any());
        when(client.searchMagnets(any())).thenReturn(new PanSouSearchResult(List.of(entry(2, "Show 1080p", 100L))));
        var paused = search.refreshRecommendSeries(new SeriesReleaseRecommendationRequest(null, null, null, "Show", null, 1, "1080p"));
        assertThat(paused.comparison().status()).isEqualTo("PAUSED");
        assertThat(paused.items()).hasSize(1);
    }

    @Test void prowlarrDirectMagnetsAndMissingTitleQualityUseTheSameFileAssessment() throws Exception {
        when(settings.usesPanSouForMagnetSearch()).thenReturn(false);
        var first = new com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease("Show S01", 999L, 20, 1, 10,
                "Indexer", null, 7, "guid1", magnet(1));
        var second = new com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease("Show S01 1080p", 100L, 5, 1, 10,
                "Indexer", null, 7, "guid2", magnet(2));
        when(prowlarr.search(anyString())).thenReturn(List.of(first, second));
        when(guangya.resolve(eq(magnet(1)), any())).thenThrow(new GuangyaException(GuangyaException.Reason.RESOURCE_REJECTED, "文件违规（353）"));
        var initial = search.searchSeries(request);
        var complete = await(initial.comparison().id());
        assertThat(complete.comparison().status()).isEqualTo("COMPLETE");
        assertThat(complete.comparison().recommendedRef()).isEqualTo(magnet(2));
        assertThat(complete.items()).hasSize(2);
        assertThat(complete.items().get(0).indexerId()).isEqualTo(7);
        assertThat(complete.items().get(0).seeders()).isEqualTo(5);
        assertThat(complete.items().get(1).evidence().reason()).contains("353");
        verify(client, atLeastOnce()).searchMagnets(any());
    }

    @Test void originalFileTableFallbackCanWinComparisonBeforeSubmission() throws Exception {
        when(settings.usesPanSouForMagnetSearch()).thenReturn(false);
        var release = new com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease("Show", 999L, 20, 1, 10,
                "Indexer", null, 7, "guid", "opaque");
        when(prowlarr.search(anyString())).thenReturn(List.of(release));
        var resolved = table(magnet(1), "Show S01 1080p", "Show.S01E01.1080p.mkv");
        var originalClient = mock(ProwlarrClient.class);
        when(originalClient.resolveTorrent(eq(release), any())).thenReturn(
                new com.medianexus.orchestrator.integration.prowlarr.ResolvedTorrent(magnet(1), Optional.of(resolved)));
        var shared = new ReleaseFileTables(guangya, originalClient);
        try (var comparison = new ReleaseCandidateComparison(new ReleaseCandidateDiscovery(client, prowlarr, settings, tags, shared),
                tags, shared, new ReleaseFileAssessment(new MovieSeriesFileRenameService(), tags), auth)) {
            var result = comparison.previewSeries(new SeriesReleaseRecommendationRequest(null, null, null, "Show", null, 1, "1080p"));
            assertThat(result.comparison().status()).isEqualTo("COMPLETE");
            assertThat(result.item().downloadRef()).isEqualTo("opaque");
            assertThat(result.item().evidence().reason()).contains("原始 torrent");
            assertThat(shared.knownMagnet(7, "opaque")).contains(magnet(1));
            verifyNoInteractions(guangya);
        }
    }

    @Test void differentReferencesResolvingToOneHashMergeWithoutLosingSourceEvidence() {
        when(settings.usesPanSouForMagnetSearch()).thenReturn(false);
        var first = new com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease("Show S01 1080p", 100L, 5, null, null,
                "A", null, 1, "guid-a", "opaque-a");
        var second = new com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease("Show S01 1080p 尝鲜版", 100L, 5, null, null,
                "B", null, 2, "guid-b", "opaque-b");
        when(prowlarr.search(anyString())).thenReturn(List.of(first, second));
        doReturn(new com.medianexus.orchestrator.integration.prowlarr.ResolvedTorrent(
                magnet(1), Optional.of(table(magnet(1), "Show S01 1080p", "Show.S01E01.1080p.mkv"))))
                .when(originalClient).resolveTorrent(any(), any());
        var result = search.previewSeries(new SeriesReleaseRecommendationRequest(null, null, null, "Show", null, 1, "1080p"));
        assertThat(result.comparison().status()).isEqualTo("COMPLETE");
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.evidence().status()).isEqualTo("RECOMMENDABLE");
            assertThat(item.evidence().preview()).isTrue();
        });
    }

    @Test void zeroSeedersRemainVisibleButNeverAutomaticallySelected() {
        when(settings.usesPanSouForMagnetSearch()).thenReturn(false);
        var release = new com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease("Show S01 1080p", 100L, 0, null, null,
                "Indexer", null, 7, "guid", magnet(1));
        when(prowlarr.search(anyString())).thenReturn(List.of(release));
        var request = new SeriesReleaseRecommendationRequest(null, null, null, "Show", null, 1, "1080p");
        assertThat(search.previewSeries(request).items()).hasSize(1);
        assertThatThrownBy(() -> search.recommendSeries(request)).isInstanceOf(ProwlarrReleaseIngestService.NoMatchingReleaseException.class);
    }

    @Test void sameHashUsesThePositiveSeederPublicationAndItsBackupReference() {
        when(settings.usesPanSouForMagnetSearch()).thenReturn(false);
        var first = new com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease("Show S01 1080p", 100L, 0, null, null,
                "A", null, 1, "guid-a", magnet(1));
        var second = new com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease("Show S01 1080p", 100L, 9, null, null,
                "B", null, 2, "guid-b", magnet(1), null, "backup");
        when(prowlarr.search(anyString())).thenReturn(List.of(first, second));
        var result = search.recommendSeries(new SeriesReleaseRecommendationRequest(null, null, null, "Show", null, 1, "1080p"));
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.indexerId()).isEqualTo(2);
            assertThat(item.seeders()).isEqualTo(9);
        });
    }

    private ProwlarrReleaseSearchResponse await(String id) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        ProwlarrReleaseSearchResponse view;
        do { view = search.comparison(id); if (!view.comparison().status().equals("RUNNING")) return view; Thread.sleep(10); } while (System.nanoTime() < end);
        throw new AssertionError("比较未进入终态");
    }
    private static String magnet(int number) { return "magnet:?xt=urn:btih:%040x".formatted(number); }
    private PanSouSearchEntry entry(int number, String title, Long size) { return new PanSouSearchEntry(magnet(number), "", title, null, "source", size); }
    private TorrentFileTable table(String magnet, String root, String... files) {
        return new TorrentFileTable(PanSouMagnetIdentity.fromUrl(magnet).orElseThrow(), root,
                Arrays.stream(files).map(file -> new TorrentFileTable.Entry(root + "/" + file, 104_857_600L)).toList(), files.length * 104_857_600L);
    }
}
