package com.medianexus.orchestrator.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.medianexus.orchestrator.config.EmbyWatchCollectionSchema;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyItemMetadataLookup;
import com.medianexus.orchestrator.mapper.*;
import java.time.Clock;
import java.util.List;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** HTTP 采集测试复用真实持久化，不启动应用；只允许任务专属本机数据库。 */
public final class EmbyWatchTestDatabase {
    private final SqlSessionTemplate sql;
    private final DataSourceTransactionManager transactions;
    public EmbyWatchTestDatabase() throws Exception {
        String url=System.getenv("EMBY_WATCH_TEST_JDBC_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(url!=null, "真实 MySQL 测试需显式设置 EMBY_WATCH_TEST_JDBC_URL；未配置时跳过，不使用默认数据库");
        if (!url.startsWith("jdbc:mysql://127.0.0.1:43379/emby_watch_collection_test?")) throw new IllegalStateException("Refusing non-disposable DB");
        var ds=new DriverManagerDataSource(url,System.getenv("EMBY_WATCH_TEST_JDBC_USER"),System.getenv("EMBY_WATCH_TEST_JDBC_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);
        for (String table:List.of("emby_watch_daily_contributions","emby_watch_sessions","emby_active_playback_sessions","emby_watch_collection_checkpoint")) jdbc.execute("DROP TABLE IF EXISTS "+table);
        var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(EmbyActivePlaybackSessionMapper.class); config.addMapper(EmbyWatchSessionMapper.class); config.addMapper(EmbyWatchCollectionMapper.class);
        var factory=new MybatisSqlSessionFactoryBean(); factory.setConfiguration(config); factory.setDataSource(ds);
        sql=new SqlSessionTemplate(factory.getObject()); transactions=new DataSourceTransactionManager(ds);
        sql.getMapper(EmbyActivePlaybackSessionMapper.class).createTableIfNotExists();
        sql.getMapper(EmbyWatchSessionMapper.class).createTableIfNotExists();
        sql.getMapper(EmbyWatchSessionMapper.class).createDailyContributionsTableIfNotExists();
        new EmbyWatchCollectionSchema(jdbc).initialize();
    }
    public EmbyPlaybackSnapshotCollector collector(EmbyClient client, EmbyItemMetadataLookup metadata) {
        return new EmbyPlaybackSnapshotCollector(new EmbyWatchAccounting(client,metadata,
                sql.getMapper(EmbyActivePlaybackSessionMapper.class),sql.getMapper(EmbyWatchSessionMapper.class),
                sql.getMapper(EmbyWatchCollectionMapper.class),transactions,Clock.systemUTC()));
    }
}
