package com.medianexus.orchestrator.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ProductionConfigurationGuard implements InitializingBean {

    private static final List<String> PRODUCTION_PROFILES = List.of("prod", "production");

    private final Environment environment;
    private final DatabaseSshTunnelProperties sshTunnelProperties;
    private final AuthProperties authProperties;
    private final AniRssProperties aniRssProperties;
    private final CloudDrive2Properties cloudDrive2Properties;
    private final OpenListProperties openListProperties;
    private final ProwlarrProperties prowlarrProperties;
    private final TmdbProperties tmdbProperties;
    private final String datasourceUrl;
    private final String datasourceUsername;
    private final String datasourcePassword;

    public ProductionConfigurationGuard(
            Environment environment,
            DatabaseSshTunnelProperties sshTunnelProperties,
            AuthProperties authProperties,
            AniRssProperties aniRssProperties,
            CloudDrive2Properties cloudDrive2Properties,
            OpenListProperties openListProperties,
            ProwlarrProperties prowlarrProperties,
            TmdbProperties tmdbProperties,
            @Value("${spring.datasource.url:}") String datasourceUrl,
            @Value("${spring.datasource.username:}") String datasourceUsername,
            @Value("${spring.datasource.password:}") String datasourcePassword
    ) {
        this.environment = environment;
        this.sshTunnelProperties = sshTunnelProperties;
        this.authProperties = authProperties;
        this.aniRssProperties = aniRssProperties;
        this.cloudDrive2Properties = cloudDrive2Properties;
        this.openListProperties = openListProperties;
        this.prowlarrProperties = prowlarrProperties;
        this.tmdbProperties = tmdbProperties;
        this.datasourceUrl = datasourceUrl;
        this.datasourceUsername = datasourceUsername;
        this.datasourcePassword = datasourcePassword;
    }

    @Override
    public void afterPropertiesSet() {
        if (!isProductionProfile()) {
            return;
        }

        List<String> violations = new ArrayList<>();
        validateApiDocsDisabled(violations);
        validateDatasource(violations);
        validateSshTunnel(violations);
        validateSecretPlaceholders(violations);
        validateCloudDrive2(violations);

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Unsafe production configuration:\n- " + String.join("\n- ", violations)
            );
        }
    }

    private boolean isProductionProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(PRODUCTION_PROFILES::contains);
    }

    private void validateApiDocsDisabled(List<String> violations) {
        if (isEnabled("knife4j.enable")) {
            violations.add("knife4j.enable must be false under prod/production profile");
        }
        if (isEnabled("springdoc.api-docs.enabled")) {
            violations.add("springdoc.api-docs.enabled must be false under prod/production profile");
        }
        if (isEnabled("springdoc.swagger-ui.enabled")) {
            violations.add("springdoc.swagger-ui.enabled must be false under prod/production profile");
        }
    }

    private boolean isEnabled(String propertyName) {
        return environment.getProperty(propertyName, Boolean.class, false);
    }

    private void validateDatasource(List<String> violations) {
        if (!StringUtils.hasText(datasourceUrl)) {
            violations.add("spring.datasource.url must be configured for production");
            return;
        }

        String normalizedUrl = datasourceUrl.toLowerCase(Locale.ROOT);
        boolean usesTunnelEndpoint = usesConfiguredLocalTunnelEndpoint(normalizedUrl);
        boolean usesManagedTunnelEndpoint = sshTunnelProperties.isEnabled() && usesTunnelEndpoint;
        boolean usesDockerNetworkMysql = usesDockerNetworkMysql(normalizedUrl);

        if (usesTunnelEndpoint && !sshTunnelProperties.isEnabled()) {
            violations.add("spring.datasource.url points at the local SSH tunnel endpoint but MEDIANEXUS_DB_SSH_TUNNEL_ENABLED is false");
        }
        if (!usesManagedTunnelEndpoint && !usesDockerNetworkMysql && containsJdbcOption(normalizedUrl, "usessl", "false")) {
            violations.add("spring.datasource.url must not set useSSL=false in production");
        }
        if (!usesManagedTunnelEndpoint && !usesDockerNetworkMysql && containsJdbcOption(normalizedUrl, "sslmode", "disabled")) {
            violations.add("spring.datasource.url must not set sslMode=DISABLED in production");
        }
        if (!usesManagedTunnelEndpoint && containsJdbcOption(normalizedUrl, "allowpublickeyretrieval", "true")) {
            violations.add("spring.datasource.url must not set allowPublicKeyRetrieval=true in production");
        }
        if (!StringUtils.hasText(datasourceUsername)) {
            violations.add("spring.datasource.username must be configured for production");
        } else if ("root".equalsIgnoreCase(datasourceUsername.trim())) {
            violations.add("spring.datasource.username must not be root in production");
        }
        if (!StringUtils.hasText(datasourcePassword)) {
            violations.add("spring.datasource.password must be configured for production");
        }
    }

    private boolean containsJdbcOption(String normalizedUrl, String optionName, String expectedValue) {
        return normalizedUrl.contains(optionName + "=" + expectedValue);
    }

    private boolean usesDockerNetworkMysql(String normalizedUrl) {
        return normalizedUrl.contains("//medianexus_mysql:3306/");
    }

    private boolean usesConfiguredLocalTunnelEndpoint(String normalizedUrl) {
        int localPort = sshTunnelProperties.getLocalPort() > 0 ? sshTunnelProperties.getLocalPort() : 3307;
        List<String> localHosts = new ArrayList<>(List.of("127.0.0.1", "localhost"));
        if (StringUtils.hasText(sshTunnelProperties.getLocalHost())) {
            localHosts.add(sshTunnelProperties.getLocalHost().toLowerCase(Locale.ROOT));
        }
        return localHosts.stream()
                .distinct()
                .anyMatch(localHost -> normalizedUrl.contains(localHost + ":" + localPort));
    }

    private void validateSshTunnel(List<String> violations) {
        if (sshTunnelProperties.isEnabled() && !sshTunnelProperties.isStrictHostKeyChecking()) {
            violations.add("MEDIANEXUS_DB_SSH_STRICT_HOST_KEY_CHECKING must be true when production SSH tunnel is enabled");
        }
    }

    private void validateSecretPlaceholders(List<String> violations) {
        rejectPlaceholder("spring.datasource.password", datasourcePassword, violations);
        rejectPlaceholder("medianexus.auth.registration-code", authProperties.getRegistrationCode(), violations);
        rejectPlaceholder("medianexus.ani-rss.api-key", aniRssProperties.getApiKey(), violations);
        rejectPlaceholder("medianexus.openlist.authorization", openListProperties.getAuthorization(), violations);
        rejectPlaceholder("medianexus.prowlarr.api-key", prowlarrProperties.getApiKey(), violations);
        rejectPlaceholder("medianexus.tmdb.api-token", tmdbProperties.getApiToken(), violations);
    }

    private void validateCloudDrive2(List<String> violations) {
        if (!cloudDrive2Properties.isOrganizationEnabled()) {
            return;
        }
        if (!StringUtils.hasText(cloudDrive2Properties.getHost())) {
            violations.add("medianexus.clouddrive2.host must be configured when CD2 organization is enabled");
        } else {
            rejectPlaceholder("medianexus.clouddrive2.host", cloudDrive2Properties.getHost(), violations);
        }
        if (!StringUtils.hasText(cloudDrive2Properties.getApiToken())) {
            violations.add("medianexus.clouddrive2.api-token must be configured when CD2 organization is enabled");
        } else {
            rejectPlaceholder("medianexus.clouddrive2.api-token", cloudDrive2Properties.getApiToken(), violations);
        }
    }

    private void rejectPlaceholder(String propertyName, String value, List<String> violations) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        String normalizedValue = value.trim().toLowerCase(Locale.ROOT);
        if (List.of("change-me", "changeme", "replace-me", "password", "secret").contains(normalizedValue)) {
            violations.add(propertyName + " must not use a placeholder value in production");
        }
    }
}
