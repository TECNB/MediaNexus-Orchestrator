package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.admin.request.AdminRegistrationCodeGenerateRequest;
import com.medianexus.orchestrator.dto.admin.response.AdminRegistrationCodeResponse;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AdminRegistrationCodeService {

    private final RegistrationCodeSettingsService registrationCodeSettingsService;
    private final AuthService authService;
    private final UserAdminAuditService auditService;
    private final UserMapper userMapper;

    public AdminRegistrationCodeService(
            RegistrationCodeSettingsService registrationCodeSettingsService,
            AuthService authService,
            UserAdminAuditService auditService,
            UserMapper userMapper
    ) {
        this.registrationCodeSettingsService = registrationCodeSettingsService;
        this.authService = authService;
        this.auditService = auditService;
        this.userMapper = userMapper;
    }

    @Transactional(readOnly = true)
    public AdminRegistrationCodeResponse getCurrentRegistrationCode() {
        authService.requireAdminUser();
        return toResponse(registrationCodeSettingsService.resolveEffectiveRegistrationCode());
    }

    @Transactional
    public AdminRegistrationCodeResponse generateRegistrationCode(
            AdminRegistrationCodeGenerateRequest request
    ) {
        User admin = authService.requireAdminUser();
        User inviter = resolveInviter(request == null ? null : request.inviterUserId());
        RegistrationCodeSettingsService.RegistrationCodeSetting previous =
                registrationCodeSettingsService.lockEffectiveRegistrationCode();
        String registrationCode = registrationCodeSettingsService.rotateRegistrationCode(
                inviter == null ? null : inviter.getId(),
                inviter == null ? null : inviter.getUsername()
        );
        auditService.record(
                admin.getId(),
                inviter == null ? null : inviter.getId(),
                UserAdminAuditService.ACTION_GENERATE_REGISTRATION_CODE,
                auditValue(
                        previous.registrationCode(),
                        previous.source(),
                        previous.inviterUserId(),
                        previous.inviterUsername()
                ),
                auditValue(
                        registrationCode,
                        "DATABASE",
                        inviter == null ? null : inviter.getId(),
                        inviter == null ? null : inviter.getUsername()
                )
        );
        return new AdminRegistrationCodeResponse(
                registrationCode,
                "DATABASE",
                inviter == null ? null : inviter.getId(),
                inviter == null ? null : inviter.getUsername()
        );
    }

    private AdminRegistrationCodeResponse toResponse(
            RegistrationCodeSettingsService.RegistrationCodeSetting setting
    ) {
        return new AdminRegistrationCodeResponse(
                setting.registrationCode(),
                setting.source(),
                setting.inviterUserId(),
                setting.inviterUsername()
        );
    }

    private User resolveInviter(Long inviterUserId) {
        if (inviterUserId == null) {
            return null;
        }
        User inviter = userMapper.selectById(inviterUserId);
        if (inviter == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邀请人用户不存在");
        }
        return inviter;
    }

    private String auditValue(
            String registrationCode,
            String source,
            Long inviterUserId,
            String inviterUsername
    ) {
        String inviterValue = inviterUserId == null || !StringUtils.hasText(inviterUsername)
                ? "none"
                : inviterUserId + ":" + inviterUsername;
        if (!StringUtils.hasText(registrationCode)) {
            return "registration_code=blank;source=" + source + ";inviter=" + inviterValue;
        }
        return "registration_code=" + mask(registrationCode)
                + ";source=" + source
                + ";inviter=" + inviterValue;
    }

    private String mask(String registrationCode) {
        String trimmed = registrationCode.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        return "****" + trimmed.substring(trimmed.length() - 4);
    }
}
