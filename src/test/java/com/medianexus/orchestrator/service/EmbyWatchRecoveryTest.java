package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.medianexus.orchestrator.config.EmbyWatchCollectionSchema;
import com.medianexus.orchestrator.integration.emby.*;
import com.medianexus.orchestrator.mapper.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.controller.EmbyWebhookController;
import com.medianexus.orchestrator.common.exception.GlobalExceptionHandler;
import com.medianexus.orchestrator.common.exception.BusinessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 完整采集→真实 MySQL→公开查询；仅使用独立恢复测试库，不启动应用。 */
class EmbyWatchRecoveryTest {
    final QueryBarrier barrier = new QueryBarrier();
    JdbcTemplate jdbc;
    DriverManagerDataSource dataSource;
    SqlSessionTemplate sql;
    DataSourceTransactionManager transactions;
    EmbyWatchSessionMapper history;
    EmbyClient client;
    EmbyItemMetadataLookup metadata;
    EmbyWatchAccounting accounting;
    EmbyPlaybackSnapshotCollector collector;
    EmbyWatchDetailsQuery details;
    final MutableClock clock = new MutableClock();

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("EMBY_WATCH_TEST_JDBC_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(url != null, "真实恢复测试需显式设置 EMBY_WATCH_TEST_JDBC_URL；未配置时跳过，不使用默认数据库");
        if (!url.startsWith("jdbc:mysql://127.0.0.1:43379/emby_watch_collection_recovery_test?")) {
            throw new IllegalStateException("Refusing non-recovery DB");
        }
        dataSource = new DriverManagerDataSource(url, System.getenv("EMBY_WATCH_TEST_JDBC_USER"),
                System.getenv("EMBY_WATCH_TEST_JDBC_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addInterceptor(barrier);
        config.addMapper(EmbyActivePlaybackSessionMapper.class);
        config.addMapper(EmbyWatchSessionMapper.class);
        config.addMapper(EmbyWatchDetailsMapper.class);
        config.addMapper(EmbyWatchCollectionMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setConfiguration(config); factory.setDataSource(dataSource);
        sql = new SqlSessionTemplate(factory.getObject());
        history = sql.getMapper(EmbyWatchSessionMapper.class);
        transactions = new DataSourceTransactionManager(dataSource);
        oldSchema();
        new EmbyWatchCollectionSchema(jdbc).initialize();
        client = mock(EmbyClient.class);
        metadata = mock(EmbyItemMetadataLookup.class);
        rebuild();
    }

    @Test void failedCollectionReconcilesStillPresentMissingAndNewWithoutAccruingGap() {
        sample("12:00:00", playing("A"), playing("B"));
        sample("12:00:03", playing("A"), playing("B"));
        var committed = collector.currentSnapshot();
        fail("12:00:04");
        assertThat(collector.currentSnapshot().collectionStatus()).isEqualTo("stale");
        assertThat(collector.currentSnapshot().collectedAt()).isEqualTo(committed.collectedAt());
        assertThat(collector.currentSnapshot().sessions()).isEqualTo(committed.sessions());
        assertThat(activity()).hasSize(2).allSatisfy(s -> {
            assertThat(s.watchSeconds()).isEqualTo(3); assertThat(s.state()).isEqualTo("unverified");
            assertThat(s.observedAt()).isEqualTo(time("12:00:03"));
        });
        sample("12:01:00", playing("A"), playing("C"));
        assertActive("A", 3, "12:01:00", "playing");
        assertActive("C", 0, "12:01:00", "playing");
        assertThat(history.selectRecentWatchSessions(8)).singleElement().satisfies(s -> {
            assertThat(s.getEmbySessionId()).isEqualTo("B"); assertThat(s.getWatchSeconds()).isEqualTo(3);
            assertThat(s.getStopTime()).isEqualTo(time("12:00:03")); assertThat(s.getStopTimeSource()).isEqualTo("inferred");
        });
        sample("12:01:02", playing("A"), playing("C"));
        assertActive("A", 5, "12:01:02", "playing"); assertActive("C", 2, "12:01:02", "playing");
        sample("12:01:03"); sample("12:01:04");
        assertSummary(3, 10);
        assertThat(activity()).isEmpty();
        assertThat(details.sessions("user", null, null, "day", "2026-11-05", null, 1, 20).items())
                .hasSize(3).allSatisfy(s -> assertThat(s.source()).isEqualTo("sampling"));
    }

    @Test void rebuiltCollectorRetainsSavedIdentityAndCutoffButRequiresFreshBaseline() {
        sample("12:00:00", playing("A")); sample("12:00:03", playing("A"));
        String instance = jdbc.queryForObject("SELECT playback_instance_id FROM emby_active_playback_sessions", String.class);
        var persisted = watchTables();
        rebuild();
        assertThat(collector.currentSnapshot().collectionStatus()).isEqualTo("unavailable");
        assertThat(collector.currentSnapshot().sessions()).isEmpty();
        assertActive("A", 3, "12:00:03", "unverified");
        fail("12:01:00");
        assertThat(watchTables()).isEqualTo(persisted);
        assertThat(collector.currentSnapshot().collectedAt()).isNull();
        assertActive("A", 3, "12:00:03", "unverified");
        sample("12:02:00", playing("A"));
        assertActive("A", 3, "12:02:00", "playing");
        assertThat(jdbc.queryForObject("SELECT playback_instance_id FROM emby_active_playback_sessions", String.class)).isEqualTo(instance);
        sample("12:02:02", playing("A"));
        assertActive("A", 5, "12:02:02", "playing");
        rebuild(); sample("12:03:00", playing("A"));
        assertActive("A", 5, "12:03:00", "playing");
        sample("12:03:01");
        assertThat(history.selectRecentWatchSessions(8)).singleElement().satisfies(s -> {
            assertThat(s.getPlaybackInstanceId()).isEqualTo(instance); assertThat(s.getWatchSeconds()).isEqualTo(5);
            assertThat(s.getStopTime()).isEqualTo(time("12:03:00"));
        });
        rebuild(); sample("12:04:00"); sample("12:04:01");
        assertSummary(1, 5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM emby_watch_daily_contributions", Integer.class)).isEqualTo(1);
    }

    @Test void dailyInsertFailureRollsBackHistoryActiveAndCheckpointBeforePublishing() {
        sample("12:00:00", playing("A")); sample("12:00:03", playing("A"));
        var persisted = watchTables();
        var successful = collector.currentSnapshot();
        failDailyAfterHistory();
        try {
            sample("12:00:04");
            assertThat(watchTables()).isEqualTo(persisted);
            assertThat(collector.currentSnapshot().collectionStatus()).isEqualTo("stale");
            assertThat(collector.currentSnapshot().sessions()).isEqualTo(successful.sessions());
            assertThat(collector.currentSnapshot().collectedAt()).isEqualTo(successful.collectedAt());
            assertActive("A", 3, "12:00:03", "unverified"); assertSummary(0, 0);
        } finally {
            jdbc.execute("DROP TRIGGER fail_recovery_day");
        }
        sample("12:01:00"); sample("12:01:01");
        assertSummary(1, 3);
        assertThat(history.selectRecentWatchSessions(8)).singleElement().satisfies(s ->
                assertThat(s.getStopTime()).isEqualTo(time("12:00:03")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM emby_watch_daily_contributions", Integer.class)).isEqualTo(1);
    }

    @Test void persistenceFailureThenSameIdentityRecoveryDoesNotRecountUnknownInterval() {
        sample("12:00:00", playing("A")); sample("12:00:03", playing("A"));
        failDailyAfterHistory();
        try {
            sample("12:00:04");
            assertThat(collector.currentSnapshot().collectionStatus()).isEqualTo("stale");
            assertActive("A", 3, "12:00:03", "unverified");
        } finally {
            jdbc.execute("DROP TRIGGER fail_recovery_day");
        }
        sample("12:01:00", playing("A")); assertActive("A", 3, "12:01:00", "playing");
        sample("12:01:02", playing("A")); sample("12:01:03");
        assertSummary(1, 5);
    }

    @Test void checkpointNowaitSkipsUpstreamAndDuplicateOrOlderSamplesDoNotMutate() throws Exception {
        sample("12:00:00", playing("A")); sample("12:00:03", playing("A"));
        var saved = watchTables();
        var owner = collector;
        rebuild();
        var contender = collector;
        clearInvocations(client);
        try (var held = dataSource.getConnection()) {
            held.setAutoCommit(false);
            try (var lock = held.prepareStatement("SELECT id FROM emby_watch_collection_checkpoint WHERE id=1 FOR UPDATE")) {
                lock.executeQuery().close();
            }
            org.junit.jupiter.api.Assertions.assertTimeout(Duration.ofSeconds(3), contender::collectSnapshot);
            verifyNoInteractions(client);
            assertThat(watchTables()).isEqualTo(saved);
            assertThat(contender.currentSnapshot().collectionStatus()).isEqualTo("unavailable");
            held.rollback();
        }
        collector = contender;
        sample("12:01:00", playing("A")); assertActive("A", 3, "12:01:00", "playing");
        sample("12:01:02", playing("A")); assertActive("A", 5, "12:01:02", "playing");
        var accepted = collector.currentSnapshot();
        saved = watchTables();
        sample("12:01:02"); sample("12:01:01");
        assertThat(watchTables()).isEqualTo(saved);
        assertThat(collector.currentSnapshot()).isEqualTo(accepted);
        sample("12:01:03"); sample("12:01:04");
        assertSummary(1, 5);
        assertThat(owner.currentSnapshot().collectedAt()).isEqualTo(Instant.parse("2026-11-05T04:00:03Z"));
    }

    @Test void failureOrRebuildAfterOnePlayingObservationCannotManufactureBonusSecond() {
        sample("12:00:00", playing("A")); fail("12:00:01"); sample("12:01:00");
        sample("12:02:00", playing("B")); rebuild(); fail("12:02:01"); sample("12:03:00");
        assertSummary(0, 0);
        assertThat(history.selectRecentWatchSessions(8)).hasSize(2).allSatisfy(s -> assertThat(s.getWatchSeconds()).isZero());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM emby_watch_daily_contributions", Integer.class)).isZero();
        assertThat(ranking().getRankings("day", "2026-11-05", null, 20).webhookStatus().recentEvents())
                .hasSize(4).allSatisfy(e -> assertThat(e.source()).isEqualTo("sampling"));
    }

    @Test void legacyHandoffPreservesSavedTotalsAndClosedSpansAtEvidenceCutoff() {
        seedOldHistory();
        var oldHistory = oldHistoryRows(); var oldDays = oldDayRows();
        legacyActive("A", 3, "[{\"start\":\"2026-11-05T12:00:00\",\"end\":\"2026-11-05T12:00:03\"}]", "12:00:10", "12:00:12");
        legacyActive("L", 7, null, "12:00:10", "12:00:12");
        sample("12:02:00", playing("A"));
        assertThat(collector.currentSnapshot().collectionStatus()).isEqualTo("fresh");
        assertActive("A", 0, "12:02:00", "playing");
        String fresh = jdbc.queryForObject("SELECT playback_instance_id FROM emby_active_playback_sessions", String.class);
        assertThatCode(() -> UUID.fromString(fresh)).doesNotThrowAnyException();
        assertThat(history.selectRecentWatchSessions(8)).filteredOn(s -> "migration".equals(s.getStopTimeSource()))
                .hasSize(2).allSatisfy(s -> {
                    assertThat(s.getPlaybackInstanceId()).isNull(); assertThat(s.getStopTime()).isEqualTo(time("12:00:12"));
                    assertThat(s.getWatchSeconds()).isEqualTo(s.getEmbySessionId().equals("A") ? 3 : 7);
                    assertThat(s.getAccountingBasis()).isEqualTo(s.getEmbySessionId().equals("A") ? "calendar" : "legacy");
                });
        assertThat(oldHistoryRows()).isEqualTo(oldHistory); assertThat(oldDayRows()).isEqualTo(oldDays);
        assertSummary(5, 25);
        assertThat(ranking().getRankings("day", "2026-11-05", null, 20).webhookStatus().recentEvents())
                .filteredOn(e -> "migration".equals(e.stopTimeSource())).hasSize(2)
                .allSatisfy(e -> assertThat(e.source()).isEqualTo("webhook"));
        rebuild(); sample("12:03:00", playing("A")); sample("12:03:02", playing("A")); sample("12:03:03");
        rebuild(); sample("12:04:00");
        assertSummary(6, 27);
        assertThat(history.selectRecentWatchSessions(8)).filteredOn(s -> fresh.equals(s.getPlaybackInstanceId())).hasSize(1);
        assertThat(oldHistoryRows()).isEqualTo(oldHistory); assertThat(oldDayRows()).isEqualTo(oldDays);
    }

    @Test void inconsistentLegacyEvidenceFreezesWithoutChangingOrHidingSavedCumulativeTotal() {
        legacyActive("A", 7, "[{\"start\":\"2026-11-05T12:00:00\",\"end\":\"2026-11-05T12:00:03\"}]", "12:00:10", "12:00:12");
        var saved = watchTables();
        sample("12:02:00", playing("A"));
        assertThat(collector.currentSnapshot().collectionStatus()).isEqualTo("unavailable");
        assertThat(watchTables()).isEqualTo(saved);
        assertThatThrownBy(accounting::collectSnapshot)
                .isInstanceOf(IllegalStateException.class).hasMessage("旧观看累计与闭合分段不一致，保留原活动记录");
        assertActive("A", 7, "12:00:12", "unverified");
        assertSummary(0, 0);
    }

    @Test void oldDdlMigrationRetainsNullIdentityRowsAndAllowsIndependentIdenticalTimestampInstances() {
        oldSchema(); seedOldHistory();
        assertMigrationAndIndependentInstances();
    }

    @ParameterizedTest
    @ValueSource(ints={1, 2, 3})
    void interruptedIdentityColumnsCanResumeWithoutRewritingLegacyRows(int committedColumns) {
        oldSchema(); seedOldHistory();
        var tables = List.of("emby_active_playback_sessions", "emby_watch_sessions", "emby_watch_daily_contributions");
        for (int i=0; i<committedColumns; i++) {
            jdbc.execute("ALTER TABLE " + tables.get(i) + " ADD COLUMN playback_instance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL");
        }
        assertMigrationAndIndependentInstances();
    }

    void assertMigrationAndIndependentInstances() {
        var beforeHistory = oldHistoryRows();
        var beforeDays = jdbc.queryForList("SELECT emby_session_id,item_id,start_time,watch_date,watch_seconds FROM emby_watch_daily_contributions ORDER BY start_time");
        var schema = new EmbyWatchCollectionSchema(jdbc);
        schema.initialize(); schema.initialize();
        assertThat(oldHistoryRows()).isEqualTo(beforeHistory); assertThat(oldDayRows()).isEqualTo(beforeDays);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM emby_watch_sessions WHERE playback_instance_id IS NULL", Integer.class)).isEqualTo(3);
        assertSummary(3, 15);
        for (int seconds : List.of(2, 4)) {
            String instance = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO emby_watch_sessions(playback_instance_id,emby_session_id,emby_user_id,item_id,item_type,
                        start_time,stop_time,accounting_basis,watch_seconds,watch_date,stop_time_source)
                    VALUES(?,'old-shared','user','film','Movie','2026-11-05 12:00:00','2026-11-05 12:00:03','calendar',?,'2026-11-05','inferred')
                    """, instance, seconds);
            jdbc.update("""
                    INSERT INTO emby_watch_daily_contributions(playback_instance_id,emby_session_id,item_id,start_time,watch_date,watch_seconds)
                    VALUES(?,'old-shared','film','2026-11-05 12:00:00','2026-11-05',?)
                    """, instance, seconds);
        }
        assertSummary(5, 21);
        assertThat(details.sessions("user", null, null, "day", "2026-11-05", null, 1, 20).items())
                .extracting(s -> s.rangeWatchSeconds()).containsExactlyInAnyOrder(2L, 3L, 4L, 5L, 7L);
        assertThat(details.users("day", "2026-11-05", null, 1, 20).items()).singleElement().satisfies(u -> {
            assertThat(u.watchSeconds()).isEqualTo(21); assertThat(u.playCount()).isEqualTo(5);
        });
        assertThat(details.media("unknown", "duration", "day", "2026-11-05", null, 1, 20).items()).singleElement().satisfies(m -> {
            assertThat(m.watchSeconds()).isEqualTo(21); assertThat(m.playCount()).isEqualTo(5);
        });
        assertThat(details.viewers("film", "unknown", "day", "2026-11-05", null, 1, 20).items()).singleElement().satisfies(v -> {
            assertThat(v.watchSeconds()).isEqualTo(21); assertThat(v.playCount()).isEqualTo(5);
        });
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='emby_active_playback_sessions' AND INDEX_NAME='uk_emby_active_session_item' ORDER BY SEQ_IN_INDEX", String.class))
                .containsExactly("emby_session_id", "item_id");
    }

    @Test void authenticatedLegacyPlaybackHttpAndPublicQueriesDoNotWriteOrReadEmby() throws Exception {
        seedOldHistory(); sample("12:00:00", playing("A")); sample("12:00:03", playing("A"));
        jdbc.update("UPDATE emby_active_playback_sessions SET updated_at='2026-10-01 00:00:00'");
        var saved = watchTables();
        clearInvocations(client, metadata);
        var properties = new EmbyProperties(); properties.setWebhookSecret(" secret ");
        var library = mock(AdultOtherLibraryWebhookService.class);
        var http = MockMvcBuilders.standaloneSetup(new EmbyWebhookController(new EmbyPlaybackWebhookService(properties), library))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String body : List.of("", "not-json", "{\"Event\":\"playback.stop\"}", "{\"Event\":\"playback.start\"}")) {
            http.perform(post("/api/v1/emby/webhooks/playback").param("secret", "secret")
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(content().json("{\"code\":200,\"message\":\"success\",\"data\":null}", true));
        }
        http.perform(post("/api/v1/emby/webhooks/playback").param("secret", "wrong").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(content().json("{\"code\":403,\"message\":\"Emby webhook secret 无效\",\"data\":null}", true));
        http.perform(post("/api/v1/emby/webhooks/playback").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        properties.setWebhookSecret(" ");
        http.perform(post("/api/v1/emby/webhooks/playback").param("secret", "secret").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertThat(activity()).singleElement().satisfies(s -> assertThat(s.watchSeconds()).isEqualTo(3));
        assertThat(details.sessions("user", null, null, "day", "2026-11-05", null, 1, 20).items()).hasSize(4);
        details.users("day", "2026-11-05", null, 1, 20);
        details.media("unknown", "duration", "day", "2026-11-05", null, 1, 20);
        details.viewers("film", "unknown", "day", "2026-11-05", null, 1, 20);
        assertThat(ranking().getRankings("day", "2026-11-05", null, 20).summary().totalWatchSeconds()).isEqualTo(15);
        assertThat(watchTables()).isEqualTo(saved);
        verifyNoInteractions(client, metadata, library);
    }

    @ParameterizedTest
    @ValueSource(strings={"not-json", "[{\"start\":\"invalid-date\",\"end\":\"2026-11-05T12:00:03\"}]"})
    void malformedSavedIntervalsRetainExistingQueryFailureContract(String intervals) {
        legacyActive("A", 7, intervals, "12:00:10", "12:00:12");
        var saved = watchTables();
        clearInvocations(client, metadata);
        assertThatThrownBy(this::activity).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(400); assertThat(e.getHttpStatus().value()).isEqualTo(400);
            assertThat(e.getMessage()).isEqualTo("观看分段暂不可用，请稍后重试");
        });
        assertThatThrownBy(() -> details.sessions("user", null, null, "day", "2026-11-05", null, 1, 20))
                .isInstanceOf(BusinessException.class).hasMessage("观看分段暂不可用，请稍后重试");
        assertThat(watchTables()).isEqualTo(saved);
        verifyNoInteractions(client, metadata);
    }

    void legacyActive(String connection, int total, String intervals, String open, String verified) {
        jdbc.update("""
                INSERT INTO emby_active_playback_sessions(emby_session_id,emby_user_id,item_id,item_type,
                    start_time,accumulated_watch_seconds,playback_intervals_json,playback_start_time,last_verified_at,updated_at)
                VALUES(?,'user','film','Movie','2026-11-05 12:00:00',?,?,?,?, '2026-11-06 20:00:00')
                """, connection, total, intervals, time(open), time(verified));
    }
    void seedOldHistory() {
        jdbc.update("""
                INSERT INTO emby_watch_sessions(emby_session_id,emby_user_id,item_id,item_type,start_time,stop_time,
                    accounting_basis,watch_seconds,watch_date,stop_time_source)
                VALUES('old-shared','user','film','Movie','2026-11-05 11:00:00','2026-11-05 11:00:05','calendar',5,'2026-11-05','event'),
                    ('old-shared','user','film','Movie','2026-11-05 12:00:00','2026-11-05 12:00:03','calendar',3,'2026-11-05','event'),
                    ('old-legacy','user','film','Movie','2026-11-05 10:00:00','2026-11-05 10:00:07',NULL,7,'2026-11-05',NULL)
                """);
        jdbc.update("""
                INSERT INTO emby_watch_daily_contributions(emby_session_id,item_id,start_time,watch_date,watch_seconds)
                VALUES('old-shared','film','2026-11-05 11:00:00','2026-11-05',5),
                    ('old-shared','film','2026-11-05 12:00:00','2026-11-05',3)
                """);
    }
    List<Map<String,Object>> oldHistoryRows() {
        return jdbc.queryForList("SELECT id,emby_session_id,item_id,start_time,stop_time,watch_seconds,watch_date,accounting_basis,stop_time_source FROM emby_watch_sessions WHERE emby_session_id LIKE 'old-%' ORDER BY id");
    }
    List<Map<String,Object>> oldDayRows() {
        return jdbc.queryForList("SELECT emby_session_id,item_id,start_time,watch_date,watch_seconds FROM emby_watch_daily_contributions WHERE emby_session_id LIKE 'old-%' AND playback_instance_id IS NULL ORDER BY start_time");
    }

    void failDailyAfterHistory() {
        // 只有同事务历史已可见才拒绝日贡献，确保故障发生在历史插入之后。
        jdbc.execute("""
                CREATE TRIGGER fail_recovery_day BEFORE INSERT ON emby_watch_daily_contributions
                FOR EACH ROW BEGIN
                    IF EXISTS (SELECT 1 FROM emby_watch_sessions WHERE playback_instance_id=NEW.playback_instance_id) THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='recovery test: history exists before daily failure';
                    END IF;
                END
                """);
    }

    @Test void concurrentSettlementCannotDuplicateObservedStartOrMixRankingSnapshots() throws Exception {
        sample("12:00:00", playing("A")); sample("12:00:03", playing("A"));
        var ranking = ranking();
        var executor = Executors.newSingleThreadExecutor();
        barrier.armed.set(true);
        try {
            var response = executor.submit(() -> ranking.getRankings("day", "2026-11-05", null, 20));
            assertThat(barrier.activeRead.await(5, TimeUnit.SECONDS)).isTrue();
            sample("12:00:04");
            assertSummary(1, 3);
            barrier.continueRead.countDown();
            var beforeSettlement = response.get(5, TimeUnit.SECONDS);
            assertThat(beforeSettlement.summary().totalPlayCount()).isZero();
            assertThat(beforeSettlement.users()).isEmpty();
            assertThat(beforeSettlement.webhookStatus().recentEvents()).singleElement().satisfies(e -> {
                assertThat(e.event()).isEqualTo("playback.start"); assertThat(e.eventTime()).isEqualTo(time("12:00:00"));
            });
            assertThat(beforeSettlement.webhookStatus().activeSessionCount()).isEqualTo(1);
            var afterSettlement = ranking.getRankings("day", "2026-11-05", null, 20);
            assertThat(afterSettlement.summary().totalPlayCount()).isEqualTo(1);
            assertThat(afterSettlement.users()).singleElement().satisfies(u -> assertThat(u.watchSeconds()).isEqualTo(3));
            assertThat(afterSettlement.webhookStatus().recentEvents()).hasSize(2);
            assertThat(afterSettlement.webhookStatus().activeSessionCount()).isZero();
        } finally {
            barrier.continueRead.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    EmbyWatchRankingService ranking() {
        var target = new EmbyWatchRankingService(mock(AuthService.class), new EmbyProperties(),
                sql.getMapper(EmbyActivePlaybackSessionMapper.class), history);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(advice);
        return (EmbyWatchRankingService) proxy.getProxy();
    }

    /** 仅暂停真实查询的返回边界，让另一个物理连接在两次查询之间提交。 */
    @Intercepts(@Signature(type=Executor.class, method="query", args={MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    public static class QueryBarrier implements Interceptor {
        final AtomicBoolean armed = new AtomicBoolean();
        final CountDownLatch activeRead = new CountDownLatch(1);
        final CountDownLatch continueRead = new CountDownLatch(1);
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            if (((MappedStatement) invocation.getArgs()[0]).getId().endsWith(".selectRecentActiveSessions")
                    && armed.compareAndSet(true, false)) {
                activeRead.countDown();
                if (!continueRead.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent settlement did not complete");
            }
            return result;
        }
    }

    List<List<Map<String, Object>>> watchTables() {
        return List.of("emby_active_playback_sessions", "emby_watch_sessions", "emby_watch_daily_contributions", "emby_watch_collection_checkpoint")
                .stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
    }

    void oldSchema() {
        for (String table : List.of("emby_watch_daily_contributions", "emby_watch_sessions", "emby_active_playback_sessions", "emby_watch_collection_checkpoint")) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        sql.getMapper(EmbyActivePlaybackSessionMapper.class).createTableIfNotExists();
        history.createTableIfNotExists(); history.createDailyContributionsTableIfNotExists();
    }
    void rebuild() {
        accounting = new EmbyWatchAccounting(client, metadata,
                sql.getMapper(EmbyActivePlaybackSessionMapper.class), history,
                sql.getMapper(EmbyWatchCollectionMapper.class), transactions, clock);
        collector = new EmbyPlaybackSnapshotCollector(accounting);
        details = new EmbyWatchDetailsQuery(mock(AuthService.class), sql.getMapper(EmbyWatchDetailsMapper.class), collector);
    }
    void sample(String at, EmbyPlaybackSession... sessions) {
        clock.at = time(at).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        doReturn(List.of(sessions)).when(client).listPlaybackSessions();
        collector.collectSnapshot();
    }
    void fail(String at) {
        clock.at = time(at).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        doThrow(new EmbyClientException("test upstream failure")).when(client).listPlaybackSessions();
        collector.collectSnapshot();
    }
    List<com.medianexus.orchestrator.dto.emby.response.EmbyWatchDetails.Session> activity() {
        return details.activity("day", "2026-11-05", null, 1, 20).items();
    }
    void assertActive(String connection, long seconds, String observed, String state) {
        String id = "active:" + jdbc.queryForObject("SELECT id FROM emby_active_playback_sessions WHERE emby_session_id=?", Long.class, connection);
        assertThat(activity()).filteredOn(s -> s.id().equals(id)).singleElement().satisfies(s -> {
            assertThat(s.watchSeconds()).isEqualTo(seconds); assertThat(s.observedAt()).isEqualTo(time(observed));
            assertThat(s.state()).isEqualTo(state);
        });
    }
    void assertSummary(long plays, long seconds) {
        var summary = history.selectSummary(LocalDate.of(2026,11,5), LocalDate.of(2026,11,6));
        assertThat(summary.getTotalPlayCount()).isEqualTo(plays);
        assertThat(summary.getTotalWatchSeconds()).isEqualTo(seconds);
    }
    static LocalDateTime time(String at) { return LocalDateTime.parse("2026-11-05T" + at); }
    static EmbyPlaybackSession playing(String id) {
        return new EmbyPlaybackSession(id, "user", "User", "Client", "Device", false, "film", "Movie", "Film",
                null, null, null, null, 0.0, 2.0, List.of());
    }
    static class MutableClock extends Clock {
        Instant at = Instant.parse("2026-11-05T04:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return at; }
    }
}
