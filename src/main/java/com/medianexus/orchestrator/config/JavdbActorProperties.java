package com.medianexus.orchestrator.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Canonical actor names and exact aliases; an empty map keeps rating-only selection. */
@Component
@Validated
@ConfigurationProperties(prefix = "medianexus.javdb-actors")
public class JavdbActorProperties {
    @NotNull
    private Map<@NotBlank String, @NotNull List<@NotBlank String>> followed = new LinkedHashMap<>();

    public Map<String, List<String>> getFollowed() { return followed; }
    public void setFollowed(Map<String, List<String>> followed) { this.followed = followed; }
}
