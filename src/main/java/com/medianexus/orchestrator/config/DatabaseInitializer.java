package com.medianexus.orchestrator.config;

import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.EmbyActivePlaybackSessionMapper;
import com.medianexus.orchestrator.mapper.EmbyWatchSessionMapper;
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
        embyActivePlaybackSessionMapper.createTableIfNotExists();
        embyWatchSessionMapper.createTableIfNotExists();
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

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry database initialization", exception);
        }
    }
}
