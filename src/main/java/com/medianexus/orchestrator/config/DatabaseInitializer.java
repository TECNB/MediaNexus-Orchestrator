package com.medianexus.orchestrator.config;

import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
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
    private final AnimeMagnetIngestTaskMapper animeMagnetIngestTaskMapper;
    private final AnimeMagnetIngestTaskLogMapper animeMagnetIngestTaskLogMapper;

    public DatabaseInitializer(
            DatabaseSshTunnelLifecycle databaseSshTunnelLifecycle,
            UserMapper userMapper,
            AnimeMagnetIngestTaskMapper animeMagnetIngestTaskMapper,
            AnimeMagnetIngestTaskLogMapper animeMagnetIngestTaskLogMapper
    ) {
        this.databaseSshTunnelLifecycle = databaseSshTunnelLifecycle;
        this.userMapper = userMapper;
        this.animeMagnetIngestTaskMapper = animeMagnetIngestTaskMapper;
        this.animeMagnetIngestTaskLogMapper = animeMagnetIngestTaskLogMapper;
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
        animeMagnetIngestTaskMapper.createTableIfNotExists();
        animeMagnetIngestTaskLogMapper.createTableIfNotExists();
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
