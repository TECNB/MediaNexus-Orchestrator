package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.config.AuthProperties;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import java.security.SecureRandom;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RegistrationCodeSettingsService {

    public static final String REGISTRATION_CODE_KEY = "auth_registration_code";
    public static final String REGISTRATION_CODE_INVITER_USER_ID_KEY =
            "auth_registration_code_inviter_user_id";
    public static final String REGISTRATION_CODE_INVITER_USERNAME_KEY =
            "auth_registration_code_inviter_username";
    private static final String REGISTRATION_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int REGISTRATION_CODE_GROUP_SIZE = 4;
    private static final int REGISTRATION_CODE_GROUP_COUNT = 4;

    private final SystemSettingMapper systemSettingMapper;
    private final AuthProperties authProperties;
    private final SecureRandom secureRandom = new SecureRandom();

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
            return databaseSetting(storedRegistrationCode);
        }

        String configuredRegistrationCode = cleanValue(
                authProperties == null ? null : authProperties.getRegistrationCode()
        );
        if (StringUtils.hasText(configuredRegistrationCode)) {
            return new RegistrationCodeSetting(configuredRegistrationCode, "CONFIG", null, null);
        }

        return new RegistrationCodeSetting(null, "NONE", null, null);
    }

    public void updateRegistrationCode(String registrationCode) {
        systemSettingMapper.upsertSetting(REGISTRATION_CODE_KEY, registrationCode);
    }

    public RegistrationCodeSetting lockEffectiveRegistrationCode() {
        if (systemSettingMapper == null) {
            return resolveEffectiveRegistrationCode();
        }

        String configuredRegistrationCode = cleanValue(
                authProperties == null ? null : authProperties.getRegistrationCode()
        );
        systemSettingMapper.insertSettingIfAbsent(
                REGISTRATION_CODE_KEY,
                StringUtils.hasText(configuredRegistrationCode) ? configuredRegistrationCode : ""
        );
        String storedRegistrationCode = cleanValue(
                systemSettingMapper.selectSettingValueForUpdate(REGISTRATION_CODE_KEY)
        );
        if (StringUtils.hasText(storedRegistrationCode)) {
            return databaseSetting(storedRegistrationCode);
        }
        if (StringUtils.hasText(configuredRegistrationCode)) {
            updateRegistrationCode(configuredRegistrationCode);
            return databaseSetting(configuredRegistrationCode);
        }
        return new RegistrationCodeSetting(null, "NONE", null, null);
    }

    public String rotateRegistrationCode() {
        return rotateRegistrationCode(null, null);
    }

    public String rotateRegistrationCode(Long inviterUserId, String inviterUsername) {
        String registrationCode = generateCode();
        updateRegistrationCode(registrationCode);
        updateInviter(inviterUserId, inviterUsername);
        return registrationCode;
    }

    private RegistrationCodeSetting databaseSetting(String registrationCode) {
        Long inviterUserId = parseUserId(systemSettingMapper.selectSettingValue(
                REGISTRATION_CODE_INVITER_USER_ID_KEY
        ));
        String inviterUsername = cleanValue(systemSettingMapper.selectSettingValue(
                REGISTRATION_CODE_INVITER_USERNAME_KEY
        ));
        if (inviterUserId == null || !StringUtils.hasText(inviterUsername)) {
            return new RegistrationCodeSetting(registrationCode, "DATABASE", null, null);
        }
        return new RegistrationCodeSetting(
                registrationCode,
                "DATABASE",
                inviterUserId,
                inviterUsername
        );
    }

    private void updateInviter(Long inviterUserId, String inviterUsername) {
        boolean hasInviter = inviterUserId != null
                && inviterUserId > 0
                && StringUtils.hasText(inviterUsername);
        systemSettingMapper.upsertSetting(
                REGISTRATION_CODE_INVITER_USER_ID_KEY,
                hasInviter ? inviterUserId.toString() : ""
        );
        systemSettingMapper.upsertSetting(
                REGISTRATION_CODE_INVITER_USERNAME_KEY,
                hasInviter ? inviterUsername.trim() : ""
        );
    }

    private Long parseUserId(String value) {
        String cleaned = cleanValue(value);
        if (!StringUtils.hasText(cleaned)) {
            return null;
        }
        try {
            long parsed = Long.parseLong(cleaned);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String generateCode() {
        StringBuilder code = new StringBuilder(
                REGISTRATION_CODE_GROUP_COUNT * REGISTRATION_CODE_GROUP_SIZE
                        + REGISTRATION_CODE_GROUP_COUNT - 1
        );
        for (int groupIndex = 0; groupIndex < REGISTRATION_CODE_GROUP_COUNT; groupIndex++) {
            if (groupIndex > 0) {
                code.append('-');
            }
            for (int characterIndex = 0; characterIndex < REGISTRATION_CODE_GROUP_SIZE; characterIndex++) {
                code.append(REGISTRATION_CODE_ALPHABET.charAt(
                        secureRandom.nextInt(REGISTRATION_CODE_ALPHABET.length())
                ));
            }
        }
        return code.toString();
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
            String source,
            Long inviterUserId,
            String inviterUsername
    ) {
    }
}
