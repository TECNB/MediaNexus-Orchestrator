package com.medianexus.orchestrator.config;

import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.TestUserMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer implements ApplicationRunner {

    private final TestUserMapper testUserMapper;
    private final AnimeMagnetIngestTaskMapper animeMagnetIngestTaskMapper;
    private final AnimeMagnetIngestTaskLogMapper animeMagnetIngestTaskLogMapper;

    public DatabaseInitializer(
            TestUserMapper testUserMapper,
            AnimeMagnetIngestTaskMapper animeMagnetIngestTaskMapper,
            AnimeMagnetIngestTaskLogMapper animeMagnetIngestTaskLogMapper
    ) {
        this.testUserMapper = testUserMapper;
        this.animeMagnetIngestTaskMapper = animeMagnetIngestTaskMapper;
        this.animeMagnetIngestTaskLogMapper = animeMagnetIngestTaskLogMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        testUserMapper.createTableIfNotExists();
        animeMagnetIngestTaskMapper.createTableIfNotExists();
        animeMagnetIngestTaskLogMapper.createTableIfNotExists();
    }
}
