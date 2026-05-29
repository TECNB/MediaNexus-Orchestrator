package com.medianexus.orchestrator.config;

import com.medianexus.orchestrator.mapper.TestUserMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer implements ApplicationRunner {

    private final TestUserMapper testUserMapper;

    public DatabaseInitializer(TestUserMapper testUserMapper) {
        this.testUserMapper = testUserMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        testUserMapper.createTableIfNotExists();
    }
}
