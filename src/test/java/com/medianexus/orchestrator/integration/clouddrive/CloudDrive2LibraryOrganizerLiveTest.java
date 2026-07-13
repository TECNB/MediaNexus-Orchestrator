package com.medianexus.orchestrator.integration.clouddrive;

import static org.assertj.core.api.Assertions.assertThat;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.DeleteOperation;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.MoveOperation;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.RenameOperation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@Tag("live")
@EnabledIfSystemProperty(
        named = "clouddrive2.organizerLiveFixtureRoot",
        matches = "/WebDAV/Media/_MediaNexus-CD2-Live-Test-.+"
)
class CloudDrive2LibraryOrganizerLiveTest {

    private static final String FIXTURE_PREFIX = "/WebDAV/Media/_MediaNexus-CD2-Live-Test-";
    private static final Duration VISIBILITY_TIMEOUT = Duration.ofMinutes(3);

    @Test
    void verifiesRealOrganizerPathMappingManifestAndCleanup() throws Throwable {
        CloudDrive2Properties properties = liveProperties();
        String cloudDriveFixtureRoot = System.getProperty("clouddrive2.organizerLiveFixtureRoot");
        assertThat(cloudDriveFixtureRoot).startsWith(FIXTURE_PREFIX);
        String fixtureName = cloudDriveFixtureRoot.substring(cloudDriveFixtureRoot.lastIndexOf('/') + 1);
        String ingestFixtureRoot = "/pikpak" + cloudDriveFixtureRoot.substring("/WebDAV".length());
        String ingestReleaseDirectory = ingestFixtureRoot + "/Release";

        CloudDrive2GrpcClient client = new CloudDrive2GrpcClient(properties);
        CloudDrive2LibraryOrganizer organizer = new CloudDrive2LibraryOrganizer(client, properties);
        Throwable primaryFailure = null;
        try {
            LibraryOrganizationPlan plan = new LibraryOrganizationPlan(
                    ingestFixtureRoot,
                    List.of(
                            new RenameOperation(ingestReleaseDirectory + "/[Group] Show - 01.mkv", "Show S01E01.mkv"),
                            new RenameOperation(ingestReleaseDirectory + "/[Group] Show - 02.mkv", "Show S01E02.mkv"),
                            new RenameOperation(ingestReleaseDirectory + "/[Group] Show - 03.mkv", "Show S01E03.mkv")
                    ),
                    List.of(
                            new MoveOperation(ingestReleaseDirectory + "/Show S01E01.mkv", ingestFixtureRoot),
                            new MoveOperation(ingestReleaseDirectory + "/Show S01E02.mkv", ingestFixtureRoot),
                            new MoveOperation(ingestReleaseDirectory + "/Show S01E03.mkv", ingestFixtureRoot)
                    ),
                    List.of(new DeleteOperation(ingestFixtureRoot + "/Scans")),
                    Set.of("Show S01E01.mkv", "Show S01E02.mkv", "Show S01E03.mkv"),
                    Set.of(ingestFixtureRoot + "/Scans")
            );

            List<String> progress = new ArrayList<>();
            Instant started = Instant.now();
            organizer.organize(plan, (message, detail) -> progress.add(message));
            Duration organizerDuration = Duration.between(started, Instant.now());

            await(() -> {
                Set<String> names = visibleNames(client, cloudDriveFixtureRoot);
                return names.containsAll(plan.expectedTargetNames())
                        && !names.contains("Release")
                        && !names.contains("Scans");
            }, "Organizer 最终目录或清理结果未完整可见");
            assertThat(progress).contains("CD2 已看见完整源文件", "CD2 整理结果已完整可见");
            System.out.printf("CD2_ORGANIZER_LIVE_RESULT files=3 total=%dms progressEvents=%d%n",
                    organizerDuration.toMillis(), progress.size());
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                cleanupFixture(properties, cloudDriveFixtureRoot, fixtureName);
            } catch (Throwable cleanupFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            } finally {
                client.close();
            }
        }
    }

    private static Set<String> visibleNames(CloudDrive2GrpcClient client, String directory) {
        return client.list(directory, true).stream()
                .map(CloudDrive2FileEntry::name)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    private static void cleanupFixture(
            CloudDrive2Properties properties,
            String cloudDriveFixtureRoot,
            String fixtureName
    ) {
        CloudDrive2GrpcClient cleanupClient = new CloudDrive2GrpcClient(properties);
        try {
            cleanupClient.delete(List.of(cloudDriveFixtureRoot));
            await(() -> !visibleNames(cleanupClient, "/WebDAV/Media").contains(fixtureName),
                    "Organizer 测试目录清理未可见");
        } finally {
            cleanupClient.close();
        }
    }

    private static void await(BooleanSupplier condition, String failureMessage) {
        Instant deadline = Instant.now().plus(VISIBILITY_TIMEOUT);
        do {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待 CD2 可见性时被中断", exception);
            }
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError(failureMessage);
    }

    private static CloudDrive2Properties liveProperties() throws IOException {
        Properties env = new Properties();
        try (InputStream input = Files.newInputStream(Path.of(".env"))) {
            env.load(input);
        }
        CloudDrive2Properties properties = new CloudDrive2Properties();
        properties.setHost(required(env, "MEDIANEXUS_CLOUDDRIVE2_HOST"));
        properties.setPort(Integer.parseInt(required(env, "MEDIANEXUS_CLOUDDRIVE2_PORT")));
        properties.setApiToken(required(env, "MEDIANEXUS_CLOUDDRIVE2_API_TOKEN"));
        properties.setIngestPathPrefix(required(env, "MEDIANEXUS_CLOUDDRIVE2_INGEST_PATH_PREFIX"));
        properties.setCloudDrivePathPrefix(required(env, "MEDIANEXUS_CLOUDDRIVE2_PATH_PREFIX"));
        properties.setOperationTimeout(Duration.ofMinutes(3));
        properties.setVisibilityTimeout(VISIBILITY_TIMEOUT);
        properties.setVisibilityPollInterval(Duration.ofSeconds(1));
        return properties;
    }

    private static String required(Properties env, String key) {
        String value = env.getProperty(key);
        assertThat(value).as(key).isNotBlank();
        return value.trim();
    }
}
