package com.medianexus.orchestrator.integration.clouddrive;

import static org.assertj.core.api.Assertions.assertThat;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
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
@EnabledIfSystemProperty(named = "clouddrive2.live", matches = "true")
@EnabledIfSystemProperty(named = "clouddrive2.liveFixtureRoot", matches = "/WebDAV/Media/_MediaNexus-CD2-Live-Test-.+")
class CloudDrive2GrpcClientLiveTest {

    private static final String FIXTURE_PREFIX = "/WebDAV/Media/_MediaNexus-CD2-Live-Test-";
    private static final int FILE_COUNT = 69;
    private static final Duration VISIBILITY_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    @Test
    void verifiesRealBatchRenameMoveDeleteAndVisibility() throws Throwable {
        CloudDrive2Properties properties = liveProperties();
        String fixtureRoot = System.getProperty("clouddrive2.liveFixtureRoot");
        assertThat(fixtureRoot).startsWith(FIXTURE_PREFIX);
        String fixtureName = fixtureRoot.substring(fixtureRoot.lastIndexOf('/') + 1);
        String sourceDirectory = fixtureRoot + "/source";
        String targetDirectory = fixtureRoot + "/target";

        CloudDrive2GrpcClient client = new CloudDrive2GrpcClient(properties);
        Throwable primaryFailure = null;
        try {
            Instant sourceVisibilityStarted = Instant.now();
            await(() -> visibleNames(client, sourceDirectory).containsAll(originalNames()), "测试源文件未完整可见");
            Duration sourceVisibilityDuration = Duration.between(sourceVisibilityStarted, Instant.now());

            List<CloudDrive2RenameOperation> renames = new ArrayList<>();
            for (int index = 1; index <= FILE_COUNT; index++) {
                renames.add(new CloudDrive2RenameOperation(
                        sourceDirectory + "/" + originalName(index),
                        renamedName(index)
                ));
            }

            Instant renameStarted = Instant.now();
            client.rename(renames);
            Duration renameRpcDuration = Duration.between(renameStarted, Instant.now());
            await(() -> visibleNames(client, sourceDirectory).containsAll(renamedNames()), "批量重命名未完整可见");
            Duration renameVisibleDuration = Duration.between(renameStarted, Instant.now());

            List<String> renamedPaths = new ArrayList<>();
            for (int index = 1; index <= FILE_COUNT; index++) {
                renamedPaths.add(sourceDirectory + "/" + renamedName(index));
            }
            Instant moveStarted = Instant.now();
            client.move(renamedPaths, targetDirectory);
            Duration moveRpcDuration = Duration.between(moveStarted, Instant.now());
            await(() -> visibleNames(client, targetDirectory).containsAll(renamedNames()), "批量移动目标未完整可见");
            await(() -> visibleNames(client, sourceDirectory).stream().noneMatch(renamedNames()::contains),
                    "批量移动源路径仍然可见");
            Duration moveVisibleDuration = Duration.between(moveStarted, Instant.now());

            String deleteTarget = targetDirectory + "/" + renamedName(FILE_COUNT);
            Instant deleteStarted = Instant.now();
            client.delete(List.of(deleteTarget));
            Duration deleteRpcDuration = Duration.between(deleteStarted, Instant.now());
            await(() -> !visibleNames(client, targetDirectory).contains(renamedName(FILE_COUNT)), "删除结果未可见");
            Duration deleteVisibleDuration = Duration.between(deleteStarted, Instant.now());

            assertThat(visibleNames(client, targetDirectory)).hasSize(FILE_COUNT - 1);
            System.out.printf(
                    "CD2_LIVE_RESULT files=%d sourceVisible=%dms renameRpc=%dms renameVisible=%dms "
                            + "moveRpc=%dms moveVisible=%dms renameMoveVisible=%dms deleteRpc=%dms deleteVisible=%dms%n",
                    FILE_COUNT,
                    sourceVisibilityDuration.toMillis(),
                    renameRpcDuration.toMillis(),
                    renameVisibleDuration.toMillis(),
                    moveRpcDuration.toMillis(),
                    moveVisibleDuration.toMillis(),
                    renameVisibleDuration.plus(moveVisibleDuration).toMillis(),
                    deleteRpcDuration.toMillis(),
                    deleteVisibleDuration.toMillis()
            );
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                cleanupFixture(properties, fixtureRoot, fixtureName);
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
            String fixtureRoot,
            String fixtureName
    ) {
        CloudDrive2GrpcClient cleanupClient = new CloudDrive2GrpcClient(properties);
        try {
            cleanupClient.delete(List.of(fixtureRoot));
            await(() -> !visibleNames(cleanupClient, "/WebDAV/Media").contains(fixtureName), "测试目录清理未可见");
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
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待 CD2 可见性时被中断", exception);
            }
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError(failureMessage);
    }

    private static Set<String> originalNames() {
        Set<String> names = new LinkedHashSet<>();
        for (int index = 1; index <= FILE_COUNT; index++) {
            names.add(originalName(index));
        }
        return names;
    }

    private static Set<String> renamedNames() {
        Set<String> names = new LinkedHashSet<>();
        for (int index = 1; index <= FILE_COUNT; index++) {
            names.add(renamedName(index));
        }
        return names;
    }

    private static String originalName(int index) {
        return "fixture-%02d.txt".formatted(index);
    }

    private static String renamedName(int index) {
        return "renamed-%02d.txt".formatted(index);
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
        properties.setOperationTimeout(Duration.ofMinutes(3));
        return properties;
    }

    private static String required(Properties env, String key) {
        String value = env.getProperty(key);
        assertThat(value).as(key).isNotBlank();
        return value.trim();
    }
}
