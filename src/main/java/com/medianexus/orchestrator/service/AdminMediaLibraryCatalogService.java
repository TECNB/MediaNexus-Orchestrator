package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.admin.response.AdminMediaLibraryItemResponse;
import com.medianexus.orchestrator.dto.admin.response.AdminMediaLibraryPageResponse;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyClientException;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import com.medianexus.orchestrator.integration.emby.EmbyMediaLibraryItem;
import com.medianexus.orchestrator.integration.emby.EmbyMediaLibraryPage;
import com.medianexus.orchestrator.integration.emby.EmbyPrimaryImage;
import java.util.Arrays;
import java.util.Locale;
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
        authService.requireAdminUser();
        AllowedLibrary allowedLibrary = AllowedLibrary.fromRequest(library);
        try {
            EmbyLibrary embyLibrary = resolveLibrary(allowedLibrary);
            EmbyMediaLibraryPage result = embyClient.listTopLevelMediaItems(
                    embyLibrary.id(),
                    allowedLibrary.itemType,
                    (page - 1) * pageSize,
                    pageSize,
                    search
            );
            return new AdminMediaLibraryPageResponse(
                    result.items().stream()
                            .map(item -> responseItem(item, embyLibrary))
                            .toList(),
                    page,
                    pageSize,
                    result.totalRecordCount()
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

    private EmbyLibrary resolveLibrary(AllowedLibrary allowedLibrary) {
        return embyClient.listLibraries().stream()
                .filter(library -> allowedLibrary.embyName.equalsIgnoreCase(library.name()))
                .findFirst()
                .orElseThrow(() -> new EmbyClientException(
                        "Allowed Emby library is missing: " + allowedLibrary.embyName
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
                library.id(),
                library.name(),
                item.type()
        );
    }

    private BusinessException unavailable(
            String operation,
            AllowedLibrary library,
            String itemId,
            EmbyClientException exception
    ) {
        log.warn(
                "Admin Emby media library unavailable operation={} library={} itemId={} reason={}",
                operation,
                library == null ? null : library.requestValue,
                itemId,
                exception.getMessage()
        );
        return new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE,
                "Emby 媒体库暂时不可用",
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    private enum AllowedLibrary {
        MOVIES("movies", "Movies", "Movie"),
        TV("tv", "TV", "Series"),
        ANIME("anime", "Anime", "Series");

        private final String requestValue;
        private final String embyName;
        private final String itemType;

        AllowedLibrary(String requestValue, String embyName, String itemType) {
            this.requestValue = requestValue;
            this.embyName = embyName;
            this.itemType = itemType;
        }

        private static AllowedLibrary fromRequest(String library) {
            String normalized = StringUtils.hasText(library)
                    ? library.trim().toLowerCase(Locale.ROOT)
                    : "";
            return Arrays.stream(values())
                    .filter(candidate -> candidate.requestValue.equals(normalized))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.BAD_REQUEST,
                            "媒体库只能是 movies、tv 或 anime"
                    ));
        }
    }
}
