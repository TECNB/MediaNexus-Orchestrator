package com.medianexus.orchestrator.integration.clouddrive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.config.LibraryOrganizerConfiguration;
import com.medianexus.orchestrator.integration.openlist.OpenListLibraryOrganizer;
import com.medianexus.orchestrator.service.organization.LibraryOrganizer;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.DeleteOperation;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.MoveOperation;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.RenameOperation;
import io.grpc.Status;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class CloudDrive2LibraryOrganizerTest {

    @Test
    void checksKnownParentDirectlyWithoutRefreshingItsAncestorChain() {
        FakeFileOperations fileOperations = new FakeFileOperations();
        String target = "/WebDAV/Media/Adult/JAV/7.15";
        fileOperations.addFile(target, "DVAJ-749", 1024L);
        CloudDrive2LibraryOrganizer organizer = new CloudDrive2LibraryOrganizer(
                fileOperations,
                properties()
        );
        LibraryOrganizationPlan plan = new LibraryOrganizationPlan(
                "/pikpak/Media/Adult/JAV/7.15",
                List.of(),
                List.of(),
                List.of(),
                Set.of("DVAJ-749"),
                Set.of()
        );

        organizer.organize(plan, (message, detail) -> { });

        assertThat(fileOperations.listedPaths).containsExactly(target);
    }

    @Test
    void refreshesAncestorChainAndRetriesWhenDirectParentIsNotFound() {
        FakeFileOperations fileOperations = new FakeFileOperations();
        String target = "/WebDAV/Media/Adult/JAV/7.15";
        fileOperations.addFile(target, "DVAJ-749", 1024L);
        fileOperations.failNotFound(target, 1);
        CloudDrive2LibraryOrganizer organizer = new CloudDrive2LibraryOrganizer(
                fileOperations,
                properties()
        );
        LibraryOrganizationPlan plan = new LibraryOrganizationPlan(
                "/pikpak/Media/Adult/JAV/7.15",
                List.of(),
                List.of(),
                List.of(),
                Set.of("DVAJ-749"),
                Set.of()
        );

        organizer.organize(plan, (message, detail) -> { });

        assertThat(fileOperations.listedPaths).containsExactly(
                target,
                "/WebDAV",
                "/WebDAV/Media",
                "/WebDAV/Media/Adult",
                "/WebDAV/Media/Adult/JAV",
                target
        );
    }

    @Test
    void executesOneBatchPerOperationTypeAndWaitsForTargetManifest() {
        FakeFileOperations fileOperations = new FakeFileOperations();
        String target = "/WebDAV/Media/Anime/Show/Season 01";
        String release = target + "/Release";
        fileOperations.addDirectory(target, "Release");
        fileOperations.addDirectory(target, "Scans");
        fileOperations.addFile(release, "[Group] Show - 01.mkv", 1024L);
        fileOperations.addFile(release, "[Group] Show - 02.mkv", 2048L);

        CloudDrive2LibraryOrganizer organizer = new CloudDrive2LibraryOrganizer(
                fileOperations,
                properties()
        );
        LibraryOrganizationPlan plan = new LibraryOrganizationPlan(
                "/pikpak/Media/Anime/Show/Season 01",
                List.of(
                        new RenameOperation(
                                "/pikpak/Media/Anime/Show/Season 01/Release/[Group] Show - 01.mkv",
                                "Show S01E01.mkv"
                        ),
                        new RenameOperation(
                                "/pikpak/Media/Anime/Show/Season 01/Release/[Group] Show - 02.mkv",
                                "Show S01E02.mkv"
                        )
                ),
                List.of(
                        new MoveOperation(
                                "/pikpak/Media/Anime/Show/Season 01/Release/Show S01E01.mkv",
                                "/pikpak/Media/Anime/Show/Season 01"
                        ),
                        new MoveOperation(
                                "/pikpak/Media/Anime/Show/Season 01/Release/Show S01E02.mkv",
                                "/pikpak/Media/Anime/Show/Season 01"
                        )
                ),
                List.of(new DeleteOperation("/pikpak/Media/Anime/Show/Season 01/Scans")),
                Set.of("Show S01E01.mkv", "Show S01E02.mkv"),
                Set.of("/pikpak/Media/Anime/Show/Season 01/Scans")
        );

        List<String> progressMessages = new ArrayList<>();
        organizer.organize(plan, (message, detail) -> progressMessages.add(message));

        assertThat(fileOperations.renameCalls).hasSize(1);
        assertThat(fileOperations.renameCalls.get(0)).hasSize(2);
        assertThat(fileOperations.moveCalls).hasSize(1);
        assertThat(fileOperations.moveCalls.get(0).sourcePaths()).hasSize(2);
        assertThat(fileOperations.listedPaths)
                .contains(target, release)
                .doesNotContain("/WebDAV", "/WebDAV/Media");
        assertThat(fileOperations.children(target))
                .extracting(CloudDrive2FileEntry::name)
                .containsExactlyInAnyOrder("Show S01E01.mkv", "Show S01E02.mkv");
        assertThat(progressMessages).contains("CD2 已看见完整源文件", "CD2 整理结果已完整可见");
    }

    @Test
    void sendsSeparateMoveRequestsForSourcesFromDifferentParentDirectories() {
        FakeFileOperations fileOperations = new FakeFileOperations();
        fileOperations.rejectMixedSourceParents();
        String target = "/WebDAV/Media/Adult/JAV/7.15";
        String firstSourceDirectory = target + "/adult-task-01";
        String secondSourceDirectory = target + "/adult-task-02";
        fileOperations.addDirectory(target, "adult-task-01");
        fileOperations.addDirectory(target, "adult-task-02");
        fileOperations.addDirectory(firstSourceDirectory, "DVAJ-749");
        fileOperations.addDirectory(secondSourceDirectory, "PRED-877-U");
        CloudDrive2LibraryOrganizer organizer = new CloudDrive2LibraryOrganizer(
                fileOperations,
                properties()
        );
        LibraryOrganizationPlan plan = new LibraryOrganizationPlan(
                "/pikpak/Media/Adult/JAV/7.15",
                List.of(),
                List.of(
                        new MoveOperation(
                                "/pikpak/Media/Adult/JAV/7.15/adult-task-01/DVAJ-749",
                                "/pikpak/Media/Adult/JAV/7.15"
                        ),
                        new MoveOperation(
                                "/pikpak/Media/Adult/JAV/7.15/adult-task-02/PRED-877-U",
                                "/pikpak/Media/Adult/JAV/7.15"
                        )
                ),
                List.of(),
                Set.of("DVAJ-749", "PRED-877-U"),
                Set.of()
        );

        organizer.organize(plan, (message, detail) -> { });

        assertThat(fileOperations.moveCalls).hasSize(2);
        assertThat(fileOperations.moveCalls)
                .allSatisfy(call -> assertThat(call.sourcePaths()).hasSize(1));
        assertThat(fileOperations.children(target))
                .extracting(CloudDrive2FileEntry::name)
                .containsExactlyInAnyOrder("DVAJ-749", "PRED-877-U");
    }

    @Test
    void readsFinalTargetManifestFromCd2MoveCacheBeforeFallingBackToForcedRefresh() {
        FakeFileOperations fileOperations = new FakeFileOperations();
        String target = "/WebDAV/Media/Adult/JAV/7.15";
        String sourceDirectory = target + "/adult-task-01";
        fileOperations.addDirectory(target, "adult-task-01");
        fileOperations.addDirectory(sourceDirectory, "DVAJ-749");
        fileOperations.hideNameFromForcedListings(target, "DVAJ-749");
        CloudDrive2Properties properties = properties();
        properties.setVisibilityTimeout(Duration.ofMillis(10));
        CloudDrive2LibraryOrganizer organizer = new CloudDrive2LibraryOrganizer(
                fileOperations,
                properties
        );
        LibraryOrganizationPlan plan = new LibraryOrganizationPlan(
                "/pikpak/Media/Adult/JAV/7.15",
                List.of(),
                List.of(new MoveOperation(
                        "/pikpak/Media/Adult/JAV/7.15/adult-task-01/DVAJ-749",
                        "/pikpak/Media/Adult/JAV/7.15"
                )),
                List.of(),
                Set.of("DVAJ-749"),
                Set.of()
        );

        organizer.organize(plan, (message, detail) -> { });

        assertThat(fileOperations.cachedListedPaths).contains(target);
        assertThat(fileOperations.forcedListedPaths).doesNotContain(target);
    }

    @Test
    void fallsBackToForcedRefreshWhenFinalTargetIsMissingFromCd2Cache() {
        FakeFileOperations fileOperations = new FakeFileOperations();
        String target = "/WebDAV/Media/Adult/JAV/7.15";
        fileOperations.addDirectory(target, "DVAJ-749");
        fileOperations.hideNameFromCachedListings(target, "DVAJ-749");
        CloudDrive2LibraryOrganizer organizer = new CloudDrive2LibraryOrganizer(
                fileOperations,
                properties()
        );
        LibraryOrganizationPlan plan = new LibraryOrganizationPlan(
                "/pikpak/Media/Adult/JAV/7.15",
                List.of(),
                List.of(),
                List.of(),
                Set.of("DVAJ-749"),
                Set.of()
        );

        organizer.organize(plan, (message, detail) -> { });

        assertThat(fileOperations.cachedListedPaths).contains(target);
        assertThat(fileOperations.forcedListedPaths).contains(target);
    }

    @Test
    void rejectsPlanOutsideConfiguredIngestPrefixBeforeCallingCloudDrive() {
        FakeFileOperations fileOperations = new FakeFileOperations();
        CloudDrive2LibraryOrganizer organizer = new CloudDrive2LibraryOrganizer(
                fileOperations,
                properties()
        );
        LibraryOrganizationPlan plan = new LibraryOrganizationPlan(
                "/other/Season 01",
                List.of(),
                List.of(),
                List.of(),
                Set.of("Show S01E01.mkv"),
                Set.of()
        );

        assertThatThrownBy(() -> organizer.organize(plan, (message, detail) -> { }))
                .isInstanceOf(CloudDrive2ClientException.class)
                .hasMessageContaining("不在已配置的映射前缀下");
        assertThat(fileOperations.listCalls).isZero();
    }

    @Test
    void confirmsDeletionSourceBeforeDeletingAndRemovesItsEmptyParent() {
        FakeFileOperations fileOperations = new FakeFileOperations();
        String target = "/WebDAV/Media/Movie/Example (2026)";
        String release = target + "/Release";
        String junk = release + "/sample.txt";
        fileOperations.addDirectory(target, "Release");
        fileOperations.addFile(release, "sample.txt", 16L);
        CloudDrive2LibraryOrganizer organizer = new CloudDrive2LibraryOrganizer(
                fileOperations,
                properties()
        );
        LibraryOrganizationPlan plan = new LibraryOrganizationPlan(
                "/pikpak/Media/Movie/Example (2026)",
                List.of(),
                List.of(),
                List.of(new DeleteOperation("/pikpak/Media/Movie/Example (2026)/Release/sample.txt")),
                Set.of(),
                Set.of()
        );

        organizer.organize(plan, (message, detail) -> { });

        assertThat(fileOperations.events.indexOf("list:" + release))
                .isLessThan(fileOperations.events.indexOf("delete:" + junk));
        assertThat(fileOperations.children(target))
                .extracting(CloudDrive2FileEntry::name)
                .doesNotContain("Release");
    }

    @Test
    void selectsCloudDrivePerProductAndKeepsIndependentOpenListFallback() {
        CloudDrive2Properties properties = properties();
        properties.setOrganizationEnabled(true);
        properties.setAnimeOrganizationEnabled(true);
        properties.setMovieOrganizationEnabled(false);
        properties.setSeriesOrganizationEnabled(true);
        properties.setAdultOrganizationEnabled(false);
        CloudDrive2LibraryOrganizer cloudDrive2Organizer = new CloudDrive2LibraryOrganizer(
                new FakeFileOperations(),
                properties
        );
        OpenListLibraryOrganizer openListOrganizer = new OpenListLibraryOrganizer(null);
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("cloudDrive2LibraryOrganizer", cloudDrive2Organizer);
        LibraryOrganizerConfiguration configuration = new LibraryOrganizerConfiguration();

        LibraryOrganizer anime = configuration.animeLibraryOrganizer(
                properties,
                openListOrganizer,
                beanFactory.getBeanProvider(CloudDrive2LibraryOrganizer.class)
        );
        LibraryOrganizer movie = configuration.movieLibraryOrganizer(
                properties,
                openListOrganizer,
                beanFactory.getBeanProvider(CloudDrive2LibraryOrganizer.class)
        );
        LibraryOrganizer series = configuration.seriesLibraryOrganizer(
                properties,
                openListOrganizer,
                beanFactory.getBeanProvider(CloudDrive2LibraryOrganizer.class)
        );
        LibraryOrganizer adult = configuration.adultLibraryOrganizer(
                properties,
                openListOrganizer,
                beanFactory.getBeanProvider(CloudDrive2LibraryOrganizer.class)
        );

        assertThat(anime).isSameAs(cloudDrive2Organizer);
        assertThat(movie).isSameAs(openListOrganizer);
        assertThat(series).isSameAs(cloudDrive2Organizer);
        assertThat(adult).isSameAs(openListOrganizer);

        properties.setOrganizationEnabled(false);

        assertThat(configuration.animeLibraryOrganizer(
                properties,
                openListOrganizer,
                beanFactory.getBeanProvider(CloudDrive2LibraryOrganizer.class)
        )).isSameAs(openListOrganizer);
        assertThat(configuration.seriesLibraryOrganizer(
                properties,
                openListOrganizer,
                beanFactory.getBeanProvider(CloudDrive2LibraryOrganizer.class)
        )).isSameAs(openListOrganizer);
    }

    private CloudDrive2Properties properties() {
        CloudDrive2Properties properties = new CloudDrive2Properties();
        properties.setIngestPathPrefix("/pikpak");
        properties.setCloudDrivePathPrefix("/WebDAV");
        properties.setVisibilityTimeout(Duration.ofSeconds(1));
        properties.setVisibilityPollInterval(Duration.ofMillis(1));
        return properties;
    }

    private static final class FakeFileOperations implements CloudDrive2FileOperations {

        private final Map<String, LinkedHashMap<String, CloudDrive2FileEntry>> entries = new LinkedHashMap<>();
        private final Map<String, Integer> notFoundCallsRemaining = new LinkedHashMap<>();
        private final List<List<CloudDrive2RenameOperation>> renameCalls = new ArrayList<>();
        private final List<MoveCall> moveCalls = new ArrayList<>();
        private final List<String> listedPaths = new ArrayList<>();
        private final List<String> cachedListedPaths = new ArrayList<>();
        private final List<String> forcedListedPaths = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private final Map<String, Set<String>> hiddenNamesFromCachedListings = new LinkedHashMap<>();
        private final Map<String, Set<String>> hiddenNamesFromForcedListings = new LinkedHashMap<>();
        private int listCalls;
        private boolean rejectMixedSourceParents;

        @Override
        public List<CloudDrive2FileEntry> list(String path, boolean forceRefresh) {
            listCalls++;
            listedPaths.add(path);
            (forceRefresh ? forcedListedPaths : cachedListedPaths).add(path);
            events.add("list:" + path);
            int notFoundCalls = notFoundCallsRemaining.getOrDefault(path, 0);
            if (notFoundCalls > 0) {
                notFoundCallsRemaining.put(path, notFoundCalls - 1);
                throw new CloudDrive2ClientException("not found: " + path, Status.Code.NOT_FOUND, null);
            }
            Map<String, Set<String>> hiddenNamesByPath = forceRefresh
                    ? hiddenNamesFromForcedListings
                    : hiddenNamesFromCachedListings;
            Set<String> hiddenNames = hiddenNamesByPath.getOrDefault(path, Set.of());
            return children(path).stream()
                    .filter(entry -> !hiddenNames.contains(entry.name()))
                    .toList();
        }

        @Override
        public void rename(List<CloudDrive2RenameOperation> operations) {
            renameCalls.add(List.copyOf(operations));
            for (CloudDrive2RenameOperation operation : operations) {
                String parent = parent(operation.sourcePath());
                CloudDrive2FileEntry source = entries.get(parent).remove(name(operation.sourcePath()));
                entries.get(parent).put(operation.targetName(), new CloudDrive2FileEntry(
                        operation.targetName(),
                        parent + "/" + operation.targetName(),
                        source.size(),
                        source.directory()
                ));
            }
        }

        @Override
        public void move(List<String> sourcePaths, String targetDirectory) {
            if (rejectMixedSourceParents && sourcePaths.stream().map(FakeFileOperations::parent).distinct().count() > 1) {
                throw new CloudDrive2ClientException("CloudDrive2 move failed");
            }
            moveCalls.add(new MoveCall(List.copyOf(sourcePaths), targetDirectory));
            entries.computeIfAbsent(targetDirectory, ignored -> new LinkedHashMap<>());
            for (String sourcePath : sourcePaths) {
                String sourceParent = parent(sourcePath);
                CloudDrive2FileEntry source = entries.get(sourceParent).remove(name(sourcePath));
                entries.get(targetDirectory).put(source.name(), new CloudDrive2FileEntry(
                        source.name(),
                        targetDirectory + "/" + source.name(),
                        source.size(),
                        source.directory()
                ));
            }
        }

        @Override
        public void delete(List<String> paths) {
            for (String path : paths) {
                events.add("delete:" + path);
                LinkedHashMap<String, CloudDrive2FileEntry> parentEntries = entries.get(parent(path));
                if (parentEntries != null) {
                    parentEntries.remove(name(path));
                }
                entries.remove(path);
            }
        }

        void addDirectory(String parent, String name) {
            entries.computeIfAbsent(parent, ignored -> new LinkedHashMap<>())
                    .put(name, new CloudDrive2FileEntry(name, parent + "/" + name, 0L, true));
            entries.computeIfAbsent(parent + "/" + name, ignored -> new LinkedHashMap<>());
        }

        void addFile(String parent, String name, long size) {
            entries.computeIfAbsent(parent, ignored -> new LinkedHashMap<>())
                    .put(name, new CloudDrive2FileEntry(name, parent + "/" + name, size, false));
        }

        void failNotFound(String path, int calls) {
            notFoundCallsRemaining.put(path, calls);
        }

        void rejectMixedSourceParents() {
            rejectMixedSourceParents = true;
        }

        void hideNameFromForcedListings(String path, String name) {
            hiddenNamesFromForcedListings.computeIfAbsent(path, ignored -> new LinkedHashSet<>())
                    .add(name);
        }

        void hideNameFromCachedListings(String path, String name) {
            hiddenNamesFromCachedListings.computeIfAbsent(path, ignored -> new LinkedHashSet<>())
                    .add(name);
        }

        List<CloudDrive2FileEntry> children(String path) {
            return new ArrayList<>(entries.getOrDefault(path, new LinkedHashMap<>()).values());
        }

        private static String parent(String path) {
            return path.substring(0, path.lastIndexOf('/'));
        }

        private static String name(String path) {
            return path.substring(path.lastIndexOf('/') + 1);
        }

        private record MoveCall(List<String> sourcePaths, String targetDirectory) {
        }
    }
}
