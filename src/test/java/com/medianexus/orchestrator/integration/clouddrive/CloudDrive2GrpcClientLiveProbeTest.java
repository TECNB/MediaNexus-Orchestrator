package com.medianexus.orchestrator.integration.clouddrive;

import static org.assertj.core.api.Assertions.assertThat;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@Tag("live")
@EnabledIfSystemProperty(named = "clouddrive2.liveProbePath", matches = "/.*")
class CloudDrive2GrpcClientLiveProbeTest {

    @Test
    void listsConfiguredPathWithForceRefresh() throws IOException {
        Properties env = new Properties();
        try (InputStream input = Files.newInputStream(Path.of(".env"))) {
            env.load(input);
        }
        CloudDrive2Properties properties = new CloudDrive2Properties();
        properties.setHost(required(env, "MEDIANEXUS_CLOUDDRIVE2_HOST"));
        properties.setPort(Integer.parseInt(required(env, "MEDIANEXUS_CLOUDDRIVE2_PORT")));
        properties.setApiToken(required(env, "MEDIANEXUS_CLOUDDRIVE2_API_TOKEN"));
        properties.setOperationTimeout(Duration.ofMinutes(3));

        String path = System.getProperty("clouddrive2.liveProbePath");
        CloudDrive2GrpcClient client = new CloudDrive2GrpcClient(properties);
        try {
            var entries = client.list(path, true);
            System.out.printf("CD2_LIVE_PROBE path=%s count=%d names=%s%n", path, entries.size(),
                    entries.stream().map(CloudDrive2FileEntry::name).limit(30).toList());
        } finally {
            client.close();
        }
    }

    private static String required(Properties env, String key) {
        String value = env.getProperty(key);
        assertThat(value).as(key).isNotBlank();
        return value.trim();
    }
}
