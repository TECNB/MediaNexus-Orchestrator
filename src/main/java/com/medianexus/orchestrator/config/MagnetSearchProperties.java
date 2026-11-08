package com.medianexus.orchestrator.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "medianexus.magnet-search")
public class MagnetSearchProperties {
    @NotBlank
    @Pattern(regexp = "PANSOU|PROWLARR", message = "磁力搜索引擎只能为 PANSOU 或 PROWLARR")
    private String engine;

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
}
