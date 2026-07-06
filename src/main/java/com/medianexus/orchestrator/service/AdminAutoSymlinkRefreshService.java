package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.autosymlink.request.AutoSymlinkRefreshRequest;
import com.medianexus.orchestrator.dto.autosymlink.response.AutoSymlinkRefreshResponse;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AdminAutoSymlinkRefreshService {

    private final AuthService authService;
    private final AutoSymlinkRefreshService autoSymlinkRefreshService;

    public AdminAutoSymlinkRefreshService(
            AuthService authService,
            AutoSymlinkRefreshService autoSymlinkRefreshService
    ) {
        this.authService = authService;
        this.autoSymlinkRefreshService = autoSymlinkRefreshService;
    }

    public AutoSymlinkRefreshResponse refresh(AutoSymlinkRefreshRequest request) {
        authService.requireAdminUser();
        String target = normalizeTarget(request);
        LocalDateTime startedAt = LocalDateTime.now();
        AutoSymlinkRefreshService.RefreshOutcome outcome = switch (target) {
            case "MOVIE" -> autoSymlinkRefreshService.refreshMovie();
            case "TV" -> autoSymlinkRefreshService.refreshSeries();
            case "ANIME" -> autoSymlinkRefreshService.refreshAnime();
            case "ADULT" -> autoSymlinkRefreshService.refreshAdult();
            default -> throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "不支持的 AutoSymlink 刷新目标: " + target,
                    HttpStatus.BAD_REQUEST
            );
        };
        return new AutoSymlinkRefreshResponse(
                target,
                outcome.status().name(),
                outcome.message(),
                outcome.detail(),
                startedAt,
                LocalDateTime.now()
        );
    }

    private String normalizeTarget(AutoSymlinkRefreshRequest request) {
        if (request == null || !StringUtils.hasText(request.target())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "刷新目标不能为空", HttpStatus.BAD_REQUEST);
        }
        return request.target().trim().toUpperCase(Locale.ROOT);
    }
}
