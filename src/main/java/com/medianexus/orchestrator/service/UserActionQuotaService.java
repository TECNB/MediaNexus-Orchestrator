package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.mapper.UserActionUsageMapper;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.model.UserActionType;
import com.medianexus.orchestrator.model.UserActionUsage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserActionQuotaService {

    private static final String ADMIN_ROLE = "ADMIN";
    private static final ZoneId USAGE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<UserActionType> DAILY_CONTENT_CREATE_ACTIONS = List.of(
            UserActionType.MAGNET_INGEST_CREATE,
            UserActionType.ANIME_SUBSCRIBE_CREATE
    );

    private final UserActionUsageMapper usageMapper;
    private final UserQuotaSettingsService quotaSettingsService;

    public UserActionQuotaService(
            UserActionUsageMapper usageMapper,
            UserQuotaSettingsService quotaSettingsService
    ) {
        this.usageMapper = usageMapper;
        this.quotaSettingsService = quotaSettingsService;
    }

    @Transactional
    public void consumeDailyContentCreate(User user, UserActionType actionType) {
        if (!DAILY_CONTENT_CREATE_ACTIONS.contains(actionType)) {
            throw new IllegalArgumentException("Unsupported daily content create action: " + actionType);
        }
        if (isAdmin(user)) {
            return;
        }

        LocalDate usageDate = LocalDate.now(USAGE_ZONE);
        List<String> actionTypes = dailyContentCreateActionNames();

        for (String groupedActionType : actionTypes) {
            usageMapper.ensureUsageRow(user.getId(), groupedActionType, usageDate);
        }

        int usedCount = usageMapper.selectUsageRowsForUpdate(user.getId(), usageDate, actionTypes)
                .stream()
                .map(UserActionUsage::getUsedCount)
                .mapToInt(count -> count == null ? 0 : count)
                .sum();
        assertWithinDailyContentCreateLimit(user, usedCount);

        usageMapper.incrementUsageCount(user.getId(), actionType.name(), usageDate);
    }

    public void assertDailyContentCreateAvailable(User user) {
        if (isAdmin(user)) {
            return;
        }

        LocalDate usageDate = LocalDate.now(USAGE_ZONE);
        Integer usedCount = usageMapper.sumUsageCount(
                user.getId(),
                usageDate,
                dailyContentCreateActionNames()
        );
        assertWithinDailyContentCreateLimit(user, usedCount == null ? 0 : usedCount);
    }

    /** 预览与运行结果读取实际用量；管理员豁免不伪装成无限剩余次数。 */
    public DailyContentCreateQuota dailyContentCreateQuota(User user) {
        LocalDate date = LocalDate.now(USAGE_ZONE);
        Integer used = usageMapper.sumUsageCount(user.getId(), date, dailyContentCreateActionNames());
        int usedCount = used == null ? 0 : used;
        int limit = quotaSettingsService.resolveDailyContentCreateLimit(user);
        boolean exempt = isAdmin(user);
        return new DailyContentCreateQuota(user.getId(), user.getUsername(), date.toString(),
                usedCount, limit, exempt ? null : Math.max(0, limit - usedCount), exempt);
    }

    @Schema(description = "任务归属成员的北京时间自然日创建次数；预览不预留额度")
    public record DailyContentCreateQuota(Long ownerUserId, String ownerUsername, String usageDate,
                                          int used, int limit,
                                          @Schema(description = "普通成员剩余创建次数；管理员豁免时为 null", nullable = true)
                                          Integer remaining, boolean exempt) {
    }

    private List<String> dailyContentCreateActionNames() {
        return DAILY_CONTENT_CREATE_ACTIONS.stream()
                .map(UserActionType::name)
                .toList();
    }

    private void assertWithinDailyContentCreateLimit(User user, int usedCount) {
        int dailyLimit = quotaSettingsService.resolveDailyContentCreateLimit(user);
        if (usedCount >= dailyLimit) {
            throw new BusinessException(
                    ErrorCode.TOO_MANY_REQUESTS,
                    "今日创建次数已达上限，请明天再试",
                    HttpStatus.TOO_MANY_REQUESTS
            );
        }
    }

    private boolean isAdmin(User user) {
        return user != null && ADMIN_ROLE.equalsIgnoreCase(user.getRole());
    }
}
