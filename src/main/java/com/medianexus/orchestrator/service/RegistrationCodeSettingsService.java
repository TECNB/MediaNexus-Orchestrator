package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.config.AuthProperties;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RegistrationCodeSettingsService {

    public static final String REGISTRATION_CODE_KEY = "auth_registration_code";

    private final SystemSettingMapper systemSettingMapper;
    private final AuthProperties authProperties;

    public RegistrationCodeSettingsService(
            SystemSettingMapper systemSettingMapper,
            AuthProperties authProperties
    ) {
        this.systemSettingMapper = systemSettingMapper;
        this.authProperties = authProperties;
    }

    public RegistrationCodeSetting resolveEffectiveRegistrationCode() {
        String storedRegistrationCode = systemSettingMapper == null
                ? null
                : cleanValue(systemSettingMapper.selectSettingValue(REGISTRATION_CODE_KEY));
        if (StringUtils.hasText(storedRegistrationCode)) {
            return new RegistrationCodeSetting(storedRegistrationCode, "DATABASE");
        }

        String configuredRegistrationCode = cleanValue(
                authProperties == null ? null : authProperties.getRegistrationCode()
        );
        if (StringUtils.hasText(configuredRegistrationCode)) {
            return new RegistrationCodeSetting(configuredRegistrationCode, "CONFIG");
        }

        return new RegistrationCodeSetting(null, "NONE");
    }

    public void updateRegistrationCode(String registrationCode) {
        systemSettingMapper.upsertSetting(REGISTRATION_CODE_KEY, registrationCode);
    }

    private String cleanValue(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("'") && trimmed.endsWith("'"))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\"")))) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    public record RegistrationCodeSetting(
            String registrationCode,
            String source
    ) {
    }
}
