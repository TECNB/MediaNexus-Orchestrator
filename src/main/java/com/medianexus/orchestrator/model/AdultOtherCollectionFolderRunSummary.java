package com.medianexus.orchestrator.model;

import java.time.LocalDateTime;

public class AdultOtherCollectionFolderRunSummary {

    private String sourceFolderPath;

    private LocalDateTime latestPreviewAt;

    private LocalDateTime latestSyncAt;

    private Integer lastSyncedItemCount;

    private Integer lastSyncedGroupCount;

    private LocalDateTime latestEmptyCleanupAt;

    private Integer cleanupObservedItemCount;

    private Integer cleanupObservedGroupCount;

    public String getSourceFolderPath() {
        return sourceFolderPath;
    }

    public void setSourceFolderPath(String sourceFolderPath) {
        this.sourceFolderPath = sourceFolderPath;
    }

    public LocalDateTime getLatestPreviewAt() {
        return latestPreviewAt;
    }

    public void setLatestPreviewAt(LocalDateTime latestPreviewAt) {
        this.latestPreviewAt = latestPreviewAt;
    }

    public LocalDateTime getLatestSyncAt() {
        return latestSyncAt;
    }

    public void setLatestSyncAt(LocalDateTime latestSyncAt) {
        this.latestSyncAt = latestSyncAt;
    }

    public Integer getLastSyncedItemCount() {
        return lastSyncedItemCount;
    }

    public void setLastSyncedItemCount(Integer lastSyncedItemCount) {
        this.lastSyncedItemCount = lastSyncedItemCount;
    }

    public Integer getLastSyncedGroupCount() {
        return lastSyncedGroupCount;
    }

    public void setLastSyncedGroupCount(Integer lastSyncedGroupCount) {
        this.lastSyncedGroupCount = lastSyncedGroupCount;
    }

    public LocalDateTime getLatestEmptyCleanupAt() {
        return latestEmptyCleanupAt;
    }

    public void setLatestEmptyCleanupAt(LocalDateTime latestEmptyCleanupAt) {
        this.latestEmptyCleanupAt = latestEmptyCleanupAt;
    }

    public Integer getCleanupObservedItemCount() {
        return cleanupObservedItemCount;
    }

    public void setCleanupObservedItemCount(Integer cleanupObservedItemCount) {
        this.cleanupObservedItemCount = cleanupObservedItemCount;
    }

    public Integer getCleanupObservedGroupCount() {
        return cleanupObservedGroupCount;
    }

    public void setCleanupObservedGroupCount(Integer cleanupObservedGroupCount) {
        this.cleanupObservedGroupCount = cleanupObservedGroupCount;
    }
}
