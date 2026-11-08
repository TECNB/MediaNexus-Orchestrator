package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.*;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.*;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Operation;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseItemResponse;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.BgmWishlistSourceStore.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BgmWishlistOperationTest {
    @Test void overlappingBatchIsExplicitConflictAndScheduledWorkUsesSameAutomaticRules() {
        Fixture f = new Fixture(); f.service.submit("source", List.of(1L));
        assertThrows(RuntimeException.class, () -> f.service.submit("source", List.of(1L, 2L)));
        Fixture daily = new Fixture(); SourceState old = daily.sources.get("source"); Source s = old.source();
        Source enabled = new Source(s.id(),s.bgmUsername(),s.ownerUserId(),s.ownerUsername(),true,s.scheduleTime(),s.targetCount(),s.quality(),s.initialScope(),s.timezone(),true);
        SourceState state = new SourceState(enabled,old.subjects(),old.knownSubjectIds(),old.candidateSubjectIds(),old.identities(),old.createdTasks(),old.refreshStatus(),null,old.lastAttemptAt(),old.lastSuccessAt());
        when(daily.sources.get("source")).thenReturn(state); when(daily.wishlist.refreshSourceForOperation("source")).thenReturn(state);
        when(daily.executions.claimDay(anyString(), any())).thenReturn(true, false);
        ZonedDateTime due = ZonedDateTime.of(2026,11,7,9,0,0,0,ZoneId.of("Asia/Shanghai"));
        Fixture disabled = new Fixture();
        assertNull(disabled.service.schedule("source", due));
        verify(disabled.executions,never()).claimDay(anyString(),any());
        Operation operation = daily.service.schedule("source", due);
        assertNotNull(operation); assertEquals("SCHEDULED", operation.triggerType());
        assertNull(daily.service.schedule("source", due)); daily.executor.runPending();
        verify(daily.tasks, times(1)).create(any(), any(), eq(daily.owner));
    }

    @Test void persistentRecordRoundTripsCandidatesAndConcurrentSearchUpdates() {
        Fixture f = new Fixture(); Operation accepted = f.service.submit("source", List.of(1L));
        var mapper = mock(com.medianexus.orchestrator.mapper.BgmWishlistExecutionMapper.class);
        Map<String,String> stored = new HashMap<>();
        doAnswer(call -> { stored.put(call.getArgument(0),call.getArgument(4)); return null; }).when(mapper).insert(anyString(),anyString(),anyString(),any(),anyString());
        when(mapper.select(anyString(),eq("OPERATION"))).thenAnswer(call -> stored.get(call.getArgument(0)));
        when(mapper.lockOperation(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
        doAnswer(call -> { stored.put(call.getArgument(0),call.getArgument(1)); return null; }).when(mapper).updateOperation(anyString(),anyString());
        var transactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        BgmWishlistOperationStore store = new BgmWishlistOperationStore(mapper,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),transactions);
        store.create(accepted); assertEquals(accepted, store.get(accepted.id()));
        Entry previous = accepted.entries().get(0);
        Candidate candidate = new Candidate("key",f.release("中文"));
        Entry withCandidate = new Entry(previous.item(),"CREATING",previous.startedAt(),List.of(new Search("query","展示标题","SUCCEEDED",52,82)),List.of(candidate),candidate,null,null,null);
        store.update(accepted.id(), operation -> new Operation(operation.id(),operation.source(),operation.action(),operation.triggerType(),operation.actorUserId(),operation.startedAt(),operation.updatedAt(),operation.status(),operation.requestedIds(),List.of(withCandidate),null));
        assertEquals(withCandidate,store.get(accepted.id()).entries().get(0));
    }

    @Test void acceptsBeforeSearchingAndDuplicateClickCreatesOnlyOneTask() {
        Fixture f = new Fixture();
        Operation accepted = f.service.submit("source", List.of(1L));
        assertEquals("QUEUED", accepted.status());
        verifyNoInteractions(f.tasks, f.quota);
        assertEquals(accepted.id(), f.service.submit("source", List.of(1L)).id());
        f.executor.runPending();
        Operation completed = f.rows.get(accepted.id());
        assertEquals("SUCCEEDED", completed.status());
        assertEquals("CREATED", completed.entries().get(0).status());
        assertEquals("中文发布", completed.entries().get(0).selected().release().title());
        assertEquals("SUCCEEDED", completed.entries().get(0).searches().get(0).status());
        verify(f.tasks, times(1)).create(any(), any(), eq(f.owner));
        verify(f.sources).recordCreated(eq("source"), eq(1L), any());
    }

    @Test void mixedBatchContinuesAndSeparatesNoReleaseNonChineseAndSearchFailure() {
        Fixture f = new Fixture();
        Operation accepted = f.service.submit("source", List.of(1L, 2L, 3L, 4L));
        f.executor.runPending();
        Operation operation = f.rows.get(accepted.id());
        assertEquals("WAITING", operation.status());
        assertEquals(List.of("CREATED", "WAITING_CONFIRMATION", "WAITING_RESOURCE", "FAILED"), operation.entries().stream().map(Entry::status).toList());
        assertEquals("RELEASE_CHECK_FAILED", operation.entries().get(3).reason().code());
        verify(f.notifications, times(2)).send(anyString(), anyString(), anyString(), anyString());
        verify(f.tasks, times(1)).create(any(), any(), eq(f.owner));
        assertEquals("FAILED", operation.entries().get(2).notification());
    }

    @Test void confirmationPinsSpecificCandidateAndRechecksBeforeCreating() {
        Fixture f = new Fixture();
        Operation accepted = f.service.submit("source", List.of(2L)); f.executor.runPending();
        Entry pending = f.rows.get(accepted.id()).entries().get(0);
        assertThrows(RuntimeException.class, () -> f.service.confirm(accepted.id(), new CandidateInput(2L, "old-key")));
        String key = pending.candidates().get(0).key();
        f.service.confirm(accepted.id(), new CandidateInput(2L, key));
        assertThrows(RuntimeException.class, () -> f.service.confirm(accepted.id(), new CandidateInput(2L, key)));
        when(f.wishlist.evaluateForOperation("source", 2L)).thenReturn(f.item(2L, false));
        f.executor.runPending();
        assertEquals("SKIPPED", f.rows.get(accepted.id()).entries().get(0).status());
        verify(f.tasks, never()).create(any(), any(), any());
    }

    @Test void refreshFailureAndSourceChangeNeverCreateAndCreatedTasksCannotBeResearched() {
        Fixture f = new Fixture();
        when(f.wishlist.refreshSourceForOperation("source")).thenThrow(new IllegalStateException("network"));
        Operation accepted = f.service.submit("source", List.of(1L)); f.executor.runPending();
        assertEquals("FAILED", f.rows.get(accepted.id()).status()); verifyNoInteractions(f.tasks);
        Fixture successful = new Fixture();
        Operation created = successful.service.submit("source", List.of(1L)); successful.executor.runPending();
        assertThrows(RuntimeException.class, () -> successful.service.retry(created.id(), 1L));
    }

    @Test void liveRecordUsesTaskStatusAndFailureNoticeWithoutNewDownloadState() {
        Fixture f = new Fixture(); Operation accepted = f.service.submit("source", List.of(1L)); f.executor.runPending();
        when(f.tasks.currentTask("SERIES", "task-1")).thenReturn(new Task("SERIES", "task-1", "FAILED", "failed", "离线下载超时", null));
        when(f.notifications.status("task:series:task-1")).thenReturn("SENT");
        Task task = f.service.get(accepted.id()).entries().get(0).task();
        assertEquals("FAILED", task.status()); assertEquals("SENT", task.notification()); assertEquals("离线下载超时", task.error());
    }

    @Test void interruptedWorkerReconcilesExistingTaskWithoutReplayingCreation() {
        Fixture f = new Fixture(); Operation accepted = f.service.submit("source", List.of(1L));
        Operation orphan = new Operation("orphan", accepted.source(), accepted.action(), accepted.triggerType(), accepted.actorUserId(),
                accepted.startedAt(), accepted.updatedAt(), "RUNNING", accepted.requestedIds(), accepted.entries(), null);
        f.rows.clear(); f.rows.put("orphan", orphan);
        when(f.tasks.previousTask(any())).thenReturn(new BgmWishlistTaskGateway.ActiveTask("SERIES", "already-created", "DOWNLOADING"));
        f.service.reconcile();
        assertEquals("INTERRUPTED", f.rows.get("orphan").status());
        assertEquals("already-created", f.rows.get("orphan").entries().get(0).task().taskId());
        verify(f.tasks, never()).create(any(), any(), any());
    }

    @Test void titleRuleRemainsHanHeuristicAndManualSupplyCannotChangeTargetOrQuality() {
        assertTrue(BgmWishlistOperationService.hasChineseTitle("中文发布"));
        assertTrue(BgmWishlistOperationService.hasChineseTitle("2.5次元の誘惑"));
        assertFalse(BgmWishlistOperationService.hasChineseTitle("English CHS"));
        Fixture f = new Fixture(); Operation accepted = f.service.submit("source", List.of(3L)); f.executor.runPending();
        assertThrows(RuntimeException.class, () -> f.service.supply(accepted.id(), new SupplyInput(3L, new IdentityInput("SERIES", 999, 1), f.release("中文"))));
        var wrong = new ProwlarrReleaseItemResponse("中文", 1L, 1, 0, 0, "indexer", null, 1, "ref", List.of("720p"), List.of(), List.of("S01"), null, null);
        assertThrows(RuntimeException.class, () -> f.service.supply(accepted.id(), new SupplyInput(3L, new IdentityInput("SERIES", 3, 1), wrong)));
        f.service.supply(accepted.id(), new SupplyInput(3L, new IdentityInput("SERIES", 3, 1), f.release("中文"))); f.executor.runPending();
        assertEquals("CREATED", f.rows.get(accepted.id()).entries().get(0).status());
    }

    @Test void realGatewayPreservesUncertainCandidatesThroughOperationAndConfirmation() {
        Fixture f = new Fixture();
        var releases = mock(ProwlarrReleaseIngestService.class);
        var release = f.release("[LoliHouse] 2.5次元的诱惑 [01-24 合集][1080p][简繁内封字幕]");
        org.springframework.test.util.ReflectionTestUtils.setField(f.tasks, "releases", releases);
        doCallRealMethod().when(f.tasks).candidates(any(), anyString(), any());
        doThrow(new ProwlarrReleaseIngestService.NoMatchingReleaseException(
                "SEASON_UNKNOWN", "季度待核对", List.of(release)))
                .when(releases).recommendSeriesReleaseForAutomation(any(), any());
        Operation accepted = f.service.submit("source", List.of(1L));
        f.executor.runPending();
        Entry entry = f.rows.get(accepted.id()).entries().get(0);
        assertEquals("WAITING_CONFIRMATION", entry.status());
        assertEquals(release, entry.candidates().get(0).release());
        verify(f.tasks, never()).create(any(), any(), any());
        clearInvocations(releases);
        f.service.confirm(accepted.id(), new CandidateInput(1L, entry.candidates().get(0).key()));
        f.executor.runPending();
        verifyNoInteractions(releases);
        verify(f.tasks).create(any(), eq(release), eq(f.owner));
    }

    @Test void passesBangumiChineseTitleToRealGatewayAndKeepsFallbackForConfirmation() {
        Fixture f = new Fixture();
        var releases = mock(ProwlarrReleaseIngestService.class);
        var previous = f.item(1L, true);
        var identity = new Identity("SERIES", 1, 1, "雾尾粉丝俱乐部", "霧尾ファンクラブ", 2026);
        var item = new Item(1L, "雾尾粉丝后援会", "霧尾ファンクラブ", null, 2026, previous.updatedAt(), identity,
                "ABSENT", "AVAILABLE", true, List.of(), null, true, "NOT_CHECKED", null);
        when(f.wishlist.evaluateForOperation("source", 1L)).thenReturn(item);
        var release = f.release("[LoliHouse] 雾尾粉丝后援会 [01-12 合集][1080p][简繁字幕]");
        org.springframework.test.util.ReflectionTestUtils.setField(f.tasks, "releases", releases);
        doCallRealMethod().when(f.tasks).candidates(any(), anyString(), any());
        doThrow(new ProwlarrReleaseIngestService.NoMatchingReleaseException("SEASON_UNKNOWN", "季度待核对", List.of(release)))
                .when(releases).recommendSeriesReleaseForAutomation(any(), eq("雾尾粉丝后援会"), any());
        Operation accepted = f.service.submit("source", List.of(1L)); f.executor.runPending();
        Entry entry = f.rows.get(accepted.id()).entries().get(0);
        assertEquals("WAITING_CONFIRMATION", entry.status());
        assertEquals(release, entry.candidates().get(0).release());
        verify(releases).recommendSeriesReleaseForAutomation(argThat(request -> request.title().equals("雾尾粉丝俱乐部")),
                eq("雾尾粉丝后援会"), any());
        verify(f.tasks, never()).create(any(), any(), any());
    }

    @Test void retainsUncertainPanSouCandidatesForManualConfirmationWithoutCreating() {
        Fixture f = new Fixture();
        var release = f.release("中文作品 Batch 1080p");
        doThrow(new ProwlarrReleaseIngestService.NoMatchingReleaseException("SEASON_UNKNOWN", "季度待核对", List.of(release)))
                .when(f.tasks).candidates(any(), anyString(), any());
        Operation accepted = f.service.submit("source", List.of(1L));
        f.executor.runPending();
        Entry entry = f.rows.get(accepted.id()).entries().get(0);
        assertEquals("WAITING_CONFIRMATION", entry.status());
        assertEquals(release, entry.candidates().get(0).release());
        verify(f.tasks, never()).create(any(), any(), any());
    }

    @Test void createsQualifiedPanSouWithoutSeedersAndConfirmsSavedCandidateWithoutNewSearch() {
        Fixture f = new Fixture();
        var chinese = new ProwlarrReleaseItemResponse("中文作品 S01 Complete 1080p", null, null, null, null,
                "Nyaa", null, null, "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                List.of("1080p"), List.of(), List.of("S01"), "展示标题", "中文作品");
        var english = new ProwlarrReleaseItemResponse("English S01 Complete 1080p", null, null, null, null,
                "Nyaa", null, null, "magnet:?xt=urn:btih:abcdef0123456789abcdef0123456789abcdef01",
                List.of("1080p"), List.of(), List.of("S01"), "原始标题", "English");
        doReturn(List.of(chinese)).when(f.tasks).candidates(argThat(target -> target.tmdbId() == 1), anyString(), any());
        doReturn(List.of(english)).when(f.tasks).candidates(argThat(target -> target.tmdbId() == 2), anyString(), any());
        Operation accepted = f.service.submit("source", List.of(1L, 2L));
        f.executor.runPending();
        assertEquals("CREATED", f.rows.get(accepted.id()).entries().get(0).status());
        verify(f.tasks).create(any(), eq(chinese), eq(f.owner));
        Entry pending = f.rows.get(accepted.id()).entries().get(1);
        assertEquals("WAITING_CONFIRMATION", pending.status());
        clearInvocations(f.tasks);
        f.service.confirm(accepted.id(), new CandidateInput(2L, pending.candidates().get(0).key()));
        f.executor.runPending();
        verify(f.tasks, never()).candidates(any(), anyString(), any());
        verify(f.tasks).create(any(), eq(english), eq(f.owner));
    }

    static class Fixture {
        final AuthService auth = mock(AuthService.class);
        final BgmWishlistService wishlist = mock(BgmWishlistService.class);
        final BgmWishlistSourceStore sources = mock(BgmWishlistSourceStore.class);
        final BgmWishlistExecutionStore executions = mock(BgmWishlistExecutionStore.class);
        final BgmWishlistOperationStore records = mock(BgmWishlistOperationStore.class);
        final BgmWishlistSessionLocks locks = mock(BgmWishlistSessionLocks.class);
        final BgmWishlistTaskGateway tasks = mock(BgmWishlistTaskGateway.class);
        final UserActionQuotaService quota = mock(UserActionQuotaService.class);
        final UserMapper users = mock(UserMapper.class);
        final IngestExceptionNotifier notifications = mock(IngestExceptionNotifier.class);
        final User owner = new User();
        final Map<String, Operation> rows = new LinkedHashMap<>();
        final QueueExecutor executor = new QueueExecutor();
        final BgmWishlistOperationService service;
        Fixture() {
            owner.setId(1L); owner.setUsername("owner"); when(auth.requireAdminUser()).thenReturn(owner); when(users.selectById(1L)).thenReturn(owner);
            Source source = new Source("source", "account", 1L, "owner", false, "09:00", 3, "1080p", "INCLUDE_EXISTING", "Asia/Shanghai", true);
            List<CollectedSubject> subjects = new ArrayList<>(); Map<Long, Identity> identities = new HashMap<>();
            for (long id = 1; id <= 4; id++) { subjects.add(new CollectedSubject(id, "作品" + id, "Original", null, 2024, "2026-11-01T00:00:00Z")); identities.put(id, item(id, true).identity()); when(wishlist.evaluateForOperation("source", id)).thenReturn(item(id, true)); }
            SourceState state = new SourceState(source, subjects, Set.of(1L,2L,3L,4L), Set.of(1L,2L,3L,4L), identities, Map.of(), "SUCCESS", null, Instant.now(), Instant.now());
            when(sources.get("source")).thenReturn(state); when(wishlist.refreshSourceForOperation("source")).thenReturn(state);
            when(executions.withSourceLock(anyString(), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
            when(locks.withLock(anyString(), any(), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(2).get());
            doAnswer(call -> { Operation operation = call.getArgument(0); rows.put(operation.id(), operation); return null; }).when(records).create(any());
            when(records.get(anyString())).thenAnswer(call -> rows.get(call.getArgument(0)));
            when(records.update(anyString(), any())).thenAnswer(call -> { String id = call.getArgument(0); Operation value = call.<UnaryOperator<Operation>>getArgument(1).apply(rows.get(id)); rows.put(id, value); return value; });
            when(records.open()).thenAnswer(call -> rows.values().stream().filter(operation -> Set.of("QUEUED","RUNNING","WAITING").contains(operation.status())).toList());
            when(quota.dailyContentCreateQuota(owner)).thenReturn(new UserActionQuotaService.DailyContentCreateQuota(1L,"owner",LocalDate.now().toString(),0,10,10,false));
            when(tasks.candidates(any(), anyString(), any())).thenAnswer(call -> {
                var target = call.<BgmWishlistTaskGateway.Target>getArgument(0);
                var callback = call.<Consumer<ProwlarrReleaseIngestService.ReleaseSearchProgress>>getArgument(2);
                callback.accept(new ProwlarrReleaseIngestService.ReleaseSearchProgress("query", "展示标题", "RUNNING", 0, null));
                if (target.tmdbId() == 4) throw new IllegalStateException("timeout");
                callback.accept(new ProwlarrReleaseIngestService.ReleaseSearchProgress("query", "展示标题", "SUCCEEDED", 100, 2));
                return target.tmdbId() == 3 ? List.of() : target.tmdbId() == 2 ? List.of(release("English")) : List.of(release("English"), release("中文发布"));
            });
            when(tasks.create(any(), any(), any())).thenAnswer(call -> new BgmWishlistTaskGateway.ActiveTask("SERIES", "task-" + call.<BgmWishlistTaskGateway.Target>getArgument(0).tmdbId(), "PENDING"));
            when(notifications.send(anyString(), anyString(), anyString(), anyString())).thenReturn("FAILED");
            service = new BgmWishlistOperationService(auth,wishlist,sources,executions,records,locks,tasks,quota,users,notifications,executor);
        }
        Item item(long id, boolean eligible) {
            return new Item(id,"作品"+id,"Original",null,2024,"2026-11-01T00:00:00Z", new Identity("SERIES",(int)id,1,"作品"+id,"Original",2024),
                    eligible ? "ABSENT" : "PRESENT","AVAILABLE",eligible,eligible ? List.of() : List.of(new Reason("ALREADY_PRESENT","已在库")),null,true,"NOT_CHECKED",null);
        }
        ProwlarrReleaseItemResponse release(String title) { return new ProwlarrReleaseItemResponse(title,1L,1,0,0,"indexer",null,1,"ref-"+title,List.of("1080p"),List.of(),List.of("S01"),"展示标题","query"); }
    }
    static class QueueExecutor extends AbstractExecutorService {
        final Queue<Runnable> pending = new ArrayDeque<>(); boolean closed;
        public void execute(Runnable task) { pending.add(task); }
        void runPending() { while (!pending.isEmpty()) pending.remove().run(); }
        public void shutdown() { closed = true; }
        public List<Runnable> shutdownNow() { closed = true; return List.of(); }
        public boolean isShutdown() { return closed; }
        public boolean isTerminated() { return closed; }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return closed; }
    }
}
