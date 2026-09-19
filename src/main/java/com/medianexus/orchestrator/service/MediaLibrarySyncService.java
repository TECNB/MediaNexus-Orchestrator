package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.admin.response.AdminMediaLibrarySyncResponse;
import com.medianexus.orchestrator.dto.admin.response.AdminMediaLibrarySyncTargetResponse;
import com.medianexus.orchestrator.integration.clouddrive.CloudDrive2MediaDeletion;
import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.integration.emby.EmbyDeletionItem;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.quark.QuarkDirectClient;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
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
        return sync(library, deep, List.of());
    }

    public AdminMediaLibrarySyncResponse sync(String library, boolean deep, Collection<String> selectedPaths) {
        return sync(library, deep, selectedPaths, selectedPaths);
    }

    public AdminMediaLibrarySyncResponse sync(
            String library,
            boolean deep,
            Collection<String> selectedPaths,
            Collection<String> deepPaths
    ) {
        authService.requireAdminUser();
        AdminMediaLibraryScope scope = AdminMediaLibraryScope.fromRequest(library);
        EmbyLibrary embyLibrary = catalogService.resolveLibrary(scope);
        long started = System.nanoTime();
        Map<String, SyncTarget> targets = new LinkedHashMap<>();
        List<String> skippedMedia = new ArrayList<>();
        int skipped = 0;
        for (EmbyDeletionItem item : embyClient.listLibraryMediaItemsForSync(embyLibrary.id())) {
            boolean selected = !selectedPaths.isEmpty() && StringUtils.hasText(item.path()) && selectedPaths.stream()
                    .anyMatch(path -> item.path().equals(path) || item.path().startsWith(path + "/"));
            if (deep && !selectedPaths.isEmpty() && !selected) {
                continue;
            }
            if (deep && !selectedPaths.isEmpty() && !StringUtils.hasText(item.path())) {
                skipped++;
                addDetail(skippedMedia, item.name(), item.path());
                continue;
            }
            String source = resolveMediaSourcePath(item);
            if (!supportedSource(source)) {
                skipped++;
                addDetail(skippedMedia, item.name(), item.path());
                continue;
            }
            Path localPath = Path.of(item.path()).toAbsolutePath().normalize();
            Path localDirectory = localPath.getParent();
            if (localDirectory == null) {
                skipped++;
                addDetail(skippedMedia, item.name(), item.path());
                continue;
            }
            boolean deepTarget = deep && selected && deepPaths.stream()
                    .anyMatch(path -> item.path().equals(path) || item.path().startsWith(path + "/"));
            boolean directChild = selectedPaths.stream().anyMatch(path -> localDirectory.toString().equals(path));
            // A shallow selected target checks direct media files and the first
            // child folders. It does not recurse into files below those folders.
            boolean fileCheck = !deep || selectedPaths.isEmpty() || deepTarget || directChild;
            String remoteTarget = remotePath(source, fileCheck, scope);
            if (remoteTarget == null || !StringUtils.hasText(item.path())) {
                skipped++;
                addDetail(skippedMedia, item.name(), item.path());
                continue;
            }
            Path localTarget = fileCheck ? localPath : localDirectory;
            String key = remoteTarget + "\n" + localTarget;
            targets.computeIfAbsent(key, ignored -> new SyncTarget(remoteTarget, localTarget))
                    .paths.add(item.path());
        }

        int removedDirectories = 0;
        int removedItems = 0;
        CloudDrive2MediaDeletion.MediaSourceCheckResult cloudCheck = existingCloudPaths(targets.values());
        Set<String> existingCloudPaths = cloudCheck.existing();
        Set<String> failedCloudPaths = cloudCheck.failed();
        int errors = failedCloudPaths.size();
        List<String> removedMedia = new ArrayList<>();
        List<String> failedMedia = new ArrayList<>();
        for (SyncTarget target : targets.values()) {
            try {
                if (failedCloudPaths.contains(target.remoteDirectory)) {
                    addDetail(failedMedia, target.label(), target.remoteDirectory);
                    continue;
                }
                if (isCloudPath(target.remoteDirectory)
                        ? existingCloudPaths.contains(target.remoteDirectory)
                        : exists(target.remoteDirectory)) {
                    continue;
                }
                localStrmDeletion.delete(List.of(target.localTarget.toString()));
                embyClient.notifyMediaDeleted(target.paths);
                removedDirectories++;
                removedItems += target.paths.size();
                addDetail(removedMedia, target.label(), target.remoteDirectory);
            } catch (RuntimeException exception) {
                errors++;
                addDetail(failedMedia, target.label(), target.remoteDirectory);
            }
        }
        return new AdminMediaLibrarySyncResponse(
                deep ? 0 : targets.size(), deep ? targets.size() : 0,
                removedDirectories, removedItems, skipped, errors,
                (System.nanoTime() - started) / 1_000_000L,
                deep, removedMedia, skippedMedia, failedMedia
        );
    }

    private void addDetail(List<String> details, String preferred, String fallback) {
        String value = StringUtils.hasText(preferred) ? preferred : fallback;
        if (StringUtils.hasText(value) && details.size() < 50 && !details.contains(value)) {
            details.add(value);
        }
    }

    public List<AdminMediaLibrarySyncTargetResponse> searchTargets(String library, String query) {
        authService.requireAdminUser();
        AdminMediaLibraryScope scope = AdminMediaLibraryScope.fromRequest(library);
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isEmpty()) {
            return List.of();
        }
        Map<String, AdminMediaLibrarySyncTargetResponse> results = new LinkedHashMap<>();
        for (EmbyDeletionItem item : embyClient.listLibraryMediaItemsForSync(catalogService.resolveLibrary(scope).id())) {
            String source = resolveMediaSourcePath(item);
            if (!supportedSource(source) || !StringUtils.hasText(item.path())) {
                continue;
            }
            Path file = Path.of(item.path()).toAbsolutePath().normalize();
            Path folder = topLevelFolder(file, source, scope);
            if (folder == null || folder.getFileName() == null) {
                continue;
            }
            String label = folder.getFileName().toString();
            String haystack = (label + " " + source).toLowerCase(Locale.ROOT);
            if (!haystack.contains(normalizedQuery)) {
                continue;
            }
            addTarget(results, folder, label, "folder", item.path());
            if (results.size() >= 30) {
                break;
            }
        }
        return List.copyOf(results.values());
    }

    private void addTarget(
            Map<String, AdminMediaLibrarySyncTargetResponse> results,
            Path path,
            String label,
            String targetType,
            String detail
    ) {
        if (path == null || !StringUtils.hasText(label) || results.containsKey(path.toString())) {
            return;
        }
        results.put(path.toString(), new AdminMediaLibrarySyncTargetResponse(label, path.toString(), targetType, detail, false));
    }

    private String resolveMediaSourcePath(EmbyDeletionItem item) {
        String source = item.mediaSourcePaths().stream()
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
        return localStrmDeletion.resolveMediaSourcePath(source);
    }

    private Path topLevelFolder(Path file, String source, AdminMediaLibraryScope scope) {
        String remoteTop = remoteTopFolder(source, scope);
        Path current = file.getParent();
        Path match = null;
        while (current != null && current.getFileName() != null) {
            if (remoteTop != null && current.getFileName().toString().equals(remoteTop)) {
                match = current;
            }
            current = current.getParent();
        }
        if (match != null) {
            return match;
        }
        return file.getParent() == null ? null : file.getParent().getParent();
    }

    private String remoteTopFolder(String source, AdminMediaLibraryScope scope) {
        String path = remotePath(source, true, scope);
        if (path == null) {
            return null;
        }
        String[] markers = switch (scope) {
            case ANIME -> new String[]{"/Anime/"};
            case TV -> new String[]{"/TV/"};
            case VARIETY -> new String[]{"/Variety/", "/综艺/"};
            case ADULT_OTHER -> new String[]{"/Adult/Other/", "/Other/"};
            case ADULT_JAV -> new String[]{"/Adult/JAV/", "/JAV/"};
            case MOVIES -> new String[]{"/Movies/", "/Movie/"};
        };
        for (String marker : markers) {
            int index = path.toLowerCase(Locale.ROOT).indexOf(marker.toLowerCase(Locale.ROOT));
            if (index >= 0) {
                String rest = path.substring(index + marker.length());
                int slash = rest.indexOf('/');
                return slash > 0 ? rest.substring(0, slash) : rest;
            }
        }
        return null;
    }

    private CloudDrive2MediaDeletion.MediaSourceCheckResult existingCloudPaths(Collection<SyncTarget> targets) {
        Set<String> cloudPaths = targets.stream()
                .map(target -> target.remoteDirectory)
                .filter(this::isCloudPath)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (cloudPaths.isEmpty()) {
            return new CloudDrive2MediaDeletion.MediaSourceCheckResult(Set.of(), Set.of());
        }
        CloudDrive2MediaDeletion deletion = cloudDriveDeletion.getIfAvailable();
        if (deletion == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "CloudDrive2 未启用", HttpStatus.SERVICE_UNAVAILABLE);
        }
        return deletion.checkMediaSourcePaths(cloudPaths);
    }

    private boolean isCloudPath(String path) {
        return path.startsWith(normalize(cloudDriveProperties.getMediaSourcePathPrefix()));
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

    static String remotePath(String source, boolean deep) {
        return remotePath(source, deep, null);
    }

    static String remotePath(String source, boolean deep, AdminMediaLibraryScope scope) {
        if (!StringUtils.hasText(source)) {
            return null;
        }
        try {
            String path = source;
            int marker = path.indexOf("/smartstrm_fid/");
            if (marker >= 0) {
                path = URI.create(source).getPath();
                if (!StringUtils.hasText(path)) {
                    return null;
                }
                marker = path.indexOf("/smartstrm_fid/");
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
            if (scope == AdminMediaLibraryScope.ANIME) {
                String animeTarget = animeRootTarget(path);
                if (animeTarget != null) {
                    return animeTarget;
                }
            }
            int fileStart = path.lastIndexOf('/');
            return fileStart >= 0 ? ensureLeadingSlash(path.substring(0, fileStart)) : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String ensureLeadingSlash(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String animeRootTarget(String path) {
        int marker = path.indexOf("/Media/Anime/");
        if (marker < 0) {
            marker = path.indexOf("/Anime/");
            if (marker < 0) {
                return null;
            }
            marker += "/Anime/".length();
        } else {
            marker += "/Media/Anime/".length();
        }
        int nextSlash = path.indexOf('/', marker);
        return nextSlash > marker ? path.substring(0, nextSlash) : null;
    }

    private static final class SyncTarget {
        private final String remoteDirectory;
        private final Path localTarget;
        private final List<String> paths = new ArrayList<>();

        private SyncTarget(String remoteDirectory, Path localTarget) {
            this.remoteDirectory = remoteDirectory;
            this.localTarget = localTarget;
        }

        private String label() {
            Path labelPath = localTarget;
            String fileName = labelPath.getFileName() == null ? "" : labelPath.getFileName().toString();
            if (fileName.contains(".")) {
                labelPath = labelPath.getParent() == null ? labelPath : labelPath.getParent();
            }
            Path name = labelPath.getFileName();
            return name == null ? remoteDirectory : name.toString();
        }
    }
}
