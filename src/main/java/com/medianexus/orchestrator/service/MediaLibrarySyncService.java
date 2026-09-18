package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.admin.response.AdminMediaLibrarySyncResponse;
import com.medianexus.orchestrator.integration.clouddrive.CloudDrive2MediaDeletion;
import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.integration.emby.EmbyDeletionItem;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.quark.QuarkDirectClient;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MediaLibrarySyncService {

    private final AuthService authService;
    private final AdminMediaLibraryCatalogService catalogService;
    private final EmbyClient embyClient;
    private final QuarkDirectClient quarkClient;
    private final ObjectProvider<CloudDrive2MediaDeletion> cloudDriveDeletion;
    private final LocalStrmDeletion localStrmDeletion;
    private final CloudDrive2Properties cloudDriveProperties;

    public MediaLibrarySyncService(
            AuthService authService,
            AdminMediaLibraryCatalogService catalogService,
            EmbyClient embyClient,
            QuarkDirectClient quarkClient,
            ObjectProvider<CloudDrive2MediaDeletion> cloudDriveDeletion,
            LocalStrmDeletion localStrmDeletion,
            CloudDrive2Properties cloudDriveProperties
    ) {
        this.authService = authService;
        this.catalogService = catalogService;
        this.embyClient = embyClient;
        this.quarkClient = quarkClient;
        this.cloudDriveDeletion = cloudDriveDeletion;
        this.localStrmDeletion = localStrmDeletion;
        this.cloudDriveProperties = cloudDriveProperties;
    }

    public AdminMediaLibrarySyncResponse sync(String library) {
        return sync(library, false);
    }

    public AdminMediaLibrarySyncResponse sync(String library, boolean deep) {
        authService.requireAdminUser();
        AdminMediaLibraryScope scope = AdminMediaLibraryScope.fromRequest(library);
        EmbyLibrary embyLibrary = catalogService.resolveLibrary(scope);
        long started = System.nanoTime();
        Map<String, SyncTarget> targets = new LinkedHashMap<>();
        int skipped = 0;
        for (EmbyDeletionItem item : embyClient.listLibraryMediaItemsForSync(embyLibrary.id())) {
            String source = item.mediaSourcePaths().stream().filter(StringUtils::hasText).findFirst().orElse(null);
            if (!supportedSource(source)) {
                skipped++;
                continue;
            }
            String remoteTarget = remotePath(source, deep);
            if (remoteTarget == null || !StringUtils.hasText(item.path())) {
                skipped++;
                continue;
            }
            Path localPath = Path.of(item.path()).toAbsolutePath().normalize();
            Path localDirectory = localPath.getParent();
            if (localDirectory == null) {
                skipped++;
                continue;
            }
            Path localTarget = deep ? localPath : localDirectory;
            String key = remoteTarget + "\n" + localTarget;
            targets.computeIfAbsent(key, ignored -> new SyncTarget(remoteTarget, localTarget))
                    .paths.add(item.path());
        }

        int removedDirectories = 0;
        int removedItems = 0;
        int errors = 0;
        for (SyncTarget target : targets.values()) {
            try {
                if (exists(target.remoteDirectory)) {
                    continue;
                }
                localStrmDeletion.delete(List.of(target.localDirectory.toString()));
                embyClient.notifyMediaDeleted(target.paths);
                removedDirectories++;
                removedItems += target.paths.size();
            } catch (RuntimeException exception) {
                errors++;
            }
        }
        return new AdminMediaLibrarySyncResponse(
                deep ? 0 : targets.size(), deep ? targets.size() : 0,
                removedDirectories, removedItems, skipped, errors,
                (System.nanoTime() - started) / 1_000_000L,
                deep
        );
    }

    private boolean exists(String remoteDirectory) {
        if (remoteDirectory.startsWith(normalize(cloudDriveProperties.getMediaSourcePathPrefix()))) {
            CloudDrive2MediaDeletion deletion = cloudDriveDeletion.getIfAvailable();
            if (deletion == null) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "CloudDrive2 未启用", HttpStatus.SERVICE_UNAVAILABLE);
            }
            return deletion.mediaSourcePathExists(remoteDirectory);
        }
        return quarkClient.ownedPathExists(remoteDirectory);
    }

    private boolean supportedSource(String source) {
        if (!StringUtils.hasText(source)) {
            return false;
        }
        if (source.contains("/smartstrm_fid/")) {
            return true;
        }
        return source.startsWith(normalize(cloudDriveProperties.getMediaSourcePathPrefix()));
    }

    private String normalize(String path) {
        return StringUtils.hasText(path) ? path.trim().replaceAll("/+$", "") : "";
    }

    private String remotePath(String source, boolean deep) {
        if (!StringUtils.hasText(source)) {
            return null;
        }
        try {
            URI uri = URI.create(source);
            String path = uri.getPath();
            if (!StringUtils.hasText(path)) {
                return null;
            }
            int marker = path.indexOf("/smartstrm_fid/");
            if (marker >= 0) {
                String afterMarker = path.substring(marker + "/smartstrm_fid/".length());
                int fileIdEnd = afterMarker.indexOf('/');
                if (fileIdEnd < 0 || fileIdEnd == afterMarker.length() - 1) {
                    return null;
                }
                path = afterMarker.substring(fileIdEnd + 1);
            }
            if (deep) {
                return ensureLeadingSlash(path);
            }
            int fileStart = path.lastIndexOf('/');
            return fileStart >= 0 ? ensureLeadingSlash(path.substring(0, fileStart)) : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String ensureLeadingSlash(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    private static final class SyncTarget {
        private final String remoteDirectory;
        private final Path localDirectory;
        private final List<String> paths = new ArrayList<>();

        private SyncTarget(String remoteDirectory, Path localDirectory) {
            this.remoteDirectory = remoteDirectory;
            this.localDirectory = localDirectory;
        }
    }
}
