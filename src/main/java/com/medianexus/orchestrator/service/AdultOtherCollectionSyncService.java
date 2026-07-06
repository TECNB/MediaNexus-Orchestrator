package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.dto.emby.request.AdultOtherCollectionSyncRequest;
import com.medianexus.orchestrator.dto.emby.response.AdultOtherCollectionSourceFolderResponse;
import com.medianexus.orchestrator.dto.emby.response.AdultOtherCollectionSyncGroupResponse;
import com.medianexus.orchestrator.dto.emby.response.AdultOtherCollectionSyncRunResponse;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyCollection;
import com.medianexus.orchestrator.integration.emby.EmbyItem;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import com.medianexus.orchestrator.mapper.AdultOtherCollectionSyncGroupMapper;
import com.medianexus.orchestrator.mapper.AdultOtherCollectionKnownItemMapper;
import com.medianexus.orchestrator.mapper.AdultOtherCollectionSyncRunMapper;
import com.medianexus.orchestrator.model.AdultOtherCollectionFolderRunSummary;
import com.medianexus.orchestrator.model.AdultOtherCollectionKnownItem;
import com.medianexus.orchestrator.model.AdultOtherCollectionSyncGroup;
import com.medianexus.orchestrator.model.AdultOtherCollectionSyncRun;
import com.medianexus.orchestrator.model.User;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AdultOtherCollectionSyncService {

    private static final int DEFAULT_MIN_ITEM_COUNT = 2;
    private static final int SAMPLE_NAME_LIMIT = 3;
    private static final int TELEGRAM_INCREMENTAL_PAGE_SIZE = 500;
    private static final int TELEGRAM_INCREMENTAL_KNOWN_HIT_STOP_COUNT = 25;
    private static final int TEXT_LIMIT_LONG = 1024;
    private static final int TEXT_LIMIT_NAME = 512;
    private static final String UNGROUPED_COLLECTION_NAME = "__UNGROUPED__";
    private static final Pattern BUCKET_DATE_PATTERN = Pattern.compile(
            "^(?:\\d{1,2}[._-]\\d{1,2}|\\d{4}[._-]\\d{1,2}[._-]\\d{1,2})$"
    );
    private static final Set<String> SOURCE_BUCKETS = Set.of("电报", "telegram", "tg");

    private final AuthService authService;
    private final EmbyProperties embyProperties;
    private final EmbyClient embyClient;
    private final AdultOtherCollectionSyncRunMapper runMapper;
    private final AdultOtherCollectionSyncGroupMapper groupMapper;
    private final AdultOtherCollectionKnownItemMapper knownItemMapper;
    private final ObjectMapper objectMapper;

    public AdultOtherCollectionSyncService(
            AuthService authService,
            EmbyProperties embyProperties,
            EmbyClient embyClient,
            AdultOtherCollectionSyncRunMapper runMapper,
            AdultOtherCollectionSyncGroupMapper groupMapper,
            AdultOtherCollectionKnownItemMapper knownItemMapper,
            ObjectMapper objectMapper
    ) {
        this.authService = authService;
        this.embyProperties = embyProperties;
        this.embyClient = embyClient;
        this.runMapper = runMapper;
        this.groupMapper = groupMapper;
        this.knownItemMapper = knownItemMapper;
        this.objectMapper = objectMapper;
    }

    public AdultOtherCollectionSyncRunResponse preview(AdultOtherCollectionSyncRequest request) {
        User admin = authService.requireAdminUser();
        ensureSyncTablesReady();
        return execute(admin.getId(), "DRY_RUN", minItemCount(request), sourceFolderPath(request), false);
    }

    public AdultOtherCollectionSyncRunResponse sync(AdultOtherCollectionSyncRequest request) {
        User admin = authService.requireAdminUser();
        ensureSyncTablesReady();
        return execute(admin.getId(), "APPLY", minItemCount(request), sourceFolderPath(request), true);
    }

    public AdultOtherCollectionSyncRunResponse cleanupPreview(AdultOtherCollectionSyncRequest request) {
        User admin = authService.requireAdminUser();
        ensureSyncTablesReady();
        return executeCleanup(admin.getId(), cleanupSourceFolderPath(request), false);
    }

    public AdultOtherCollectionSyncRunResponse cleanup(AdultOtherCollectionSyncRequest request) {
        User admin = authService.requireAdminUser();
        ensureSyncTablesReady();
        return executeCleanup(admin.getId(), cleanupSourceFolderPath(request), true);
    }

    public List<AdultOtherCollectionSourceFolderResponse> sourceFolders() {
        authService.requireAdminUser();
        ensureSyncTablesReady();
        EmbyLibrary library = adultOtherLibrary();
        List<EmbyItem> items = embyClient.listLibraryVideoItems(library.id());
        Map<String, AdultOtherCollectionFolderRunSummary> runSummariesByPath = folderRunSummariesByPath();
        Map<String, SourceFolderStats> statsByPath = new LinkedHashMap<>();

        for (EmbyItem item : items) {
            if (!StringUtils.hasText(item.id())) {
                continue;
            }
            List<String> parts = relativeParts(item.path(), library.locations());
            List<String> paths = sourceFolderOptionPaths(parts);
            GroupIdentity identity = groupIdentity(item.path(), library.locations());
            for (String path : paths) {
                SourceFolderStats stats = statsByPath.computeIfAbsent(path, SourceFolderStats::new);
                stats.addItem(identity == null ? null : identity.collectionName());
            }
        }

        for (Map.Entry<String, AdultOtherCollectionFolderRunSummary> entry : runSummariesByPath.entrySet()) {
            if (statsByPath.containsKey(entry.getKey()) || entry.getValue().getLatestSyncAt() == null) {
                continue;
            }
            SourceFolderStats stats = statsByPath.computeIfAbsent(entry.getKey(), SourceFolderStats::new);
            stats.markMissing();
        }

        return statsByPath.values().stream()
                .peek(stats -> stats.applyRunSummary(runSummariesByPath.get(stats.path())))
                .sorted(Comparator.comparingInt((SourceFolderStats stats) -> pathDepth(stats.path()))
                        .thenComparing(SourceFolderStats::path))
                .map(SourceFolderStats::toResponse)
                .toList();
    }

    public AdultOtherCollectionSyncRunResponse latestRun() {
        authService.requireAdminUser();
        ensureSyncTablesReady();
        AdultOtherCollectionSyncRun run = runMapper.selectLatest();
        if (run == null) {
            return null;
        }
        return toResponse(run, groupMapper.selectByRunId(run.getId()));
    }

    private void ensureSyncTablesReady() {
        runMapper.createTableIfNotExists();
        Integer sourceFolderPathColumnCount = runMapper.countSourceFolderPathColumn();
        if (sourceFolderPathColumnCount == null || sourceFolderPathColumnCount == 0) {
            runMapper.addSourceFolderPathColumn();
        }
        Integer deletedCollectionCountColumnCount = runMapper.countDeletedCollectionCountColumn();
        if (deletedCollectionCountColumnCount == null || deletedCollectionCountColumnCount == 0) {
            runMapper.addDeletedCollectionCountColumn();
        }
        Integer reviewCollectionCountColumnCount = runMapper.countReviewCollectionCountColumn();
        if (reviewCollectionCountColumnCount == null || reviewCollectionCountColumnCount == 0) {
            runMapper.addReviewCollectionCountColumn();
        }
        Integer observedItemCountColumnCount = runMapper.countObservedItemCountColumn();
        if (observedItemCountColumnCount == null || observedItemCountColumnCount == 0) {
            runMapper.addObservedItemCountColumn();
        }
        Integer observedGroupCountColumnCount = runMapper.countObservedGroupCountColumn();
        if (observedGroupCountColumnCount == null || observedGroupCountColumnCount == 0) {
            runMapper.addObservedGroupCountColumn();
        }
        groupMapper.createTableIfNotExists();
        knownItemMapper.createTableIfNotExists();
    }

    private Map<String, AdultOtherCollectionFolderRunSummary> folderRunSummariesByPath() {
        Map<String, AdultOtherCollectionFolderRunSummary> summariesByPath = new LinkedHashMap<>();
        for (AdultOtherCollectionFolderRunSummary summary : runMapper.selectFolderRunSummaries()) {
            if (StringUtils.hasText(summary.getSourceFolderPath())) {
                summariesByPath.put(summary.getSourceFolderPath(), summary);
            }
        }
        return summariesByPath;
    }

    private AdultOtherCollectionSyncRunResponse execute(
            Long createdByUserId,
            String mode,
            int minItemCount,
            String sourceFolderPath,
            boolean apply
    ) {
        if (isTelegramIncrementalSource(sourceFolderPath)) {
            return executeTelegramIncremental(createdByUserId, mode, minItemCount, sourceFolderPath, apply);
        }

        AdultOtherCollectionSyncRun run = newRun(createdByUserId, mode, minItemCount, sourceFolderPath);
        runMapper.insert(run);

        List<AdultOtherCollectionSyncGroup> groupRecords = new ArrayList<>();
        try {
            EmbyLibrary library = adultOtherLibrary();
            List<EmbyItem> items = embyClient.listLibraryVideoItems(library.id());
            List<EmbyItem> scopedItems = scopedItems(items, library.locations(), sourceFolderPath);
            Map<String, CandidateGroup> candidateGroups = candidateGroups(scopedItems, library.locations());
            Map<String, EmbyCollection> existingCollections = existingCollectionsByName();

            int skippedItemCount = scopedItems.size() - candidateGroups.values().stream()
                    .mapToInt(group -> group.items().size())
                    .sum();
            SyncCounters counters = new SyncCounters();
            for (CandidateGroup group : candidateGroups.values().stream()
                    .sorted(Comparator.comparingInt((CandidateGroup group) -> group.items().size()).reversed()
                            .thenComparing(CandidateGroup::collectionName))
                    .toList()) {
                AdultOtherCollectionSyncGroup groupRecord = evaluateGroup(group, existingCollections, minItemCount, apply);
                groupRecords.add(groupRecord);
                counters.add(groupRecord);
            }

            finishRun(run, "SUCCEEDED", scopedItems.size(), candidateGroups, skippedItemCount, counters, null);
        } catch (RuntimeException exception) {
            finishRun(run, "FAILED", 0, Map.of(), 0, new SyncCounters(), message(exception));
            throw exception;
        } finally {
            for (AdultOtherCollectionSyncGroup groupRecord : groupRecords) {
                groupRecord.setRunId(run.getId());
                groupMapper.insert(groupRecord);
            }
        }

        return toResponse(run, groupRecords);
    }

    private AdultOtherCollectionSyncRunResponse executeTelegramIncremental(
            Long createdByUserId,
            String mode,
            int minItemCount,
            String sourceFolderPath,
            boolean apply
    ) {
        AdultOtherCollectionSyncRun run = newRun(createdByUserId, mode, minItemCount, sourceFolderPath);
        runMapper.insert(run);

        List<AdultOtherCollectionSyncGroup> groupRecords = new ArrayList<>();
        try {
            EmbyLibrary library = adultOtherLibrary();
            Set<String> knownItemIds = new LinkedHashSet<>(knownItemMapper.selectKnownItemIds(sourceFolderPath));
            List<EmbyItem> discoveredItems = discoverTelegramItems(library, sourceFolderPath, knownItemIds);
            if (apply) {
                upsertKnownItems(discoveredItems, library.locations(), sourceFolderPath);
            }

            List<EmbyItem> candidateItems = new ArrayList<>();
            candidateItems.addAll(knownItemsToEmbyItems(knownItemMapper.selectUnsyncedBySourceFolderPath(sourceFolderPath)));
            candidateItems.addAll(discoveredItems.stream()
                    .filter(item -> !knownItemIds.contains(item.id()))
                    .toList());
            candidateItems = deduplicateItems(candidateItems);

            Map<String, CandidateGroup> candidateGroups = candidateGroups(candidateItems, library.locations());
            Map<String, EmbyCollection> existingCollections = existingCollectionsByName();

            int skippedItemCount = candidateItems.size() - candidateGroups.values().stream()
                    .mapToInt(group -> group.items().size())
                    .sum();
            SyncCounters counters = new SyncCounters();
            for (CandidateGroup group : candidateGroups.values().stream()
                    .sorted(Comparator.comparingInt((CandidateGroup group) -> group.items().size()).reversed()
                            .thenComparing(CandidateGroup::collectionName))
                    .toList()) {
                AdultOtherCollectionSyncGroup groupRecord = evaluateGroup(
                        group,
                        existingCollections,
                        minItemCount,
                        apply,
                        true
                );
                groupRecords.add(groupRecord);
                counters.add(groupRecord);
            }
            if (apply) {
                markKnownItemsSynced(sourceFolderPath, candidateItems.stream().map(EmbyItem::id).toList());
            }

            int knownTotalItemCount = apply
                    ? knownItemMapper.countLiveItems(sourceFolderPath)
                    : knownItemIds.size() + discoveredItems.size();
            int knownGroupCount = apply
                    ? knownItemMapper.countLiveGroups(sourceFolderPath)
                    : estimatedKnownGroupCount(sourceFolderPath, discoveredItems, library.locations());
            finishIncrementalRun(
                    run,
                    "SUCCEEDED",
                    knownTotalItemCount,
                    knownGroupCount,
                    candidateGroups,
                    skippedItemCount,
                    counters,
                    null
            );
        } catch (RuntimeException exception) {
            finishIncrementalRun(run, "FAILED", 0, 0, Map.of(), 0, new SyncCounters(), message(exception));
            throw exception;
        } finally {
            for (AdultOtherCollectionSyncGroup groupRecord : groupRecords) {
                groupRecord.setRunId(run.getId());
                groupMapper.insert(groupRecord);
            }
        }

        return toResponse(run, groupRecords);
    }

    private AdultOtherCollectionSyncRunResponse executeCleanup(
            Long createdByUserId,
            String sourceFolderPath,
            boolean apply
    ) {
        AdultOtherCollectionSyncRun baselineRun = runMapper.selectLatestSuccessfulApplyBySourceFolderPath(sourceFolderPath);
        if (baselineRun == null) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "未找到该同步范围的成功执行记录，无法确认清理边界",
                    HttpStatus.BAD_REQUEST
            );
        }

        AdultOtherCollectionSyncRun run = newRun(
                createdByUserId,
                apply ? "CLEANUP_APPLY" : "CLEANUP_DRY_RUN",
                DEFAULT_MIN_ITEM_COUNT,
                sourceFolderPath
        );
        runMapper.insert(run);

        List<AdultOtherCollectionSyncGroup> groupRecords = new ArrayList<>();
        try {
            EmbyLibrary library = adultOtherLibrary();
            SourceSnapshot sourceSnapshot = sourceSnapshot(library, sourceFolderPath);
            List<AdultOtherCollectionSyncGroup> baselineGroups = groupMapper.selectByRunId(baselineRun.getId());
            Map<String, EmbyCollection> collectionsById = new LinkedHashMap<>();
            Map<String, EmbyCollection> collectionsByName = new LinkedHashMap<>();
            for (EmbyCollection collection : embyClient.listCollections()) {
                if (StringUtils.hasText(collection.id())) {
                    collectionsById.put(collection.id(), collection);
                }
                if (StringUtils.hasText(collection.name())) {
                    collectionsByName.putIfAbsent(collection.name(), collection);
                }
            }

            CleanupCounters counters = new CleanupCounters();
            Set<String> seenCollections = new LinkedHashSet<>();
            for (AdultOtherCollectionSyncGroup baselineGroup : baselineGroups) {
                if (!Boolean.TRUE.equals(baselineGroup.getEligible())
                        || !StringUtils.hasText(baselineGroup.getCollectionName())) {
                    continue;
                }
                if (sourceSnapshot.collectionNames().contains(baselineGroup.getCollectionName())) {
                    continue;
                }
                String collectionKey = StringUtils.hasText(baselineGroup.getEmbyCollectionId())
                        ? baselineGroup.getEmbyCollectionId()
                        : baselineGroup.getCollectionName();
                if (!seenCollections.add(collectionKey)) {
                    continue;
                }
                AdultOtherCollectionSyncGroup cleanupRecord = evaluateCleanupGroup(
                        baselineGroup,
                        collectionsById,
                        collectionsByName,
                        apply
                );
                groupRecords.add(cleanupRecord);
                counters.add(cleanupRecord);
            }

            finishCleanupRun(run, "SUCCEEDED", groupRecords, counters, sourceSnapshot, null);
        } catch (RuntimeException exception) {
            finishCleanupRun(run, "FAILED", 0, 0, 0, 0, message(exception));
            throw exception;
        } finally {
            for (AdultOtherCollectionSyncGroup groupRecord : groupRecords) {
                groupRecord.setRunId(run.getId());
                groupMapper.insert(groupRecord);
            }
        }

        return toResponse(run, groupRecords);
    }

    private AdultOtherCollectionSyncGroup evaluateCleanupGroup(
            AdultOtherCollectionSyncGroup baselineGroup,
            Map<String, EmbyCollection> collectionsById,
            Map<String, EmbyCollection> collectionsByName,
            boolean apply
    ) {
        AdultOtherCollectionSyncGroup record = new AdultOtherCollectionSyncGroup();
        record.setCollectionName(limit(baselineGroup.getCollectionName(), TEXT_LIMIT_NAME));
        record.setSourceFolderPath(limit(baselineGroup.getSourceFolderPath(), TEXT_LIMIT_LONG));
        record.setAddedItemCount(0);

        EmbyCollection collection = null;
        if (StringUtils.hasText(baselineGroup.getEmbyCollectionId())) {
            collection = collectionsById.get(baselineGroup.getEmbyCollectionId());
        }
        if (collection == null) {
            collection = collectionsByName.get(baselineGroup.getCollectionName());
        }

        if (collection == null || !StringUtils.hasText(collection.id())) {
            record.setEligible(false);
            record.setAction("MISSING_COLLECTION");
            record.setItemCount(0);
            record.setEmbyCollectionId(baselineGroup.getEmbyCollectionId());
            record.setSkipReason("Collection 已不存在");
            return record;
        }

        record.setEmbyCollectionId(collection.id());
        List<EmbyItem> currentItems = embyClient.listCollectionVideoItems(collection.id());
        record.setItemCount(currentItems.size());
        record.setSampleItemNamesJson(sampleItemNamesJson(currentItems));
        if (!currentItems.isEmpty()) {
            record.setEligible(false);
            record.setAction("REVIEW");
            record.setSkipReason("Collection 仍有 " + currentItems.size() + " 个成员，已跳过");
            return record;
        }

        record.setEligible(true);
        record.setAction("DELETE");
        if (apply) {
            embyClient.deleteCollection(collection.id());
        }
        return record;
    }

    private AdultOtherCollectionSyncGroup evaluateGroup(
            CandidateGroup group,
            Map<String, EmbyCollection> existingCollections,
            int minItemCount,
            boolean apply
    ) {
        return evaluateGroup(group, existingCollections, minItemCount, apply, false);
    }

    private AdultOtherCollectionSyncGroup evaluateGroup(
            CandidateGroup group,
            Map<String, EmbyCollection> existingCollections,
            int minItemCount,
            boolean apply,
            boolean allowExistingCollectionBelowMinItemCount
    ) {
        AdultOtherCollectionSyncGroup record = new AdultOtherCollectionSyncGroup();
        record.setCollectionName(limit(group.collectionName(), TEXT_LIMIT_NAME));
        record.setSourceFolderPath(limit(group.sourceFolderPath(), TEXT_LIMIT_LONG));
        record.setItemCount(group.items().size());
        record.setSampleItemNamesJson(sampleItemNamesJson(group.items()));
        record.setAddedItemCount(0);

        EmbyCollection existing = existingCollections.get(group.collectionName());
        if (group.items().size() < minItemCount
                && !(allowExistingCollectionBelowMinItemCount && existing != null)) {
            record.setEligible(false);
            record.setAction("SKIPPED");
            record.setSkipReason("媒体数量少于 " + minItemCount);
            return record;
        }

        record.setEligible(true);
        if (existing == null) {
            record.setAction("CREATE");
            record.setAddedItemCount(group.items().size());
            if (apply) {
                String collectionId = embyClient.createCollection(group.collectionName(), group.itemIds());
                existingCollections.put(group.collectionName(), new EmbyCollection(collectionId, group.collectionName()));
                record.setEmbyCollectionId(collectionId);
            }
            return record;
        }

        record.setEmbyCollectionId(existing.id());
        Set<String> currentItemIds = new LinkedHashSet<>(embyClient.listCollectionVideoItems(existing.id()).stream()
                .map(EmbyItem::id)
                .filter(StringUtils::hasText)
                .toList());
        List<String> missingItemIds = group.itemIds().stream()
                .filter(itemId -> !currentItemIds.contains(itemId))
                .toList();
        record.setAddedItemCount(missingItemIds.size());
        record.setAction(missingItemIds.isEmpty() ? "UNCHANGED" : "UPDATE");
        if (apply && !missingItemIds.isEmpty()) {
            embyClient.addItemsToCollection(existing.id(), missingItemIds);
        }
        return record;
    }

    private Map<String, CandidateGroup> candidateGroups(List<EmbyItem> items, List<String> libraryLocations) {
        Map<String, CandidateGroup> groups = new LinkedHashMap<>();
        for (EmbyItem item : items) {
            GroupIdentity identity = groupIdentity(item.path(), libraryLocations);
            if (identity == null || !StringUtils.hasText(item.id())) {
                continue;
            }
            groups.computeIfAbsent(identity.collectionName(), ignored -> new CandidateGroup(
                    identity.collectionName(),
                    identity.sourceFolderPath(),
                    new ArrayList<>()
            )).items().add(item);
        }
        return groups;
    }

    private List<EmbyItem> scopedItems(List<EmbyItem> items, List<String> libraryLocations, String sourceFolderPath) {
        if (!StringUtils.hasText(sourceFolderPath)) {
            return items;
        }
        return items.stream()
                .filter(item -> isInSourceFolder(item.path(), libraryLocations, sourceFolderPath))
                .toList();
    }

    private SourceSnapshot sourceSnapshot(EmbyLibrary library, String sourceFolderPath) {
        List<EmbyItem> scopedItems = scopedItems(
                embyClient.listLibraryVideoItems(library.id()),
                library.locations(),
                sourceFolderPath
        );
        Set<String> collectionNames = new LinkedHashSet<>();
        for (EmbyItem item : scopedItems) {
            GroupIdentity identity = groupIdentity(item.path(), library.locations());
            if (identity != null && StringUtils.hasText(identity.collectionName())) {
                collectionNames.add(identity.collectionName());
            }
        }
        return new SourceSnapshot(scopedItems.size(), collectionNames.size(), collectionNames);
    }

    private List<EmbyItem> discoverTelegramItems(
            EmbyLibrary library,
            String sourceFolderPath,
            Set<String> knownItemIds
    ) {
        List<EmbyItem> discoveredItems = new ArrayList<>();
        int knownTelegramHitCount = 0;
        int startIndex = 0;
        while (true) {
            List<EmbyItem> page = embyClient.listLibraryVideoItemsByDateCreated(
                    library.id(),
                    startIndex,
                    TELEGRAM_INCREMENTAL_PAGE_SIZE
            );
            if (page.isEmpty()) {
                break;
            }
            for (EmbyItem item : page) {
                if (!StringUtils.hasText(item.id())
                        || !isInSourceFolder(item.path(), library.locations(), sourceFolderPath)) {
                    continue;
                }
                if (knownItemIds.contains(item.id())) {
                    knownTelegramHitCount++;
                } else {
                    discoveredItems.add(item);
                }
            }
            if (page.size() < TELEGRAM_INCREMENTAL_PAGE_SIZE
                    || (!knownItemIds.isEmpty()
                    && knownTelegramHitCount >= TELEGRAM_INCREMENTAL_KNOWN_HIT_STOP_COUNT)) {
                break;
            }
            startIndex += TELEGRAM_INCREMENTAL_PAGE_SIZE;
        }
        return discoveredItems;
    }

    private void upsertKnownItems(
            List<EmbyItem> items,
            List<String> libraryLocations,
            String sourceFolderPath
    ) {
        List<AdultOtherCollectionKnownItem> knownItems = items.stream()
                .map(item -> toKnownItem(item, libraryLocations, sourceFolderPath))
                .filter(item -> StringUtils.hasText(item.getEmbyItemId()))
                .toList();
        if (!knownItems.isEmpty()) {
            knownItemMapper.upsertDiscoveredItems(knownItems, LocalDateTime.now());
        }
    }

    private AdultOtherCollectionKnownItem toKnownItem(
            EmbyItem item,
            List<String> libraryLocations,
            String sourceFolderPath
    ) {
        GroupIdentity identity = groupIdentity(item.path(), libraryLocations);

        AdultOtherCollectionKnownItem knownItem = new AdultOtherCollectionKnownItem();
        knownItem.setEmbyItemId(item.id());
        knownItem.setSourceFolderPath(sourceFolderPath);
        knownItem.setCollectionName(limit(
                identity == null ? UNGROUPED_COLLECTION_NAME : identity.collectionName(),
                TEXT_LIMIT_NAME
        ));
        knownItem.setItemName(limit(item.name(), TEXT_LIMIT_NAME));
        knownItem.setItemPath(limit(item.path(), TEXT_LIMIT_LONG));
        knownItem.setDateCreated(limit(item.dateCreated(), 64));
        return knownItem;
    }

    private List<EmbyItem> knownItemsToEmbyItems(List<AdultOtherCollectionKnownItem> knownItems) {
        return knownItems.stream()
                .map(item -> new EmbyItem(
                        item.getEmbyItemId(),
                        item.getItemName(),
                        "Video",
                        item.getItemPath(),
                        item.getDateCreated()
                ))
                .toList();
    }

    private List<EmbyItem> deduplicateItems(List<EmbyItem> items) {
        Map<String, EmbyItem> itemsById = new LinkedHashMap<>();
        for (EmbyItem item : items) {
            if (StringUtils.hasText(item.id())) {
                itemsById.putIfAbsent(item.id(), item);
            }
        }
        return new ArrayList<>(itemsById.values());
    }

    private void markKnownItemsSynced(String sourceFolderPath, List<String> itemIds) {
        List<String> distinctItemIds = itemIds.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (!distinctItemIds.isEmpty()) {
            knownItemMapper.markSynced(sourceFolderPath, distinctItemIds, LocalDateTime.now());
        }
    }

    private int estimatedKnownGroupCount(
            String sourceFolderPath,
            List<EmbyItem> discoveredItems,
            List<String> libraryLocations
    ) {
        Set<String> groupNames = new LinkedHashSet<>();
        for (AdultOtherCollectionKnownItem knownItem : knownItemMapper.selectUnsyncedBySourceFolderPath(sourceFolderPath)) {
            if (StringUtils.hasText(knownItem.getCollectionName())
                    && !UNGROUPED_COLLECTION_NAME.equals(knownItem.getCollectionName())) {
                groupNames.add(knownItem.getCollectionName());
            }
        }
        for (EmbyItem item : discoveredItems) {
            GroupIdentity identity = groupIdentity(item.path(), libraryLocations);
            if (identity != null && StringUtils.hasText(identity.collectionName())) {
                groupNames.add(identity.collectionName());
            }
        }
        return Math.max(knownItemMapper.countLiveGroups(sourceFolderPath), groupNames.size());
    }

    private boolean isInSourceFolder(String itemPath, List<String> libraryLocations, String sourceFolderPath) {
        List<String> itemParts = relativeParts(itemPath, libraryLocations);
        List<String> sourceParts = splitPath(sourceFolderPath);
        if (sourceParts.isEmpty() || itemParts.size() < sourceParts.size()) {
            return false;
        }
        for (int index = 0; index < sourceParts.size(); index++) {
            if (!sourceParts.get(index).equals(itemParts.get(index))) {
                return false;
            }
        }
        return true;
    }

    private GroupIdentity groupIdentity(String path, List<String> libraryLocations) {
        List<String> parts = relativeParts(path, libraryLocations);
        if (parts.size() < 2) {
            return null;
        }
        String first = parts.get(0);
        if (isBucketFolder(first)) {
            if (parts.size() < 3) {
                return null;
            }
            return new GroupIdentity(parts.get(1), first + "/" + parts.get(1));
        }
        return new GroupIdentity(first, first);
    }

    private List<String> relativeParts(String path, List<String> libraryLocations) {
        String normalizedPath = normalizePath(path);
        for (String libraryLocation : libraryLocations.stream()
                .filter(StringUtils::hasText)
                .map(this::normalizePath)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList()) {
            String prefix = libraryLocation.endsWith("/") ? libraryLocation : libraryLocation + "/";
            if (normalizedPath.startsWith(prefix)) {
                return List.of(normalizedPath.substring(prefix.length()).split("/")).stream()
                        .filter(StringUtils::hasText)
                        .toList();
            }
        }
        return List.of();
    }

    private List<String> splitPath(String path) {
        String normalized = normalizePath(path);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        return List.of(normalized.split("/")).stream()
                .filter(StringUtils::hasText)
                .toList();
    }

    private List<String> sourceFolderOptionPaths(List<String> parts) {
        if (parts.size() < 2) {
            return List.of();
        }
        return List.of(parts.get(0));
    }

    private int pathDepth(String path) {
        return splitPath(path).size();
    }

    private boolean isBucketFolder(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.trim();
        return BUCKET_DATE_PATTERN.matcher(normalized).matches()
                || SOURCE_BUCKETS.contains(normalized.toLowerCase(Locale.ROOT));
    }

    private EmbyLibrary adultOtherLibrary() {
        String libraryName = StringUtils.hasText(embyProperties.getAdultOtherLibraryName())
                ? embyProperties.getAdultOtherLibraryName().trim()
                : "Adult - Other";
        return embyClient.listLibraries().stream()
                .filter(library -> libraryName.equalsIgnoreCase(library.name()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "未找到 Emby 媒体库: " + libraryName,
                        HttpStatus.NOT_FOUND
                ));
    }

    private Map<String, EmbyCollection> existingCollectionsByName() {
        Map<String, EmbyCollection> collectionsByName = new LinkedHashMap<>();
        for (EmbyCollection collection : embyClient.listCollections()) {
            if (StringUtils.hasText(collection.name())) {
                collectionsByName.putIfAbsent(collection.name(), collection);
            }
        }
        return collectionsByName;
    }

    private AdultOtherCollectionSyncRun newRun(
            Long createdByUserId,
            String mode,
            int minItemCount,
            String sourceFolderPath
    ) {
        LocalDateTime now = LocalDateTime.now();
        AdultOtherCollectionSyncRun run = new AdultOtherCollectionSyncRun();
        run.setId(UUID.randomUUID().toString());
        run.setCreatedByUserId(createdByUserId);
        run.setMode(mode);
        run.setStatus("RUNNING");
        run.setMinItemCount(minItemCount);
        run.setSourceFolderPath(sourceFolderPath);
        run.setTotalItemCount(0);
        run.setGroupedItemCount(0);
        run.setSkippedItemCount(0);
        run.setGroupCount(0);
        run.setEligibleGroupCount(0);
        run.setCreatedCollectionCount(0);
        run.setUpdatedCollectionCount(0);
        run.setUnchangedCollectionCount(0);
        run.setDeletedCollectionCount(0);
        run.setReviewCollectionCount(0);
        run.setItemAddCount(0);
        run.setStartedAt(now);
        return run;
    }

    private void finishRun(
            AdultOtherCollectionSyncRun run,
            String status,
            int totalItemCount,
            Map<String, CandidateGroup> candidateGroups,
            int skippedItemCount,
            SyncCounters counters,
            String errorMessage
    ) {
        run.setStatus(status);
        run.setTotalItemCount(totalItemCount);
        run.setGroupedItemCount(candidateGroups.values().stream().mapToInt(group -> group.items().size()).sum());
        run.setSkippedItemCount(skippedItemCount);
        run.setGroupCount(candidateGroups.size());
        run.setEligibleGroupCount(counters.eligibleGroupCount());
        run.setCreatedCollectionCount(counters.createdCollectionCount());
        run.setUpdatedCollectionCount(counters.updatedCollectionCount());
        run.setUnchangedCollectionCount(counters.unchangedCollectionCount());
        run.setDeletedCollectionCount(0);
        run.setReviewCollectionCount(0);
        run.setItemAddCount(counters.itemAddCount());
        run.setObservedItemCount(totalItemCount);
        run.setObservedGroupCount(candidateGroups.size());
        run.setErrorMessage(limit(errorMessage, TEXT_LIMIT_LONG));
        run.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(run);
    }

    private void finishIncrementalRun(
            AdultOtherCollectionSyncRun run,
            String status,
            int totalItemCount,
            int totalGroupCount,
            Map<String, CandidateGroup> candidateGroups,
            int skippedItemCount,
            SyncCounters counters,
            String errorMessage
    ) {
        run.setStatus(status);
        run.setTotalItemCount(totalItemCount);
        run.setGroupedItemCount(candidateGroups.values().stream().mapToInt(group -> group.items().size()).sum());
        run.setSkippedItemCount(skippedItemCount);
        run.setGroupCount(totalGroupCount);
        run.setEligibleGroupCount(counters.eligibleGroupCount());
        run.setCreatedCollectionCount(counters.createdCollectionCount());
        run.setUpdatedCollectionCount(counters.updatedCollectionCount());
        run.setUnchangedCollectionCount(counters.unchangedCollectionCount());
        run.setDeletedCollectionCount(0);
        run.setReviewCollectionCount(0);
        run.setItemAddCount(counters.itemAddCount());
        run.setObservedItemCount(totalItemCount);
        run.setObservedGroupCount(totalGroupCount);
        run.setErrorMessage(limit(errorMessage, TEXT_LIMIT_LONG));
        run.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(run);
    }

    private void finishCleanupRun(
            AdultOtherCollectionSyncRun run,
            String status,
            List<AdultOtherCollectionSyncGroup> groupRecords,
            CleanupCounters counters,
            SourceSnapshot sourceSnapshot,
            String errorMessage
    ) {
        int currentMemberCount = groupRecords.stream()
                .map(AdultOtherCollectionSyncGroup::getItemCount)
                .mapToInt(value -> value == null ? 0 : value)
                .sum();
        finishCleanupRun(
                run,
                status,
                groupRecords.size(),
                currentMemberCount,
                counters.deletedCollectionCount(),
                counters.reviewCollectionCount(),
                sourceSnapshot == null ? null : sourceSnapshot.itemCount(),
                sourceSnapshot == null ? null : sourceSnapshot.groupCount(),
                errorMessage
        );
    }

    private void finishCleanupRun(
            AdultOtherCollectionSyncRun run,
            String status,
            int groupCount,
            int currentMemberCount,
            int deletedCollectionCount,
            int reviewCollectionCount,
            String errorMessage
    ) {
        finishCleanupRun(
                run,
                status,
                groupCount,
                currentMemberCount,
                deletedCollectionCount,
                reviewCollectionCount,
                null,
                null,
                errorMessage
        );
    }

    private void finishCleanupRun(
            AdultOtherCollectionSyncRun run,
            String status,
            int groupCount,
            int currentMemberCount,
            int deletedCollectionCount,
            int reviewCollectionCount,
            Integer observedItemCount,
            Integer observedGroupCount,
            String errorMessage
    ) {
        run.setStatus(status);
        run.setTotalItemCount(groupCount);
        run.setGroupedItemCount(currentMemberCount);
        run.setSkippedItemCount(reviewCollectionCount);
        run.setGroupCount(groupCount);
        run.setEligibleGroupCount(deletedCollectionCount);
        run.setCreatedCollectionCount(0);
        run.setUpdatedCollectionCount(0);
        run.setUnchangedCollectionCount(0);
        run.setDeletedCollectionCount(deletedCollectionCount);
        run.setReviewCollectionCount(reviewCollectionCount);
        run.setItemAddCount(0);
        run.setObservedItemCount(observedItemCount);
        run.setObservedGroupCount(observedGroupCount);
        run.setErrorMessage(limit(errorMessage, TEXT_LIMIT_LONG));
        run.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(run);
    }

    private AdultOtherCollectionSyncRunResponse toResponse(
            AdultOtherCollectionSyncRun run,
            List<AdultOtherCollectionSyncGroup> groups
    ) {
        return new AdultOtherCollectionSyncRunResponse(
                run.getId(),
                run.getMode(),
                run.getStatus(),
                run.getMinItemCount(),
                run.getSourceFolderPath(),
                run.getTotalItemCount(),
                run.getGroupedItemCount(),
                run.getSkippedItemCount(),
                run.getGroupCount(),
                run.getEligibleGroupCount(),
                run.getCreatedCollectionCount(),
                run.getUpdatedCollectionCount(),
                run.getUnchangedCollectionCount(),
                run.getDeletedCollectionCount(),
                run.getReviewCollectionCount(),
                run.getItemAddCount(),
                run.getErrorMessage(),
                run.getStartedAt(),
                run.getFinishedAt(),
                groups.stream().map(this::toGroupResponse).toList()
        );
    }

    private AdultOtherCollectionSyncGroupResponse toGroupResponse(AdultOtherCollectionSyncGroup group) {
        return new AdultOtherCollectionSyncGroupResponse(
                group.getCollectionName(),
                group.getSourceFolderPath(),
                group.getItemCount(),
                group.getEligible(),
                group.getAction(),
                group.getEmbyCollectionId(),
                group.getAddedItemCount(),
                group.getSkipReason(),
                sampleItemNames(group.getSampleItemNamesJson())
        );
    }

    private String sampleItemNamesJson(List<EmbyItem> items) {
        try {
            return objectMapper.writeValueAsString(items.stream()
                    .map(EmbyItem::name)
                    .filter(StringUtils::hasText)
                    .limit(SAMPLE_NAME_LIMIT)
                    .toList());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Adult-Other sample item names could not be serialized", exception);
        }
    }

    private List<String> sampleItemNames(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private int minItemCount(AdultOtherCollectionSyncRequest request) {
        return request == null || request.minItemCount() == null ? DEFAULT_MIN_ITEM_COUNT : request.minItemCount();
    }

    private String sourceFolderPath(AdultOtherCollectionSyncRequest request) {
        if (request == null || !StringUtils.hasText(request.sourceFolderPath())) {
            return null;
        }
        return normalizePath(request.sourceFolderPath());
    }

    private String cleanupSourceFolderPath(AdultOtherCollectionSyncRequest request) {
        String sourceFolderPath = sourceFolderPath(request);
        if (!StringUtils.hasText(sourceFolderPath)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "清理 Collection 前必须选择具体同步范围",
                    HttpStatus.BAD_REQUEST
            );
        }
        return sourceFolderPath;
    }

    private String normalizePath(String path) {
        String normalized = path == null ? "" : path.trim().replace("\\", "/");
        normalized = normalized.replaceAll("/{2,}", "/");
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isTelegramIncrementalSource(String sourceFolderPath) {
        List<String> parts = splitPath(sourceFolderPath);
        return parts.size() == 1 && SOURCE_BUCKETS.contains(parts.get(0).toLowerCase(Locale.ROOT));
    }

    private String limit(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }

    private String message(RuntimeException exception) {
        return StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : exception.getClass().getSimpleName();
    }

    private record GroupIdentity(String collectionName, String sourceFolderPath) {
    }

    private record CandidateGroup(String collectionName, String sourceFolderPath, List<EmbyItem> items) {

        List<String> itemIds() {
            return items.stream().map(EmbyItem::id).toList();
        }
    }

    private record SourceSnapshot(int itemCount, int groupCount, Set<String> collectionNames) {
    }

    private static class SourceFolderStats {

        private final String path;
        private final Set<String> groupNames = new LinkedHashSet<>();
        private int itemCount;
        private LocalDateTime latestPreviewAt;
        private LocalDateTime latestSyncAt;
        private Integer lastSyncedItemCount;
        private Integer lastSyncedGroupCount;
        private LocalDateTime latestEmptyCleanupAt;
        private Integer cleanupObservedItemCount;
        private Integer cleanupObservedGroupCount;
        private boolean missing;

        SourceFolderStats(String path) {
            this.path = path;
        }

        String path() {
            return path;
        }

        void addItem(String groupName) {
            itemCount++;
            if (StringUtils.hasText(groupName)) {
                groupNames.add(groupName);
            }
        }

        void markMissing() {
            missing = true;
        }

        void applyRunSummary(AdultOtherCollectionFolderRunSummary summary) {
            if (summary == null) {
                return;
            }
            latestPreviewAt = summary.getLatestPreviewAt();
            latestSyncAt = summary.getLatestSyncAt();
            lastSyncedItemCount = summary.getLastSyncedItemCount();
            lastSyncedGroupCount = summary.getLastSyncedGroupCount();
            latestEmptyCleanupAt = summary.getLatestEmptyCleanupAt();
            cleanupObservedItemCount = summary.getCleanupObservedItemCount();
            cleanupObservedGroupCount = summary.getCleanupObservedGroupCount();
        }

        AdultOtherCollectionSourceFolderResponse toResponse() {
            int currentGroupCount = groupNames.size();
            boolean deletionAcknowledged = deletionAcknowledged(currentGroupCount);
            Integer effectiveLastSyncedItemCount = deletionAcknowledged
                    ? Integer.valueOf(itemCount)
                    : lastSyncedItemCount;
            Integer effectiveLastSyncedGroupCount = deletionAcknowledged
                    ? Integer.valueOf(currentGroupCount)
                    : lastSyncedGroupCount;
            Integer itemDelta = latestSyncAt == null || effectiveLastSyncedItemCount == null
                    ? null
                    : itemCount - effectiveLastSyncedItemCount;
            Integer groupDelta = latestSyncAt == null || effectiveLastSyncedGroupCount == null
                    ? null
                    : currentGroupCount - effectiveLastSyncedGroupCount;
            return new AdultOtherCollectionSourceFolderResponse(
                    path,
                    path,
                    itemCount,
                    currentGroupCount,
                    latestPreviewAt,
                    latestSyncAt,
                    effectiveLastSyncedItemCount,
                    effectiveLastSyncedGroupCount,
                    itemDelta,
                    groupDelta,
                    changeStatus(itemDelta, groupDelta)
            );
        }

        private boolean deletionAcknowledged(int currentGroupCount) {
            if (latestSyncAt == null || latestEmptyCleanupAt == null || !latestEmptyCleanupAt.isAfter(latestSyncAt)) {
                return false;
            }
            if (cleanupObservedItemCount == null || cleanupObservedGroupCount == null) {
                return false;
            }
            if (cleanupObservedItemCount != itemCount || cleanupObservedGroupCount != currentGroupCount) {
                return false;
            }
            int itemDelta = lastSyncedItemCount == null ? 0 : itemCount - lastSyncedItemCount;
            int groupDelta = lastSyncedGroupCount == null ? 0 : currentGroupCount - lastSyncedGroupCount;
            return itemDelta <= 0 && groupDelta <= 0 && (itemDelta < 0 || groupDelta < 0 || missing);
        }

        private String changeStatus(Integer itemDelta, Integer groupDelta) {
            int safeItemDelta = itemDelta == null ? 0 : itemDelta;
            int safeGroupDelta = groupDelta == null ? 0 : groupDelta;
            if (latestSyncAt == null) {
                return "NEVER_SYNCED";
            }
            if (safeItemDelta == 0 && safeGroupDelta == 0) {
                return "UNCHANGED";
            }
            if (missing) {
                return "MISSING";
            }
            if (safeItemDelta >= 0 && safeGroupDelta >= 0) {
                return "INCREASED";
            }
            if (safeItemDelta <= 0 && safeGroupDelta <= 0) {
                return "DECREASED";
            }
            return "CHANGED";
        }
    }

    private static class CleanupCounters {

        private int deletedCollectionCount;
        private int reviewCollectionCount;

        void add(AdultOtherCollectionSyncGroup group) {
            if ("DELETE".equals(group.getAction())) {
                deletedCollectionCount++;
            } else if ("REVIEW".equals(group.getAction())) {
                reviewCollectionCount++;
            }
        }

        int deletedCollectionCount() {
            return deletedCollectionCount;
        }

        int reviewCollectionCount() {
            return reviewCollectionCount;
        }
    }

    private static class SyncCounters {

        private int eligibleGroupCount;
        private int createdCollectionCount;
        private int updatedCollectionCount;
        private int unchangedCollectionCount;
        private int itemAddCount;

        void add(AdultOtherCollectionSyncGroup group) {
            if (Boolean.TRUE.equals(group.getEligible())) {
                eligibleGroupCount++;
            }
            if ("CREATE".equals(group.getAction())) {
                createdCollectionCount++;
            } else if ("UPDATE".equals(group.getAction())) {
                updatedCollectionCount++;
            } else if ("UNCHANGED".equals(group.getAction())) {
                unchangedCollectionCount++;
            }
            itemAddCount += group.getAddedItemCount() == null ? 0 : group.getAddedItemCount();
        }

        int eligibleGroupCount() {
            return eligibleGroupCount;
        }

        int createdCollectionCount() {
            return createdCollectionCount;
        }

        int updatedCollectionCount() {
            return updatedCollectionCount;
        }

        int unchangedCollectionCount() {
            return unchangedCollectionCount;
        }

        int itemAddCount() {
            return itemAddCount;
        }
    }
}
