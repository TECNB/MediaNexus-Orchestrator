package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.UserQuotaProperties;
import com.medianexus.orchestrator.dto.admin.response.AdminDefaultQuotaResponse;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import com.medianexus.orchestrator.model.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class UserQuotaSettingsService {

    private static final String DAILY_CONTENT_CREATE_LIMIT_KEY = "daily_content_create_limit";
    private static final int MIN_MANAGED_QUOTA = 0;
    private static final int MAX_MANAGED_QUOTA = 9;

    private final SystemSettingMapper systemSettingMapper;
    private final UserQuotaProperties quotaProperties;
    private final AuthService authService;
    private final UserAdminAuditService auditService;

    public UserQuotaSettingsService(
            SystemSettingMapper systemSettingMapper,
            UserQuotaProperties quotaProperties,
            AuthService authService,
            UserAdminAuditService auditService
    ) {
        this.systemSettingMapper = systemSettingMapper;
        this.quotaProperties = quotaProperties;
        this.authService = authService;
        this.auditService = auditService;
    }

    public AdminDefaultQuotaResponse getDefaultQuotaForAdmin() {
        authService.requireAdminUser();
        return new AdminDefaultQuotaResponse(getDefaultDailyContentCreateLimit());
    }

    @Transactional
    public AdminDefaultQuotaResponse updateDefaultQuota(Integer quotaValue) {
        User admin = authService.requireAdminUser();
        int normalizedQuota = validateManagedQuota(quotaValue);
        int previousQuota = getDefaultDailyContentCreateLimit();
        systemSettingMapper.upsertSetting(DAILY_CONTENT_CREATE_LIMIT_KEY, String.valueOf(normalizedQuota));
        auditService.record(
                admin.getId(),
                null,
                UserAdminAuditService.ACTION_UPDATE_GLOBAL_QUOTA,
                "daily_content_create_limit=" + previousQuota,
                "daily_content_create_limit=" + normalizedQuota
        );
        return new AdminDefaultQuotaResponse(normalizedQuota);
    }

    public int getDefaultDailyContentCreateLimit() {
        String storedValue = systemSettingMapper.selectSettingValue(DAILY_CONTENT_CREATE_LIMIT_KEY);
        if (StringUtils.hasText(storedValue)) {
            try {
                return validateManagedQuota(Integer.parseInt(storedValue.trim()));
            } catch (NumberFormatException exception) {
                return configuredDefaultQuota();
            }
        }
        return configuredDefaultQuota();
    }

    public int resolveDailyContentCreateLimit(User user) {
        if (user != null && user.getDailyContentCreateLimitOverride() != null) {
            return validateManagedQuota(user.getDailyContentCreateLimitOverride());
        }
        return getDefaultDailyContentCreateLimit();
    }

    public int validateManagedQuota(Integer quotaValue) {
        if (quotaValue == null || quotaValue < MIN_MANAGED_QUOTA || quotaValue > MAX_MANAGED_QUOTA) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "额度必须为 0-9 的整数");
        }
        return quotaValue;
    }

    private int configuredDefaultQuota() {
        return Math.min(MAX_MANAGED_QUOTA, Math.max(MIN_MANAGED_QUOTA, quotaProperties.getDailyContentCreateLimit()));
    }
}
