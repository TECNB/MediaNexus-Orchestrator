package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.dto.admin.response.AdminRegistrationCodeResponse;
import com.medianexus.orchestrator.model.User;
import java.security.SecureRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AdminRegistrationCodeService {

    private static final String REGISTRATION_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int REGISTRATION_CODE_GROUP_SIZE = 4;
    private static final int REGISTRATION_CODE_GROUP_COUNT = 4;

    private final RegistrationCodeSettingsService registrationCodeSettingsService;
    private final AuthService authService;
    private final UserAdminAuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminRegistrationCodeService(
            RegistrationCodeSettingsService registrationCodeSettingsService,
            AuthService authService,
            UserAdminAuditService auditService
    ) {
        this.registrationCodeSettingsService = registrationCodeSettingsService;
        this.authService = authService;
        this.auditService = auditService;
    }

    public AdminRegistrationCodeResponse getCurrentRegistrationCode() {
        authService.requireAdminUser();
        return toResponse(registrationCodeSettingsService.resolveEffectiveRegistrationCode());
    }

    @Transactional
    public AdminRegistrationCodeResponse generateRegistrationCode() {
        User admin = authService.requireAdminUser();
        RegistrationCodeSettingsService.RegistrationCodeSetting previous =
                registrationCodeSettingsService.resolveEffectiveRegistrationCode();
        String registrationCode = generateCode();
        registrationCodeSettingsService.updateRegistrationCode(registrationCode);
        auditService.record(
                admin.getId(),
                null,
                UserAdminAuditService.ACTION_GENERATE_REGISTRATION_CODE,
                auditValue(previous.registrationCode(), previous.source()),
                auditValue(registrationCode, "DATABASE")
        );
        return new AdminRegistrationCodeResponse(registrationCode, "DATABASE");
    }

    private AdminRegistrationCodeResponse toResponse(
            RegistrationCodeSettingsService.RegistrationCodeSetting setting
    ) {
        return new AdminRegistrationCodeResponse(setting.registrationCode(), setting.source());
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

    private String auditValue(String registrationCode, String source) {
        if (!StringUtils.hasText(registrationCode)) {
            return "registration_code=blank;source=" + source;
        }
        return "registration_code=" + mask(registrationCode) + ";source=" + source;
    }

    private String mask(String registrationCode) {
        String trimmed = registrationCode.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        return "****" + trimmed.substring(trimmed.length() - 4);
    }
}
