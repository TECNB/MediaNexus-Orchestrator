package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.integration.emby.*;
import com.medianexus.orchestrator.mapper.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 只连接显式指定的本机临时 schema；不启动应用或读取 .env。 */
class EmbyWatchCollectionTest {
    JdbcTemplate jdbc;
    SqlSessionTemplate sql;
    EmbyWatchSessionMapper history;
    EmbyClient client;
    EmbyItemMetadataLookup metadata;
    EmbyPlaybackSnapshotCollector collector;
    DataSourceTransactionManager transactions;
    MutableClock clock = new MutableClock();

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("EMBY_WATCH_TEST_JDBC_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(url != null, "真实 MySQL 测试需显式设置 EMBY_WATCH_TEST_JDBC_URL；未配置时跳过，不使用默认数据库");
        if (!url.startsWith("jdbc:mysql://127.0.0.1:43379/emby_watch_collection_test?")) {
            throw new IllegalStateException("Refusing non-disposable DB");
        }
        var ds = new DriverManagerDataSource(url, System.getenv("EMBY_WATCH_TEST_JDBC_USER"), System.getenv("EMBY_WATCH_TEST_JDBC_PASSWORD"));
        jdbc = new JdbcTemplate(ds);
        for (String table : List.of("emby_watch_daily_contributions", "emby_watch_sessions", "emby_active_playback_sessions", "emby_watch_collection_checkpoint")) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(EmbyActivePlaybackSessionMapper.class);
        config.addMapper(EmbyWatchSessionMapper.class);
        config.addMapper(EmbyWatchDetailsMapper.class);
        config.addMapper(EmbyWatchCollectionMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setConfiguration(config); factory.setDataSource(ds);
        sql = new SqlSessionTemplate(factory.getObject());
        sql.getMapper(EmbyActivePlaybackSessionMapper.class).createTableIfNotExists();
        history = sql.getMapper(EmbyWatchSessionMapper.class);
        history.createTableIfNotExists(); history.createDailyContributionsTableIfNotExists();
        client = mock(EmbyClient.class);
        metadata = mock(EmbyItemMetadataLookup.class);
        new com.medianexus.orchestrator.config.EmbyWatchCollectionSchema(jdbc).initialize();
        transactions = new DataSourceTransactionManager(ds);
        rebuildCollector();
    }

    @Test void collectionPersistsActiveEstimateWithoutEnteringFormalRanking() {
        when(client.listPlaybackSessions()).thenReturn(List.of(playing("one", false)));
        collector.collectSnapshot();
        assertThat(collector.currentSnapshot().sessions()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM emby_active_playback_sessions", Long.class)).isEqualTo(1L);
        assertThat(history.selectSummary(LocalDate.of(2026,11,5), LocalDate.of(2026,11,6)).getTotalPlayCount()).isZero();
    }

    @Test void pauseResumeAndDisappearanceSettleElapsedTimeOnlyOnce() {
        sample("2026-11-05T04:00:00Z", List.of(playing("one", false)));
        sample("2026-11-05T04:00:03Z", List.of(playing("one", true)));
        sample("2026-11-05T04:00:08Z", List.of(playing("one", false)));
        sample("2026-11-05T04:00:12Z", List.of(playing("one", false)));
        sample("2026-11-05T04:00:13Z", List.of());
        sample("2026-11-05T04:00:14Z", List.of());
        var summary = history.selectSummary(LocalDate.of(2026,11,5), LocalDate.of(2026,11,6));
        assertThat(summary.getTotalPlayCount()).isEqualTo(1L);
        assertThat(summary.getTotalWatchSeconds()).isEqualTo(7L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM emby_active_playback_sessions", Long.class)).isZero();
        var query=new EmbyWatchDetailsQuery(mock(AuthService.class),sql.getMapper(EmbyWatchDetailsMapper.class),collector);
        assertThat(query.users("day","2026-11-05",null,1,20).items()).singleElement()
                .satisfies(user -> { assertThat(user.watchSeconds()).isEqualTo(7L); assertThat(user.playCount()).isEqualTo(1L); });
        assertThat(query.sessions("user",null,null,"day","2026-11-05",null,1,20).items()).singleElement()
                .satisfies(session -> { assertThat(session.watchSeconds()).isEqualTo(7L); assertThat(session.source()).isEqualTo("sampling");
                    assertThat(session.stopTimeSource()).isEqualTo("inferred"); });
        assertThat(query.media("unknown","duration","day","2026-11-05",null,1,20).items()).singleElement()
                .satisfies(work -> { assertThat(work.title()).isEqualTo("分类待确认"); assertThat(work.posterItemId()).isNull(); });
        assertThat(query.viewers("film","unknown","day","2026-11-05",null,1,20).items()).singleElement()
                .satisfies(viewer -> assertThat(viewer.watchSeconds()).isEqualTo(7L));
    }
    @Test void fractionalMidnightCountsBothContributingDaysButDeduplicatesMonth() {
        sample("2026-11-05T15:59:59.800Z", List.of(playing("one", false)));
        sample("2026-11-05T16:00:01.200Z", List.of(playing("one", true)));
        sample("2026-11-05T16:00:02Z", List.of());
        var first=history.selectSummary(LocalDate.of(2026,11,5), LocalDate.of(2026,11,6));
        var second=history.selectSummary(LocalDate.of(2026,11,6), LocalDate.of(2026,11,7));
        var month=history.selectSummary(LocalDate.of(2026,11,1), LocalDate.of(2026,12,1));
        assertThat(first.getTotalPlayCount()).isEqualTo(1L); assertThat(first.getTotalWatchSeconds()).isZero();
        assertThat(second.getTotalPlayCount()).isEqualTo(1L); assertThat(second.getTotalWatchSeconds()).isEqualTo(1L);
        assertThat(month.getTotalPlayCount()).isEqualTo(1L); assertThat(month.getTotalWatchSeconds()).isEqualTo(1L);
    }

    @Test void detailsReadOnlySavedCutoffAndFreezeWhenCollectionFails() {
        sample("2026-11-05T04:00:00Z", List.of(playing("one", false)));
        sample("2026-11-05T04:00:03Z", List.of(playing("one", false)));
        var query=new EmbyWatchDetailsQuery(mock(AuthService.class), sql.getMapper(EmbyWatchDetailsMapper.class), collector);
        clearInvocations(client);
        var item=query.activity("day","2026-11-05",null,1,20).items().get(0);
        assertThat(item.watchSeconds()).isEqualTo(3L);
        verifyNoInteractions(client);
        when(client.listPlaybackSessions()).thenThrow(new EmbyClientException("failure"));
        collector.collectSnapshot(); clearInvocations(client);
        item=query.activity("day","2026-11-05",null,1,20).items().get(0);
        assertThat(item.watchSeconds()).isEqualTo(3L); assertThat(item.state()).isEqualTo("unverified");
        assertThat(item.observedAt()).isEqualTo(LocalDateTime.parse("2026-11-05T12:00:03"));
        verifyNoInteractions(client);
    }

    @Test void oneObservationAtFractionalMidnightCreditsFirstDateAndKeepsRealEnd() {
        sample("2026-11-05T15:59:59.800Z", List.of(playing("one", false)));
        sample("2026-11-05T16:00:01Z", List.of());
        var first=history.selectSummary(LocalDate.of(2026,11,5),LocalDate.of(2026,11,6));
        assertThat(first.getTotalPlayCount()).isEqualTo(1L); assertThat(first.getTotalWatchSeconds()).isEqualTo(1L);
        assertThat(history.selectSummary(LocalDate.of(2026,11,6),LocalDate.of(2026,11,7)).getTotalPlayCount()).isZero();
        var ended=history.selectRecentWatchSessions(8).get(0);
        assertThat(ended.getStartTime()).isEqualTo(LocalDateTime.parse("2026-11-05T23:59:59.800"));
        assertThat(ended.getStopTime()).isEqualTo(ended.getStartTime());
        assertThat(ended.getStopTimeSource()).isEqualTo("inferred");
    }

    @Test void replaySameSecondHasIndependentIdentityAndZeroContributionKeepsBothMovements() {
        sample("2026-11-05T04:00:00.100Z",List.of(playing("one",false)));
        sample("2026-11-05T04:00:00.200Z",List.of());
        sample("2026-11-05T04:00:00.300Z",List.of(playing("one",false)));
        sample("2026-11-05T04:00:00.400Z",List.of());
        sample("2026-11-05T04:00:00.500Z",List.of(playing("paused",true),playing("unknown",null)));
        sample("2026-11-05T04:00:00.600Z",List.of());
        var summary=history.selectSummary(LocalDate.of(2026,11,5),LocalDate.of(2026,11,6));
        assertThat(summary.getTotalPlayCount()).isEqualTo(2L); assertThat(summary.getTotalWatchSeconds()).isEqualTo(2L);
        assertThat(history.selectRecentWatchSessions(8)).hasSize(4).extracting(s -> s.getPlaybackInstanceId()).doesNotHaveDuplicates();
        var ranking=new EmbyWatchRankingService(mock(AuthService.class),new com.medianexus.orchestrator.config.EmbyProperties(),
                sql.getMapper(EmbyActivePlaybackSessionMapper.class),history);
        var movements=ranking.getRankings("day","2026-11-05",null,20).webhookStatus().recentEvents();
        assertThat(movements).hasSize(8);
        assertThat(movements).filteredOn(e -> e.event().equals("playback.start")).hasSize(4);
        assertThat(movements).filteredOn(e -> e.event().equals("playback.stop")).hasSize(4);
        assertThat(movements).allSatisfy(e -> assertThat(e.source()).isEqualTo("sampling"));
    }

    @Test void unknownFreezesWithoutLosingIdentityAndResumeOpensFreshSpan() {
        sample("2026-11-05T04:00:00Z",List.of(playing("one",false)));
        sample("2026-11-05T04:00:02Z",List.of(playing("one",false)));
        sample("2026-11-05T04:00:05Z",List.of(playing("one",null)));
        sample("2026-11-05T04:00:07Z",List.of(playing("one",false)));
        sample("2026-11-05T04:00:09Z",List.of(playing("one",true)));
        sample("2026-11-05T04:00:10Z",List.of());
        assertThat(history.selectRecentWatchSessions(8)).singleElement().satisfies(s -> assertThat(s.getWatchSeconds()).isEqualTo(4));
    }

    @Test void sensitiveLiveResponsesAreRedactedAndPrivateTitleRevealUsesActiveRows() throws Exception {
        when(metadata.findItemMetadata("user","film")).thenReturn(Optional.of(new EmbyItemMetadata("film","Movie","secret title",null,null,null,null,null,"adult","library","Adult")));
        sample("2026-11-05T04:00:00Z",List.of(playing("one",false)));
        var live=collector.currentSnapshot().sessions().get(0);
        assertThat(live.title()).isEqualTo("私密观看"); assertThat(live.posters()).isEmpty(); assertThat(live.seriesName()).isNull();
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(collector.currentSnapshot())).doesNotContain("secret title");
        var ranking=new EmbyWatchRankingService(mock(AuthService.class),new com.medianexus.orchestrator.config.EmbyProperties(),
                sql.getMapper(EmbyActivePlaybackSessionMapper.class),history);
        assertThat(ranking.getPrivateTitles(List.of("film"))).singleElement().satisfies(title -> assertThat(title.title()).isEqualTo("secret title"));
        sample("2026-11-05T04:00:01Z",List.of(playing("one",false)));
        verify(metadata,times(1)).findItemMetadata("user","film");
        sample("2026-11-05T04:00:02Z",List.of());
        assertThat(ranking.getPrivateTitles(List.of("film"))).singleElement().satisfies(title -> assertThat(title.title()).isEqualTo("secret title"));
    }

    @Test void replacingUserStartsNewInstanceAndMultipleDevicesRemainIndependent() {
        sample("2026-11-05T04:00:00Z",List.of(playing("one",false),playing("two",false)));
        sample("2026-11-05T04:00:02Z",List.of(playing("one",false),playing("two",false)));
        var switched=new EmbyPlaybackSession("one","other","Other","Client","Device",false,"film","Movie","Film",null,null,null,null,999.0,2.0,List.of());
        sample("2026-11-05T04:00:03Z",List.of(switched,playing("two",false)));
        sample("2026-11-05T04:00:04Z",List.of(switched,playing("two",false)));
        sample("2026-11-05T04:00:05Z",List.of());
        var summary=history.selectSummary(LocalDate.of(2026,11,5),LocalDate.of(2026,11,6));
        assertThat(summary.getTotalPlayCount()).isEqualTo(3L); assertThat(summary.getActiveUserCount()).isEqualTo(2L);
        assertThat(summary.getTotalWatchSeconds()).isEqualTo(7L);
    }

    @Test void duplicateConnectionResponseIsFailureRatherThanChoosingOneRow() {
        sample("2026-11-05T04:00:00Z",List.of(playing("one",false)));
        var previous=collector.currentSnapshot();
        sample("2026-11-05T04:00:01Z",List.of(playing("one",false),playing("one",true)));
        assertThat(collector.currentSnapshot().collectionStatus()).isEqualTo("stale");
        assertThat(collector.currentSnapshot().collectedAt()).isEqualTo(previous.collectedAt());
        assertThat(history.selectRecentWatchSessions(8)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"item", "user"})
    void singleObservationIdentityReplacementCreditsOldPlaybackOnFirstDate(String change) {
        sample("2026-11-05T15:59:59.800Z", List.of(playing("one", false)));
        var replacement = new EmbyPlaybackSession("one", "user".equals(change) ? "other" : "user", "User", "Client", "Device",
                false, "item".equals(change) ? "other-film" : "film", "Movie", "Film", null, null, null, null, 0.0, 2.0, List.of());
        sample("2026-11-05T16:00:01Z", List.of(replacement));
        var first = history.selectSummary(LocalDate.of(2026,11,5), LocalDate.of(2026,11,6));
        assertThat(first.getTotalPlayCount()).isEqualTo(1L);
        assertThat(first.getTotalWatchSeconds()).isEqualTo(1L);
        assertThat(first.getActiveUserCount()).isEqualTo(1L);
        assertThat(first.getTotalWorkCount()).isEqualTo(1L);
        assertThat(history.selectSummary(LocalDate.of(2026,11,6), LocalDate.of(2026,11,7)).getTotalPlayCount()).isZero();
        var query = new EmbyWatchDetailsQuery(mock(AuthService.class), sql.getMapper(EmbyWatchDetailsMapper.class), collector);
        assertThat(query.users("day", "2026-11-05", null, 1, 20).items()).singleElement().satisfies(user -> {
            assertThat(user.watchSeconds()).isEqualTo(1L); assertThat(user.playCount()).isEqualTo(1L);
        });
        assertThat(query.media("unknown", "plays", "day", "2026-11-05", null, 1, 20).items()).hasSize(1);
        assertThat(query.viewers("film", "unknown", "day", "2026-11-05", null, 1, 20).items()).singleElement()
                .satisfies(viewer -> assertThat(viewer.watchSeconds()).isEqualTo(1L));
        assertThat(query.sessions("user", "film", "unknown", "day", "2026-11-05", null, 1, 20).items())
                .filteredOn(session -> "settled".equals(session.state())).singleElement().satisfies(session -> {
                    assertThat(session.watchSeconds()).isEqualTo(1L); assertThat(session.rangeWatchSeconds()).isEqualTo(1L);
                    assertThat(session.startTime()).isEqualTo(LocalDateTime.parse("2026-11-05T23:59:59.800"));
                    assertThat(session.stopTime()).isEqualTo(session.startTime());
                    assertThat(session.stopTimeSource()).isEqualTo("inferred");
                    assertThat(session.source()).isEqualTo("sampling");
                });
        sample("2026-11-05T16:00:02Z", List.of());
        sample("2026-11-05T16:00:03Z", List.of());
        assertThat(history.selectSummary(LocalDate.of(2026,11,5), LocalDate.of(2026,11,7)).getTotalPlayCount()).isEqualTo(2L);
    }

    @ParameterizedTest
    @CsvSource({"failure,item", "failure,user", "rebuild,item", "rebuild,user", "paused,item", "paused,user", "unknown,item", "unknown,user"})
    void identityReplacementCannotCreditUnverifiedOrNonPlayingObservation(String condition, String change) {
        sample("2026-11-05T15:59:59.800Z", List.of(playing("one", "unknown".equals(condition) ? null : "paused".equals(condition))));
        if ("failure".equals(condition)) {
            when(client.listPlaybackSessions()).thenThrow(new EmbyClientException("controlled failure"));
            collector.collectSnapshot();
            assertThat(collector.currentSnapshot().collectionStatus()).isEqualTo("stale");
        }
        if ("rebuild".equals(condition)) rebuildCollector();
        var replacement = new EmbyPlaybackSession("one", "user".equals(change) ? "other" : "user", "User", "Client", "Device",
                false, "item".equals(change) ? "other-film" : "film", "Movie", "Film", null, null, null, null, 0.0, 2.0, List.of());
        sample("2026-11-05T16:00:01Z", List.of(replacement));
        assertThat(collector.currentSnapshot().collectionStatus()).isEqualTo("fresh");
        var summary = history.selectSummary(LocalDate.of(2026,11,5), LocalDate.of(2026,11,7));
        assertThat(summary.getTotalWatchSeconds()).isZero(); assertThat(summary.getTotalPlayCount()).isZero();
        assertThat(history.selectRecentWatchSessions(8)).singleElement().satisfies(session -> {
            assertThat(session.getWatchSeconds()).isZero();
            assertThat(session.getStopTime()).isEqualTo(LocalDateTime.parse("2026-11-05T23:59:59.800"));
        });
    }

    @Test void collectionLogsSafeFailureChangesDuringOutageAndSuppressesIdenticalRepeats() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EmbyPlaybackSnapshotCollector.class);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start(); logger.addAppender(captured);
        String sentinel = "SENTINEL-key-password-user-title-http://secret.invalid/?api_key=hidden SELECT password FROM users";
        try {
            sample("2026-11-05T04:00:00Z", List.of(playing("one", false)));
            List<RuntimeException> failures = List.of(
                    new EmbyClientException(sentinel, new java.net.http.HttpTimeoutException(sentinel)),
                    new EmbyClientException("Emby returned non-success status 401"),
                    new EmbyClientException("Emby returned non-success status 503"),
                    new EmbyClientException("Emby returned non-success status 502"),
                    new EmbyClientException("Emby sessions response is incomplete"));
            List<String> reasons = List.of("upstream/timeout", "upstream/auth-401", "upstream/status-503", "upstream/status-502", "upstream/invalid-response");
            for (int index = 0; index < failures.size(); index++) {
                doThrow(failures.get(index)).when(client).listPlaybackSessions();
                collector.collectSnapshot(); collector.collectSnapshot();
                assertThat(collector.currentSnapshot().collectionStatus()).isEqualTo("stale");
                assertThat(captured.list).hasSize(index + 1);
                assertThat(captured.list.get(index).getFormattedMessage()).contains(reasons.get(index));
            }
            List<Integer> sqlCodes = List.of(1062, 1146, 1045);
            List<String> sqlStates = List.of("23000", "42S02", "28000");
            for (int index = 0; index < sqlCodes.size(); index++) {
                jdbc.execute("CREATE TRIGGER review_fail_checkpoint BEFORE UPDATE ON emby_watch_collection_checkpoint FOR EACH ROW SIGNAL SQLSTATE '"
                        + sqlStates.get(index) + "' SET MYSQL_ERRNO = " + sqlCodes.get(index) + ", MESSAGE_TEXT = '" + sentinel + "'");
                try {
                    sample("2026-11-05T04:00:01Z", List.of(playing("one", false)));
                    collector.collectSnapshot();
                    assertThat(captured.list).hasSize(6 + index);
                    assertThat(captured.list.get(5 + index).getFormattedMessage())
                            .contains("persistence/sql-" + sqlCodes.get(index) + "/" + sqlStates.get(index));
                } finally { jdbc.execute("DROP TRIGGER review_fail_checkpoint"); }
            }
            jdbc.update("UPDATE emby_active_playback_sessions SET playback_intervals_json = ?", sentinel);
            collector.collectSnapshot(); collector.collectSnapshot();
            assertThat(captured.list).hasSize(9);
            assertThat(captured.list.get(8).getFormattedMessage()).contains("accounting/failure");
            jdbc.update("UPDATE emby_active_playback_sessions SET playback_intervals_json = '[]', playback_instance_id = NULL, accumulated_watch_seconds = 7");
            collector.collectSnapshot(); collector.collectSnapshot();
            assertThat(captured.list).hasSize(10);
            assertThat(captured.list.get(9).getFormattedMessage()).contains("accounting/legacy-evidence");
            jdbc.update("UPDATE emby_active_playback_sessions SET accumulated_watch_seconds = 0");
            sample("2026-11-05T04:00:02Z", List.of(playing("one", false)));
            assertThat(collector.currentSnapshot().collectionStatus()).isEqualTo("fresh");
            doThrow(failures.get(0)).when(client).listPlaybackSessions();
            collector.collectSnapshot(); collector.collectSnapshot();
            assertThat(captured.list).hasSize(11);
            assertThat(captured.list).allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain("SENTINEL", "http:", "secret.invalid", "api_key", "SELECT", "password", "Film", "EmbyClientException");
            });
        } finally { logger.detachAppender(captured); captured.stop(); }
    }

    @ParameterizedTest
    @CsvSource({"00,00,00,00", "10,03,05,10", "05,03,12,12", "05,15,12,15"})
    void legacyReadAndSettlementUseSameLatestEvidenceCutoff(String open, String closed, String verified, String expected) {
        String intervals = "[{\"start\":\"2026-11-05T12:00:00\",\"end\":\"2026-11-05T12:00:" + closed + "\"}]";
        jdbc.update("""
                INSERT INTO emby_active_playback_sessions(emby_session_id,emby_user_id,item_id,item_type,
                    start_time,accumulated_watch_seconds,playback_intervals_json,playback_start_time,last_verified_at,updated_at)
                VALUES('legacy','user','film','Movie','2026-11-05 12:00:00',?,?,?,?, '2026-11-06 20:00:00')
                """, Integer.parseInt(closed), intervals, LocalDateTime.parse("2026-11-05T12:00:" + open), LocalDateTime.parse("2026-11-05T12:00:" + verified));
        var query = new EmbyWatchDetailsQuery(mock(AuthService.class), sql.getMapper(EmbyWatchDetailsMapper.class), collector);
        assertThat(query.activity("day", "2026-11-05", null, 1, 20).items()).singleElement().satisfies(session -> {
            assertThat(session.observedAt()).isEqualTo(LocalDateTime.parse("2026-11-05T12:00:" + expected));
            assertThat(session.watchSeconds()).isEqualTo(Integer.parseInt(closed));
        });
        sample("2026-11-05T04:01:00Z", List.of());
        assertThat(collector.currentSnapshot().collectionStatus()).isEqualTo("fresh");
        assertThat(history.selectRecentWatchSessions(8)).singleElement().satisfies(session -> {
            assertThat(session.getStopTime()).isEqualTo(LocalDateTime.parse("2026-11-05T12:00:" + expected));
            assertThat(session.getWatchSeconds()).isEqualTo(Integer.parseInt(closed));
            assertThat(session.getStopTimeSource()).isEqualTo("migration");
        });
    }

    void rebuildCollector() {
        collector = new EmbyPlaybackSnapshotCollector(new EmbyWatchAccounting(client, metadata,
                sql.getMapper(EmbyActivePlaybackSessionMapper.class), history, sql.getMapper(EmbyWatchCollectionMapper.class), transactions, clock));
    }
    void sample(String at, List<EmbyPlaybackSession> sessions) {
        clock.at = Instant.parse(at); doReturn(sessions).when(client).listPlaybackSessions();
        collector.collectSnapshot();
    }
    static class MutableClock extends Clock {
        Instant at = Instant.parse("2026-11-05T04:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return at; }
    }

    EmbyPlaybackSession playing(String id, Boolean paused) {
        return new EmbyPlaybackSession(id, "user", "User", "Client", "Device", paused, "film", "Movie", "Film", null,
                null,null,null,0.0,2.0,List.of(new EmbyPlaybackSession.Poster("poster","tag")));
    }
}
