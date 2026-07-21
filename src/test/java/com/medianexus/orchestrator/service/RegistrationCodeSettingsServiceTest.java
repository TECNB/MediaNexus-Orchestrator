package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.config.AuthProperties;
import com.medianexus.orchestrator.dto.admin.request.AdminRegistrationCodeGenerateRequest;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.User;
import org.junit.jupiter.api.Test;

class RegistrationCodeSettingsServiceTest {

    @Test
    void databaseRegistrationCodeWinsOverConfiguredCode() {
        SystemSettingMapper mapper = mock(SystemSettingMapper.class);
        AuthProperties authProperties = new AuthProperties();
        authProperties.setRegistrationCode("CONFIG-CODE");
        when(mapper.selectSettingValue(RegistrationCodeSettingsService.REGISTRATION_CODE_KEY))
                .thenReturn("'DATABASE-CODE'");
        when(mapper.selectSettingValue(RegistrationCodeSettingsService.REGISTRATION_CODE_INVITER_USER_ID_KEY))
                .thenReturn("12");
        when(mapper.selectSettingValue(RegistrationCodeSettingsService.REGISTRATION_CODE_INVITER_USERNAME_KEY))
                .thenReturn("friend");

        RegistrationCodeSettingsService service = new RegistrationCodeSettingsService(mapper, authProperties);

        RegistrationCodeSettingsService.RegistrationCodeSetting setting =
                service.resolveEffectiveRegistrationCode();

        assertThat(setting.registrationCode()).isEqualTo("DATABASE-CODE");
        assertThat(setting.source()).isEqualTo("DATABASE");
        assertThat(setting.inviterUserId()).isEqualTo(12L);
        assertThat(setting.inviterUsername()).isEqualTo("friend");
    }

    @Test
    void configuredRegistrationCodeIsFallback() {
        SystemSettingMapper mapper = mock(SystemSettingMapper.class);
        AuthProperties authProperties = new AuthProperties();
        authProperties.setRegistrationCode("\"CONFIG-CODE\"");
        when(mapper.selectSettingValue(RegistrationCodeSettingsService.REGISTRATION_CODE_KEY))
                .thenReturn(" ");

        RegistrationCodeSettingsService service = new RegistrationCodeSettingsService(mapper, authProperties);

        RegistrationCodeSettingsService.RegistrationCodeSetting setting =
                service.resolveEffectiveRegistrationCode();

        assertThat(setting.registrationCode()).isEqualTo("CONFIG-CODE");
        assertThat(setting.source()).isEqualTo("CONFIG");
    }

    @Test
    void rotatingRegistrationCodePersistsAndClearsInviterBinding() {
        SystemSettingMapper mapper = mock(SystemSettingMapper.class);
        RegistrationCodeSettingsService service = new RegistrationCodeSettingsService(
                mapper,
                new AuthProperties()
        );

        service.rotateRegistrationCode(12L, " friend ");

        verify(mapper).upsertSetting(
                RegistrationCodeSettingsService.REGISTRATION_CODE_INVITER_USER_ID_KEY,
                "12"
        );
        verify(mapper).upsertSetting(
                RegistrationCodeSettingsService.REGISTRATION_CODE_INVITER_USERNAME_KEY,
                "friend"
        );

        service.rotateRegistrationCode();

        verify(mapper).upsertSetting(
                RegistrationCodeSettingsService.REGISTRATION_CODE_INVITER_USER_ID_KEY,
                ""
        );
        verify(mapper).upsertSetting(
                RegistrationCodeSettingsService.REGISTRATION_CODE_INVITER_USERNAME_KEY,
                ""
        );
    }

    @Test
    void adminGeneratePersistsNewCode() {
        TestRegistrationCodeSettingsService settingsService = new TestRegistrationCodeSettingsService();
        TestAuthService authService = new TestAuthService();
        TestUserAdminAuditService auditService = new TestUserAdminAuditService();
        UserMapper userMapper = mock(UserMapper.class);
        User admin = new User();
        admin.setId(7L);
        authService.admin = admin;
        User inviter = new User();
        inviter.setId(12L);
        inviter.setUsername("friend");
        when(userMapper.selectById(12L)).thenReturn(inviter);

        AdminRegistrationCodeService service = new AdminRegistrationCodeService(
                settingsService,
                authService,
                auditService,
                userMapper
        );

        var response = service.generateRegistrationCode(new AdminRegistrationCodeGenerateRequest(12L));

        assertThat(response.registrationCode()).matches("[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){3}");
        assertThat(response.source()).isEqualTo("DATABASE");
        assertThat(settingsService.updatedRegistrationCode).isEqualTo(response.registrationCode());
        assertThat(settingsService.inviterUserId).isEqualTo(12L);
        assertThat(settingsService.inviterUsername).isEqualTo("friend");
        assertThat(response.inviterUserId()).isEqualTo(12L);
        assertThat(response.inviterUsername()).isEqualTo("friend");
        assertThat(auditService.adminUserId).isEqualTo(7L);
        assertThat(auditService.targetUserId).isEqualTo(12L);
        assertThat(auditService.actionType).isEqualTo(UserAdminAuditService.ACTION_GENERATE_REGISTRATION_CODE);
    }

    private static class TestRegistrationCodeSettingsService extends RegistrationCodeSettingsService {

        private String updatedRegistrationCode;
        private Long inviterUserId;
        private String inviterUsername;

        private TestRegistrationCodeSettingsService() {
            super(null, null);
        }

        @Override
        public RegistrationCodeSetting resolveEffectiveRegistrationCode() {
            return new RegistrationCodeSetting("OLD-CODE", "CONFIG", null, null);
        }

        @Override
        public String rotateRegistrationCode(Long inviterUserId, String inviterUsername) {
            this.updatedRegistrationCode = "ABCD-EFGH-JKLM-NPQR";
            this.inviterUserId = inviterUserId;
            this.inviterUsername = inviterUsername;
            return updatedRegistrationCode;
        }
    }

    private static class TestAuthService extends AuthService {

        private User admin;

        private TestAuthService() {
            super(null, (AuthProperties) null, null);
        }

        @Override
        public User requireAdminUser() {
            return admin;
        }
    }

    private static class TestUserAdminAuditService extends UserAdminAuditService {

        private Long adminUserId;
        private Long targetUserId;
        private String actionType;

        private TestUserAdminAuditService() {
            super(null);
        }

        @Override
        public void record(Long adminUserId, Long targetUserId, String actionType, String oldValue, String newValue) {
            this.adminUserId = adminUserId;
            this.targetUserId = targetUserId;
            this.actionType = actionType;
        }
    }
}
