package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.*;
import com.medianexus.orchestrator.dto.douban.DoubanChartContract.*;
import com.medianexus.orchestrator.dto.resources.response.*;
import com.medianexus.orchestrator.integration.douban.DoubanChartClient;
import com.medianexus.orchestrator.integration.openlist.*;
import com.medianexus.orchestrator.mapper.*;
import com.medianexus.orchestrator.model.*;
import com.medianexus.orchestrator.service.BgmWishlistTaskGateway.*;
import com.medianexus.orchestrator.service.organization.LibraryOrganizer;
import com.medianexus.orchestrator.service.settings.targets.IngestTargetRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Real chart/store/gateway/release/task acceptance path; only persistence and external I/O are replaced. */
class DoubanChartRetryPolicyTest {
    Fixture f;
    @BeforeEach void setup() { f = new Fixture(); }
    @AfterEach void close() { f.chart.close(); }

    @Test void settledTimeoutPollCreatesLinkedDifferentHashWithoutRequestAuthentication() throws Exception {
        f.start(); f.chart.catchUpDaily(); f.idle();
        assertThat(f.inserted).hasSize(1);
        MovieMagnetIngestTask next = f.inserted.get(0);
        assertThat(next.getMagnetHash()).isEqualTo("b".repeat(40));
        assertThat(next.getAttemptGroupId()).isEqualTo("original");
        assertThat(next.getRetryOfTaskType()).isEqualTo("MOVIE");
        assertThat(next.getRetryOfTaskId()).isEqualTo("original");
        assertThat(next.getCreatedByUserId()).isEqualTo(7L);
        assertThat(next.getSavePath()).isEqualTo(f.original.getSavePath());
        assertThat(next.getTempPath()).isEqualTo(f.original.getTempPath());
        assertThat(f.original.getStatus()).isEqualTo("FAILED");
        assertThat(f.original.getTempPath()).isEqualTo("/movie/Title");
        assertThat(f.store.counts()).isEqualTo(new DoubanChartStore.Counts(1, 1));
        assertThat(f.store.operations(ChartId.movie_hot_gaia, 1, 10).items()).singleElement()
                .satisfies(op -> { assertThat(op.triggerType()).isEqualTo("AUTO_RETRY");
                    assertThat(op.entries().get(0).task().taskId()).isEqualTo(next.getId()); });
        verifyNoInteractions(f.auth);
        verify(f.safety).checkMovie(eq(123), eq(f.magnet("b")), any(), argThat(metadata -> !metadata.acceptUnverifiedRisk()));
        verify(f.quota).consumeDailyContentCreate(eq(f.admin), any());
        verify(f.client, never()).fetch(any());
        verify(f.openlist, never()).addOfflineDownload(anyString(), anyString()); // worker is queued, never run by this test
    }

    @Test void acceptanceExcludesEveryHashInExistingChainAndLinksLatestAttempt() throws Exception {
        var second = f.attempt("second", "b", 2);
        f.attempts.add(0, second);
        f.ledger.put("MOVIE:123", new ActiveTask("MOVIE", "original", "FAILED"));
        f.available = List.of(f.release("a"), f.release("b"), f.release("c"));
        f.start(); f.chart.pollAlternatives(); f.idle();
        assertThat(f.inserted).singleElement().satisfies(next -> {
            assertThat(next.getRetryOfTaskId()).isEqualTo("second");
            assertThat(next.getAttemptGroupId()).isEqualTo("original");
            assertThat(next.getMagnetHash()).isEqualTo("c".repeat(40));
        });
        assertThat(f.gateway.currentTask("MOVIE", "original").taskId()).isEqualTo(f.inserted.get(0).getId());
    }

    @Test void totalAttemptCapNeverSearchesOrCreates() throws Exception {
        f.attempts.add(0, f.attempt("second", "b", 2)); f.attempts.add(0, f.attempt("third", "c", 3));
        f.start(); f.chart.pollAlternatives(); f.idle();
        assertThat(f.inserted).isEmpty(); assertThat(f.store.counts().checked()).isZero();
        verifyNoInteractions(f.comparisons);
    }

    @Test void exhaustedCandidatesUseCheckBudgetButNeverCreateAndRestartDoesNotSearchAgain() throws Exception {
        f.available = List.of(f.release("a"));
        f.start(); f.chart.pollAlternatives(); f.idle();
        assertThat(f.inserted).isEmpty(); assertThat(f.store.counts()).isEqualTo(new DoubanChartStore.Counts(1, 0));
        f.chart.close(); f.chart = f.newChart(); f.chart.pollAlternatives(); f.idle();
        assertThat(f.store.counts()).isEqualTo(new DoubanChartStore.Counts(1, 0));
        verify(f.comparisons, times(1)).recommendMovie(any(), any(java.util.function.Consumer.class));
    }

    @Test void pendingCancellationUnknownOrganizationAndInterruptedNeverRetry() throws Exception {
        for (String error : List.of("timeout", "[OFFLINE_UNRESOLVED] cancel unknown", "[ORGANIZATION_UNRESOLVED] conflict")) {
            f.original.setErrorMessage(error); f.start(); f.chart.pollAlternatives(); f.idle();
        }
        f.original.setErrorMessage(f.proof()); f.original.setStatus("INTERRUPTED");
        f.chart.pollAlternatives(); f.idle();
        assertThat(f.inserted).isEmpty(); verifyNoInteractions(f.comparisons);
    }

    @Test void deletedStateSevenIsNeverPositiveStopProofEvenWithOldSettledMessage() throws Exception {
        when(f.openlist.offlineTaskInfo(anyString())).thenAnswer(call -> new OpenListOfflineTaskInfo(call.getArgument(0), 7, null, 0, null, "the task has been deleted"));
        f.start(); f.chart.pollAlternatives(); f.idle();
        assertThat(f.inserted).isEmpty(); verifyNoInteractions(f.comparisons);
    }

    @Test void retainedClaimsOrJournalNeverSearch() throws Exception {
        when(f.downloads.alternativeReleaseAvailable(anyString(), anyString())).thenReturn(false);
        f.start(); f.chart.pollAlternatives(); f.idle();
        when(f.downloads.alternativeReleaseAvailable(anyString(), anyString())).thenReturn(true);
        when(f.journal.status("movie", "original")).thenReturn(mock(OrganizationRecoveryJournal.RecoveryStatus.class));
        f.chart.pollAlternatives(); f.idle();
        assertThat(f.inserted).isEmpty(); verifyNoInteractions(f.comparisons);
    }

    @Test void conflictFilesBlockBeforeInsertAndUnknownBarrierSurvivesRestart() throws Exception {
        doThrow(new OpenListClientException("目录非空，禁止覆盖")).when(f.openlist).requireEmptyAlternativeTarget("/movie/Title");
        f.start(); f.chart.pollAlternatives(); f.idle();
        assertThat(f.inserted).isEmpty(); assertThat(f.store.targetUncertain("MOVIE:123:null")).isTrue();
        assertThat(f.ledger.get("MOVIE:123").status()).isEqualTo("UNKNOWN");
        f.chart.close(); f.chart = f.newChart(); f.chart.pollAlternatives(); f.idle();
        assertThat(f.inserted).isEmpty();
        verify(f.tables, times(3)).resolveSubmissionMagnet(eq(1), eq("ref-b"), anyString());
    }

    @Test void unknownInsertOutcomeIsNeverReplayedAcrossBoardsOrRestart() throws Exception {
        doThrow(new IllegalStateException("commit outcome unknown")).when(f.movies).insert(any(MovieMagnetIngestTask.class));
        f.start(); f.chart.pollAlternatives(); f.idle();
        assertThat(f.store.targetUncertain("MOVIE:123:null")).isTrue();
        f.chart.close(); f.chart = f.newChart(); f.chart.pollAlternatives(); f.idle();
        verify(f.movies, times(1)).insert(any(MovieMagnetIngestTask.class));
        assertThat(f.ledger.get("MOVIE:123").status()).isEqualTo("UNKNOWN");
    }

    @Test void sharedDailyCreateAndCheckLimitsAlsoApplyToAlternatives() throws Exception {
        f.store.limits(new LimitsInput(0, 0)); f.start(); f.chart.pollAlternatives(); f.idle();
        assertThat(f.inserted).isEmpty(); verifyNoInteractions(f.comparisons);
    }

    @Test void crossChartPollingAcceptsOnlyOneNewAttempt() throws Exception {
        f.start();
        f.store.updateBoard(ChartId.movie_weekly_best, old -> f.board(ChartId.movie_weekly_best));
        f.chart.pollAlternatives(); f.idle();
        assertThat(f.inserted).hasSize(1);
        assertThat(f.gateway.previousTask(f.target).taskId()).isEqualTo(f.inserted.get(0).getId());
    }

    @Test void referenceResolvedAgainAtAcceptanceCannotReplayOldHash() throws Exception {
        when(f.tables.resolveSubmissionMagnet(eq(1), eq("ref-b"), anyString())).thenReturn(f.magnet("b"), f.magnet("b"), f.magnet("a"));
        f.start(); f.chart.pollAlternatives(); f.idle();
        assertThat(f.inserted).isEmpty(); assertThat(f.store.targetUncertain("MOVIE:123:null")).isTrue();
    }

    @Test void seriesAcceptancePreservesProductSeasonAndRetryChainWithoutAuthentication() {
        var original = new SeriesMagnetIngestTask();
        original.setId("series-original"); original.setAttemptGroupId("series-original"); original.setStatus("FAILED");
        original.setErrorMessage(f.proof().replace("up-original", "series-upstream")); original.setOpenlistTaskId("series-upstream"); original.setMagnet(f.magnet("a"));
        original.setMagnetHash("a".repeat(40)); original.setTmdbId(123); original.setSeasonNumber(2); original.setTaskProductType("SERIES");
        original.setTitle("Title"); original.setSavePath("/tv/Title/Season 2"); original.setTempPath(original.getSavePath());
        when(f.series.selectById("series-original")).thenReturn(original);
        when(f.series.selectList(any(Wrapper.class))).thenReturn(List.of(original));
        var accepted = f.releases.ingestAlternativeForOwner("SERIES", "series-original", f.release("b"), f.admin);
        var capture = org.mockito.ArgumentCaptor.forClass(SeriesMagnetIngestTask.class);
        verify(f.series).insert(capture.capture());
        var task = capture.getValue();
        assertThat(task.getAttemptGroupId()).isEqualTo("series-original");
        assertThat(task.getRetryOfTaskType()).isEqualTo("SERIES");
        assertThat(task.getRetryOfTaskId()).isEqualTo("series-original");
        assertThat(task.getSeasonNumber()).isEqualTo(2); assertThat(task.getTaskProductType()).isEqualTo("SERIES");
        assertThat(task.getTempPath()).isEqualTo(original.getTempPath());
        assertThat(accepted.taskId()).isEqualTo(task.getId()); verifyNoInteractions(f.auth);
    }

    @Test void workerRechecksEmptyTargetBeforeWritingAndRefusesNewConflicts() {
        var task = f.attempt("new-attempt", "b", 2); task.setRetryOfTaskId("original");
        ReflectionTestUtils.invokeMethod(f.magnetService, "prepareMovieDownloadRoot", task);
        verify(f.openlist).ensureDirectoryReady("/movie/Title", "/movie");
        verify(f.openlist).requireEmptyAlternativeTarget(task.getSavePath());
        clearInvocations(f.openlist);
        doThrow(new OpenListClientException("目录非空，禁止覆盖")).when(f.openlist).requireEmptyAlternativeTarget(task.getSavePath());
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(f.magnetService, "prepareMovieDownloadRoot", task)).hasMessageContaining("禁止");
        verify(f.openlist, never()).ensureDirectoryReady(anyString(), anyString());
    }

    @Test void sharedCreateLimitStopsRetryAfterSearchWithoutUnknownClaim() throws Exception {
        f.store.limits(new LimitsInput(null, 0)); f.start(); f.chart.pollAlternatives(); f.idle();
        assertThat(f.inserted).isEmpty(); assertThat(f.store.counts()).isEqualTo(new DoubanChartStore.Counts(1, 0));
        assertThat(f.store.targetUncertain("MOVIE:123:null")).isFalse();
    }

    @Test void zeroSeederProwlarrAlternativeCannotBeAccepted() {
        var release = f.release("b");
        var zero = new ProwlarrReleaseItemResponse(release.title(), release.size(), 0, 0, 0, release.indexer(), null,
                release.indexerId(), release.downloadRef(), release.resolutionTags(), List.of(), List.of(), null, null)
                .withEvidence(release.evidence(), release.resolutionTags());
        assertThatThrownBy(() -> f.releases.ingestAlternativeForOwner("MOVIE", "original", zero, f.admin)).hasMessageContaining("正做种");
        assertThat(f.inserted).isEmpty(); verifyNoInteractions(f.quota);
    }

    @Test void schedulerRetainedClaimsAndRestartBlockPreventAlternativeAdmission() {
        var client = mock(OpenListClient.class);
        when(client.offlineDirectoryForPath(anyString())).thenAnswer(call -> new OpenListClient.OfflineDirectory("account", "root", call.getArgument(0)));
        try (var scheduler = new OfflineIngestScheduler(client)) {
            assertThat(scheduler.alternativeReleaseAvailable("/title", "hash")).isTrue();
            scheduler.retainOrganizationRecovery("movie:old", "/title", "oldhash");
            assertThat(scheduler.alternativeReleaseAvailable("/title", "newhash")).isFalse();
            assertThat(scheduler.alternativeReleaseAvailable("/other", "oldhash")).isFalse();
            scheduler.completeOrganizationRecovery("movie:old", "/title", "oldhash");
            assertThat(scheduler.alternativeReleaseAvailable("/title", "newhash")).isTrue();
            scheduler.blockUnresolvedRestart("unknown submission");
            assertThat(scheduler.alternativeReleaseAvailable("/title", "newhash")).isFalse();
        }
    }

    @Test void confirmedCancellationDoneBelowHundredRetriesButCompletedDownloadDoesNot() throws Exception {
        f.original.setErrorMessage(f.proof().replace("state=4", "state=2"));
        when(f.openlist.offlineTaskInfo(anyString())).thenAnswer(call -> new OpenListOfflineTaskInfo(call.getArgument(0), 2, null, 0, null, null));
        f.start(); f.chart.pollAlternatives(); f.idle();
        assertThat(f.inserted).hasSize(1);
    }

    @Test void completedOrMismatchedCancellationObservationNeverRetries() throws Exception {
        f.original.setErrorMessage(f.proof().replace("state=4", "state=2").replace("progress=0", "progress=100"));
        when(f.openlist.offlineTaskInfo(anyString())).thenAnswer(call -> new OpenListOfflineTaskInfo(call.getArgument(0), 2, null, 100, null, null));
        f.start(); f.chart.pollAlternatives(); f.idle();
        assertThat(f.inserted).isEmpty(); verifyNoInteractions(f.comparisons);
        f.original.setErrorMessage(f.proof().replace("up-original", "another-upstream"));
        when(f.openlist.offlineTaskInfo(anyString())).thenAnswer(call -> new OpenListOfflineTaskInfo(call.getArgument(0), 4, null, 0, null, null));
        f.chart.pollAlternatives(); f.idle();
        assertThat(f.inserted).isEmpty(); verifyNoInteractions(f.comparisons);
    }

    @Test void invalidEarlierChartOwnerDoesNotBlockLaterChart() throws Exception {
        f.start();
        f.store.updateBoard(ChartId.movie_hot_gaia, old -> new Board(old.id(), old.label(), true, 999L,
                old.refreshStatus(), old.refreshError(), old.lastAttemptAt(), old.lastSuccessAt(), old.nextRunAt(), old.candidates()));
        f.store.updateBoard(ChartId.movie_weekly_best, old -> f.board(ChartId.movie_weekly_best));
        f.chart.pollAlternatives(); f.idle();
        assertThat(f.inserted).hasSize(1);
    }

    @Test void failingEarlierCandidateDoesNotBlockNextCandidate() throws Exception {
        f.start();
        f.store.updateBoard(ChartId.movie_hot_gaia, old -> {
            var first = old.candidates().get(0);
            var subject = new Subject(2, "Title", "Original", null, 2020, "MOVIE", null, null, 2, null, Instant.now());
            var second = new com.medianexus.orchestrator.dto.douban.DoubanChartContract.Candidate(subject, true,
                    Instant.now(), Instant.now(), 2, false, List.of(), f.identity, null);
            return new Board(old.id(), old.label(), true, 7L, "SUCCESS", null, Instant.now(), Instant.now(), null, List.of(first, second));
        });
        when(f.openlist.offlineTaskInfo(anyString())).thenThrow(new OpenListClientException("read unavailable"))
                .thenAnswer(call -> new OpenListOfflineTaskInfo(call.getArgument(0), 4, null, 0, null, null));
        f.chart.pollAlternatives(); f.idle();
        assertThat(f.inserted).hasSize(1);
    }

    static class Fixture {
        final MovieMagnetIngestTaskMapper movies = mock(MovieMagnetIngestTaskMapper.class);
        final SeriesMagnetIngestTaskMapper series = mock(SeriesMagnetIngestTaskMapper.class);
        final OpenListClient openlist = mock(OpenListClient.class);
        final OfflineIngestScheduler downloads = mock(OfflineIngestScheduler.class);
        final OrganizationRecoveryJournal journal = mock(OrganizationRecoveryJournal.class);
        final ReleaseSubmissionSafety safety = mock(ReleaseSubmissionSafety.class);
        final AuthService auth = mock(AuthService.class);
        final UserActionQuotaService quota = mock(UserActionQuotaService.class);
        final ReleaseFileTables tables = mock(ReleaseFileTables.class);
        final ReleaseCandidateComparison comparisons = mock(ReleaseCandidateComparison.class);
        final DoubanChartClient client = mock(DoubanChartClient.class);
        final DoubanChartEligibility eligibility = mock(DoubanChartEligibility.class);
        final UserMapper users = mock(UserMapper.class);
        final User admin = new User();
        final List<MovieMagnetIngestTask> attempts = new CopyOnWriteArrayList<>(), inserted = new CopyOnWriteArrayList<>();
        final Map<String, ActiveTask> ledger = new ConcurrentHashMap<>();
        final BgmWishlistSessionLocks locks = mock(BgmWishlistSessionLocks.class);
        final Map<String, String> rows = new ConcurrentHashMap<>(), kinds = new ConcurrentHashMap<>(), charts = new ConcurrentHashMap<>();
        final DoubanChartMapper mapper = mock(DoubanChartMapper.class);
        final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final Target target = new Target("MOVIE", 123, null, "Title", "Original", 2020);
        final Identity identity = new Identity("MOVIE", 123, null, "Title", "Original", 2020);
        final MovieMagnetIngestTask original = attempt("original", "a", 1);
        final DoubanChartStore store;
        final BgmWishlistTaskGateway gateway;
        final MagnetIngestService magnetService;
        final ProwlarrReleaseIngestService releases;
        List<ProwlarrReleaseItemResponse> available = List.of(release("a"), release("b"));
        DoubanChartService chart;
        Fixture() {
            for (Class<?> type : List.of(MovieMagnetIngestTask.class, SeriesMagnetIngestTask.class, AnimeMagnetIngestTask.class))
                TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), type.getName()), type);
            admin.setId(7L); admin.setRole("ADMIN"); attempts.add(original);
            when(users.selectById(7L)).thenReturn(admin);
            when(locks.withLock(anyString(), any(), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(2).get());
            when(openlist.normalizePath(anyString())).thenAnswer(call -> call.getArgument(0));
            when(openlist.offlineTaskInfo(anyString())).thenAnswer(call -> new OpenListOfflineTaskInfo(call.getArgument(0), 4, null, 0, null, null));
            when(downloads.alternativeReleaseAvailable(anyString(), anyString())).thenReturn(true);
            when(movies.selectById(anyString())).thenAnswer(call -> attempts.stream().filter(task -> task.getId().equals(call.getArgument(0))).findFirst().orElse(null));
            when(movies.selectList(any(Wrapper.class))).thenAnswer(call -> List.copyOf(attempts));
            when(movies.selectOne(any(Wrapper.class))).thenAnswer(call -> {
                Wrapper<?> query = call.getArgument(0);
                return query.getSqlSegment().contains("attempt_group_id") ? attempts.get(0) : null;
            });
            when(movies.insert(any(MovieMagnetIngestTask.class))).thenAnswer(call -> { MovieMagnetIngestTask task = call.getArgument(0); inserted.add(task); attempts.add(0, task); return 1; });
            var presence = mock(MediaLibraryPresenceService.class);
            when(presence.checkForAutomation(any())).thenReturn(new MediaLibraryPresenceService.AutomationPresence(true, false, true));
            var registry = mock(IngestTargetRegistry.class);
            when(registry.rootForExistingPath(eq("MOVIE"), anyString(), nullable(String.class))).thenReturn("/movie");
            when(registry.rootForExistingPath(eq("TV"), anyString(), nullable(String.class))).thenReturn("/tv");
            var magnet = new MagnetIngestService(movies, mock(MovieMagnetIngestTaskLogMapper.class), series,
                    mock(SeriesMagnetIngestTaskLogMapper.class), openlist, new OpenListProperties(), mock(MovieSeriesFileRenameService.class),
                    mock(LibraryOrganizer.class), mock(LibraryOrganizer.class), mock(LibraryOrganizer.class), auth, quota,
                    mock(AutoSymlinkRefreshService.class), presence, registry, mock(ExecutorService.class), mock(IngestTaskNotifier.class));
            magnetService = magnet;
            ReflectionTestUtils.setField(magnet, "offlineScheduler", downloads);
            ReflectionTestUtils.setField(magnet, "organizationJournal", journal);
            ReflectionTestUtils.setField(magnet, "submissionSafety", safety);
            when(tables.resolveSubmissionMagnet(eq(1), anyString(), anyString())).thenAnswer(call -> magnet(call.<String>getArgument(1).substring(4)));
            releases = new ProwlarrReleaseIngestService(auth, magnet, mock(AnimeMagnetIngestTaskService.class), comparisons, tables);
            when(comparisons.recommendMovie(any(), any(java.util.function.Consumer.class))).thenAnswer(call -> {
                var response = mock(ProwlarrReleaseRecommendationResponse.class); when(response.items()).thenReturn(available); return response;
            });
            var previous = mock(BgmWishlistTargetTaskMapper.class);
            when(previous.select(anyString())).thenAnswer(call -> ledger.get(call.getArgument(0)));
            doAnswer(call -> { ledger.put(call.getArgument(0), call.getArgument(1)); return null; }).when(previous).insert(anyString(), any());
            doAnswer(call -> { ledger.put(call.getArgument(0), call.getArgument(1)); return null; }).when(previous).replace(anyString(), any());
            ledger.put("MOVIE:123", new ActiveTask("MOVIE", "original", "FAILED"));
            gateway = new BgmWishlistTaskGateway(movies, series, mock(AnimeMagnetIngestTaskMapper.class), releases, presence, locks, previous, mock(MediaLibraryDeletionWorkflow.class));
            when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(mapper.insert(anyString(), anyString(), nullable(String.class), anyString())).thenAnswer(call -> {
                String key = call.getArgument(0); if (rows.putIfAbsent(key, call.getArgument(3)) != null) return 0;
                kinds.put(key, call.getArgument(1)); if (call.getArgument(2) != null) charts.put(key, call.getArgument(2)); return 1;
            });
            when(mapper.select(anyString())).thenAnswer(call -> rows.get(call.getArgument(0)));
            when(mapper.lock(anyString())).thenAnswer(call -> rows.get(call.getArgument(0)));
            doAnswer(call -> { rows.put(call.getArgument(0), call.getArgument(1)); return null; }).when(mapper).update(anyString(), anyString());
            doAnswer(call -> { rows.remove(call.getArgument(0)); return null; }).when(mapper).delete(anyString());
            when(mapper.operations(anyString(), anyLong(), anyInt())).thenAnswer(call -> rows.entrySet().stream()
                    .filter(row -> "OPERATION".equals(kinds.get(row.getKey())) && call.getArgument(0).equals(charts.get(row.getKey()))).map(Map.Entry::getValue).toList());
            when(eligibility.assess(any(), nullable(Identity.class))).thenReturn(new DoubanChartEligibility.Assessment(identity, target, List.of(), null));
            when(eligibility.completeRelease(any(), any())).thenReturn(true);
            store = new DoubanChartStore(mapper, json, tx); chart = newChart();
        }
        DoubanChartService newChart() { return new DoubanChartService(store, client, eligibility, gateway, locks, auth, users, downloads,
                Clock.fixed(Instant.parse("2026-01-03T00:00:00Z"), DoubanChartStore.ZONE)); }
        String proof() { return "已核实取消后上游停止；保留原 FAILED 和源文件，未重试或整理；upstream=up-original;state=4;progress=0"; }
        MovieMagnetIngestTask attempt(String id, String hash, int day) {
            var task = new MovieMagnetIngestTask(); task.setId(id); task.setStatus("FAILED"); task.setStage("failed"); task.setTmdbId(123);
            task.setTitle("Title"); task.setOriginalTitle("Original"); task.setYear(2020); task.setSavePath("/movie/Title"); task.setTempPath("/movie/Title");
            task.setMagnet(magnet(hash)); task.setMagnetHash(hash.repeat(40)); task.setOpenlistTaskId("up-" + id); task.setAttemptGroupId("original");
            task.setCreatedAt(LocalDateTime.of(2026, 1, day, 0, 0)); task.setUpdatedAt(LocalDateTime.of(2026, 2, 10 - day, 0, 0));
            task.setErrorMessage(proof().replace("up-original", "up-" + id)); return task;
        }
        String magnet(String hash) { return "magnet:?xt=urn:btih:" + hash.repeat(40); }
        ProwlarrReleaseItemResponse release(String hash) { return new ProwlarrReleaseItemResponse("Title 1080p", 100L, 1, 1, 1, "indexer", null, 1,
                "ref-" + hash, List.of("1080p"), List.of(), List.of(), null, null).withEvidence(
                new ReleaseFileEvidence("RECOMMENDABLE", "verified", null, null, null, false, false), List.of("1080p")); }
        Board board(ChartId id) {
            var subject = new Subject(1, "Title", "Original", null, 2020, "MOVIE", null, null, 1, null, Instant.now());
            var candidate = new com.medianexus.orchestrator.dto.douban.DoubanChartContract.Candidate(subject, true, Instant.now(), Instant.now(), 1, false, List.of(), identity, new Reason("CURRENT_CANDIDATE", "current"));
            return new Board(id, id.label(), true, 7L, "SUCCESS", null, Instant.now(), Instant.now(), null, List.of(candidate));
        }
        void start() { store.updateBoard(ChartId.movie_hot_gaia, old -> board(ChartId.movie_hot_gaia)); store.recordTask("MOVIE:123:null", new ActiveTask("MOVIE", "original", "FAILED")); }
        void idle() throws Exception {
            var executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(chart, "executor");
            executor.submit(() -> {}).get(8, TimeUnit.SECONDS);
        }
    }
}
