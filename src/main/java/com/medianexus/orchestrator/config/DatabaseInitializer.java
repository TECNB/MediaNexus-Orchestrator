package com.medianexus.orchestrator.config;

import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.EmbyActivePlaybackSessionMapper;
import com.medianexus.orchestrator.mapper.EmbyWatchSessionMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.SubtitleUploadLogMapper;
import com.medianexus.orchestrator.mapper.SubtitleUploadMapper;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import com.medianexus.orchestrator.mapper.UserActionUsageMapper;
import com.medianexus.orchestrator.mapper.UserAdminAuditLogMapper;
import com.medianexus.orchestrator.mapper.UserMapper;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);
    private static final int MAX_INITIALIZATION_ATTEMPTS = 5;
    private static final Duration INITIALIZATION_RETRY_DELAY = Duration.ofSeconds(2);

    private final DatabaseSshTunnelLifecycle databaseSshTunnelLifecycle;
    private final UserMapper userMapper;
    private final UserActionUsageMapper userActionUsageMapper;
    private final SystemSettingMapper systemSettingMapper;
    private final UserAdminAuditLogMapper userAdminAuditLogMapper;
    private final AnimeMagnetIngestTaskMapper animeMagnetIngestTaskMapper;
    private final AnimeMagnetIngestTaskLogMapper animeMagnetIngestTaskLogMapper;
    private final MovieMagnetIngestTaskMapper movieMagnetIngestTaskMapper;
    private final MovieMagnetIngestTaskLogMapper movieMagnetIngestTaskLogMapper;
    private final SeriesMagnetIngestTaskMapper seriesMagnetIngestTaskMapper;
    private final SeriesMagnetIngestTaskLogMapper seriesMagnetIngestTaskLogMapper;
    private final SubtitleUploadMapper subtitleUploadMapper;
    private final SubtitleUploadLogMapper subtitleUploadLogMapper;
    private final EmbyActivePlaybackSessionMapper embyActivePlaybackSessionMapper;
    private final EmbyWatchSessionMapper embyWatchSessionMapper;

    public DatabaseInitializer(
            DatabaseSshTunnelLifecycle databaseSshTunnelLifecycle,
            UserMapper userMapper,
            UserActionUsageMapper userActionUsageMapper,
            SystemSettingMapper systemSettingMapper,
            UserAdminAuditLogMapper userAdminAuditLogMapper,
            AnimeMagnetIngestTaskMapper animeMagnetIngestTaskMapper,
            AnimeMagnetIngestTaskLogMapper animeMagnetIngestTaskLogMapper,
            MovieMagnetIngestTaskMapper movieMagnetIngestTaskMapper,
            MovieMagnetIngestTaskLogMapper movieMagnetIngestTaskLogMapper,
            SeriesMagnetIngestTaskMapper seriesMagnetIngestTaskMapper,
            SeriesMagnetIngestTaskLogMapper seriesMagnetIngestTaskLogMapper,
            SubtitleUploadMapper subtitleUploadMapper,
            SubtitleUploadLogMapper subtitleUploadLogMapper,
            EmbyActivePlaybackSessionMapper embyActivePlaybackSessionMapper,
            EmbyWatchSessionMapper embyWatchSessionMapper
    ) {
        this.databaseSshTunnelLifecycle = databaseSshTunnelLifecycle;
        this.userMapper = userMapper;
        this.userActionUsageMapper = userActionUsageMapper;
        this.systemSettingMapper = systemSettingMapper;
        this.userAdminAuditLogMapper = userAdminAuditLogMapper;
        this.animeMagnetIngestTaskMapper = animeMagnetIngestTaskMapper;
        this.animeMagnetIngestTaskLogMapper = animeMagnetIngestTaskLogMapper;
        this.movieMagnetIngestTaskMapper = movieMagnetIngestTaskMapper;
        this.movieMagnetIngestTaskLogMapper = movieMagnetIngestTaskLogMapper;
        this.seriesMagnetIngestTaskMapper = seriesMagnetIngestTaskMapper;
        this.seriesMagnetIngestTaskLogMapper = seriesMagnetIngestTaskLogMapper;
        this.subtitleUploadMapper = subtitleUploadMapper;
        this.subtitleUploadLogMapper = subtitleUploadLogMapper;
        this.embyActivePlaybackSessionMapper = embyActivePlaybackSessionMapper;
        this.embyWatchSessionMapper = embyWatchSessionMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (int attempt = 1; attempt <= MAX_INITIALIZATION_ATTEMPTS; attempt++) {
            try {
                databaseSshTunnelLifecycle.ensureRunning();
                createTablesIfNotExists();
                return;
            } catch (RuntimeException exception) {
                if (attempt == MAX_INITIALIZATION_ATTEMPTS) {
                    throw exception;
                }
                log.warn(
                        "Database initialization failed; retrying in {} seconds ({}/{}) because: {}",
                        INITIALIZATION_RETRY_DELAY.toSeconds(),
                        attempt,
                        MAX_INITIALIZATION_ATTEMPTS,
                        exception.getMessage()
                );
                sleep(INITIALIZATION_RETRY_DELAY);
            }
        }
    }

    private void createTablesIfNotExists() {
        userMapper.createTableIfNotExists();
        ensureUserQuotaOverrideColumn();
        userActionUsageMapper.createTableIfNotExists();
        systemSettingMapper.createTableIfNotExists();
        userAdminAuditLogMapper.createTableIfNotExists();
        animeMagnetIngestTaskMapper.createTableIfNotExists();
        ensureAnimeMagnetTaskOwnerColumn();
        animeMagnetIngestTaskLogMapper.createTableIfNotExists();
        movieMagnetIngestTaskMapper.createTableIfNotExists();
        ensureMovieMagnetTaskTagColumns();
        movieMagnetIngestTaskLogMapper.createTableIfNotExists();
        seriesMagnetIngestTaskMapper.createTableIfNotExists();
        ensureSeriesMagnetTaskTagColumns();
        seriesMagnetIngestTaskLogMapper.createTableIfNotExists();
        subtitleUploadMapper.createTableIfNotExists();
        ensureSubtitleUploadColumns();
        subtitleUploadLogMapper.createTableIfNotExists();
        embyActivePlaybackSessionMapper.createTableIfNotExists();
        ensureEmbyActivePlaybackSessionColumns();
        embyWatchSessionMapper.createTableIfNotExists();
        ensureEmbyWatchSessionColumns();
    }

    private void ensureUserQuotaOverrideColumn() {
        Integer columnCount = userMapper.countDailyContentCreateLimitOverrideColumn();
        if (columnCount == null || columnCount == 0) {
            userMapper.addDailyContentCreateLimitOverrideColumn();
        }
    }

    private void ensureAnimeMagnetTaskOwnerColumn() {
        Integer columnCount = animeMagnetIngestTaskMapper.countCreatedByUserIdColumn();
        if (columnCount == null || columnCount == 0) {
            animeMagnetIngestTaskMapper.addCreatedByUserIdColumn();
        }
        Integer indexCount = animeMagnetIngestTaskMapper.countOwnerCreatedAtIndex();
        if (indexCount == null || indexCount == 0) {
            animeMagnetIngestTaskMapper.addOwnerCreatedAtIndex();
        }
    }

    private void ensureMovieMagnetTaskTagColumns() {
        Integer sourceTypeColumnCount = movieMagnetIngestTaskMapper.countSourceTypeColumn();
        if (sourceTypeColumnCount == null || sourceTypeColumnCount == 0) {
            movieMagnetIngestTaskMapper.addReleaseMetadataColumns();
        }
        Integer qualityTagColumnCount = movieMagnetIngestTaskMapper.countQualityTagColumn();
        if (qualityTagColumnCount == null || qualityTagColumnCount == 0) {
            movieMagnetIngestTaskMapper.addQualityTagColumn();
        }
        Integer dynamicRangeTagsColumnCount = movieMagnetIngestTaskMapper.countDynamicRangeTagsColumn();
        if (dynamicRangeTagsColumnCount == null || dynamicRangeTagsColumnCount == 0) {
            movieMagnetIngestTaskMapper.addDynamicRangeTagsColumn();
        }
    }

    private void ensureSeriesMagnetTaskTagColumns() {
        Integer sourceTypeColumnCount = seriesMagnetIngestTaskMapper.countSourceTypeColumn();
        if (sourceTypeColumnCount == null || sourceTypeColumnCount == 0) {
            seriesMagnetIngestTaskMapper.addReleaseMetadataColumns();
        }
        Integer qualityTagColumnCount = seriesMagnetIngestTaskMapper.countQualityTagColumn();
        if (qualityTagColumnCount == null || qualityTagColumnCount == 0) {
            seriesMagnetIngestTaskMapper.addQualityTagColumn();
        }
        Integer dynamicRangeTagsColumnCount = seriesMagnetIngestTaskMapper.countDynamicRangeTagsColumn();
        if (dynamicRangeTagsColumnCount == null || dynamicRangeTagsColumnCount == 0) {
            seriesMagnetIngestTaskMapper.addDynamicRangeTagsColumn();
        }
    }

    private void ensureSubtitleUploadColumns() {
        Integer seasonNumberColumnCount = subtitleUploadMapper.countSeasonNumberColumn();
        if (seasonNumberColumnCount == null || seasonNumberColumnCount == 0) {
            subtitleUploadMapper.addSeasonNumberColumn();
        }
        Integer requiredYearColumnCount = subtitleUploadMapper.countRequiredYearColumn();
        if (requiredYearColumnCount != null && requiredYearColumnCount > 0) {
            subtitleUploadMapper.makeYearNullable();
        }
    }

    private void ensureEmbyActivePlaybackSessionColumns() {
        Integer requiredStartPositionTicksColumnCount =
                embyActivePlaybackSessionMapper.countRequiredStartPositionTicksColumn();
        if (requiredStartPositionTicksColumnCount != null && requiredStartPositionTicksColumnCount > 0) {
            embyActivePlaybackSessionMapper.makeStartPositionTicksNullable();
        }
    }

    private void ensureEmbyWatchSessionColumns() {
        Integer requiredStartPositionTicksColumnCount =
                embyWatchSessionMapper.countRequiredStartPositionTicksColumn();
        if (requiredStartPositionTicksColumnCount != null && requiredStartPositionTicksColumnCount > 0) {
            embyWatchSessionMapper.makeStartPositionTicksNullable();
        }

        Integer requiredStopPositionTicksColumnCount =
                embyWatchSessionMapper.countRequiredStopPositionTicksColumn();
        if (requiredStopPositionTicksColumnCount != null && requiredStopPositionTicksColumnCount > 0) {
            embyWatchSessionMapper.makeStopPositionTicksNullable();
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry database initialization", exception);
        }
    }
}
