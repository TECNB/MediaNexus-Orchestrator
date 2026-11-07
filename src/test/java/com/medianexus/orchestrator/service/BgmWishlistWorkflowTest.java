package com.medianexus.orchestrator.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.BangumiProperties;
import com.medianexus.orchestrator.config.UserQuotaProperties;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.*;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistExecutionContract.*;
import com.medianexus.orchestrator.dto.resources.response.MediaLibraryPresenceResponse;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseItemResponse;
import com.medianexus.orchestrator.dto.magnet.response.MovieMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.magnet.response.SeriesMagnetIngestTaskResponse;
import com.medianexus.orchestrator.integration.bangumi.BangumiClient;
import com.medianexus.orchestrator.integration.bangumi.BgmTmdbMappings;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.mapper.*;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.BgmWishlistTaskGateway.ActiveTask;
import com.medianexus.orchestrator.service.BgmWishlistTaskGateway.Target;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** The agreed BGM application seam, with deterministic HTTP, database and task adapters; no downloads. */
class BgmWishlistWorkflowTest {
    @Test
    void asynchronousRefreshReadsWholeSourceButOnlyChecksSelectedCatalog() throws Exception {
        Fixture f = new Fixture(); f.collection = List.of(1L,2L);
        Source source = f.source(false,"NEW_ONLY",3); f.wishlist.refresh(source.id());
        f.identify(source,1L,101); f.identify(source,2L,102);
        org.mockito.Mockito.clearInvocations(f.tmdb,f.presence);
        var refreshed = f.wishlist.refreshSourceForOperation(source.id());
        assertEquals(2,refreshed.subjects().size());
        org.mockito.Mockito.verifyNoInteractions(f.tmdb,f.presence);
        assertTrue(f.wishlist.evaluateForOperation(source.id(),1L).eligible());
        org.mockito.Mockito.verify(f.presence).checkForAutomation("movie",101,null,null);
        org.mockito.Mockito.verify(f.presence,org.mockito.Mockito.never()).checkForAutomation("movie",102,null,null);
        f.responses.add(new IOException("upstream"));
        assertThrows(RuntimeException.class,()->f.wishlist.refreshSourceForOperation(source.id()));
        assertEquals("FAILED",f.sources.get(source.id()).refreshStatus());
        assertEquals(2,f.sources.get(source.id()).subjects().size());
    }

    @Test
    void refreshAutomaticallyMatchesTypedTargetsAndPreservesManualIdentity() throws Exception {
        Fixture f = new Fixture();
        f.collection = List.of(1L, 2L, 3L, 4L, 5L);
        Source source = f.source(false, "NEW_ONLY", 3);
        f.wishlist.refresh(source.id());
        f.identify(source, 3, 103);
        f.movie(101, "{\"type\":4,\"release_date\":\"2020-01-01T00:00:00Z\"}");
        when(f.tmdb.getTvDetails(201, "zh-CN")).thenReturn(f.json.readTree("""
                {"id":201,"name":"真实系列","first_air_date":"2020-01-01",
                 "seasons":[{"season_number":1,"episode_count":1}]}
                """));
        when(f.tmdb.getTvSeasonDetails(201, 1, "zh-CN")).thenReturn(f.json.readTree("""
                {"id":301,"season_number":1,"episodes":[{"episode_number":1,"air_date":"2020-01-01"}]}
                """));
        when(f.tmdb.getMovieDetails(999, "zh-CN")).thenThrow(new com.medianexus.orchestrator.integration.tmdb.TmdbClientException(
                com.medianexus.orchestrator.integration.tmdb.TmdbClientException.Reason.UPSTREAM, "target missing"));
        when(f.mappings.find(anySet())).thenReturn(Map.of(
                1L, new IdentityInput("MOVIE", 101, null),
                2L, new IdentityInput("SERIES", 201, 1),
                3L, new IdentityInput("MOVIE", 999, null),
                5L, new IdentityInput("MOVIE", 999, null)));
        Wishlist refreshed = f.wishlist.refresh(source.id());
        Map<Long, Item> byId = new HashMap<>();
        refreshed.items().forEach(item -> byId.put(item.subjectId(), item));
        assertNotNull(byId.get(1L).identity(), "刷新必须自动确认可核实的目录，而非让所有作品等待人工选择");
        assertEquals("MOVIE", byId.get(1L).identity().mediaType());
        assertEquals(101, byId.get(1L).identity().tmdbId());
        assertEquals(1, byId.get(2L).identity().seasonNumber());
        assertEquals(103, byId.get(3L).identity().tmdbId());
        assertNull(byId.get(4L).identity());
        assertNull(byId.get(5L).identity());
        assertEquals(3, f.sources.get(source.id()).identities().size());
        assertTrue(f.submitted.isEmpty());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void publishedMappingsUseExplicitTypesAndSeasonsAndVerifyReleaseIntegrity() throws Exception {
        ObjectMapper json = new ObjectMapper();
        byte[] raw = """
                {"schemaVersion":1,"subjects":[
                 {"bangumiId":513352,"targets":[{"type":"movie","id":1360829}]},
                 {"bangumiId":530290,"targets":[{"type":"tv","id":258579,"season":1,"episode":14,"episodeEnd":24}]},
                 {"bangumiId":331887,"targets":[{"type":"tv","id":100565,"season":1},{"type":"tv","id":100565,"season":0}]},
                 {"bangumiId":10,"targets":[{"type":"tv","id":100}]},
                 {"bangumiId":11,"targets":[{"type":"tv","id":100,"season":1},{"type":"tv","id":100,"season":2}]},
                 {"bangumiId":12,"targets":[{"type":"movie","id":100},{"type":"tv","id":100,"season":1}]},
                 {"bangumiId":13,"targets":[{"type":"tv","id":100,"season":0}]}]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));
        String manifest = "{\"schemaVersion\":1,\"sourceCommit\":\"" + "a".repeat(40)
                + "\",\"files\":{\"subjects.json\":{\"sha256\":\"" + digest + "\"}}}";
        HttpClient http = mock(HttpClient.class);
        HttpResponse first = mock(HttpResponse.class), second = mock(HttpResponse.class);
        when(first.statusCode()).thenReturn(200); when(second.statusCode()).thenReturn(200);
        when(first.body()).thenReturn(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(second.body()).thenReturn(raw);
        doReturn(first, second).when(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        BangumiProperties config = new BangumiProperties();
        config.setTimeout(Duration.ofSeconds(1)); config.setUserAgent("BgmWishlistWorkflowTest");
        config.setMappingReleaseBaseUrl("https://mapping.invalid/releases"); config.setMappingCacheTtl(Duration.ofHours(6));
        BgmTmdbMappings mappings = new BgmTmdbMappings(config, json, http);
        var matches = mappings.find(Set.of(513352L, 530290L, 331887L, 10L, 11L, 12L, 13L));
        assertEquals(3, matches.size());
        assertEquals(new IdentityInput("MOVIE", 1360829, null), matches.get(513352L));
        assertEquals(new IdentityInput("SERIES", 258579, 1), matches.get(530290L));
        assertEquals(new IdentityInput("SERIES", 100565, 1), matches.get(331887L));
        assertEquals(matches.get(530290L), mappings.find(Set.of(530290L)).get(530290L));
        verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        when(first.body()).thenReturn(manifest.replace(digest, "0".repeat(64)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        doReturn(first, second).when(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertThrows(BgmTmdbMappings.MappingUnavailableException.class,
                () -> new BgmTmdbMappings(config, json, http).find(Set.of(513352L)));
    }

    @Test
    void fullReadFailureRetainsSnapshotAndBaseline() throws Exception {
        Fixture f = new Fixture();
        f.collection = range(51);
        Source source = f.source(true, "NEW_ONLY", 2);
        assertEquals(51, f.wishlist.items(source.id()).items().size());
        assertTrue(f.wishlist.items(source.id()).items().stream().noneMatch(Item::automaticCandidate));
        f.collection = range(52);
        var good = f.wishlist.refresh(source.id());
        assertEquals(52, good.items().size());
        assertEquals(List.of(52L), candidateIds(good));
        // An HTTP failure, drifting total, repeated subject ID and empty tail must all preserve the complete snapshot.
        for (Object tail : List.of(new IOException("second page unavailable"), f.page(53, 50, List.of(51L, 52L)),
                f.page(52, 50, List.of(50L, 52L)), f.page(52, 50, List.of()), "")) {
            f.responses.add(f.page(52, 0, range(50)));
            f.responses.add(tail);
            var failed = f.wishlist.refresh(source.id());
            assertEquals("FAILED", failed.refreshStatus());
            assertEquals(52, failed.items().size());
            assertEquals(good.lastSuccessAt(), failed.lastSuccessAt());
            assertEquals(List.of(52L), candidateIds(failed));
            assertTrue(failed.items().stream().noneMatch(Item::eligible));
        }
        f.responses.add(f.page(52, 0, range(50)));
        f.responses.add(new IOException("failed execution read"));
        Run run = f.execution.runScheduledIfDue(source.id(), day(6));
        assertEquals("SNAPSHOT_UNAVAILABLE", run.reason().code());
        assertTrue(run.created().isEmpty());
        assertTrue(f.submitted.isEmpty());
        assertNull(f.execution.runScheduledIfDue(source.id(), day(6)));
        assertEquals(52, f.wishlist.refresh(source.id()).items().size());
        assertEquals(List.of(52L), candidateIds(f.wishlist.items(source.id())));
    }

    @Test
    void newOnlyEnablePauseResumePreservesIdCandidates() throws Exception {
        Fixture f = new Fixture();
        f.collection = List.of(1L);
        Source source = f.source(false, "NEW_ONLY", 2);
        f.wishlist.refresh(source.id());
        f.responses.add(new IOException("first enable failed"));
        assertEquals(502, assertThrows(BusinessException.class,
                () -> f.wishlist.updateSource(source.id(), f.input(true, "NEW_ONLY", 2, "1080p"))).getCode());
        assertFalse(f.wishlist.items(source.id()).source().enabled());
        assertFalse(f.wishlist.items(source.id()).source().baselineInitialized());
        f.collection = List.of(1L, 2L);
        f.wishlist.updateSource(source.id(), f.input(true, "NEW_ONLY", 2, "1080p"));
        assertTrue(candidateIds(f.wishlist.items(source.id())).isEmpty());
        f.collection = List.of(1L, 2L, 3L);
        assertEquals(List.of(3L), candidateIds(f.wishlist.refresh(source.id())));
        f.wishlist.updateSource(source.id(), f.input(false, "NEW_ONLY", 2, "1080p"));
        f.collection = List.of(1L, 2L, 3L, 4L);
        f.updateSuffix = "T10:00:00Z";
        assertEquals(List.of(3L, 4L), candidateIds(f.wishlist.refresh(source.id())));
        assertNull(f.execution.runScheduledIfDue(source.id(), day(6)));
        f.wishlist.updateSource(source.id(), f.input(true, "NEW_ONLY", 2, "1080p"));
        assertEquals(List.of(3L, 4L), candidateIds(f.wishlist.items(source.id())));
        f.identify(source, 3, 103);
        f.identify(source, 4, 104);
        // Enabling after the configured minute does not catch up; next configured occurrence executes.
        assertNull(f.execution.runScheduledIfDue(source.id(), day(6).plusMinutes(1)));
        Run run = f.execution.runScheduledIfDue(source.id(), day(7));
        assertEquals(List.of(3L, 4L), run.created().stream().map(outcome -> outcome.item().subjectId()).toList());
        assertNull(run.actorUserId());
        assertEquals("DAILY", run.action());
        assertEquals("SCHEDULED", run.triggerType());
        assertTrue(candidateIds(f.wishlist.items(source.id())).isEmpty());
        f.collection = List.of(5L, 6L);
        assertEquals(List.of(5L, 6L), candidateIds(f.wishlist.items(f.source(true, "INCLUDE_EXISTING", 1).id())));
    }

    @Test
    void manualPreviewIsFixedOneTimeOwnerAndRealQuota() throws Exception {
        Fixture f = new Fixture();
        f.collection = List.of(1L, 2L, 3L, 4L);
        Source source = f.source(false, "NEW_ONLY", 1);
        f.wishlist.refresh(source.id());
        for (int id = 1; id <= 4; id++) f.identify(source, id, 100 + id);
        Preview preview = f.execution.preview(source.id(), new PreviewInput(List.of(1L, 2L, 3L)));
        assertEquals(List.of(1L, 2L, 3L), preview.items().stream().map(Item::subjectId).toList());
        assertEquals("SELECTED", preview.action());
        f.actor.setId(8L);
        assertEquals(403, assertThrows(BusinessException.class,
                () -> f.execution.execute(source.id(), new ExecuteInput(preview.id()))).getCode());
        f.actor.setId(7L);
        f.failedTargets.add(102);
        Run run = f.execution.execute(source.id(), new ExecuteInput(preview.id()));
        assertEquals(List.of(1L, 3L), run.created().stream().map(outcome -> outcome.item().subjectId()).toList());
        assertEquals(2L, run.failed().get(0).item().subjectId());
        assertEquals(List.of(101, 102, 103), f.attempted);
        assertEquals(List.of(9L, 9L, 9L), f.attemptOwners);
        assertEquals(7L, run.actorUserId());
        assertEquals(9L, run.ownerUserId());
        assertEquals(0, run.quotaBefore().used());
        assertEquals(3, run.quotaAfter().used());
        assertEquals(6, run.quotaAfter().remaining());
        assertEquals("PARTIAL_SUCCESS", run.status());
        assertEquals(run, f.execution.run(run.id()));
        assertEquals(run, f.execution.runs(source.id(), 1, 20).items().get(0));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> f.execution.execute(source.id(), new ExecuteInput(preview.id()))).getCode());
        Preview changed = f.execution.preview(source.id(), new PreviewInput(List.of(4L)));
        f.wishlist.updateSource(source.id(), f.input(false, "NEW_ONLY", 1, "2160p"));
        assertEquals(400, assertThrows(BusinessException.class,
                () -> f.execution.execute(source.id(), new ExecuteInput(changed.id()))).getCode());
        assertEquals(List.of(101, 102, 103), f.attempted);
    }

    @Test
    void scheduledFailuresContinueAndStopAtRealQuota() throws Exception {
        Fixture f = new Fixture();
        f.collection = List.of(1L, 2L, 3L, 4L, 5L);
        Source source = f.source(true, "INCLUDE_EXISTING", 2);
        for (int id = 1; id <= 5; id++) f.identify(source, id, 100 + id);
        f.failedTargets.add(101);
        Run first = f.execution.runScheduledIfDue(source.id(), day(6));
        assertEquals(List.of(2L, 3L), first.created().stream().map(outcome -> outcome.item().subjectId()).toList());
        assertEquals(1, first.failed().size());
        assertEquals(3, first.quotaAfter().used());
        assertEquals(List.of(101, 102, 103), f.attempted);
        // A fresh application execution instance uses the same durable day claim.
        assertNull(f.newExecution().runScheduledIfDue(source.id(), day(6)));
        f.owner.setDailyContentCreateLimitOverride(4);
        f.collection = List.of(6L, 7L, 8L);
        Source next = f.source(true, "INCLUDE_EXISTING", 2);
        for (int id = 6; id <= 8; id++) f.identify(next, id, 100 + id);
        f.failedTargets.add(106);
        Run exhausted = f.execution.runScheduledIfDue(next.id(), day(6));
        assertEquals("QUOTA_EXHAUSTED", exhausted.reason().code());
        assertEquals(4, exhausted.quotaAfter().used());
        assertEquals(0, exhausted.quotaAfter().remaining());
        assertEquals(1, exhausted.failed().size());
        assertEquals(List.of(7L, 8L), exhausted.skipped().stream().map(outcome -> outcome.item().subjectId()).toList());
        assertEquals(List.of(101, 102, 103, 106), f.attempted);
        assertEquals(List.of(6L, 7L, 8L), candidateIds(f.wishlist.items(next.id())));
    }

    @Test
    void duplicateTargetsAndPreviousTasksNeverCreateAgain() throws Exception {
        Fixture f = new Fixture();
        f.collection = List.of(1L, 2L);
        Source first = f.source(true, "INCLUDE_EXISTING", 2);
        f.identify(first, 1, 101);
        f.identify(first, 2, 101);
        Preview preview = f.execution.preview(first.id(), new PreviewInput(List.of(1L, 2L)));
        assertEquals(1, preview.items().size());
        assertEquals("DUPLICATE_TARGET", preview.skipped().get(0).reason().code());
        f.collection = List.of(3L);
        f.selectedOwnerId = 10L;
        Source other = f.source(true, "INCLUDE_EXISTING", 2);
        f.identify(other, 3, 101);
        Preview otherPreview = f.execution.preview(other.id(), new PreviewInput(List.of(3L)));
        f.collection = List.of(1L, 2L);
        Run created = f.execution.execute(first.id(), new ExecuteInput(preview.id()));
        f.collection = List.of(3L);
        Run blocked = f.execution.execute(other.id(), new ExecuteInput(otherPreview.id()));
        assertEquals("PREVIOUS_TASK", blocked.skipped().get(0).reason().code());
        assertTrue(blocked.created().isEmpty());
        assertEquals(List.of(101), f.attempted);
        String taskId = created.created().get(0).task().taskId();
        f.active.clear();
        f.taskById.put(taskId, new ActiveTask("MOVIE", taskId, "FAILED"));
        f.collection = List.of(1L, 2L);
        Preview previous = f.execution.preview(first.id(), new PreviewInput(List.of(1L)));
        assertTrue(previous.items().isEmpty());
        assertEquals("PREVIOUS_TASK", previous.skipped().get(0).reason().code());
        assertEquals("FAILED", previous.skipped().get(0).item().associatedTask().status());
        // A different subject in the original source and a different owner's source remain blocked after FAILED.
        Preview alias = f.execution.preview(first.id(), new PreviewInput(List.of(2L)));
        assertTrue(alias.items().isEmpty());
        assertEquals("PREVIOUS_TASK", alias.skipped().get(0).reason().code());
        assertEquals(taskId, alias.skipped().get(0).task().taskId());
        assertEquals("FAILED", alias.skipped().get(0).task().status());
        assertFalse(alias.skipped().get(0).item().automaticCandidate());
        f.collection = List.of(3L);
        Preview otherFailed = f.execution.preview(other.id(), new PreviewInput(List.of(3L)));
        assertTrue(otherFailed.items().isEmpty());
        assertEquals("PREVIOUS_TASK", otherFailed.skipped().get(0).reason().code());
        assertEquals("FAILED", otherFailed.skipped().get(0).item().associatedTask().status());
        assertTrue(f.newExecution().runScheduledIfDue(other.id(), day(7)).created().isEmpty());
        assertEquals(List.of(101), f.attempted);
        // An unrelated ordinary/manual FAILED task is deliberately not a BGM target ledger entry.
        f.taskById.put("manual-failed", new ActiveTask("MOVIE", "manual-failed", "FAILED"));
        f.identify(other, 3, 102);
        assertEquals(1, f.execution.preview(other.id(), new PreviewInput(List.of(3L))).items().size());

        Fixture seasons = new Fixture();
        seasons.collection = List.of(1L, 2L);
        Source seasonSource = seasons.source(true, "INCLUDE_EXISTING", 2);
        seasons.identifySeason(seasonSource, 1, 201, 1);
        seasons.identifySeason(seasonSource, 2, 201, 1);
        Preview seasonPreview = seasons.execution.preview(seasonSource.id(), new PreviewInput(List.of(1L)));
        Run seasonRun = seasons.execution.execute(seasonSource.id(), new ExecuteInput(seasonPreview.id()));
        TaskLink seasonTask = seasonRun.created().get(0).task();
        seasons.active.clear();
        seasons.taskById.put(seasonTask.taskId(), new ActiveTask("SERIES", seasonTask.taskId(), "FAILED"));
        assertEquals("PREVIOUS_TASK", seasons.execution.preview(seasonSource.id(), new PreviewInput(List.of(2L))).skipped().get(0).reason().code());
        seasons.collection = List.of(3L, 4L);
        seasons.selectedOwnerId = 10;
        Source otherSeasons = seasons.source(true, "INCLUDE_EXISTING", 2);
        seasons.identifySeason(otherSeasons, 3, 201, 1);
        seasons.identifySeason(otherSeasons, 4, 201, 2);
        Preview remainingSeason = seasons.execution.preview(otherSeasons.id(), new PreviewInput(List.of(3L, 4L)));
        assertEquals(List.of(4L), remainingSeason.items().stream().map(Item::subjectId).toList());
        assertEquals(seasonTask.taskId(), remainingSeason.skipped().get(0).task().taskId());
        assertEquals("FAILED", remainingSeason.skipped().get(0).task().status());
        assertEquals(List.of(201), seasons.attempted);
    }

    @Test
    void finalGatewayRefusalsPreserveCurrentObservation() throws Exception {
        for (boolean scheduled : List.of(false, true)) {
            Fixture f = new Fixture();
            f.collection = List.of(1L, 2L, 3L, 4L);
            Source source = f.source(true, "INCLUDE_EXISTING", 4);
            for (int id = 1; id <= 4; id++) f.identify(source, id, 100 + id);
            Preview preview = scheduled ? null : f.execution.preview(source.id(), new PreviewInput(range(4)));
            // Eligibility sees ABSENT; the final gateway observes an outage/present/active/prior target.
            doAnswer(call -> {
                Target target = call.getArgument(0);
                switch (target.tmdbId()) {
                    case 101 -> f.unknownPresence.add(101);
                    case 102 -> f.presentTargets.add(102);
                    case 103 -> f.active.put(103, new ActiveTask("MOVIE", "concurrent-active", "DOWNLOADING"));
                    case 104 -> {
                        ActiveTask prior = new ActiveTask("MOVIE", "concurrent-prior", "FAILED");
                        f.ledger.put("MOVIE:104", prior);
                        f.taskById.put(prior.taskId(), prior);
                    }
                    default -> fail("Unexpected target");
                }
                return Fixture.release();
            }).when(f.gateway).recommend(any(), anyString());
            Run run = scheduled ? f.execution.runScheduledIfDue(source.id(), day(6))
                    : f.execution.execute(source.id(), new ExecuteInput(preview.id()));
            assertTrue(run.created().isEmpty());
            assertTrue(run.failed().isEmpty());
            assertEquals(List.of("LIBRARY_UNKNOWN", "ALREADY_PRESENT", "ACTIVE_TASK", "PREVIOUS_TASK"),
                    run.skipped().stream().map(outcome -> outcome.reason().code()).toList());
            assertEquals("UNKNOWN", run.skipped().get(0).item().libraryStatus());
            assertEquals("PRESENT", run.skipped().get(1).item().libraryStatus());
            assertEquals("DOWNLOADING", run.skipped().get(2).item().activeTask().status());
            assertEquals("FAILED", run.skipped().get(3).item().associatedTask().status());
            assertEquals("concurrent-prior", run.skipped().get(3).task().taskId());
            assertEquals(run, f.execution.run(run.id()));
            assertEquals(0, run.quotaAfter().used());
            assertTrue(f.attempted.isEmpty());
        }
    }

    @Test
    void catalogAndStateRejectionsRemainAtApplicationBoundary() throws Exception {
        Fixture f = new Fixture();
        f.collection = range(9);
        Source source = f.source(false, "NEW_ONLY", 9);
        f.wishlist.refresh(source.id());
        assertThrows(BusinessException.class, () -> f.wishlist.correctIdentity(source.id(), 1, new IdentityInput("SERIES", 201, null)));
        when(f.tmdb.getTvDetails(201, "zh-CN")).thenReturn(f.json.readTree("""
                {"id":201,"name":"same title","status":"Ended","seasons":[{"season_number":1,"episode_count":2}]}
                """));
        when(f.tmdb.getTvSeasonDetails(201, 1, "zh-CN")).thenReturn(f.json.readTree("""
                {"id":301,"season_number":1,"episodes":[{"episode_number":1,"air_date":"2020-01-01"},{"episode_number":2,"air_date":"2099-01-01"}]}
                """));
        assertEquals("AIRING", f.wishlist.correctIdentity(source.id(), 2, new IdentityInput("SERIES", 201, 1)).availability());
        f.movie(103, "{\"type\":3,\"release_date\":\"2020-01-01T00:00:00Z\"}");
        assertEquals("THEATRICAL", f.identify(source, 3, 103).availability());
        f.presentTargets.add(104);
        assertEquals("ALREADY_PRESENT", f.identify(source, 4, 104).reasons().get(0).code());
        f.active.put(105, new ActiveTask("MOVIE", "existing", "DOWNLOADING"));
        assertEquals("ACTIVE_TASK", f.identify(source, 5, 105).reasons().get(0).code());
        f.unknownPresence.add(106);
        assertEquals("LIBRARY_UNKNOWN", f.identify(source, 6, 106).reasons().get(0).code());
        f.movie(107, "{\"type\":4,\"release_date\":\"2020-01-01T00:00:00Z\"},{\"type\":3,\"release_date\":\"2099-01-01T00:00:00Z\"}");
        assertTrue(f.identify(source, 7, 107).eligible());
        f.identify(source, 8, 108);
        f.noReleaseTargets.add(108);
        f.movie(109, "{\"type\":4,\"release_date\":\"2099-01-01T00:00:00Z\"}");
        assertEquals("NOT_YET_AIRED", f.identify(source, 9, 109).availability());
        Preview preview = f.execution.preview(source.id(), new PreviewInput(range(9)));
        assertEquals(List.of(7L), preview.items().stream().map(Item::subjectId).toList());
        assertEquals(Set.of("IDENTITY_UNKNOWN", "AIRING", "THEATRICAL", "ALREADY_PRESENT", "ACTIVE_TASK", "LIBRARY_UNKNOWN", "NO_RELEASE", "NOT_YET_AIRED"),
                new HashSet<>(preview.skipped().stream().map(outcome -> outcome.reason().code()).toList()));
        assertTrue(f.submitted.isEmpty());
    }

    private static ZonedDateTime day(int day) {
        return ZonedDateTime.parse("2026-11-" + String.format("%02d", day) + "T09:00:00+08:00[Asia/Shanghai]");
    }

    private static List<Long> range(int count) {
        return java.util.stream.LongStream.rangeClosed(1, count).boxed().toList();
    }

    private static List<Long> candidateIds(Wishlist wishlist) {
        return wishlist.items().stream().filter(Item::automaticCandidate).map(Item::subjectId).sorted().toList();
    }

    /** Only the external I/O boundaries are replaced; orchestration, state merging and JSON persistence stay real. */
    private static final class Fixture {
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final AuthService auth = mock(AuthService.class);
        final UserMapper users = mock(UserMapper.class);
        final TmdbClient tmdb = mock(TmdbClient.class);
        final BgmTmdbMappings mappings = mock(BgmTmdbMappings.class);
        final BgmWishlistTaskGateway gateway;
        final Map<String, ActiveTask> ledger = new HashMap<>();
        final MediaLibraryPresenceService presence = mock(MediaLibraryPresenceService.class);
        final User actor = user(7, "ADMIN");
        final User owner = user(9, "USER");
        final AtomicInteger used = new AtomicInteger();
        final Deque<Object> responses = new ArrayDeque<>();
        final Set<Integer> failedTargets = new HashSet<>();
        final Set<Integer> noReleaseTargets = new HashSet<>();
        final Set<Integer> presentTargets = new HashSet<>();
        final Set<Integer> unknownPresence = new HashSet<>();
        final Map<Integer, ActiveTask> active = new HashMap<>();
        final Map<String, ActiveTask> taskById = new HashMap<>();
        final List<Integer> attempted = new ArrayList<>();
        final List<Integer> submitted = new ArrayList<>();
        final List<Long> attemptOwners = new ArrayList<>();
        List<Long> collection = List.of();
        long selectedOwnerId = 9;
        String updateSuffix = "T00:00:00Z";
        final BgmWishlistSourceStore sources;
        final BgmWishlistExecutionStore store;
        final BgmWishlistService wishlist;
        final UserActionQuotaService quota;
        final BgmWishlistExecutionService execution;

        @SuppressWarnings({"unchecked", "rawtypes"})
        Fixture() throws Exception {
            owner.setDailyContentCreateLimitOverride(9);
            when(auth.requireAdminUser()).thenReturn(actor);
            when(users.selectById(any())).thenAnswer(call -> ((Number) call.getArgument(0)).longValue() == 9 ? owner : user(10, "USER"));
            BgmWishlistSourceMapper sourceRows = mock(BgmWishlistSourceMapper.class);
            Map<String, String> sourceJson = new LinkedHashMap<>();
            doAnswer(call -> { sourceJson.put(call.getArgument(0), call.getArgument(1)); return null; }).when(sourceRows).insertState(anyString(), anyString());
            doAnswer(call -> { sourceJson.put(call.getArgument(0), call.getArgument(1)); return null; }).when(sourceRows).updateState(anyString(), anyString());
            when(sourceRows.selectState(anyString())).thenAnswer(call -> sourceJson.get(call.getArgument(0)));
            when(sourceRows.selectStateForUpdate(anyString())).thenAnswer(call -> sourceJson.get(call.getArgument(0)));
            when(sourceRows.listStates()).thenAnswer(call -> new ArrayList<>(sourceJson.values()));
            PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
            when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
            sources = new BgmWishlistSourceStore(sourceRows, users, json, transactions);

            BgmWishlistExecutionMapper executionRows = mock(BgmWishlistExecutionMapper.class);
            Map<String, String> payloads = new LinkedHashMap<>();
            Map<String, String> kinds = new HashMap<>();
            Map<String, String> sourceIds = new HashMap<>();
            Set<String> consumed = new HashSet<>();
            doAnswer(call -> {
                String id = call.getArgument(0);
                if (payloads.containsKey(id)) throw new DuplicateKeyException("duplicate id");
                kinds.put(id, call.getArgument(1)); sourceIds.put(id, call.getArgument(2)); payloads.put(id, call.getArgument(4));
                return null;
            }).when(executionRows).insert(anyString(), anyString(), anyString(), nullable(Long.class), anyString());
            when(executionRows.select(anyString(), anyString())).thenAnswer(call ->
                    Objects.equals(kinds.get(call.getArgument(0)), call.getArgument(1)) ? payloads.get(call.getArgument(0)) : null);
            when(executionRows.consumePreview(anyString(), anyLong())).thenAnswer(call -> consumed.add(call.getArgument(0)) ? 1 : 0);
            doAnswer(call -> { payloads.put(call.getArgument(0), call.getArgument(1)); return null; }).when(executionRows).updateRun(anyString(), anyString());
            when(executionRows.listRuns(nullable(String.class), anyLong(), anyInt())).thenAnswer(call -> {
                List<String> ids = new ArrayList<>(payloads.keySet()); Collections.reverse(ids);
                return ids.stream().filter(id -> "RUN".equals(kinds.get(id)))
                        .filter(id -> call.getArgument(0) == null || sourceIds.get(id).equals(call.getArgument(0)))
                        .skip(call.<Long>getArgument(1)).limit(call.<Integer>getArgument(2)).map(payloads::get).toList();
            });
            when(executionRows.countRuns(nullable(String.class))).thenAnswer(call -> kinds.entrySet().stream()
                    .filter(entry -> "RUN".equals(entry.getValue()))
                    .filter(entry -> call.getArgument(0) == null || sourceIds.get(entry.getKey()).equals(call.getArgument(0))).count());
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet result = mock(ResultSet.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(result);
            when(result.next()).thenReturn(true);
            when(result.getInt(1)).thenReturn(1);
            BgmWishlistSessionLocks locks = new BgmWishlistSessionLocks(dataSource);
            store = new BgmWishlistExecutionStore(executionRows, json, locks);
            BgmWishlistTargetTaskMapper targetRows = mock(BgmWishlistTargetTaskMapper.class);
            when(targetRows.select(anyString())).thenAnswer(call -> ledger.get(call.getArgument(0)));
            doAnswer(call -> { ledger.put(call.getArgument(0), call.getArgument(1)); return null; })
                    .when(targetRows).insert(anyString(), any());
            ProwlarrReleaseIngestService releases = mock(ProwlarrReleaseIngestService.class);
            gateway = spy(new BgmWishlistTaskGateway(mock(MovieMagnetIngestTaskMapper.class),
                    mock(SeriesMagnetIngestTaskMapper.class), mock(AnimeMagnetIngestTaskMapper.class),
                    releases, presence, locks, targetRows));

            HttpClient http = mock(HttpClient.class);
            when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
                String query = call.<HttpRequest>getArgument(0).uri().getQuery();
                int offset = Integer.parseInt(query.substring(query.lastIndexOf("offset=") + 7));
                Object body = responses.isEmpty() ? page(collection.size(), offset,
                        collection.subList(Math.min(offset, collection.size()), Math.min(offset + 50, collection.size()))) : responses.remove();
                if (body instanceof IOException failure) throw failure;
                HttpResponse<String> response = mock(HttpResponse.class);
                when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn(body.toString());
                return response;
            });
            BangumiProperties config = new BangumiProperties();
            config.setBaseUrl("https://bgm.invalid"); config.setUserAgent("BgmWishlistWorkflowTest"); config.setTimeout(Duration.ofSeconds(1));
            config.setCatalogConcurrency(4);
            wishlist = new BgmWishlistService(auth, sources, new BangumiClient(config, json, http), tmdb, presence, gateway, mappings, config);
            when(presence.checkForAutomation(anyString(), anyInt(), isNull(), nullable(Integer.class))).thenAnswer(call -> {
                int id = call.getArgument(1);
                return new MediaLibraryPresenceResponse(!unknownPresence.contains(id), presentTargets.contains(id), id, null, call.getArgument(3));
            });
            doAnswer(call -> active.get(call.<Target>getArgument(0).tmdbId())).when(gateway).activeTask(any());
            doAnswer(call -> taskById.get(call.getArgument(1))).when(gateway).task(anyString(), anyString());
            doAnswer(call -> noReleaseTargets.contains(call.<Target>getArgument(0).tmdbId()) ? null : release()).when(gateway).recommend(any(), anyString());
            when(releases.ingestSelectedMovieForOwner(any(), any())).thenAnswer(call -> {
                var request = call.<com.medianexus.orchestrator.dto.resources.request.MovieReleaseOpenListIngestRequest>getArgument(0);
                Target target = new Target("MOVIE", request.tmdbId(), null, request.title(), null, null);
                User taskOwner = call.getArgument(1);
                attempted.add(target.tmdbId()); attemptOwners.add(taskOwner.getId()); used.incrementAndGet();
                if (failedTargets.contains(target.tmdbId())) throw new BusinessException(ErrorCode.BAD_REQUEST, "simulated failure after quota consumption");
                ActiveTask task = new ActiveTask(target.mediaType(), "task-" + target.tmdbId(), "PENDING");
                active.put(target.tmdbId(), task); taskById.put(task.taskId(), task); submitted.add(target.tmdbId());
                MovieMagnetIngestTaskResponse response = mock(MovieMagnetIngestTaskResponse.class);
                when(response.id()).thenReturn(task.taskId()); when(response.status()).thenReturn(task.status());
                return response;
            });
            when(releases.ingestSelectedAnimeForOwner(any(), any())).thenAnswer(call -> {
                var request = call.<com.medianexus.orchestrator.dto.resources.request.SeriesReleaseOpenListIngestRequest>getArgument(0);
                User taskOwner = call.getArgument(1);
                attempted.add(request.tmdbId()); attemptOwners.add(taskOwner.getId()); used.incrementAndGet();
                ActiveTask task = new ActiveTask("SERIES", "season-task-" + request.tmdbId() + "-" + request.seasonNumber(), "PENDING");
                active.put(request.tmdbId(), task); taskById.put(task.taskId(), task); submitted.add(request.tmdbId());
                SeriesMagnetIngestTaskResponse response = mock(SeriesMagnetIngestTaskResponse.class);
                when(response.id()).thenReturn(task.taskId()); when(response.status()).thenReturn(task.status());
                return response;
            });
            UserActionUsageMapper usageRows = mock(UserActionUsageMapper.class);
            when(usageRows.sumUsageCount(anyLong(), any(), anyList())).thenAnswer(call -> used.get());
            UserQuotaSettingsService settings = new UserQuotaSettingsService(mock(SystemSettingMapper.class),
                    new UserQuotaProperties(), auth, mock(UserAdminAuditService.class));
            quota = new UserActionQuotaService(usageRows, settings);
            execution = newExecution();
        }

        BgmWishlistExecutionService newExecution() {
            return new BgmWishlistExecutionService(auth, wishlist, sources, store, gateway, quota, users);
        }

        SourceInput input(boolean enabled, String scope, int count, String quality) {
            return new SourceInput("same_bgm_user", selectedOwnerId, enabled, "09:00", count, quality, scope);
        }

        Source source(boolean enabled, String scope, int count) {
            return wishlist.createSource(input(enabled, scope, count, "1080p"));
        }

        Item identify(Source source, long subjectId, int tmdbId) throws Exception {
            if (mockingDetails(tmdb).getStubbings().stream().noneMatch(stub ->
                    stub.getInvocation().getMethod().getName().equals("getMovieDetails")
                            && Objects.equals(stub.getInvocation().getArgument(0), tmdbId))) {
                movie(tmdbId, "{\"type\":4,\"release_date\":\"2020-01-01T00:00:00Z\"}");
            }
            return wishlist.correctIdentity(source.id(), subjectId, new IdentityInput("MOVIE", tmdbId, null));
        }

        Item identifySeason(Source source, long subjectId, int tmdbId, int season) throws Exception {
            when(tmdb.getTvDetails(tmdbId, "zh-CN")).thenReturn(json.readTree("{\"id\":" + tmdbId
                    + ",\"name\":\"series\",\"seasons\":[{\"season_number\":1,\"episode_count\":1},{\"season_number\":2,\"episode_count\":1}]}"));
            when(tmdb.getTvSeasonDetails(tmdbId, season, "zh-CN")).thenReturn(json.readTree("{\"id\":301,\"season_number\":"
                    + season + ",\"episodes\":[{\"episode_number\":1,\"air_date\":\"2020-01-01\"}]}"));
            return wishlist.correctIdentity(source.id(), subjectId, new IdentityInput("SERIES", tmdbId, season));
        }

        void movie(int id, String releases) throws Exception {
            when(tmdb.getMovieDetails(id, "zh-CN")).thenReturn(json.readTree("{\"id\":" + id + ",\"title\":\"same title\",\"release_date\":\"2020-01-01\"}"));
            when(tmdb.getMovieReleaseDates(id)).thenReturn(json.readTree("{\"results\":[{\"release_dates\":[" + releases + "]}]}"));
        }

        String page(int total, int offset, List<Long> ids) {
            ObjectNode root = json.createObjectNode().put("total", total).put("offset", offset).put("limit", 50);
            ArrayNode entries = root.putArray("data");
            for (long id : ids) {
                ObjectNode entry = entries.addObject().put("subject_id", id).put("subject_type", 2).put("type", 1)
                        .put("updated_at", "2026-01-" + String.format("%02d", 28 - (id % 28)) + updateSuffix);
                entry.putObject("subject").put("id", id).put("type", 2).put("name", "same title").put("date", "2020-01-01");
            }
            return root.toString();
        }

        static User user(long id, String role) {
            User user = new User(); user.setId(id); user.setUsername("user-" + id); user.setRole(role);
            user.setDailyContentCreateLimitOverride(9); return user;
        }

        static ProwlarrReleaseItemResponse release() {
            return new ProwlarrReleaseItemResponse("release 1080p", 1L, 1, null, null, "indexer", null,
                    1, "opaque-ref", List.of("1080p"), List.of(), List.of(), null, null);
        }
    }
}
