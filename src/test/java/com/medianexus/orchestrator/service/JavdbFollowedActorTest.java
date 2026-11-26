package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.JavdbActorProperties;
import com.medianexus.orchestrator.integration.emby.*;
import com.medianexus.orchestrator.integration.javdb.*;
import com.medianexus.orchestrator.mapper.*;
import com.medianexus.orchestrator.model.*;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

class JavdbFollowedActorTest {
    private final SystemSettingMapper settings = mock(SystemSettingMapper.class);
    private final JavdbClient client = mock(JavdbClient.class);
    private final EmbyClient emby = mock(EmbyClient.class);
    private final AdultMagnetIngestService ingest = mock(AdultMagnetIngestService.class);
    private final AdultMagnetIngestTaskMapper tasks = mock(AdultMagnetIngestTaskMapper.class);
    private final JavdbAutomationRunMapper runs = mock(JavdbAutomationRunMapper.class);
    private final JavdbAutomationRunItemMapper items = mock(JavdbAutomationRunItemMapper.class);
    private final JavdbAutomationLedgerMapper ledger = mock(JavdbAutomationLedgerMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final JavdbActorProperties actors = defaults();
    private final JavdbAutomationService service = new JavdbAutomationService(mock(AuthService.class), settings, client,
            emby, ingest, tasks, runs, items, ledger, mock(JavdbAutomationRunLogMapper.class),
            mock(JavdbPlaylistMembershipMapper.class), mock(JavdbPlaylistSyncRunMapper.class),
            mock(JavdbPlaylistHistoryMapper.class), mock(IngestExceptionNotifier.class), json, actors);
    private final List<JavdbAutomationRunItem> saved = new ArrayList<>();

    @BeforeEach void setUp() {
        for (Class<?> type : List.of(JavdbAutomationRun.class, JavdbAutomationRunItem.class,
                JavdbAutomationLedger.class, AdultMagnetIngestTask.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "actors"), type);
        }
        when(settings.selectSettingValue("javdb_automation_cookie")).thenReturn("cookie");
        when(settings.selectSettingValue("javdb_automation_config")).thenReturn("""
                {"dailyEnabled":true,"weeklyEnabled":false,"monthlyEnabled":false,"minimumRating":4.5,
                 "minimumReviewCount":100,"excludedTags":"熟女","limitPerRanking":60}
                """);
        when(emby.listLibraries()).thenReturn(List.of(new EmbyLibrary("lib", "Adult-JAV", List.of("/jav"))));
        when(emby.listLibraryVideoItems("lib")).thenReturn(List.of());
        when(ledger.selectList(any())).thenReturn(List.of());
        when(tasks.selectList(any())).thenReturn(List.of());
        when(items.insert(any(JavdbAutomationRunItem.class))).thenAnswer(call -> { saved.add(call.getArgument(0)); return 1; });
        when(client.ranking("daily", "cookie")).thenReturn(List.of(
                new JavdbRankingMovie("ABC-123", "ordinary title", "https://javdb.com/v/one", "2026-01-01", "daily", 1, true, 3.2, 200)));
    }

    @AfterEach void close() { ((java.util.concurrent.ExecutorService) ReflectionTestUtils.getField(service, "executorService")).shutdownNow(); }

    @Test void bindsAllElevenDefaultsAndEveryRequestedAliasExactly() {
        assertThat(actors.getFollowed()).hasSize(11).containsKeys("彩月七緒", "瀬戸環奈", "みなと羽琉", "北野未奈", "鷲尾めい", "神木麗", "白峰ミウ", "本庄鈴", "楓カレン", "橘メアリー", "涼森れむ");
        actors.getFollowed().forEach((canonical, aliases) -> {
            assertThat(JavdbAutomationService.matchedActors(List.of(canonical), actors.getFollowed())).containsExactly(canonical);
            aliases.forEach(alias -> assertThat(JavdbAutomationService.matchedActors(List.of(alias), actors.getFollowed())).containsExactly(canonical));
        });
        assertThat(JavdbAutomationService.matchedActors(List.of("楓 カレン", "白峰美羽", "橘瑪麗", "傻梦"), actors.getFollowed()))
                .containsExactlyInAnyOrder("楓カレン", "白峰ミウ", "橘メアリー", "涼森れむ");
        assertThat(JavdbAutomationService.matchedActors(List.of("りあな", "メアリー別人", "メアリさん子"), actors.getFollowed())).isEmpty();
    }

    @Test void lowRatingAliasBecomesReadyWithCanonicalReason() {
        detail(3.2, 200, List.of(), List.of("田中レモン"));
        executeDryRun();
        assertThat(saved).singleElement().satisfies(item -> {
            assertThat(item.getStatus()).isEqualTo("READY_TO_SUBMIT");
            assertThat(item.getSelectedReason()).contains("命中关注演员「楓カレン」", "允许低于最低评分");
        });
        verifyNoInteractions(ingest);
    }

    @Test void lowRatingUnfollowedAndTitleOnlyMentionsStayExcluded() {
        detail(3.2, 200, List.of(), List.of("其他演员"));
        executeDryRun();
        assertThat(saved).singleElement().extracting(JavdbAutomationRunItem::getStatus).isEqualTo("NO_MAGNET");
    }

    @Test void followedActorStillRespectsExcludedTagAndReviewThreshold() {
        detail(3.2, 200, List.of("熟女"), List.of("彩月七緒"));
        executeDryRun();
        assertThat(saved).singleElement().extracting(JavdbAutomationRunItem::getStatus).isEqualTo("NO_MAGNET");
        saved.clear();
        detail(3.2, 99, List.of(), List.of("彩月七緒"));
        executeDryRun();
        assertThat(saved).singleElement().extracting(JavdbAutomationRunItem::getStatus).isEqualTo("NO_MAGNET");
    }

    @Test void followedActorStillRequiresRequestedDownloadEdition() throws Exception {
        when(settings.selectSettingValue("javdb_automation_config")).thenReturn("""
                {"dailyEnabled":true,"weeklyEnabled":false,"monthlyEnabled":false,"minimumRating":4.5,
                 "minimumReviewCount":100,"excludedTags":"","subtitleOnly":true,"limitPerRanking":60}
                """);
        detail(3.2, 200, List.of(), List.of("彩月七緒"));
        executeDryRun();
        assertThat(saved).singleElement().extracting(JavdbAutomationRunItem::getStatus).isEqualTo("NO_MAGNET");
    }

    @Test void alreadyInEmbyIsDeduplicatedBeforeActorDetailRequest() {
        when(emby.listLibraryVideoItems("lib")).thenReturn(List.of(new EmbyItem("id", "ABC-123", "Movie", "/jav/ABC-123.strm", null, null)));
        executeDryRun();
        assertThat(saved).singleElement().extracting(JavdbAutomationRunItem::getStatus).isEqualTo("ALREADY_IN_EMBY");
        verify(client, never()).detail(anyString(), anyString(), anyString());
    }

    @Test void emptyFollowListKeepsEarlyRankingFilter() {
        actors.setFollowed(Map.of());
        executeDryRun();
        verify(client, never()).detail(anyString(), anyString(), anyString());
        assertThat(saved).singleElement().extracting(JavdbAutomationRunItem::getStatus).isEqualTo("NO_MAGNET");
    }

    @Test void oldDryRunWithoutActorPolicyIsNotReused() throws Exception {
        Object config = ReflectionTestUtils.invokeMethod(service, "loadConfig");
        var oldPolicy = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(config);
        oldPolicy.remove("followedActors");
        var old = new JavdbAutomationRun(); old.setId("old"); old.setConfigSnapshot(json.writeValueAsString(oldPolicy));
        when(runs.selectOne(any())).thenReturn(old);
        Boolean reused = ReflectionTestUtils.invokeMethod(service, "executeFromLatestDryRun", new JavdbAutomationRun(), config);
        assertThat(reused).isFalse();
        verify(items, never()).selectList(any());
    }

    @Test void actorPolicySnapshotChangesWhenAliasListChanges() throws Exception {
        Object first = ReflectionTestUtils.invokeMethod(service, "loadConfig");
        actors.setFollowed(Map.of("楓カレン", List.of("new alias")));
        Object second = ReflectionTestUtils.invokeMethod(service, "loadConfig");
        assertThat(first).isNotEqualTo(second);
        Object historical = ReflectionTestUtils.invokeMethod(service, "readConfigSnapshot", json.writeValueAsString(first));
        assertThat(historical).isEqualTo(first);
    }

    @Test void weeklyMonthlyDuplicateIsFetchedOnceAndCanBypassMissingRating() {
        when(settings.selectSettingValue("javdb_automation_config")).thenReturn("""
                {"dailyEnabled":false,"weeklyEnabled":true,"monthlyEnabled":true,"minimumRating":4.5,
                 "minimumReviewCount":100,"excludedTags":"","limitPerRanking":60}
                """);
        when(client.ranking("weekly", "cookie")).thenReturn(List.of(
                new JavdbRankingMovie("ABC-123", "title", "https://javdb.com/v/one", null, "weekly", 2, true, null, 200)));
        when(client.ranking("monthly", "cookie")).thenReturn(List.of(
                new JavdbRankingMovie("ABC-123", "title", "https://javdb.com/v/one", null, "monthly", 5, true, null, 200)));
        detail(null, 200, List.of(), List.of("白峰美羽"));
        executeDryRun();
        assertThat(saved).singleElement().satisfies(item -> {
            assertThat(item.getStatus()).isEqualTo("READY_TO_SUBMIT");
            assertThat(item.getSelectedReason()).contains("白峰ミウ");
        });
        verify(client, times(1)).detail(anyString(), anyString(), anyString());
    }

    @Test void ordinaryActorCannotUseHighDetailScoreToBypassLowRankingScore() {
        detail(4.9, 200, List.of(), List.of("其他演员"));
        executeDryRun();
        assertThat(saved).singleElement().extracting(JavdbAutomationRunItem::getStatus).isEqualTo("NO_MAGNET");
    }

    @Test void top250KeepsOriginalEarlyRatingFilter() throws Exception {
        Object base = ReflectionTestUtils.invokeMethod(service, "loadConfig");
        var policy = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(base);
        policy.put("rankingSource", "TOP_250");
        Object config = ReflectionTestUtils.invokeMethod(service, "readConfigSnapshot", json.writeValueAsString(policy));
        when(settings.selectSettingValue("javdb_top_cookie")).thenReturn("top-cookie");
        when(client.topRanking(anyInt(), anyInt(), eq("top-cookie"))).thenReturn(List.of(
                new JavdbRankingMovie("ABC-123", "楓カレン", "https://javdb.com/v/one", null, "top_2026", 1, true, 3.2, 200)));
        var run = new JavdbAutomationRun(); run.setId("top"); run.setExecutionMode("DRY_RUN");
        ReflectionTestUtils.invokeMethod(service, "executePipeline", run, config);
        assertThat(saved).singleElement().extracting(JavdbAutomationRunItem::getStatus).isEqualTo("NO_MAGNET");
        verify(client, never()).detail(anyString(), anyString(), anyString());
    }

    @Test void sameActorPolicyCanReuseCurrentDryRun() throws Exception {
        Object config = ReflectionTestUtils.invokeMethod(service, "loadConfig");
        var previous = new JavdbAutomationRun(); previous.setId("dry"); previous.setConfigSnapshot(json.writeValueAsString(config));
        when(runs.selectOne(any())).thenReturn(previous);
        when(items.selectList(any())).thenReturn(List.of());
        Boolean reused = ReflectionTestUtils.invokeMethod(service, "executeFromLatestDryRun", new JavdbAutomationRun(), config);
        assertThat(reused).isTrue();
        verifyNoInteractions(client);
    }

    @Test void ltxEd2kWinsOverLargerCrackedSubtitledMagnet() {
        String ed2k = "ed2k://|file|ABC-123-LTX.mkv|11042905835|0123456789abcdef0123456789abcdef|/";
        var ltx = new JavdbMagnet(ed2k, "ABC-123-LTX.mkv", "ed2k:0123456789abcdef0123456789abcdef", 11042905835L,
                false, true, List.of("LTX", "破解"), "filename_rule");
        var subtitled = new JavdbMagnet("magnet:?xt=urn:btih:abcdef&dn=ABC-123-UC", "ABC-123-UC", "abcdef", 20000000000L,
                true, true, List.of("破解", "中文字幕"), "filename_rule");
        when(client.detail("https://javdb.com/v/one", "ABC-123", "cookie"))
                .thenReturn(new JavdbMovieDetail("ABC-123", "title", "https://javdb.com/v/one",
                        List.of(subtitled, ltx), 4.8, 200, List.of(), List.of("彩月七緒")));
        executeDryRun();
        assertThat(saved).singleElement().satisfies(item -> {
            assertThat(item.getSelectedMagnet()).isEqualTo(ed2k);
            assertThat(item.getSelectedReason()).contains("LTX", "体积");
        });
        verifyNoInteractions(ingest);
    }

    @Test void oldDryRunWithoutLinkSelectionPolicyIsNotReused() throws Exception {
        Object config = ReflectionTestUtils.invokeMethod(service, "loadConfig");
        var oldPolicy = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(config);
        oldPolicy.remove("linkSelectionPolicy");
        var old = new JavdbAutomationRun(); old.setId("old"); old.setConfigSnapshot(json.writeValueAsString(oldPolicy));
        when(runs.selectOne(any())).thenReturn(old);
        Boolean reused = ReflectionTestUtils.invokeMethod(service, "executeFromLatestDryRun", new JavdbAutomationRun(), config);
        assertThat(reused).isFalse();
        verify(items, never()).selectList(any());
    }

    @Test void encodedEd2kActiveTaskIsDeduplicatedBeforeFetchingDetail() {
        var task = new AdultMagnetIngestTask();
        task.setId("active-ed2k");
        task.setDownloadLinksJson("[\"ed2k://|file|ABC%2D123-LTX.mkv|11042905835|0123456789abcdef0123456789abcdef|/\"]");
        when(tasks.selectList(any())).thenReturn(List.of(task));
        executeDryRun();
        assertThat(saved).singleElement().extracting(JavdbAutomationRunItem::getStatus).isEqualTo("ADULT_IN_PROGRESS");
        verify(client, never()).detail(anyString(), anyString(), anyString());
    }

    private void detail(Double rating, int reviews, List<String> tags, List<String> names) {
        var magnet = new JavdbMagnet("magnet:?xt=urn:btih:abcdef&dn=ABC-123", "ABC-123", "abcdef", 1000L, false, false, List.of(), "filename_rule");
        when(client.detail("https://javdb.com/v/one", "ABC-123", "cookie"))
                .thenReturn(new JavdbMovieDetail("ABC-123", "楓カレン appears only in title", "https://javdb.com/v/one", List.of(magnet), rating, reviews, tags, names));
    }

    private void executeDryRun() {
        Object config = ReflectionTestUtils.invokeMethod(service, "loadConfig");
        var run = new JavdbAutomationRun(); run.setId("run"); run.setExecutionMode("DRY_RUN");
        run.setStartedAt(LocalDateTime.now());
        ReflectionTestUtils.invokeMethod(service, "executePipeline", run, config);
    }

    private static JavdbActorProperties defaults() {
        try {
            var environment = new StandardEnvironment();
            new YamlPropertySourceLoader().load("default", new ClassPathResource("application.yml"))
                    .forEach(source -> environment.getPropertySources().addLast(source));
            return Binder.get(environment).bind("medianexus.javdb-actors", Bindable.of(JavdbActorProperties.class)).orElseThrow(IllegalStateException::new);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
