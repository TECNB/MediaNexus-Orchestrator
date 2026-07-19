package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.dto.emby.response.AdultOtherAutomationCollectionResponse;
import com.medianexus.orchestrator.dto.emby.response.AdultOtherCollectionSyncGroupResponse;
import com.medianexus.orchestrator.dto.emby.response.AdultOtherCollectionSyncRunResponse;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyItemState;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AdultOtherLibraryAutomationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdultOtherLibraryAutomationRunner.class);

    private final EmbyProperties properties;
    private final EmbyClient embyClient;
    private final AdultOtherCollectionSyncService collectionSyncService;
    private final EmbyCollectionPosterService collectionPosterService;
    private final AdultOtherAutomationRunRecorder automationRunRecorder;
    private final ExecutorService refreshExecutor;
    private final Sleeper sleeper;
    private final boolean ownsExecutor;

    @Autowired
    public AdultOtherLibraryAutomationRunner(
            EmbyProperties properties,
            EmbyClient embyClient,
            AdultOtherCollectionSyncService collectionSyncService,
            EmbyCollectionPosterService collectionPosterService,
            AdultOtherAutomationRunRecorder automationRunRecorder
    ) {
        this(
                properties,
                embyClient,
                collectionSyncService,
                collectionPosterService,
                automationRunRecorder,
                Executors.newFixedThreadPool(
                        Math.max(1, properties.getAdultOtherRefreshConcurrency()),
                        new AutomationThreadFactory("adult-other-refresh-")
                ),
                duration -> TimeUnit.MILLISECONDS.sleep(Math.max(0, duration.toMillis())),
                true
        );
    }

    AdultOtherLibraryAutomationRunner(
            EmbyProperties properties,
            EmbyClient embyClient,
            AdultOtherCollectionSyncService collectionSyncService,
            EmbyCollectionPosterService collectionPosterService,
            AdultOtherAutomationRunRecorder automationRunRecorder,
            ExecutorService refreshExecutor,
            Sleeper sleeper
    ) {
        this(
                properties,
                embyClient,
                collectionSyncService,
                collectionPosterService,
                automationRunRecorder,
                refreshExecutor,
                sleeper,
                false
        );
    }

    private AdultOtherLibraryAutomationRunner(
            EmbyProperties properties,
            EmbyClient embyClient,
            AdultOtherCollectionSyncService collectionSyncService,
            EmbyCollectionPosterService collectionPosterService,
            AdultOtherAutomationRunRecorder automationRunRecorder,
            ExecutorService refreshExecutor,
            Sleeper sleeper,
            boolean ownsExecutor
    ) {
        this.properties = properties;
        this.embyClient = embyClient;
        this.collectionSyncService = collectionSyncService;
        this.collectionPosterService = collectionPosterService;
        this.automationRunRecorder = automationRunRecorder;
        this.refreshExecutor = refreshExecutor;
        this.sleeper = sleeper;
        this.ownsExecutor = ownsExecutor;
    }

    public void processNewItems(Set<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return;
        }

        String automationRunId = automationRunRecorder.start("NEW_ITEMS", itemIds.size());
        try {
            processNewItems(automationRunId, itemIds);
        } catch (RuntimeException exception) {
            automationRunRecorder.fail(automationRunId, exception);
            throw exception;
        }
    }

    private void processNewItems(String automationRunId, Set<String> itemIds) {

        EmbyLibrary library = adultOtherLibrary();
        Set<String> requestedIds = itemIds.stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<EmbyItemState> fetchedStates = embyClient.listItemStates(requestedIds);
        logUnresolvedItems(requestedIds, fetchedStates);
        List<EmbyItemState> initialStates = scopedStates(fetchedStates, library.locations());
        Set<String> targetIds = initialStates.stream()
                .map(EmbyItemState::id)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> collectionNamesByItemId = new java.util.LinkedHashMap<>();
        for (EmbyItemState state : initialStates) {
            if (!StringUtils.hasText(state.id())) {
                continue;
            }
            String collectionName = collectionSyncService.collectionNameForPath(
                    state.path(),
                    library.locations()
            );
            if (StringUtils.hasText(collectionName)) {
                collectionNamesByItemId.putIfAbsent(state.id(), collectionName);
            }
        }
        automationRunRecorder.recordScopedItems(
                automationRunId,
                requestedIds,
                fetchedStates,
                targetIds,
                collectionNamesByItemId
        );
        if (targetIds.isEmpty()) {
            log.info("Adult Other automation ignored new batch because no scoped items were found requested={}",
                    requestedIds.size());
            automationRunRecorder.completeIgnored(automationRunId, "未找到 Adult - Other 范围内的媒体");
            return;
        }

        automationRunRecorder.recordScope(
                automationRunId,
                targetIds.size(),
                (int) primaryReadyCount(initialStates)
        );

        Set<String> affectedCollectionNames = collectionNamesByItemId.values().stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        AdultOtherCollectionSyncRunResponse initialSync = collectionSyncService.reconcileAutomatic();
        automationRunRecorder.recordWaiting(automationRunId);
        List<EmbyItemState> settledStates = observeNormalPrimaryProgress(targetIds, initialStates);
        List<String> missingIds = settledStates.stream()
                .filter(state -> !state.hasPrimaryImage())
                .map(EmbyItemState::id)
                .filter(StringUtils::hasText)
                .toList();
        automationRunRecorder.recordNaturalPrimary(
                automationRunId,
                (int) primaryReadyCount(settledStates),
                missingIds.size()
        );
        refreshMissingItems(missingIds);
        List<EmbyItemState> finalStates = awaitRefreshCompletion(targetIds);
        automationRunRecorder.recordItemResults(
                automationRunId,
                settledStates,
                new LinkedHashSet<>(missingIds),
                finalStates
        );

        int finalReadyCount = (int) primaryReadyCount(finalStates);
        automationRunRecorder.recordPrimaryResult(
                automationRunId,
                finalReadyCount,
                targetIds.size() - finalReadyCount
        );

        AdultOtherCollectionSyncRunResponse finalSync = collectionSyncService.reconcileAutomatic();
        ArtworkRefreshSummary artwork = refreshAffectedCollections(
                affectedCollectionNames,
                initialSync,
                finalSync
        );
        automationRunRecorder.recordCollections(automationRunId, artwork.collections());
        automationRunRecorder.completeNewItems(
                automationRunId,
                collectionActionCount("CREATE", initialSync, finalSync),
                collectionActionCount("UPDATE", initialSync, finalSync),
                artwork.attemptedCount(),
                artwork.readyCount()
        );

        log.info("Adult Other new batch completed items={} primaryReady={} primaryMissing={} collections={}",
                targetIds.size(),
                finalReadyCount,
                targetIds.size() - finalReadyCount,
                affectedCollectionNames.size()
        );
    }

    public void processDeletedPaths(Set<String> deletedPaths) {
        if (deletedPaths == null || deletedPaths.isEmpty()) {
            return;
        }
        EmbyLibrary library = adultOtherLibrary();
        boolean affectsAdultOther = deletedPaths.stream()
                .anyMatch(path -> isUnderLibrary(path, library.locations()));
        if (!affectsAdultOther) {
            return;
        }

        Set<String> affectedCollectionNames = deletedPaths.stream()
                .map(path -> collectionSyncService.collectionNameForPath(path, library.locations()))
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String automationRunId = automationRunRecorder.start("DELETIONS", deletedPaths.size());
        try {
            automationRunRecorder.recordDeletionStarted(automationRunId);
            AdultOtherCollectionSyncRunResponse cleanup = collectionSyncService.cleanupAutomatic();
            int refreshedPosterCount = refreshSurvivingCollectionsAfterDeletion(
                    affectedCollectionNames,
                    cleanup
            );
            automationRunRecorder.completeDeletion(automationRunId, cleanup);
            log.info("Adult Other delete reconciliation completed deletedCollections={} "
                            + "reviewCollections={} refreshedPosters={} paths={}",
                    cleanup.deletedCollectionCount(),
                    cleanup.reviewCollectionCount(),
                    refreshedPosterCount,
                    deletedPaths.size()
            );
        } catch (RuntimeException exception) {
            automationRunRecorder.fail(automationRunId, exception);
            throw exception;
        }
    }

    private int refreshSurvivingCollectionsAfterDeletion(
            Set<String> affectedCollectionNames,
            AdultOtherCollectionSyncRunResponse cleanup
    ) {
        Map<String, AdultOtherCollectionSyncGroupResponse> groups = groupsByName(cleanup);
        int refreshedCount = 0;
        for (String collectionName : affectedCollectionNames) {
            AdultOtherCollectionSyncGroupResponse group = groups.get(collectionName);
            if (group == null
                    || !Boolean.TRUE.equals(group.eligible())
                    || "DELETE".equals(group.action())
                    || !StringUtils.hasText(group.embyCollectionId())) {
                continue;
            }
            if (refreshCollectionArtwork(group.embyCollectionId(), collectionName)) {
                refreshedCount++;
            }
        }
        return refreshedCount;
    }

    private List<EmbyItemState> observeNormalPrimaryProgress(
            Set<String> itemIds,
            List<EmbyItemState> initialStates
    ) {
        Duration quietPeriod = positive(properties.getAdultOtherPrimaryQuietPeriod(), Duration.ofSeconds(30));
        if (quietPeriod.isZero() || allPrimaryReady(initialStates)) {
            return initialStates;
        }

        Duration pollInterval = positive(properties.getAdultOtherPrimaryPollInterval(), Duration.ofSeconds(10));
        List<EmbyItemState> states = initialStates;
        long lastProgressAt = System.nanoTime();
        long readyCount = primaryReadyCount(states);
        while (!allPrimaryReady(states)
                && System.nanoTime() - lastProgressAt < quietPeriod.toNanos()) {
            if (!pause(pollInterval)) {
                return states;
            }
            List<EmbyItemState> nextStates = embyClient.listItemStates(itemIds);
            long nextReadyCount = primaryReadyCount(nextStates);
            if (nextReadyCount > readyCount) {
                lastProgressAt = System.nanoTime();
                readyCount = nextReadyCount;
            }
            states = nextStates;
        }
        return states;
    }

    private void refreshMissingItems(List<String> missingIds) {
        if (missingIds.isEmpty()) {
            return;
        }
        log.info("Adult Other targeted image refresh submitted count={} concurrency={}",
                missingIds.size(),
                Math.max(1, properties.getAdultOtherRefreshConcurrency())
        );
        CompletableFuture<?>[] futures = missingIds.stream()
                .map(itemId -> CompletableFuture.runAsync(() -> refreshItemSafely(itemId), refreshExecutor))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();
    }

    private void refreshItemSafely(String itemId) {
        try {
            embyClient.refreshItemImages(itemId);
        } catch (RuntimeException exception) {
            log.warn("Adult Other targeted image refresh failed itemId={}", itemId, exception);
        }
    }

    private List<EmbyItemState> awaitRefreshCompletion(Set<String> itemIds) {
        List<EmbyItemState> states = embyClient.listItemStates(itemIds);
        if (allPrimaryReady(states)) {
            return states;
        }

        Duration timeout = positive(properties.getAdultOtherRefreshTimeout(), Duration.ofMinutes(5));
        Duration pollInterval = positive(properties.getAdultOtherPrimaryPollInterval(), Duration.ofSeconds(10));
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!allPrimaryReady(states) && System.nanoTime() < deadline) {
            if (!pause(pollInterval)) {
                return states;
            }
            states = embyClient.listItemStates(itemIds);
        }
        return states;
    }

    private ArtworkRefreshSummary refreshAffectedCollections(
            Set<String> affectedCollectionNames,
            AdultOtherCollectionSyncRunResponse initialSync,
            AdultOtherCollectionSyncRunResponse finalSync
    ) {
        if (affectedCollectionNames.isEmpty()) {
            return new ArtworkRefreshSummary(0, 0, List.of());
        }
        Map<String, AdultOtherCollectionSyncGroupResponse> initialGroups = groupsByName(initialSync);
        Map<String, AdultOtherCollectionSyncGroupResponse> finalGroups = groupsByName(finalSync);
        int attemptedCount = 0;
        int readyCount = 0;
        List<AdultOtherAutomationCollectionResponse> details = new ArrayList<>();
        for (String collectionName : affectedCollectionNames) {
            AdultOtherCollectionSyncGroupResponse initialGroup = initialGroups.get(collectionName);
            AdultOtherCollectionSyncGroupResponse finalGroup = finalGroups.get(collectionName);
            AdultOtherCollectionSyncGroupResponse group = finalGroup != null ? finalGroup : initialGroup;
            String action = collectionAction(initialGroup, finalGroup);
            int addedItemCount = addedItemCount(initialGroup) + addedItemCount(finalGroup);
            if (group == null
                    || !Boolean.TRUE.equals(group.eligible())
                    || !StringUtils.hasText(group.embyCollectionId())) {
                details.add(new AdultOtherAutomationCollectionResponse(
                        group == null ? null : group.embyCollectionId(),
                        collectionName,
                        action,
                        addedItemCount,
                        false,
                        "SKIPPED",
                        group == null ? "未找到合集对账结果" : group.skipReason()
                ));
                continue;
            }
            attemptedCount++;
            boolean imageReady = refreshCollectionArtwork(group.embyCollectionId(), collectionName);
            if (imageReady) {
                readyCount++;
            }
            details.add(new AdultOtherAutomationCollectionResponse(
                    group.embyCollectionId(),
                    collectionName,
                    action,
                    addedItemCount,
                    imageReady,
                    imageReady ? "IMAGE_READY" : "IMAGE_MISSING",
                    imageReady ? null : "刷新后仍未生成合集 Primary 封面"
            ));
        }
        return new ArtworkRefreshSummary(attemptedCount, readyCount, List.copyOf(details));
    }

    private Map<String, AdultOtherCollectionSyncGroupResponse> groupsByName(
            AdultOtherCollectionSyncRunResponse response
    ) {
        if (response == null || response.groups() == null) {
            return Map.of();
        }
        return response.groups().stream()
                .filter(group -> StringUtils.hasText(group.collectionName()))
                .collect(Collectors.toMap(
                        AdultOtherCollectionSyncGroupResponse::collectionName,
                        Function.identity(),
                        (left, right) -> left
                ));
    }

    private String collectionAction(
            AdultOtherCollectionSyncGroupResponse initialGroup,
            AdultOtherCollectionSyncGroupResponse finalGroup
    ) {
        List<String> actions = List.of(
                initialGroup == null ? "" : initialGroup.action(),
                finalGroup == null ? "" : finalGroup.action()
        );
        if (actions.contains("CREATE")) {
            return "CREATE";
        }
        if (actions.contains("UPDATE")) {
            return "UPDATE";
        }
        return actions.stream().filter(StringUtils::hasText).findFirst().orElse("UNCHANGED");
    }

    private int addedItemCount(AdultOtherCollectionSyncGroupResponse group) {
        return group == null || group.addedItemCount() == null ? 0 : group.addedItemCount();
    }

    private int collectionActionCount(
            String action,
            AdultOtherCollectionSyncRunResponse... syncResponses
    ) {
        Set<String> collectionNames = new LinkedHashSet<>();
        for (AdultOtherCollectionSyncRunResponse response : syncResponses) {
            if (response == null || response.groups() == null) {
                continue;
            }
            response.groups().stream()
                    .filter(group -> action.equals(group.action()))
                    .map(AdultOtherCollectionSyncGroupResponse::collectionName)
                    .filter(StringUtils::hasText)
                    .forEach(collectionNames::add);
        }
        return collectionNames.size();
    }

    private boolean refreshCollectionArtwork(String collectionId, String collectionName) {
        try {
            if (collectionPosterService.refreshCollectionPoster(collectionId)) {
                return true;
            }
            log.warn("Adult Other collection poster could not be generated collectionId={} name={}",
                    collectionId, collectionName);
        } catch (RuntimeException exception) {
            log.warn("Adult Other collection poster generation failed collectionId={} name={}",
                    collectionId, collectionName, exception);
        }
        return false;
    }

    private List<EmbyItemState> scopedStates(List<EmbyItemState> states, List<String> libraryLocations) {
        List<EmbyItemState> scoped = states.stream()
                .filter(state -> "Movie".equalsIgnoreCase(state.type()))
                .filter(state -> isUnderLibrary(state.path(), libraryLocations))
                .toList();
        if (scoped.size() < states.size()) {
            Set<String> ignoredIds = states.stream()
                    .filter(state -> !scoped.contains(state))
                    .map(EmbyItemState::id)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            log.info("Adult Other automation ignored items outside target library or type itemIds={}", ignoredIds);
        }
        return scoped;
    }

    private void logUnresolvedItems(Set<String> requestedIds, List<EmbyItemState> fetchedStates) {
        Set<String> fetchedIds = fetchedStates.stream()
                .map(EmbyItemState::id)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        Set<String> unresolvedIds = requestedIds.stream()
                .filter(itemId -> !fetchedIds.contains(itemId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!unresolvedIds.isEmpty()) {
            log.warn("Adult Other webhook items were not returned by Emby; skipped as non-existent or inaccessible itemIds={}",
                    unresolvedIds);
        }
    }

    private boolean isUnderLibrary(String path, List<String> libraryLocations) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        String normalizedPath = normalizePath(path);
        return libraryLocations.stream()
                .filter(StringUtils::hasText)
                .map(this::normalizePath)
                .anyMatch(location -> normalizedPath.equals(location)
                        || normalizedPath.startsWith(location + "/"));
    }

    private EmbyLibrary adultOtherLibrary() {
        String expectedName = StringUtils.hasText(properties.getAdultOtherLibraryName())
                ? properties.getAdultOtherLibraryName().trim()
                : "Adult - Other";
        return embyClient.listLibraries().stream()
                .filter(library -> expectedName.equalsIgnoreCase(library.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Adult Other Emby library not found: " + expectedName));
    }

    private boolean allPrimaryReady(List<EmbyItemState> states) {
        return !states.isEmpty() && states.stream().allMatch(EmbyItemState::hasPrimaryImage);
    }

    private long primaryReadyCount(List<EmbyItemState> states) {
        return states.stream().filter(EmbyItemState::hasPrimaryImage).count();
    }

    private boolean pause(Duration duration) {
        try {
            sleeper.sleep(duration);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Duration positive(Duration value, Duration fallback) {
        if (value == null || value.isNegative()) {
            return fallback;
        }
        return value;
    }

    private String normalizePath(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/').replaceAll("/+", "/");
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    @PreDestroy
    public void shutdown() {
        if (ownsExecutor) {
            refreshExecutor.shutdownNow();
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private record ArtworkRefreshSummary(
            int attemptedCount,
            int readyCount,
            List<AdultOtherAutomationCollectionResponse> collections
    ) {
    }

    private static final class AutomationThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();
        private final String prefix;

        private AutomationThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
