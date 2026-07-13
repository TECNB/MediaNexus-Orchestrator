package com.medianexus.orchestrator.integration.clouddrive;

import java.util.List;

interface CloudDrive2FileOperations {

    List<CloudDrive2FileEntry> list(String path, boolean forceRefresh);

    void rename(List<CloudDrive2RenameOperation> operations);

    void move(List<String> sourcePaths, String targetDirectory);

    void delete(List<String> paths);
}
