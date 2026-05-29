package com.medianexus.orchestrator;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.medianexus.orchestrator")
@SpringBootApplication
public class MediaNexusOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaNexusOrchestratorApplication.class, args);
    }
}
