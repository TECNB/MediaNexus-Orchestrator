package com.medianexus.orchestrator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.admin.request.AdminUserQuotaUpdateRequest;
import com.medianexus.orchestrator.dto.admin.response.AdminUserListResponse;
import com.medianexus.orchestrator.dto.admin.response.AdminUserQuotaResponse;
import com.medianexus.orchestrator.dto.admin.response.AdminUserResponse;
import com.medianexus.orchestrator.dto.admin.response.AdminUserSummaryResponse;
import com.medianexus.orchestrator.dto.admin.response.AdminUserUsageBreakdownResponse;
import com.medianexus.orchestrator.mapper.UserActionUsageMapper;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.mapper.projection.AdminUserUsageRow;
import com.medianexus.orchestrator.mapper.projection.UserUsagePeakRow;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.model.UserActionType;
import com.medianexus.orchestrator.model.UserActionUsage;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AdminUserManagementService {

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String USER_ROLE = "USER";
    private static final ZoneId USAGE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final List<UserActionType> DAILY_CONTENT_CREATE_ACTIONS = List.of(
            UserActionType.MAGNET_INGEST_CREATE,
            UserActionType.ANIME_SUBSCRIBE_CREATE
    );

    private final AuthService authService;
    private final UserMapper userMapper;
    private final UserActionUsageMapper usageMapper;
    private final UserQuotaSettingsService quotaSettingsService;
    private final UserAdminAuditService auditService;

    public AdminUserManagementService(
            AuthService authService,
            UserMapper userMapper,
            UserActionUsageMapper usageMapper,
            UserQuotaSettingsService quotaSettingsService,
            UserAdminAuditService auditService
    ) {
        this.authService = authService;
        this.userMapper = userMapper;
        this.usageMapper = usageMapper;
        this.quotaSettingsService = quotaSettingsService;
        this.auditService = auditService;
    }

    public AdminUserListResponse listUsers(
            Integer page,
            Integer pageSize,
            String keyword,
            String role,
            String sort
    ) {
        authService.requireAdminUser();
        int normalizedPage = normalizePage(page);
        int normalizedPageSize = normalizePageSize(pageSize);
        String normalizedKeyword = normalizeKeyword(keyword);
        String normalizedRole = normalizeRole(role);
        String normalizedSort = normalizeSort(sort);
        int offset = (normalizedPage - 1) * normalizedPageSize;
        LocalDate usageDate = LocalDate.now(USAGE_ZONE);
        List<String> actionTypes = dailyContentCreateActionNames();

        long total = userMapper.countAdminUsers(normalizedKeyword, normalizedRole);
        List<AdminUserUsageRow> rows = userMapper.selectAdminUsers(
                normalizedKeyword,
                normalizedRole,
                usageDate,
                actionTypes,
                normalizedSort,
                normalizedPageSize,
                offset
        );
        int globalDefaultQuota = quotaSettingsService.getDefaultDailyContentCreateLimit();
        List<AdminUserResponse> items = rows.stream()
                .map(row -> toUserResponse(row, globalDefaultQuota))
                .toList();

        return new AdminUserListResponse(items, normalizedPage, normalizedPageSize, total);
    }

    public AdminUserSummaryResponse getSummary() {
        authService.requireAdminUser();
        long totalUsers = userMapper.selectCount(new LambdaQueryWrapper<User>());
        long normalUsers = userMapper.countUsersByRole(USER_ROLE);
        UserUsagePeakRow peakRow = userMapper.selectUserUsagePeak(
                LocalDate.now(USAGE_ZONE),
                dailyContentCreateActionNames()
        );
        int highestUsageCount = peakRow == null || peakRow.getUsedCount() == null ? 0 : peakRow.getUsedCount();
        long highestUsageUserCount = highestUsageCount <= 0 || peakRow == null || peakRow.getUserCount() == null
                ? 0
                : peakRow.getUserCount();

        return new AdminUserSummaryResponse(totalUsers, normalUsers, highestUsageCount, highestUsageUserCount);
    }

    @Transactional
    public AdminUserQuotaResponse updateUserQuota(Long userId, AdminUserQuotaUpdateRequest request) {
        User admin = authService.requireAdminUser();
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请求不能为空");
        }
        User user = requireEditableNormalUser(userId);
        Integer previousOverride = user.getDailyContentCreateLimitOverride();
        Integer nextOverride = request.dailyContentCreateLimitOverride();
        user.setDailyContentCreateLimitOverride(nextOverride);
        userMapper.updateDailyContentCreateLimitOverride(user.getId(), nextOverride);
        int effectiveLimit = quotaSettingsService.resolveDailyContentCreateLimit(user);
        String actionType = nextOverride == null
                ? UserAdminAuditService.ACTION_RESTORE_USER_QUOTA_DEFAULT
                : UserAdminAuditService.ACTION_UPDATE_USER_QUOTA;
        auditService.record(
                admin.getId(),
                user.getId(),
                actionType,
                "daily_content_create_limit_override=" + nullableQuota(previousOverride),
                "daily_content_create_limit_override=" + nullableQuota(nextOverride)
        );
        return new AdminUserQuotaResponse(user.getId(), nextOverride, effectiveLimit, quotaSource(nextOverride));
    }

    @Transactional
    public AdminUserResponse resetTodayUsage(Long userId) {
        User admin = authService.requireAdminUser();
        User user = requireEditableNormalUser(userId);
        LocalDate usageDate = LocalDate.now(USAGE_ZONE);
        List<String> actionTypes = dailyContentCreateActionNames();
        for (String actionType : actionTypes) {
            usageMapper.ensureUsageRow(user.getId(), actionType, usageDate);
        }
        List<UserActionUsage> lockedRows = usageMapper.selectUsageRowsForUpdate(user.getId(), usageDate, actionTypes);
        int magnetCount = usedCountFor(lockedRows, UserActionType.MAGNET_INGEST_CREATE);
        int animeCount = usedCountFor(lockedRows, UserActionType.ANIME_SUBSCRIBE_CREATE);
        usageMapper.resetUsageCounts(user.getId(), usageDate, actionTypes);
        auditService.record(
                admin.getId(),
                user.getId(),
                UserAdminAuditService.ACTION_RESET_USER_USAGE_TODAY,
                usageValue(magnetCount, animeCount),
                usageValue(0, 0)
        );

        AdminUserUsageRow row = new AdminUserUsageRow();
        row.setId(user.getId());
        row.setUsername(user.getUsername());
        row.setEmail(user.getEmail());
        row.setRole(user.getRole());
        row.setDailyContentCreateLimitOverride(user.getDailyContentCreateLimitOverride());
        row.setCreatedAt(user.getCreatedAt());
        row.setUpdatedAt(user.getUpdatedAt());
        row.setUsedCount(0);
        row.setMagnetIngestCreateCount(0);
        row.setAnimeSubscribeCreateCount(0);
        return toUserResponse(row, quotaSettingsService.getDefaultDailyContentCreateLimit());
    }

    private AdminUserResponse toUserResponse(AdminUserUsageRow row, int globalDefaultQuota) {
        boolean admin = ADMIN_ROLE.equalsIgnoreCase(row.getRole());
        int usedCount = safeCount(row.getUsedCount());
        int magnetCount = safeCount(row.getMagnetIngestCreateCount());
        int animeCount = safeCount(row.getAnimeSubscribeCreateCount());
        Integer effectiveLimit = null;
        String quotaSource = "SYSTEM_UNLIMITED";
        String usageStatus = "UNLIMITED";

        if (!admin) {
            effectiveLimit = row.getDailyContentCreateLimitOverride() == null
                    ? globalDefaultQuota
                    : row.getDailyContentCreateLimitOverride();
            quotaSource = quotaSource(row.getDailyContentCreateLimitOverride());
            usageStatus = usageStatus(usedCount, effectiveLimit);
        }

        return new AdminUserResponse(
                row.getId(),
                row.getUsername(),
                row.getEmail(),
                row.getRole(),
                row.getDailyContentCreateLimitOverride(),
                effectiveLimit,
                quotaSource,
                usedCount,
                usageStatus,
                new AdminUserUsageBreakdownResponse(magnetCount, animeCount),
                row.getCreatedAt(),
                row.getUpdatedAt()
        );
    }

    private User requireEditableNormalUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户 id 不能为空");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户不存在");
        }
        if (ADMIN_ROLE.equalsIgnoreCase(user.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "管理员账号不可修改额度或重置次数", HttpStatus.FORBIDDEN);
        }
        return user;
    }

    private int normalizePage(Integer page) {
        return page == null || page < 1 ? DEFAULT_PAGE : page;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        return "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private String normalizeRole(String role) {
        if (!StringUtils.hasText(role) || "ALL".equalsIgnoreCase(role)) {
            return null;
        }
        String normalizedRole = role.trim().toUpperCase(Locale.ROOT);
        if (!USER_ROLE.equals(normalizedRole) && !ADMIN_ROLE.equals(normalizedRole)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户角色筛选无效");
        }
        return normalizedRole;
    }

    private String normalizeSort(String sort) {
        if (!StringUtils.hasText(sort)) {
            return "CREATED_AT_DESC";
        }
        String normalizedSort = sort.trim().toUpperCase(Locale.ROOT);
        return switch (normalizedSort) {
            case "CREATED_AT_ASC", "USED_COUNT_DESC", "USED_COUNT_ASC" -> normalizedSort;
            default -> "CREATED_AT_DESC";
        };
    }

    private List<String> dailyContentCreateActionNames() {
        return DAILY_CONTENT_CREATE_ACTIONS.stream()
                .map(UserActionType::name)
                .toList();
    }

    private int safeCount(Integer count) {
        return count == null ? 0 : count;
    }

    private String usageStatus(int usedCount, int effectiveLimit) {
        if (usedCount > effectiveLimit) {
            return "EXCEEDED";
        }
        if (usedCount == effectiveLimit) {
            return "REACHED_LIMIT";
        }
        return "AVAILABLE";
    }

    private String quotaSource(Integer override) {
        return override == null ? "GLOBAL_DEFAULT" : "USER_OVERRIDE";
    }

    private String nullableQuota(Integer quota) {
        return quota == null ? "null" : String.valueOf(quota);
    }

    private int usedCountFor(List<UserActionUsage> rows, UserActionType actionType) {
        return rows.stream()
                .filter(row -> actionType.name().equals(row.getActionType()))
                .map(UserActionUsage::getUsedCount)
                .mapToInt(this::safeCount)
                .sum();
    }

    private String usageValue(int magnetCount, int animeCount) {
        int total = magnetCount + animeCount;
        return "total=%d;MAGNET_INGEST_CREATE=%d;ANIME_SUBSCRIBE_CREATE=%d"
                .formatted(total, magnetCount, animeCount);
    }
}
