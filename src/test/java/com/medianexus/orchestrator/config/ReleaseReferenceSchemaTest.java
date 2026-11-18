package com.medianexus.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskMapper;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 只连接专用临时 MySQL 库，验证真实 DDL 与 MyBatis-Plus 读写。 */
@EnabledIfEnvironmentVariable(named = "RELEASE_REFERENCE_TEST_JDBC_URL", matches = ".+")
class ReleaseReferenceSchemaTest {

    private static final String LONG_REFERENCE = "r".repeat(2166);
    private static final String[] TABLES = {
            "anime_magnet_ingest_tasks", "movie_magnet_ingest_tasks", "series_magnet_ingest_tasks"
    };

    private Connection connection;
    private SqlSession session;
    private AnimeMagnetIngestTaskMapper animeMapper;
    private MovieMagnetIngestTaskMapper movieMapper;
    private SeriesMagnetIngestTaskMapper seriesMapper;
    private DatabaseInitializer initializer;

    @BeforeEach
    void connectToIsolatedDatabase() throws Exception {
        String url = System.getenv("RELEASE_REFERENCE_TEST_JDBC_URL");
        String username = System.getenv("RELEASE_REFERENCE_TEST_DB_USER");
        String password = System.getenv("RELEASE_REFERENCE_TEST_DB_PASSWORD");
        connection = DriverManager.getConnection(url, username, password);
        assertThat(connection.getCatalog()).startsWith("medianexus_release_ref_test_");
        for (String table : TABLES) {
            execute("DROP TABLE IF EXISTS " + table);
        }
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("release-reference-test", new JdbcTransactionFactory(),
                new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, username, password)));
        configuration.addMapper(AnimeMagnetIngestTaskMapper.class);
        configuration.addMapper(MovieMagnetIngestTaskMapper.class);
        configuration.addMapper(SeriesMagnetIngestTaskMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true);
        animeMapper = session.getMapper(AnimeMagnetIngestTaskMapper.class);
        movieMapper = session.getMapper(MovieMagnetIngestTaskMapper.class);
        seriesMapper = session.getMapper(SeriesMagnetIngestTaskMapper.class);
        var realMappers = Map.<Class<?>, Object>of(
                AnimeMagnetIngestTaskMapper.class, animeMapper,
                MovieMagnetIngestTaskMapper.class, movieMapper,
                SeriesMagnetIngestTaskMapper.class, seriesMapper);
        var constructor = DatabaseInitializer.class.getConstructors()[0];
        Class<?>[] types = constructor.getParameterTypes();
        Object[] dependencies = new Object[types.length];
        for (int index = 0; index < types.length; index++) {
            dependencies[index] = realMappers.containsKey(types[index])
                    ? realMappers.get(types[index]) : mock(types[index]);
        }
        initializer = (DatabaseInitializer) constructor.newInstance(dependencies);
    }

    @AfterEach
    void closeConnections() throws Exception {
        if (session != null) {
            session.close();
        }
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void upgradesLegacyTablesAndPreservesCompleteReferencesAcrossRepeatedInitialization() throws Exception {
        createTaskTables();
        for (String table : TABLES) {
            execute("ALTER TABLE " + table + " MODIFY COLUMN release_guid VARCHAR(1024) NULL");
        }
        insertAndReadReferences("existing", "short-reference");

        initializer.run(null);
        insertAndReadReferences("long", LONG_REFERENCE);
        initializer.run(null);

        for (String table : TABLES) {
            assertThat(columnType(table)).isEqualTo("text");
            assertReference(table, "existing", "short-reference");
            assertReference(table, "long", LONG_REFERENCE);
        }
    }

    @Test
    void createsFreshTablesThatStoreLongReferences() throws Exception {
        initializer.run(null);
        insertAndReadReferences("fresh", LONG_REFERENCE);
        for (String table : TABLES) {
            assertThat(columnType(table)).isEqualTo("text");
        }
    }

    @Test
    void doesNotNarrowExistingLargerTextColumns() throws Exception {
        createTaskTables();
        for (String table : TABLES) {
            execute("ALTER TABLE " + table + " MODIFY COLUMN release_guid MEDIUMTEXT NULL");
        }
        String largerReference = "x".repeat(70000);
        insertAndReadReferences("larger", largerReference);

        initializer.run(null);

        for (String table : TABLES) {
            assertThat(columnType(table)).isEqualTo("mediumtext");
            assertReference(table, "larger", largerReference);
        }
    }

    private void createTaskTables() {
        animeMapper.createTableIfNotExists();
        movieMapper.createTableIfNotExists();
        seriesMapper.createTableIfNotExists();
    }

    private void insertAndReadReferences(String id, String reference) throws Exception {
        var anime = new AnimeMagnetIngestTask();
        var movie = new MovieMagnetIngestTask();
        var series = new SeriesMagnetIngestTask();
        for (Object task : new Object[]{anime, movie, series}) {
            for (var field : Map.of("Id", id, "Status", "PENDING", "Stage", "created", "Magnet", "magnet:test",
                    "MagnetHash", id, "Title", "测试", "ReleaseGuid", reference,
                    "SavePath", "/test", "TempPath", "/test").entrySet()) {
                task.getClass().getMethod("set" + field.getKey(), String.class).invoke(task, field.getValue());
            }
        }
        anime.setSeasonNumber(1);
        movie.setYear(2020);
        series.setSeasonNumber(1);
        series.setSeriesName("测试");
        series.setSeasonFolder("Season 01");
        animeMapper.insert(anime);
        movieMapper.insert(movie);
        seriesMapper.insert(series);
        assertThat(animeMapper.selectById(id).getReleaseGuid()).isEqualTo(reference);
        assertThat(movieMapper.selectById(id).getReleaseGuid()).isEqualTo(reference);
        assertThat(seriesMapper.selectById(id).getReleaseGuid()).isEqualTo(reference);
    }

    private void assertReference(String table, String id, String expected) throws Exception {
        try (var statement = connection.prepareStatement("SELECT release_guid FROM " + table + " WHERE id = ?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(expected);
            }
        }
    }

    private String columnType(String table) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT DATA_TYPE FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'release_guid'
                """)) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private void execute(String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
