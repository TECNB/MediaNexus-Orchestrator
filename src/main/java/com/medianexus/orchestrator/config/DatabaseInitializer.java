package com.medianexus.orchestrator.config;

import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.AdultOtherCollectionSyncGroupMapper;
import com.medianexus.orchestrator.mapper.AdultOtherCollectionKnownItemMapper;
import com.medianexus.orchestrator.mapper.AdultOtherCollectionSyncRunMapper;
import com.medianexus.orchestrator.mapper.AdultOtherAutomationRunCollectionMapper;
import com.medianexus.orchestrator.mapper.AdultOtherAutomationRunItemMapper;
import com.medianexus.orchestrator.mapper.AdultOtherAutomationRunMapper;
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
    private final AdultMagnetIngestTaskMapper adultMagnetIngestTaskMapper;
    private final AdultMagnetIngestTaskLogMapper adultMagnetIngestTaskLogMapper;
    private final SubtitleUploadMapper subtitleUploadMapper;
    private final SubtitleUploadLogMapper subtitleUploadLogMapper;
    private final EmbyActivePlaybackSessionMapper embyActivePlaybackSessionMapper;
    private final EmbyWatchSessionMapper embyWatchSessionMapper;
    private final AdultOtherCollectionSyncRunMapper adultOtherCollectionSyncRunMapper;
    private final AdultOtherCollectionSyncGroupMapper adultOtherCollectionSyncGroupMapper;
    private final AdultOtherCollectionKnownItemMapper adultOtherCollectionKnownItemMapper;
    private final AdultOtherAutomationRunMapper adultOtherAutomationRunMapper;
    private final AdultOtherAutomationRunItemMapper adultOtherAutomationRunItemMapper;
    private final AdultOtherAutomationRunCollectionMapper adultOtherAutomationRunCollectionMapper;

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
            AdultMagnetIngestTaskMapper adultMagnetIngestTaskMapper,
            AdultMagnetIngestTaskLogMapper adultMagnetIngestTaskLogMapper,
            SubtitleUploadMapper subtitleUploadMapper,
            SubtitleUploadLogMapper subtitleUploadLogMapper,
            EmbyActivePlaybackSessionMapper embyActivePlaybackSessionMapper,
            EmbyWatchSessionMapper embyWatchSessionMapper,
            AdultOtherCollectionSyncRunMapper adultOtherCollectionSyncRunMapper,
            AdultOtherCollectionSyncGroupMapper adultOtherCollectionSyncGroupMapper,
            AdultOtherCollectionKnownItemMapper adultOtherCollectionKnownItemMapper,
            AdultOtherAutomationRunMapper adultOtherAutomationRunMapper,
            AdultOtherAutomationRunItemMapper adultOtherAutomationRunItemMapper,
            AdultOtherAutomationRunCollectionMapper adultOtherAutomationRunCollectionMapper
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
        this.adultMagnetIngestTaskMapper = adultMagnetIngestTaskMapper;
        this.adultMagnetIngestTaskLogMapper = adultMagnetIngestTaskLogMapper;
        this.subtitleUploadMapper = subtitleUploadMapper;
        this.subtitleUploadLogMapper = subtitleUploadLogMapper;
        this.embyActivePlaybackSessionMapper = embyActivePlaybackSessionMapper;
        this.embyWatchSessionMapper = embyWatchSessionMapper;
        this.adultOtherCollectionSyncRunMapper = adultOtherCollectionSyncRunMapper;
        this.adultOtherCollectionSyncGroupMapper = adultOtherCollectionSyncGroupMapper;
        this.adultOtherCollectionKnownItemMapper = adultOtherCollectionKnownItemMapper;
        this.adultOtherAutomationRunMapper = adultOtherAutomationRunMapper;
        this.adultOtherAutomationRunItemMapper = adultOtherAutomationRunItemMapper;
        this.adultOtherAutomationRunCollectionMapper = adultOtherAutomationRunCollectionMapper;
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
        ensureUserEmbyUserIdColumn();
        ensureUserQuotaOverrideColumn();
        ensureUserInviterColumns();
        userActionUsageMapper.createTableIfNotExists();
        systemSettingMapper.createTableIfNotExists();
        userAdminAuditLogMapper.createTableIfNotExists();
        animeMagnetIngestTaskMapper.createTableIfNotExists();
        ensureAnimeMagnetTaskCatalogIdentityColumns();
        ensureAnimeMagnetTaskBgmIdOptional();
        ensureAnimeMagnetTaskOwnerColumn();
        ensureAnimeMagnetTaskReleaseMetadataColumns();
        ensureAnimeMagnetTaskAttemptChainColumns();
        animeMagnetIngestTaskLogMapper.createTableIfNotExists();
        movieMagnetIngestTaskMapper.createTableIfNotExists();
        ensureMovieMagnetTaskCatalogIdentityColumns();
        ensureMovieMagnetTaskTagColumns();
        ensureMovieMagnetTaskAttemptChainColumns();
        movieMagnetIngestTaskLogMapper.createTableIfNotExists();
        seriesMagnetIngestTaskMapper.createTableIfNotExists();
        ensureSeriesMagnetTaskCatalogIdentityColumns();
        ensureSeriesMagnetTaskTagColumns();
        ensureSeriesMagnetTaskAttemptChainColumns();
        seriesMagnetIngestTaskLogMapper.createTableIfNotExists();
        adultMagnetIngestTaskMapper.createTableIfNotExists();
        ensureAdultMagnetTaskAttemptChainColumns();
        ensureAdultMagnetTaskDownloadLinksColumn();
        adultMagnetIngestTaskLogMapper.createTableIfNotExists();
        subtitleUploadMapper.createTableIfNotExists();
        ensureSubtitleUploadColumns();
        subtitleUploadLogMapper.createTableIfNotExists();
        embyActivePlaybackSessionMapper.createTableIfNotExists();
        ensureEmbyActivePlaybackSessionColumns();
        embyWatchSessionMapper.createTableIfNotExists();
        ensureEmbyWatchSessionColumns();
        adultOtherCollectionSyncRunMapper.createTableIfNotExists();
        ensureAdultOtherCollectionSyncRunColumns();
        adultOtherCollectionSyncGroupMapper.createTableIfNotExists();
        adultOtherCollectionKnownItemMapper.createTableIfNotExists();
        adultOtherAutomationRunMapper.createTableIfNotExists();
        adultOtherAutomationRunItemMapper.createTableIfNotExists();
        adultOtherAutomationRunCollectionMapper.createTableIfNotExists();
    }

    private void ensureUserQuotaOverrideColumn() {
        Integer columnCount = userMapper.countDailyContentCreateLimitOverrideColumn();
        if (columnCount == null || columnCount == 0) {
            userMapper.addDailyContentCreateLimitOverrideColumn();
        }
    }

    private void ensureUserEmbyUserIdColumn() {
        Integer columnCount = userMapper.countEmbyUserIdColumn();
        if (columnCount == null || columnCount == 0) {
            userMapper.addEmbyUserIdColumn();
        }
        Integer indexCount = userMapper.countEmbyUserIdUniqueIndex();
        if (indexCount == null || indexCount == 0) {
            userMapper.addEmbyUserIdUniqueIndex();
        }
    }

    private void ensureUserInviterColumns() {
        Integer inviterIdColumnCount = userMapper.countInvitedByUserIdColumn();
        if (inviterIdColumnCount == null || inviterIdColumnCount == 0) {
            userMapper.addInvitedByUserIdColumn();
        }
        Integer inviterUsernameColumnCount = userMapper.countInvitedByUsernameColumn();
        if (inviterUsernameColumnCount == null || inviterUsernameColumnCount == 0) {
            userMapper.addInvitedByUsernameColumn();
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

    private void ensureAnimeMagnetTaskCatalogIdentityColumns() {
        Integer columnCount = animeMagnetIngestTaskMapper.countCatalogIdentityColumns();
        if (columnCount == null || columnCount == 0) {
            animeMagnetIngestTaskMapper.addCatalogIdentityColumns();
        }
    }

    private void ensureAnimeMagnetTaskBgmIdOptional() {
        Integer columnCount = animeMagnetIngestTaskMapper.countRequiredBgmIdColumns();
        if (columnCount != null && columnCount > 0) {
            animeMagnetIngestTaskMapper.makeBgmIdNullable();
        }
    }

    private void ensureAnimeMagnetTaskReleaseMetadataColumns() {
        Integer columnCount = animeMagnetIngestTaskMapper.countSourceTypeColumn();
        if (columnCount == null || columnCount == 0) {
            animeMagnetIngestTaskMapper.addReleaseMetadataColumns();
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

    private void ensureMovieMagnetTaskCatalogIdentityColumns() {
        Integer columnCount = movieMagnetIngestTaskMapper.countCatalogIdentityColumns();
        if (columnCount == null || columnCount == 0) {
            movieMagnetIngestTaskMapper.addCatalogIdentityColumns();
        }
    }

    private void ensureMovieMagnetTaskAttemptChainColumns() {
        Integer columnCount = movieMagnetIngestTaskMapper.countAttemptChainColumns();
        if (columnCount == null || columnCount == 0) {
            movieMagnetIngestTaskMapper.addAttemptChainColumns();
        }
        Integer indexCount = movieMagnetIngestTaskMapper.countAttemptGroupIndex();
        if (indexCount == null || indexCount == 0) {
            movieMagnetIngestTaskMapper.addAttemptGroupIndex();
        }
    }

    private void ensureSeriesMagnetTaskTagColumns() {
        Integer taskProductTypeColumnCount = seriesMagnetIngestTaskMapper.countTaskProductTypeColumn();
        if (taskProductTypeColumnCount == null || taskProductTypeColumnCount == 0) {
            seriesMagnetIngestTaskMapper.addTaskProductTypeColumn();
        }
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

    private void ensureSeriesMagnetTaskCatalogIdentityColumns() {
        Integer columnCount = seriesMagnetIngestTaskMapper.countCatalogIdentityColumns();
        if (columnCount == null || columnCount == 0) {
            seriesMagnetIngestTaskMapper.addCatalogIdentityColumns();
        }
    }

    private void ensureSeriesMagnetTaskAttemptChainColumns() {
        Integer columnCount = seriesMagnetIngestTaskMapper.countAttemptChainColumns();
        if (columnCount == null || columnCount == 0) {
            seriesMagnetIngestTaskMapper.addAttemptChainColumns();
        }
        Integer indexCount = seriesMagnetIngestTaskMapper.countAttemptGroupIndex();
        if (indexCount == null || indexCount == 0) {
            seriesMagnetIngestTaskMapper.addAttemptGroupIndex();
        }
    }

    private void ensureAnimeMagnetTaskAttemptChainColumns() {
        Integer columnCount = animeMagnetIngestTaskMapper.countAttemptChainColumns();
        if (columnCount == null || columnCount == 0) {
            animeMagnetIngestTaskMapper.addAttemptChainColumns();
        }
        Integer indexCount = animeMagnetIngestTaskMapper.countAttemptGroupIndex();
        if (indexCount == null || indexCount == 0) {
            animeMagnetIngestTaskMapper.addAttemptGroupIndex();
        }
    }

    private void ensureAdultMagnetTaskAttemptChainColumns() {
        Integer columnCount = adultMagnetIngestTaskMapper.countAttemptChainColumns();
        if (columnCount == null || columnCount == 0) {
            adultMagnetIngestTaskMapper.addAttemptChainColumns();
        }
        Integer indexCount = adultMagnetIngestTaskMapper.countAttemptGroupIndex();
        if (indexCount == null || indexCount == 0) {
            adultMagnetIngestTaskMapper.addAttemptGroupIndex();
        }
    }

    private void ensureAdultMagnetTaskDownloadLinksColumn() {
        Integer columnCount = adultMagnetIngestTaskMapper.countDownloadLinksJsonColumn();
        if (columnCount == null || columnCount == 0) {
            adultMagnetIngestTaskMapper.addDownloadLinksJsonColumn();
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

    private void ensureAdultOtherCollectionSyncRunColumns() {
        Integer sourceFolderPathColumnCount = adultOtherCollectionSyncRunMapper.countSourceFolderPathColumn();
        if (sourceFolderPathColumnCount == null || sourceFolderPathColumnCount == 0) {
            adultOtherCollectionSyncRunMapper.addSourceFolderPathColumn();
        }
        Integer deletedCollectionCountColumnCount =
                adultOtherCollectionSyncRunMapper.countDeletedCollectionCountColumn();
        if (deletedCollectionCountColumnCount == null || deletedCollectionCountColumnCount == 0) {
            adultOtherCollectionSyncRunMapper.addDeletedCollectionCountColumn();
        }
        Integer reviewCollectionCountColumnCount =
                adultOtherCollectionSyncRunMapper.countReviewCollectionCountColumn();
        if (reviewCollectionCountColumnCount == null || reviewCollectionCountColumnCount == 0) {
            adultOtherCollectionSyncRunMapper.addReviewCollectionCountColumn();
        }
        Integer observedItemCountColumnCount = adultOtherCollectionSyncRunMapper.countObservedItemCountColumn();
        if (observedItemCountColumnCount == null || observedItemCountColumnCount == 0) {
            adultOtherCollectionSyncRunMapper.addObservedItemCountColumn();
        }
        Integer observedGroupCountColumnCount = adultOtherCollectionSyncRunMapper.countObservedGroupCountColumn();
        if (observedGroupCountColumnCount == null || observedGroupCountColumnCount == 0) {
            adultOtherCollectionSyncRunMapper.addObservedGroupCountColumn();
        }
    }

    private void ensureEmbyActivePlaybackSessionColumns() {
        Integer seasonNumberColumnCount = embyActivePlaybackSessionMapper.countSeasonNumberColumn();
        if (seasonNumberColumnCount == null || seasonNumberColumnCount == 0) {
            embyActivePlaybackSessionMapper.addEpisodePositionColumns();
        }

        Integer requiredStartPositionTicksColumnCount =
                embyActivePlaybackSessionMapper.countRequiredStartPositionTicksColumn();
        if (requiredStartPositionTicksColumnCount != null && requiredStartPositionTicksColumnCount > 0) {
            embyActivePlaybackSessionMapper.makeStartPositionTicksNullable();
        }
    }

    private void ensureEmbyWatchSessionColumns() {
        Integer seasonNumberColumnCount = embyWatchSessionMapper.countSeasonNumberColumn();
        if (seasonNumberColumnCount == null || seasonNumberColumnCount == 0) {
            embyWatchSessionMapper.addEpisodePositionColumns();
        }

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
