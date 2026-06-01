package com.medianexus.orchestrator;

import com.medianexus.orchestrator.config.AniRssProperties;
import com.medianexus.orchestrator.config.DatabaseSshTunnelProperties;
import com.medianexus.orchestrator.config.OpenListProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@MapperScan("com.medianexus.orchestrator")
@EnableConfigurationProperties({AniRssProperties.class, DatabaseSshTunnelProperties.class, OpenListProperties.class})
@SpringBootApplication
public class MediaNexusOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaNexusOrchestratorApplication.class, args);
    }
}
