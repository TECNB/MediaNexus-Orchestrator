package com.medianexus.orchestrator;

import com.medianexus.orchestrator.config.AuthProperties;
import com.medianexus.orchestrator.config.AutoSymlinkProperties;
import com.medianexus.orchestrator.config.AniRssProperties;
import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.config.DatabaseSshTunnelProperties;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.config.ProwlarrProperties;
import com.medianexus.orchestrator.config.SonarrProperties;
import com.medianexus.orchestrator.config.SubtitleUploadProperties;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.medianexus.orchestrator.config.UserQuotaProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@MapperScan("com.medianexus.orchestrator.mapper")
@EnableConfigurationProperties({
        AniRssProperties.class,
        AuthProperties.class,
        AutoSymlinkProperties.class,
        CloudDrive2Properties.class,
        DatabaseSshTunnelProperties.class,
        EmbyProperties.class,
        OpenListProperties.class,
        ProwlarrProperties.class,
        SonarrProperties.class,
        SubtitleUploadProperties.class,
        TmdbProperties.class,
        UserQuotaProperties.class
})
@SpringBootApplication
public class MediaNexusOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaNexusOrchestratorApplication.class, args);
    }
}
