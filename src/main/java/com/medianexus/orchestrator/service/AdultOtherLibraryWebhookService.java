package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.EmbyProperties;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AdultOtherLibraryWebhookService {

    private static final Logger log = LoggerFactory.getLogger(AdultOtherLibraryWebhookService.class);
    private static final String EVENT_NEW = "library.new";
    private static final String EVENT_DELETED = "library.deleted";

    private final EmbyProperties properties;
    private final ObjectMapper objectMapper;
    private final AdultOtherLibraryAutomationRunner automationRunner;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final Object eventLock = new Object();
    private final Set<String> pendingNewItemIds = new LinkedHashSet<>();
    private final Set<String> pendingDeletedPaths = new LinkedHashSet<>();
    private ScheduledFuture<?> newItemFuture;
    private ScheduledFuture<?> deletionFuture;

    @Autowired
    public AdultOtherLibraryWebhookService(
            EmbyProperties properties,
            ObjectMapper objectMapper,
            AdultOtherLibraryAutomationRunner automationRunner
    ) {
        this(
                properties,
                objectMapper,
                automationRunner,
                Executors.newSingleThreadScheduledExecutor(new AutomationThreadFactory()),
                true
        );
    }

    AdultOtherLibraryWebhookService(
            EmbyProperties properties,
            ObjectMapper objectMapper,
            AdultOtherLibraryAutomationRunner automationRunner,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.automationRunner = automationRunner;
        this.scheduler = scheduler;
        this.ownsScheduler = ownsScheduler;
    }

    public void receiveLibraryEvent(String secret, String body) {
        validateSecret(secret);
        if (!properties.isAdultOtherAutomationEnabled()) {
            return;
        }

        LibraryEvent event = parse(body);
        if (event == null) {
            return;
        }
        if (EVENT_NEW.equals(event.event()) && "movie".equals(event.itemType())) {
            enqueueNewItem(event.itemId());
            return;
        }
        if (EVENT_DELETED.equals(event.event())
                && ("movie".equals(event.itemType()) || "folder".equals(event.itemType()))) {
            enqueueDeletedPath(event.path());
        }
    }

    private void enqueueNewItem(String itemId) {
        if (!StringUtils.hasText(itemId)) {
            return;
        }
        synchronized (eventLock) {
            pendingNewItemIds.add(itemId.trim());
            if (newItemFuture != null) {
                newItemFuture.cancel(false);
            }
            newItemFuture = scheduler.schedule(
                    this::processNewItemBatch,
                    delayMillis(properties.getAdultOtherNewEventDebounce()),
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private void enqueueDeletedPath(String path) {
        if (!StringUtils.hasText(path)) {
            return;
        }
        synchronized (eventLock) {
            pendingDeletedPaths.add(path.trim());
            if (deletionFuture != null) {
                deletionFuture.cancel(false);
            }
            deletionFuture = scheduler.schedule(
                    this::processDeletionBatch,
                    delayMillis(properties.getAdultOtherDeleteEventDebounce()),
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private void processNewItemBatch() {
        Set<String> itemIds;
        synchronized (eventLock) {
            itemIds = new LinkedHashSet<>(pendingNewItemIds);
            pendingNewItemIds.clear();
            newItemFuture = null;
        }
        if (itemIds.isEmpty()) {
            return;
        }
        try {
            automationRunner.processNewItems(itemIds);
        } catch (RuntimeException exception) {
            log.error("Adult Other new-item automation failed batchSize={}", itemIds.size(), exception);
        }
    }

    private void processDeletionBatch() {
        Set<String> paths;
        synchronized (eventLock) {
            paths = new LinkedHashSet<>(pendingDeletedPaths);
            pendingDeletedPaths.clear();
            deletionFuture = null;
        }
        if (paths.isEmpty()) {
            return;
        }
        try {
            automationRunner.processDeletedPaths(paths);
        } catch (RuntimeException exception) {
            log.error("Adult Other deletion automation failed batchSize={}", paths.size(), exception);
        }
    }

    private LibraryEvent parse(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode item = root.path("Item");
            return new LibraryEvent(
                    normalized(root.path("Event").asText(null)),
                    clean(item.path("Id").asText(null)),
                    normalized(item.path("Type").asText(null)),
                    clean(item.path("Path").asText(null))
            );
        } catch (JsonProcessingException exception) {
            log.warn("Emby library webhook ignored because payload is not valid JSON");
            return null;
        }
    }

    private void validateSecret(String secret) {
        String expectedSecret = clean(properties.getWebhookSecret());
        if (!StringUtils.hasText(expectedSecret) || !expectedSecret.equals(secret)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Emby webhook secret 无效", HttpStatus.FORBIDDEN);
        }
    }

    private long delayMillis(Duration duration) {
        return duration == null || duration.isNegative() ? 0 : duration.toMillis();
    }

    private String normalized(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toLowerCase(Locale.ROOT);
    }

    private String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    @PreDestroy
    public void shutdown() {
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private record LibraryEvent(String event, String itemId, String itemType, String path) {
    }

    private static final class AutomationThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "adult-other-webhook");
            thread.setDaemon(true);
            return thread;
        }
    }
}
