package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.*;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.*;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Entry;
import com.medianexus.orchestrator.dto.douban.DoubanChartContract.*;
import com.medianexus.orchestrator.dto.resources.response.*;
import com.medianexus.orchestrator.integration.douban.DoubanChartClient;
import com.medianexus.orchestrator.mapper.*;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.DoubanChartStore.Run;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 Store + JSON round trip，外部边界与 mapper 替身；不宣称验证 MySQL 锁。 */
class DoubanChartServiceTest {
    private Fixture f;
    @BeforeEach void setup() { f = new Fixture(); }
    @AfterEach void close() { f.release.countDown(); f.service.close(); }

    @Test void failedRefreshRetainsMembersExclusionsAndHistoricalProgressAcrossStoreReload() throws Exception {
        when(f.client.fetch(ChartId.tv_hot)).thenReturn(List.of(f.subject(1, "SERIES"), f.subject(2, "SERIES")));
        f.refresh(ChartId.tv_hot);
        f.service.exclusion(ChartId.tv_hot, 1, new ExclusionInput(true));
        when(f.client.fetch(ChartId.tv_hot)).thenReturn(List.of(f.subject(2, "SERIES")));
        Board dropped = f.refresh(ChartId.tv_hot);
        Candidate history = dropped.candidates().stream().filter(c -> c.subject().id() == 1).findFirst().orElseThrow();
        assertFalse(history.current()); assertTrue(history.excluded()); assertEquals(1, history.lastRank());
        when(f.client.fetch(ChartId.tv_hot)).thenThrow(new IllegalStateException("429"));
        Board failed = f.refresh(ChartId.tv_hot);
        assertEquals("FAILED", failed.refreshStatus()); assertEquals(2, failed.candidates().size());
        assertEquals(dropped.lastSuccessAt(), failed.lastSuccessAt());
        assertTrue(failed.candidates().stream().filter(c -> c.subject().id() == 2).findFirst().orElseThrow().current());
        DoubanChartStore reloaded = new DoubanChartStore(f.mapper, f.json, f.transactions);
        assertTrue(reloaded.board(ChartId.tv_hot).candidates().get(0).excluded());
        assertTrue(reloaded.board(ChartId.tv_hot).candidates().get(0).observations().size() >= 3);
        assertNull(f.service.overview().settings().dailyCheckLimit());
        assertTrue(f.service.overview().boards().stream().noneMatch(Board::autoEnabled));
    }

    @Test void hotManualLimitsPersistAndFollowFreshPositionsWithoutIncludingHistory() throws Exception {
        when(f.tasks.candidates(any(), anyString(), any())).thenReturn(List.of());
        when(f.client.fetch(ChartId.movie_hot_gaia)).thenReturn(List.of(f.subject(999, "MOVIE"), f.subject(80, "MOVIE")));
        f.refresh(ChartId.movie_hot_gaia);
        var fresh = java.util.stream.LongStream.rangeClosed(1, 100).mapToObj(id -> f.subject(id, "MOVIE")).toList();
        when(f.client.fetch(ChartId.movie_hot_gaia)).thenReturn(fresh);
        for (int limit : List.of(20, 80, 100)) {
            Operation done = f.await(f.service.execute(ChartId.movie_hot_gaia, new ExecuteInput(limit)).id());
            assertEquals(limit, done.entries().size());
            assertFalse(done.entries().stream().anyMatch(entry -> entry.item().subjectId() == 999));
            assertTrue(done.entries().stream().filter(entry -> entry.item().subjectId() >= 1 && entry.item().subjectId() <= 100)
                    .map(entry -> entry.item().subjectId()).collect(java.util.stream.Collectors.toSet())
                    .containsAll(java.util.stream.LongStream.rangeClosed(1, limit).boxed().toList()));
            assertFalse(done.entries().stream().anyMatch(entry -> entry.item().subjectId() >= 1 && entry.item().subjectId() <= 100
                    && entry.item().subjectId() > limit));
            var reloaded = new DoubanChartStore(f.mapper, f.json, f.transactions);
            assertEquals(limit, reloaded.run(done.id()).operation().candidateLimit());
            assertEquals(limit, f.service.get(done.id()).candidateLimit());
            assertEquals(limit, f.service.operations(ChartId.movie_hot_gaia, 1, 10).items().stream()
                    .filter(operation -> operation.id().equals(done.id())).findFirst().orElseThrow().candidateLimit());
        }
        assertTrue(f.service.board(ChartId.movie_hot_gaia).candidates().stream().anyMatch(c -> c.subject().id() == 999 && !c.current()));
        assertFalse(f.service.board(ChartId.movie_hot_gaia).autoEnabled());
        verify(f.tasks, never()).createChartMovie(any(), any(), any());
        assertThrows(BusinessException.class, () -> f.service.execute(ChartId.tv_hot, new ExecuteInput(21)));

    }
    @Test void hotLimitUsesSourcePositionRatherThanUniqueCountAndKeepsSharedBudget() throws Exception {
        when(f.client.fetch(ChartId.tv_hot)).thenReturn(List.of(f.subject(1, "SERIES"), f.subject(20, "SERIES"), f.subject(22, "SERIES")));
        f.service.settings(new LimitsInput(1, 1));
        when(f.tasks.candidates(any(), anyString(), any())).thenReturn(List.of());
        Operation done = f.await(f.service.execute(ChartId.tv_hot, new ExecuteInput(20)).id());
        assertEquals(List.of(1L, 20L), done.entries().stream().map(entry -> entry.item().subjectId()).toList());
        assertEquals("DAILY_CHECK_LIMIT", done.entries().get(1).reason().code());
        verify(f.tasks, times(1)).candidates(any(), anyString(), any());
        verify(f.tasks, never()).createChartSeries(any(), any(), any());
    }

    @Test void duplicateHotRequestKeepsOriginalLimitAndLegacyRecordsRemainReadable() throws Exception {
        when(f.client.fetch(ChartId.tv_hot)).thenAnswer(call -> {
            f.entered.countDown(); assertTrue(f.release.await(5, TimeUnit.SECONDS)); return List.of();
        });
        Operation first = f.service.execute(ChartId.tv_hot, new ExecuteInput(20));
        assertTrue(f.entered.await(5, TimeUnit.SECONDS));
        Operation duplicate = f.service.execute(ChartId.tv_hot, new ExecuteInput(100));
        assertEquals(first.id(), duplicate.id()); assertEquals(20, duplicate.candidateLimit());
        f.release.countDown(); assertEquals(20, f.await(first.id()).candidateLimit());
        var raw = f.json.valueToTree(f.store.run(first.id()));
        ((com.fasterxml.jackson.databind.node.ObjectNode)raw.path("operation")).remove("candidateLimit");
        assertNull(f.json.treeToValue(raw, Run.class).operation().candidateLimit());
        assertThrows(BusinessException.class, () -> f.service.execute(ChartId.movie_weekly_best, new ExecuteInput(20)));
        assertEquals(0, f.store.operations(ChartId.movie_weekly_best, 1, 10).total());
    }

    @Test void asynchronousDuplicateReturnsSamePersistedOperationAndStopPreventsFurtherCreates() throws Exception {
        when(f.client.fetch(ChartId.movie_hot_gaia)).thenReturn(List.of(f.subject(1, "MOVIE"), f.subject(2, "MOVIE")));
        when(f.tasks.candidates(any(), anyString(), any())).thenAnswer(call -> {
            f.entered.countDown(); assertTrue(f.release.await(5, TimeUnit.SECONDS)); return List.of(f.release());
        });
        Operation accepted = f.service.execute(ChartId.movie_hot_gaia);
        assertTrue(Set.of("QUEUED", "RUNNING").contains(accepted.status()));
        assertNotNull(f.store.run(accepted.id())); assertTrue(f.entered.await(5, TimeUnit.SECONDS));
        assertEquals(accepted.id(), f.service.execute(ChartId.movie_hot_gaia).id());
        assertEquals("STOPPING", f.service.stop(accepted.id()).status());
        f.release.countDown(); Operation stopped = f.await(accepted.id());
        assertEquals("STOPPED", stopped.status());
        verify(f.tasks, never()).createChartMovie(any(), any(), any());
        assertEquals(1, f.store.operations(ChartId.movie_hot_gaia, 1, 10).total());
        assertTrue(stopped.entries().stream().allMatch(e -> e.item().reasons().get(0).code().equals("CURRENT_CANDIDATE")));
    }

    @Test void failedChartReadMarksWholeRunFailedAndKeepsCauseWithoutSearchingOrCreating() throws Exception {
        when(f.client.fetch(ChartId.movie_hot_gaia)).thenReturn(java.util.stream.LongStream.rangeClosed(1, 20)
                .mapToObj(id -> f.subject(id, "MOVIE")).toList());
        f.refresh(ChartId.movie_hot_gaia);
        when(f.client.fetch(ChartId.movie_hot_gaia)).thenThrow(new DoubanChartClient.ChartReadException("豆瓣 JSON 请求超时，保留上次成功清单"));
        Operation done = f.await(f.service.execute(ChartId.movie_hot_gaia).id());
        assertEquals("FAILED", done.status());
        assertEquals("CHART_REFRESH_FAILED", done.reason().code());
        assertTrue(done.reason().message().contains("超时"));
        assertEquals(20, done.entries().size());
        assertTrue(done.entries().stream().allMatch(entry -> "SOURCE_UNAVAILABLE".equals(entry.reason().code())));
        assertTrue(f.service.board(ChartId.movie_hot_gaia).refreshError().contains("超时"));
        verifyNoInteractions(f.tasks);
        // 旧版记录的汇总在读取时纠正，持久作品证据及时间不改写。
        f.store.updateRun(done.id(), old -> new Run(new Operation(done.id(), done.chartId(), done.chartLabel(), done.triggerType(),
                done.actorUserId(), done.startedAt(), done.updatedAt(), "SUCCEEDED", done.entries(), null), old.scope(), true, false));
        Operation historical = f.service.get(done.id());
        assertEquals("FAILED", historical.status());
        assertEquals(done.updatedAt(), historical.updatedAt());
        assertEquals(done.entries(), historical.entries());
        assertEquals("SUCCEEDED", f.store.run(done.id()).operation().status());
    }

    @Test void failedCurrentBoardCannotCreateButIndependentlyVerifiedHistoryUsesRegularSeriesPath() throws Exception {
        when(f.client.fetch(ChartId.tv_chinese_best_weekly)).thenReturn(List.of(f.subject(1, "SERIES"), f.subject(2, "SERIES")));
        f.refresh(ChartId.tv_chinese_best_weekly);
        when(f.client.fetch(ChartId.tv_chinese_best_weekly)).thenReturn(List.of(f.subject(2, "SERIES")));
        f.refresh(ChartId.tv_chinese_best_weekly);
        when(f.client.fetch(ChartId.tv_chinese_best_weekly)).thenThrow(new IllegalStateException("source failed"));
        Operation accepted = f.service.execute(ChartId.tv_chinese_best_weekly); Operation done = f.await(accepted.id());
        Entry historical = done.entries().stream().filter(e -> e.item().subjectId() == 1).findFirst().orElseThrow();
        Entry current = done.entries().stream().filter(e -> e.item().subjectId() == 2).findFirst().orElseThrow();
        assertEquals("CREATED", historical.status()); assertEquals("HISTORICAL_CANDIDATE", historical.item().reasons().get(0).code());
        assertFalse(historical.selected().acceptUnverifiedRisk());
        assertEquals("SOURCE_UNAVAILABLE", current.reason().code());
        assertEquals("PARTIAL_SUCCESS", done.status());
        assertEquals("CHART_REFRESH_FAILED", done.reason().code());
        verify(f.tasks).createChartSeries(argThat(target -> target.tmdbId() == 1), any(), eq(f.admin));
        verify(f.tasks, never()).createChartMovie(any(), any(), any());
        f.service.exclusion(ChartId.tv_chinese_best_weekly, 1, new ExclusionInput(true));
        assertEquals("HISTORICAL_CANDIDATE", f.service.get(done.id()).entries().get(0).item().reasons().get(0).code());
    }

    @Test void sharedLimitsAreDurableAndNoResourceIsSearchedOnlyOncePerTargetPerRun() throws Exception {
        f.service.settings(new LimitsInput(1, 1));
        when(f.client.fetch(ChartId.movie_hot_gaia)).thenReturn(List.of(f.subject(1, "MOVIE"), f.subject(2, "MOVIE")));
        when(f.tasks.candidates(any(), anyString(), any())).thenReturn(List.of());
        Operation done = f.await(f.service.execute(ChartId.movie_hot_gaia).id());
        assertEquals("WAITING_RESOURCE", done.entries().get(0).status());
        assertEquals("DAILY_CHECK_LIMIT", done.entries().get(1).reason().code());
        when(f.client.fetch(ChartId.movie_weekly_best)).thenReturn(List.of(f.subject(3, "MOVIE")));
        Operation other = f.await(f.service.execute(ChartId.movie_weekly_best).id());
        assertEquals("DAILY_CHECK_LIMIT", other.entries().get(0).reason().code());
        verify(f.tasks, times(1)).candidates(any(), anyString(), any());
        assertEquals(1, f.service.overview().settings().checkedToday());
        assertEquals(1, new DoubanChartStore(f.mapper, f.json, f.transactions).counts().checked());
        assertTrue(f.store.claimDaily(ChartId.tv_hot, LocalDate.of(2026, 1, 1)));
        assertFalse(new DoubanChartStore(f.mapper, f.json, f.transactions).claimDaily(ChartId.tv_hot, LocalDate.of(2026, 1, 1)));
    }

    @Test void sharedCreateLimitAppliesAcrossBoardsAndDuplicateTargetIsCheckedOnce() throws Exception {
        f.service.settings(new LimitsInput(null, 1));
        when(f.client.fetch(ChartId.movie_hot_gaia)).thenReturn(List.of(f.subject(1, "MOVIE"), f.subject(2, "MOVIE")));
        doAnswer(call -> {
            Subject subject = call.getArgument(0);
            Identity identity = new Identity("MOVIE", subject.id() <= 2 ? 1 : (int)subject.id(), null, subject.title(), "Original", 2020);
            return new DoubanChartEligibility.Assessment(identity, new BgmWishlistTaskGateway.Target("MOVIE", identity.tmdbId(), null, identity.title(), "Original", 2020), List.of(), null);
        }).when(f.eligibility).assess(any(), nullable(Identity.class));
        Operation first = f.await(f.service.execute(ChartId.movie_hot_gaia).id());
        assertEquals("CREATED", first.entries().get(0).status());
        assertEquals("DUPLICATE_TARGET", first.entries().get(1).reason().code());
        when(f.client.fetch(ChartId.movie_weekly_best)).thenReturn(List.of(f.subject(3, "MOVIE")));
        Operation other = f.await(f.service.execute(ChartId.movie_weekly_best).id());
        assertEquals("DAILY_CREATE_LIMIT", other.entries().get(0).reason().code());
        assertEquals(1, f.service.overview().settings().createdToday());
        verify(f.tasks, times(1)).createChartMovie(any(), any(), any());
    }

    @Test void previousFailedTaskAssociatesWithoutNewSearchAndUnknownCreationNeverReplays() throws Exception {
        when(f.client.fetch(ChartId.movie_hot_gaia)).thenReturn(List.of(f.subject(1, "MOVIE")));
        when(f.tasks.previousTask(any())).thenReturn(new BgmWishlistTaskGateway.ActiveTask("MOVIE", "failed-task", "FAILED"));
        Operation known = f.await(f.service.execute(ChartId.movie_hot_gaia).id());
        assertEquals("failed-task", known.entries().get(0).task().taskId());
        verify(f.tasks, never()).candidates(any(), anyString(), any());
        when(f.tasks.previousTask(any())).thenReturn(null);
        when(f.tasks.createChartMovie(any(), any(), any())).thenThrow(new IllegalStateException("unknown side effect"));
        Operation uncertain = f.await(f.service.execute(ChartId.movie_hot_gaia).id());
        assertEquals("INTERRUPTED", uncertain.entries().get(0).status());
        Operation repeated = f.await(f.service.execute(ChartId.movie_hot_gaia).id());
        assertEquals("CREATE_UNCERTAIN", repeated.entries().get(0).reason().code());
        verify(f.tasks, times(1)).createChartMovie(any(), any(), any());
    }

    @Test void restartReconcilesCreatingFromLedgerAndLeavesUnknownInterrupted() throws Exception {
        when(f.client.fetch(ChartId.movie_hot_gaia)).thenReturn(List.of(f.subject(1, "MOVIE")));
        f.refresh(ChartId.movie_hot_gaia);
        Run queued = f.store.submit(ChartId.movie_hot_gaia, 7L, "MANUAL");
        Subject subject = f.subject(1, "MOVIE"); Identity identity = f.identity(subject);
        Item item = new Item(1, subject.title(), null, null, 2020, null, identity, "UNKNOWN", "UNKNOWN", true, List.of(), null, true, "NOT_CHECKED", null, null, false);
        Entry entry = new Entry(item, "CREATING", Instant.now(), List.of(), List.of(), null, null, null, null);
        f.store.updateRun(queued.operation().id(), old -> new Run(new Operation(old.operation().id(), ChartId.movie_hot_gaia, "电影", "MANUAL", 7L,
                Instant.now(), Instant.now(), "RUNNING", List.of(entry), null), f.store.board(ChartId.movie_hot_gaia).candidates(), true, false));
        Run unknown = f.store.submit(ChartId.movie_weekly_best, 7L, "MANUAL");
        Subject second = f.subject(2, "MOVIE");
        Item unknownItem = new Item(2, second.title(), null, null, 2020, null, f.identity(second), "UNKNOWN", "UNKNOWN", true, List.of(), null, true, "NOT_CHECKED", null, null, false);
        f.store.updateRun(unknown.operation().id(), old -> new Run(new Operation(old.operation().id(), ChartId.movie_weekly_best, "周榜", "MANUAL", 7L,
                Instant.now(), Instant.now(), "RUNNING", List.of(new Entry(unknownItem, "CREATING", Instant.now(), List.of(), List.of(), null, null, null, null)), null), List.of(), true, false));
        when(f.tasks.previousTask(any())).thenAnswer(call -> call.getArgument(0, BgmWishlistTaskGateway.Target.class).tmdbId() == 1
                ? new BgmWishlistTaskGateway.ActiveTask("MOVIE", "known", "PENDING") : null);
        f.service.recover();
        assertEquals("known", f.store.run(queued.operation().id()).operation().entries().get(0).task().taskId());
        assertEquals("INTERRUPTED", f.store.run(queued.operation().id()).operation().status());
        Entry interrupted = f.store.run(unknown.operation().id()).operation().entries().get(0);
        assertEquals("INTERRUPTED", interrupted.status()); assertEquals("CREATE_UNCERTAIN", interrupted.reason().code());
        assertFalse(f.store.claimTarget("MOVIE:2:null", "new-attempt"));
        verify(f.tasks, never()).createChartMovie(any(), any(), any());
    }

    @Test void startupCollectsUninitializedBoardsWithoutStartingIngestAndPreservesSuccessfulBoards() throws Exception {
        when(f.client.fetch(any())).thenAnswer(call -> {
            ChartId chart = call.getArgument(0);
            return List.of(f.subject(chart.ordinal() + 10, chart.mediaType()));
        });
        f.service.recover();
        f.awaitBoardRefreshes();
        for (ChartId chart : ChartId.values()) {
            Board board = f.service.board(chart);
            assertEquals(1, board.candidates().size());
            assertFalse(board.autoEnabled());
            verify(f.client, times(1)).fetch(chart);
            assertEquals(0, f.service.operations(chart, 1, 10).total());
        }
        f.service.recover();
        // 后台队列屏障：确认第二次启动检查已结束，再断言没有重复采集。
        f.refresh(ChartId.movie_hot_gaia);
        verify(f.client, times(2)).fetch(ChartId.movie_hot_gaia);
        for (ChartId chart : List.of(ChartId.tv_hot, ChartId.movie_weekly_best, ChartId.movie_top250)) {
            verify(f.client, times(1)).fetch(chart);
        }
        verifyNoInteractions(f.tasks);
    }

    @Test void pausedDownloadsPreserveEvidenceWithoutCreatingOrReservingQuota() throws Exception {
        when(f.client.fetch(ChartId.tv_chinese_best_weekly)).thenReturn(List.of(f.subject(1,"SERIES")));
        when(f.downloads.submissionPauseReason()).thenReturn("重启任务仍待核实");
        Operation run = f.await(f.service.execute(ChartId.tv_chinese_best_weekly).id());
        assertEquals("DOWNLOAD_PAUSED", run.entries().get(0).reason().code());
        assertNotNull(run.entries().get(0).selected());
        assertEquals(0, f.store.counts().created());
        verify(f.tasks, never()).createChartSeries(any(), any(), any());
    }

    @Test void currentWeeklySeriesWithoutProgressReadsFreshDetailBeforeEligibility() throws Exception {
        Subject missing = new Subject(1,"作品1",null,null,2020,"SERIES",null,null,1,null,Instant.now());
        Subject complete = new Subject(1,"作品1",null,null,2020,"SERIES",null,null,1,"2集全",Instant.now());
        when(f.client.fetch(ChartId.tv_chinese_best_weekly)).thenReturn(List.of(missing));
        when(f.client.refreshSubject(missing)).thenReturn(complete);
        doReturn(new DoubanChartEligibility.Assessment(f.identity(missing), null, List.of(),
                new Reason("PROGRESS_UNKNOWN", "没有明确完结信息"))).when(f.eligibility).assess(eq(missing), nullable(Identity.class));
        Operation run = f.await(f.service.execute(ChartId.tv_chinese_best_weekly).id());
        assertEquals("CREATED", run.entries().get(0).status());
        verify(f.eligibility, never()).assess(eq(missing), nullable(Identity.class));
        verify(f.client).refreshSubject(complete);
        assertNull(f.service.board(ChartId.tv_chinese_best_weekly).candidates().get(0).subject().progress());
    }

    @Test void newWeeklySeriesBoardsStartDisabledAndKeepExistingBoardAndSharedTargetLedger() throws Exception {
        when(f.client.fetch(ChartId.tv_hot)).thenReturn(List.of(f.subject(1, "SERIES")));
        Operation original = f.await(f.service.execute(ChartId.tv_hot).id());
        when(f.tasks.previousTask(any())).thenReturn(new BgmWishlistTaskGateway.ActiveTask("SERIES", "series-task", "SUCCEEDED"));
        for (ChartId chart : List.of(ChartId.tv_chinese_best_weekly, ChartId.tv_global_best_weekly)) {
            when(f.client.fetch(chart)).thenReturn(List.of(f.subject(1, "SERIES")));
            assertFalse(f.service.board(chart).autoEnabled());
            Operation run = f.await(f.service.execute(chart).id());
            assertEquals("ASSOCIATED", run.entries().get(0).status());
            assertEquals("series-task", run.entries().get(0).task().taskId());
        }
        assertEquals(original.id(), f.service.operations(ChartId.tv_hot, 1, 10).items().get(0).id());
        verify(f.tasks, times(1)).createChartSeries(any(), any(), any());
    }

    @Test void dailyRefreshRunsWithAutomationOffAndOnlyEnabledBoardCreatesWithPersistentAdmin() throws Exception {
        CountDownLatch refreshed = new CountDownLatch(ChartId.values().length);
        when(f.client.fetch(any())).thenAnswer(call -> { refreshed.countDown(); ChartId chart = call.getArgument(0);
            return List.of(f.subject(chart.ordinal() + 10, chart.mediaType())); });
        f.service.daily(); assertTrue(refreshed.await(5, TimeUnit.SECONDS));
        f.awaitBoardRefreshes();
        verify(f.tasks, never()).candidates(any(), anyString(), any());
        f.service.automation(ChartId.tv_hot, new AutoInput(true));
        f.rows.keySet().removeIf(key -> key.startsWith("daily:"));
        f.service.daily();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (f.store.operations(ChartId.tv_hot, 1, 10).items().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        Operation scheduled = f.await(f.store.operations(ChartId.tv_hot, 1, 10).items().get(0).id());
        assertEquals("SCHEDULED", scheduled.triggerType()); assertEquals(7L, scheduled.actorUserId()); assertEquals(20, scheduled.candidateLimit());
        verify(f.tasks).createChartSeries(any(), any(), eq(f.admin));
        verify(f.tasks, never()).createChartMovie(any(), any(), any());
        assertFalse(f.store.board(ChartId.movie_hot_gaia).autoEnabled());
        f.admin.setRole("USER");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> f.service.execute(ChartId.movie_hot_gaia)).getHttpStatus());
    }

    @Test void unknownEvidenceAndComparisonPauseNeverCreateAndNoResourceCanRetryInNextRun() throws Exception {
        when(f.client.fetch(ChartId.movie_hot_gaia)).thenReturn(List.of(f.subject(1, "MOVIE")));
        when(f.tasks.candidates(any(), anyString(), any())).thenReturn(List.of(f.release().withEvidence(ReleaseFileEvidence.unverified("未知"), List.of())));
        Operation unsafe = f.await(f.service.execute(ChartId.movie_hot_gaia).id());
        assertEquals("WAITING_CONFIRMATION", unsafe.entries().get(0).status());
        doThrow(new ProwlarrReleaseIngestService.NoMatchingReleaseException("EVIDENCE_BUDGET_EXHAUSTED", "比较暂停", List.of()))
                .when(f.tasks).candidates(any(), anyString(), any());
        Operation paused = f.await(f.service.execute(ChartId.movie_hot_gaia).id());
        assertEquals("EVIDENCE_BUDGET_EXHAUSTED", paused.entries().get(0).reason().code());
        doReturn(List.of()).when(f.tasks).candidates(any(), anyString(), any());
        Operation noResource = f.await(f.service.execute(ChartId.movie_hot_gaia).id());
        assertEquals("WAITING_RESOURCE", noResource.entries().get(0).status());
        doReturn(List.of(f.release())).when(f.tasks).candidates(any(), anyString(), any());
        Operation retry = f.await(f.service.execute(ChartId.movie_hot_gaia).id());
        assertEquals("CREATED", retry.entries().get(0).status());
        verify(f.tasks, times(1)).createChartMovie(any(), any(), any());
    }

    @Test void historicalCompletionReasonIsDatedAndPersistsInOperation() throws Exception {
        Subject airing=new Subject(1,"作品1",null,null,2020,"SERIES",null,null,1,"更新至1集",Instant.now());
        Subject complete=new Subject(1,"作品1",null,null,2020,"SERIES",null,null,1,"2集全",Instant.now());
        when(f.client.fetch(ChartId.tv_chinese_best_weekly)).thenReturn(List.of(airing)); f.refresh(ChartId.tv_chinese_best_weekly);
        when(f.client.fetch(ChartId.tv_chinese_best_weekly)).thenReturn(List.of(f.subject(2,"SERIES")));
        when(f.client.refreshSubject(airing)).thenReturn(complete);
        Operation done=f.await(f.service.execute(ChartId.tv_chinese_best_weekly).id());
        Entry history=done.entries().stream().filter(entry->entry.item().subjectId()==1).findFirst().orElseThrow();
        assertEquals("历史连载现已确认完结",history.item().reasons().get(0).message());
        assertEquals("历史连载现已确认完结",f.service.board(ChartId.tv_chinese_best_weekly).candidates().stream()
                .filter(candidate->candidate.subject().id()==1).findFirst().orElseThrow().reason().message());
        f.service.exclusion(ChartId.tv_chinese_best_weekly,1,new ExclusionInput(true));
        assertEquals("历史连载现已确认完结",f.service.get(done.id()).entries().stream()
                .filter(entry->entry.item().subjectId()==1).findFirst().orElseThrow().item().reasons().get(0).message());
    }

    @Test void exclusionDuringFinalNetworkVerificationPreventsAdmission() throws Exception {
        when(f.client.fetch(ChartId.movie_hot_gaia)).thenReturn(List.of(f.subject(1,"MOVIE")));
        when(f.client.refreshSubject(any())).thenAnswer(call -> {
            f.service.exclusion(ChartId.movie_hot_gaia,1,new ExclusionInput(true));
            return call.getArgument(0);
        });
        Operation done = f.await(f.service.execute(ChartId.movie_hot_gaia).id());
        assertEquals("EXCLUDED",done.entries().get(0).reason().code());
        verify(f.tasks,never()).createChartMovie(any(),any(),any());
    }

    @Test void refreshReturnsWhileNetworkBlockedAndDailyClaimAlreadyContainsRecoverableRun() throws Exception {
        when(f.client.fetch(ChartId.tv_hot)).thenAnswer(call -> {
            f.entered.countDown(); assertTrue(f.release.await(5,TimeUnit.SECONDS)); return List.of(f.subject(1,"SERIES"));
        });
        assertEquals("REFRESHING",f.service.refresh(ChartId.tv_hot).refreshStatus());
        assertTrue(f.entered.await(5,TimeUnit.SECONDS));
        f.service.automation(ChartId.tv_hot,new AutoInput(true));
        var work=f.store.claimDailyWork(ChartId.tv_hot,LocalDate.of(2026,1,2));
        assertEquals("QUEUED",new DoubanChartStore(f.mapper,f.json,f.transactions).run(work.run().operation().id()).operation().status());
        assertNull(f.store.claimDailyWork(ChartId.tv_hot,LocalDate.of(2026,1,2)));
        f.release.countDown();
    }

    @Test void allWritesRequireAdminAndReadsRequireAuthenticationBeforeStateAccess() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "只读", HttpStatus.FORBIDDEN)).when(f.auth).requireAdminUser();
        List<Runnable> writes = List.of(() -> f.service.refresh(ChartId.tv_hot), () -> f.service.execute(ChartId.tv_hot),
                () -> f.service.automation(ChartId.tv_hot, new AutoInput(true)), () -> f.service.settings(new LimitsInput(null, null)),
                () -> f.service.exclusion(ChartId.tv_hot, 1, new ExclusionInput(true)),
                () -> f.service.identity(ChartId.tv_hot, 1, new IdentityInput("SERIES", 1, 1)), () -> f.service.stop("missing"));
        for (Runnable write : writes) assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class, write::run).getHttpStatus());
        doThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "登录", HttpStatus.UNAUTHORIZED)).when(f.auth).requireCurrentUser();
        List<Runnable> reads = List.of(() -> f.service.overview(), () -> f.service.board(ChartId.tv_hot), () -> f.service.get("missing"),
                () -> f.service.operations(ChartId.tv_hot, 1, 10), () -> f.service.search("词", "MOVIE"), () -> f.service.seasons(1));
        for (Runnable read : reads) assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(BusinessException.class, read::run).getHttpStatus());
        assertTrue(f.rows.isEmpty());
    }

    private static class Fixture {
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final Map<String, String> rows = new ConcurrentHashMap<>();
        final Map<String, String> charts = new ConcurrentHashMap<>();
        final Map<String, String> kinds = new ConcurrentHashMap<>();
        final DoubanChartMapper mapper = mock(DoubanChartMapper.class);
        final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        final DoubanChartClient client = mock(DoubanChartClient.class);
        final DoubanChartEligibility eligibility = mock(DoubanChartEligibility.class);
        final BgmWishlistTaskGateway tasks = mock(BgmWishlistTaskGateway.class);
        final BgmWishlistSessionLocks locks = mock(BgmWishlistSessionLocks.class);
        final AuthService auth = mock(AuthService.class);
        final UserMapper users = mock(UserMapper.class);
        final OfflineIngestScheduler downloads = mock(OfflineIngestScheduler.class);
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final User admin = new User();
        final DoubanChartStore store;
        final DoubanChartService service;
        Fixture() {
            admin.setId(7L); admin.setRole("ADMIN");
            when(auth.requireCurrentUser()).thenReturn(admin); when(auth.requireAdminUser()).thenReturn(admin);
            when(users.selectById(7L)).thenReturn(admin);
            when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
            when(mapper.insert(anyString(), anyString(), nullable(String.class), anyString())).thenAnswer(call -> {
                String key = call.getArgument(0); boolean added = rows.putIfAbsent(key, call.getArgument(3)) == null;
                if (added) { kinds.put(key, call.getArgument(1)); String chart = call.getArgument(2); if (chart != null) charts.put(key, chart); }
                return added ? 1 : 0;
            });
            when(mapper.select(anyString())).thenAnswer(call -> rows.get(call.getArgument(0)));
            when(mapper.lock(anyString())).thenAnswer(call -> rows.get(call.getArgument(0)));
            doAnswer(call -> { rows.put(call.getArgument(0), call.getArgument(1)); return null; }).when(mapper).update(anyString(), anyString());
            doAnswer(call -> { rows.remove(call.getArgument(0)); return null; }).when(mapper).delete(anyString());
            when(mapper.operations(anyString(), anyLong(), anyInt())).thenAnswer(call -> operationRows(call.getArgument(0)).stream()
                    .skip(call.getArgument(1, Long.class)).limit(call.getArgument(2, Integer.class)).toList());
            when(mapper.operationCount(anyString())).thenAnswer(call -> (long) operationRows(call.getArgument(0)).size());
            when(mapper.openOperations()).thenAnswer(call -> rows.entrySet().stream().filter(row -> "OPERATION".equals(kinds.get(row.getKey())))
                    .map(Map.Entry::getValue).filter(raw -> { try { return DoubanChartStore.active(json.readTree(raw).path("operation").path("status").asText()); }
                        catch (Exception exception) { throw new IllegalStateException(exception); } }).toList());
            when(locks.withLock(anyString(), any(), any())).thenAnswer(call -> call.getArgument(2, Supplier.class).get());
            when(client.refreshSubject(any())).thenAnswer(call -> call.getArgument(0));
            when(eligibility.assess(any(), nullable(Identity.class))).thenAnswer(call -> {
                Subject subject = call.getArgument(0); Identity identity = identity(subject);
                return new DoubanChartEligibility.Assessment(identity, new BgmWishlistTaskGateway.Target(identity.mediaType(), identity.tmdbId(),
                        identity.seasonNumber(), identity.title(), identity.originalTitle(), identity.year()), List.of(1, 2), null);
            });
            when(eligibility.completeRelease(any(), any())).thenReturn(true);
            when(tasks.candidates(any(), anyString(), any())).thenReturn(List.of(release()));
            when(tasks.createChartMovie(any(), any(), any())).thenReturn(new BgmWishlistTaskGateway.ActiveTask("MOVIE", "movie-task", "PENDING"));
            when(tasks.createChartSeries(any(), any(), any())).thenReturn(new BgmWishlistTaskGateway.ActiveTask("SERIES", "series-task", "PENDING"));
            store = new DoubanChartStore(mapper, json, transactions);
            service = new DoubanChartService(store, client, eligibility, tasks, locks, auth, users, downloads);
        }
        List<String> operationRows(String chart) { return rows.entrySet().stream().filter(row -> "OPERATION".equals(kinds.get(row.getKey()))
                && chart.equals(charts.get(row.getKey()))).map(Map.Entry::getValue).toList(); }
        Subject subject(long id, String type) { return new Subject(id, "作品" + id, "Original", null, 2020, type, null, null, (int)id, "2集全", Instant.now()); }
        Identity identity(Subject subject) { return new Identity(subject.mediaType(), (int)subject.id(), "SERIES".equals(subject.mediaType()) ? 1 : null, subject.title(), "Original", 2020); }
        ProwlarrReleaseItemResponse release() { return new ProwlarrReleaseItemResponse("作品 1080p", 100L, 1, 1, 1, "indexer", null, 1,
                "opaque", List.of("1080p"), List.of(), List.of(), null, null).withEvidence(new ReleaseFileEvidence("RECOMMENDABLE", "已核实", null, null, null, false, false), List.of("1080p")); }
        Board refresh(ChartId id) throws Exception {
            service.refresh(id);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while ("REFRESHING".equals(store.board(id).refreshStatus()) && System.nanoTime() < deadline) Thread.sleep(10);
            return service.board(id);
        }
        void awaitBoardRefreshes() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (Arrays.stream(ChartId.values()).anyMatch(id -> !"SUCCESS".equals(store.board(id).refreshStatus()))
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Arrays.stream(ChartId.values()).allMatch(id -> "SUCCESS".equals(store.board(id).refreshStatus())));
        }
        Operation await(String id) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (System.nanoTime() < deadline) {
                Operation operation = store.run(id).operation();
                if (!DoubanChartStore.active(operation.status())) return operation;
                Thread.sleep(10);
            }
            fail("operation still active: " + store.run(id)); return null;
        }
    }
}
