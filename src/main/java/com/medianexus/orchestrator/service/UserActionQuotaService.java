package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.UserQuotaProperties;
import com.medianexus.orchestrator.mapper.UserActionUsageMapper;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.model.UserActionType;
import com.medianexus.orchestrator.model.UserActionUsage;
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
    private final UserQuotaProperties quotaProperties;

    public UserActionQuotaService(
            UserActionUsageMapper usageMapper,
            UserQuotaProperties quotaProperties
    ) {
        this.usageMapper = usageMapper;
        this.quotaProperties = quotaProperties;
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
        assertWithinDailyContentCreateLimit(usedCount);

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
        assertWithinDailyContentCreateLimit(usedCount == null ? 0 : usedCount);
    }

    private List<String> dailyContentCreateActionNames() {
        return DAILY_CONTENT_CREATE_ACTIONS.stream()
                .map(UserActionType::name)
                .toList();
    }

    private void assertWithinDailyContentCreateLimit(int usedCount) {
        int dailyLimit = Math.max(0, quotaProperties.getDailyContentCreateLimit());
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
