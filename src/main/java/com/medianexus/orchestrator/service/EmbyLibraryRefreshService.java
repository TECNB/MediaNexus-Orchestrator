package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.emby.response.EmbyLibraryListResponse;
import com.medianexus.orchestrator.dto.emby.response.EmbyLibraryRefreshResponse;
import com.medianexus.orchestrator.dto.emby.response.EmbyLibrarySummaryResponse;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class EmbyLibraryRefreshService {

    private static final Set<String> SUBTITLE_LIBRARY_NAMES = Set.of("Anime", "Movies", "TV");

    private final EmbyClient embyClient;

    public EmbyLibraryRefreshService(EmbyClient embyClient) {
        this.embyClient = embyClient;
    }

    public EmbyLibraryListResponse listLibraries() {
        return new EmbyLibraryListResponse(
                subtitleTargetLibraries().stream()
                        .map(library -> new EmbyLibrarySummaryResponse(library.id(), library.name()))
                        .toList()
        );
    }

    public EmbyLibraryRefreshResponse refreshLibrary(String libraryId) {
        EmbyLibrary library = subtitleTargetLibraries().stream()
                .filter(candidate -> Objects.equals(candidate.id(), libraryId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "未找到指定的 Emby 媒体库",
                        HttpStatus.NOT_FOUND
                ));

        embyClient.refreshLibrary(library.id());
        return new EmbyLibraryRefreshResponse(
                library.id(),
                library.name(),
                "已提交 Emby 媒体库刷新"
        );
    }

    private List<EmbyLibrary> subtitleTargetLibraries() {
        return embyClient.listLibraries().stream()
                .filter(library -> StringUtils.hasText(library.id()))
                .filter(library -> SUBTITLE_LIBRARY_NAMES.contains(library.name()))
                .toList();
    }
}
