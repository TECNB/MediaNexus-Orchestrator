package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.admin.response.AdminMediaLibraryItemResponse;
import com.medianexus.orchestrator.dto.admin.response.AdminMediaLibraryPageResponse;
import com.medianexus.orchestrator.dto.admin.response.AdminMediaMetadataCandidateResponse;
import com.medianexus.orchestrator.dto.admin.response.AdminMediaPosterCandidateResponse;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyClientException;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import com.medianexus.orchestrator.integration.emby.EmbyMediaLibraryItem;
import com.medianexus.orchestrator.integration.emby.EmbyMediaLibraryPage;
import com.medianexus.orchestrator.integration.emby.EmbyPrimaryImage;
import com.medianexus.orchestrator.integration.emby.EmbyRemoteImageCandidate;
import com.medianexus.orchestrator.integration.emby.EmbyRemoteSearchCandidate;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AdminMediaLibraryCatalogService {

    private static final Logger log = LoggerFactory.getLogger(AdminMediaLibraryCatalogService.class);

    private final AuthService authService;
    private final EmbyClient embyClient;

    // Short-lived server-local cache avoids repeatedly fetching remote preview candidates.
    private final Map<String, CachedCandidates> previewCache = Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedCandidates> eldest) {
                    return size() > 128;
                }
            }
    );

    public AdminMediaLibraryCatalogService(AuthService authService, EmbyClient embyClient) {
        this.authService = authService;
        this.embyClient = embyClient;
    }

    public AdminMediaLibraryPageResponse listItems(
            String library,
            int page,
            int pageSize,
            String search
    ) {
        return listItems(library, page, pageSize, search, false);
    }

    public AdminMediaLibraryPageResponse listItems(
            String library,
            int page,
            int pageSize,
            String search,
            boolean missingPoster
    ) {
        authService.requireAdminUser();
        AdminMediaLibraryScope allowedLibrary = AdminMediaLibraryScope.fromRequest(library);
        try {
            EmbyLibrary embyLibrary = resolveLibrary(allowedLibrary);
            // ponytail: current libraries are far below 10k items; paginate upstream if that ceiling is reached.
            EmbyMediaLibraryPage result = embyClient.listTopLevelMediaItems(
                    embyLibrary.id(),
                    allowedLibrary.listingItemType(),
                    missingPoster ? 0 : (page - 1) * pageSize,
                    missingPoster ? 10_000 : pageSize,
                    search
            );
            List<EmbyMediaLibraryItem> items = result.items();
            int total = result.totalRecordCount();
            if (missingPoster) {
                List<EmbyMediaLibraryItem> missingItems = items.stream()
                        .filter(item -> !item.hasPrimaryImage())
                        .toList();
                int start = Math.min((page - 1) * pageSize, missingItems.size());
                int end = Math.min(start + pageSize, missingItems.size());
                items = missingItems.subList(start, end);
                total = missingItems.size();
            }
            return new AdminMediaLibraryPageResponse(
                    items.stream()
                            .map(item -> responseItem(item, embyLibrary))
                            .toList(),
                    page,
                    pageSize,
                    total
            );
        } catch (EmbyClientException exception) {
            throw unavailable("list", allowedLibrary, null, exception);
        }
    }

    public AdminMediaLibraryPoster getPoster(String itemId) {
        authService.requireAdminUser();
        try {
            EmbyPrimaryImage image = embyClient.getPrimaryImageWithContentType(itemId);
            return new AdminMediaLibraryPoster(image.bytes(), image.contentType());
        } catch (EmbyClientException exception) {
            throw unavailable("poster", null, itemId, exception);
        }
    }

    public List<AdminMediaMetadataCandidateResponse> searchMetadataCandidates(
            String itemId,
            String library,
            String query,
            Integer year
    ) {
        authService.requireAdminUser();
        AdminMediaLibraryScope allowedLibrary = AdminMediaLibraryScope.fromRequest(library);
        try {
            requireAllowedItem(itemId, allowedLibrary);
            List<EmbyRemoteSearchCandidate> candidates = embyClient.searchRemoteMetadata(
                    itemId, allowedLibrary.itemType(), query.trim(), year
            );
            previewCache.put(metadataCacheKey(itemId, library, query, year),
                    new CachedCandidates(Instant.now().plusSeconds(120), candidates, null));
            return candidates
                    .stream()
                    .map(this::metadataCandidateResponse)
                    .toList();
        } catch (EmbyClientException exception) {
            throw unavailable("identify-search", allowedLibrary, itemId, exception);
        }
    }

    public AdminMediaLibraryPoster getMetadataCandidateImage(
            String itemId,
            String library,
            String query,
            Integer year,
            String candidateId
    ) {
        authService.requireAdminUser();
        AdminMediaLibraryScope allowedLibrary = AdminMediaLibraryScope.fromRequest(library);
        try {
            requireAllowedItem(itemId, allowedLibrary);
            CachedCandidates cached = validPreview(metadataCacheKey(itemId, library, query, year));
            EmbyRemoteSearchCandidate candidate = cached == null
                    ? requireMetadataCandidate(itemId, allowedLibrary, query, year, candidateId)
                    : cached.metadata().stream().filter(value -> value.candidateId().equals(candidateId))
                            .findFirst().orElseThrow(this::expiredCandidate);
            if (!StringUtils.hasText(candidate.imageUrl())
                    || !StringUtils.hasText(candidate.searchProviderName())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "该识别候选没有预览图", HttpStatus.NOT_FOUND);
            }
            EmbyPrimaryImage image = embyClient.getRemoteImage(
                    candidate.searchProviderName(),
                    candidate.imageUrl()
            );
            return new AdminMediaLibraryPoster(image.bytes(), image.contentType());
        } catch (EmbyClientException exception) {
            throw unavailable("identify-preview", allowedLibrary, itemId, exception);
        }
    }

    public AdminMediaLibraryItemResponse applyMetadataCandidate(
            String itemId,
            String library,
            String query,
            Integer year,
            String candidateId,
            boolean replaceAllImages
    ) {
        authService.requireAdminUser();
        AdminMediaLibraryScope allowedLibrary = AdminMediaLibraryScope.fromRequest(library);
        try {
            EmbyLibrary embyLibrary = resolveLibrary(allowedLibrary);
            EmbyMediaLibraryItem previous = requireAllowedItem(itemId, allowedLibrary, embyLibrary);
            EmbyRemoteSearchCandidate candidate = requireMetadataCandidate(
                    itemId, allowedLibrary, query, year, candidateId
            );
            embyClient.applyRemoteMetadata(itemId, candidate, replaceAllImages);
            invalidatePreviews(itemId);
            return responseItem(
                    waitForMetadata(itemId, allowedLibrary, embyLibrary, previous, candidate, replaceAllImages),
                    embyLibrary
            );
        } catch (EmbyClientException exception) {
            throw unavailable("identify-apply", allowedLibrary, itemId, exception);
        }
    }

    public List<AdminMediaPosterCandidateResponse> listPosterCandidates(
            String itemId,
            String library
    ) {
        authService.requireAdminUser();
        AdminMediaLibraryScope allowedLibrary = AdminMediaLibraryScope.fromRequest(library);
        try {
            requireAllowedItem(itemId, allowedLibrary);
            List<EmbyRemoteImageCandidate> candidates = embyClient.listRemotePrimaryImages(itemId);
            previewCache.put(itemId + ":posters",
                    new CachedCandidates(Instant.now().plusSeconds(120), null, candidates));
            return candidates.stream()
                    .map(candidate -> new AdminMediaPosterCandidateResponse(
                            candidate.candidateId(),
                            candidate.providerName(),
                            candidate.width(),
                            candidate.height(),
                            candidate.language()
                    ))
                    .toList();
        } catch (EmbyClientException exception) {
            throw unavailable("poster-search", allowedLibrary, itemId, exception);
        }
    }

    public AdminMediaLibraryPoster getPosterCandidateImage(
            String itemId,
            String library,
            String candidateId
    ) {
        authService.requireAdminUser();
        AdminMediaLibraryScope allowedLibrary = AdminMediaLibraryScope.fromRequest(library);
        try {
            requireAllowedItem(itemId, allowedLibrary);
            CachedCandidates cached = validPreview(itemId + ":posters");
            EmbyRemoteImageCandidate candidate = cached == null
                    ? requirePosterCandidate(itemId, candidateId)
                    : cached.posters().stream().filter(value -> value.candidateId().equals(candidateId))
                            .findFirst().orElseThrow(this::expiredCandidate);
            EmbyPrimaryImage image = embyClient.getRemoteImage(candidate.providerName(), candidate.url());
            return new AdminMediaLibraryPoster(image.bytes(), image.contentType());
        } catch (EmbyClientException exception) {
            throw unavailable("poster-preview", allowedLibrary, itemId, exception);
        }
    }

    public AdminMediaLibraryItemResponse selectPosterCandidate(
            String itemId,
            String library,
            String candidateId
    ) {
        authService.requireAdminUser();
        AdminMediaLibraryScope allowedLibrary = AdminMediaLibraryScope.fromRequest(library);
        try {
            EmbyLibrary embyLibrary = resolveLibrary(allowedLibrary);
            EmbyMediaLibraryItem previous = requireAllowedItem(itemId, allowedLibrary, embyLibrary);
            EmbyRemoteImageCandidate candidate = requirePosterCandidate(itemId, candidateId);
            embyClient.downloadRemotePrimaryImage(itemId, candidate);
            invalidatePreviews(itemId);
            return responseItem(
                    waitForPoster(itemId, allowedLibrary, embyLibrary, previous),
                    embyLibrary
            );
        } catch (EmbyClientException exception) {
            throw unavailable("poster-apply", allowedLibrary, itemId, exception);
        }
    }

    EmbyLibrary resolveLibrary(AdminMediaLibraryScope allowedLibrary) {
        return embyClient.listLibraries().stream()
                .filter(library -> allowedLibrary.embyName().equalsIgnoreCase(library.name()))
                .findFirst()
                .orElseThrow(() -> new EmbyClientException(
                        "Allowed Emby library is missing: " + allowedLibrary.embyName()
                ));
    }

    private AdminMediaLibraryItemResponse responseItem(
            EmbyMediaLibraryItem item,
            EmbyLibrary library
    ) {
        return new AdminMediaLibraryItemResponse(
                item.id(),
                item.title(),
                item.year(),
                item.dateCreated(),
                item.hasPrimaryImage(),
                item.primaryImageTag(),
                library.id(),
                library.name(),
                item.type()
        );
    }

    EmbyMediaLibraryItem requireAllowedItem(String itemId, AdminMediaLibraryScope allowedLibrary) {
        EmbyLibrary library = resolveLibrary(allowedLibrary);
        return requireAllowedItem(itemId, allowedLibrary, library);
    }

    EmbyMediaLibraryItem requireAllowedItem(
            String itemId,
            AdminMediaLibraryScope allowedLibrary,
            EmbyLibrary library
    ) {
        EmbyMediaLibraryItem item = embyClient.getTopLevelMediaItem(
                library.id(), allowedLibrary.itemType(), itemId
        );
        if (item == null) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "未在指定媒体库中找到该作品",
                    HttpStatus.NOT_FOUND
            );
        }
        return item;
    }

    EmbyMediaLibraryItem requireDeletableItem(
            String itemId,
            AdminMediaLibraryScope allowedLibrary,
            EmbyLibrary library
    ) {
        EmbyMediaLibraryItem item = embyClient.getTopLevelMediaItem(
                library.id(), allowedLibrary.listingItemType(), itemId
        );
        if (item == null && !allowedLibrary.listingItemType().equals(allowedLibrary.itemType())) {
            item = embyClient.getTopLevelMediaItem(library.id(), allowedLibrary.itemType(), itemId);
        }
        if (item == null) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "未在指定媒体库中找到该作品或合集",
                    HttpStatus.NOT_FOUND
            );
        }
        return item;
    }

    private EmbyMediaLibraryItem waitForMetadata(
            String itemId,
            AdminMediaLibraryScope allowedLibrary,
            EmbyLibrary library,
            EmbyMediaLibraryItem previous,
            EmbyRemoteSearchCandidate candidate,
            boolean replaceAllImages
    ) {
        return waitForItem(itemId, allowedLibrary, library, item -> {
            boolean metadataChanged = candidate.name().equalsIgnoreCase(item.title())
                    && (candidate.productionYear() == null
                    || candidate.productionYear().equals(item.year()));
            boolean imageChanged = !replaceAllImages
                    || !StringUtils.hasText(candidate.imageUrl())
                    || !Objects.equals(previous.primaryImageTag(), item.primaryImageTag());
            return metadataChanged && imageChanged;
        });
    }

    private EmbyMediaLibraryItem waitForPoster(
            String itemId,
            AdminMediaLibraryScope allowedLibrary,
            EmbyLibrary library,
            EmbyMediaLibraryItem previous
    ) {
        return waitForItem(itemId, allowedLibrary, library,
                item -> item.hasPrimaryImage()
                        && !Objects.equals(previous.primaryImageTag(), item.primaryImageTag()));
    }

    private EmbyMediaLibraryItem waitForItem(
            String itemId,
            AdminMediaLibraryScope allowedLibrary,
            EmbyLibrary library,
            Predicate<EmbyMediaLibraryItem> ready
    ) {
        EmbyMediaLibraryItem latest = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            latest = requireAllowedItem(itemId, allowedLibrary, library);
            if (ready.test(latest)) {
                return latest;
            }
            if (attempt < 4) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return latest;
                }
            }
        }
        return latest;
    }

    private String metadataCacheKey(String itemId, String library, String query, Integer year) {
        return itemId + ":metadata:" + library + ":" + year + ":" + query.trim();
    }

    private CachedCandidates validPreview(String key) {
        CachedCandidates cached = previewCache.get(key);
        return cached != null && cached.expiresAt().isAfter(Instant.now()) ? cached : null;
    }

    private void invalidatePreviews(String itemId) {
        synchronized (previewCache) {
            previewCache.keySet().removeIf(key -> key.startsWith(itemId + ":"));
        }
    }

    private record CachedCandidates(
            Instant expiresAt,
            List<EmbyRemoteSearchCandidate> metadata,
            List<EmbyRemoteImageCandidate> posters
    ) {
    }

    private EmbyRemoteSearchCandidate requireMetadataCandidate(
            String itemId,
            AdminMediaLibraryScope allowedLibrary,
            String query,
            Integer year,
            String candidateId
    ) {
        return embyClient.searchRemoteMetadata(itemId, allowedLibrary.itemType(), query.trim(), year)
                .stream()
                .filter(candidate -> candidate.candidateId().equals(candidateId))
                .findFirst()
                .orElseThrow(this::expiredCandidate);
    }

    private EmbyRemoteImageCandidate requirePosterCandidate(String itemId, String candidateId) {
        return embyClient.listRemotePrimaryImages(itemId).stream()
                .filter(candidate -> candidate.candidateId().equals(candidateId))
                .findFirst()
                .orElseThrow(this::expiredCandidate);
    }

    private BusinessException expiredCandidate() {
        return new BusinessException(
                ErrorCode.CONFLICT,
                "候选结果已失效，请重新搜索",
                HttpStatus.CONFLICT
        );
    }

    private AdminMediaMetadataCandidateResponse metadataCandidateResponse(
            EmbyRemoteSearchCandidate candidate
    ) {
        return new AdminMediaMetadataCandidateResponse(
                candidate.candidateId(),
                candidate.name(),
                candidate.originalTitle(),
                candidate.productionYear(),
                candidate.providerIds(),
                candidate.searchProviderName(),
                candidate.overview(),
                StringUtils.hasText(candidate.imageUrl())
        );
    }

    private BusinessException unavailable(
            String operation,
            AdminMediaLibraryScope library,
            String itemId,
            EmbyClientException exception
    ) {
        log.warn(
                "Admin Emby media library unavailable operation={} library={} itemId={} reason={}",
                operation,
                library == null ? null : library.requestValue(),
                itemId,
                exception.getMessage()
        );
        return new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE,
                "Emby 媒体库暂时不可用",
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

}
