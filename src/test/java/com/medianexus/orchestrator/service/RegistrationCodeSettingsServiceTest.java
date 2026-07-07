package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.config.AuthProperties;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
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

        RegistrationCodeSettingsService service = new RegistrationCodeSettingsService(mapper, authProperties);

        RegistrationCodeSettingsService.RegistrationCodeSetting setting =
                service.resolveEffectiveRegistrationCode();

        assertThat(setting.registrationCode()).isEqualTo("DATABASE-CODE");
        assertThat(setting.source()).isEqualTo("DATABASE");
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
    void adminGeneratePersistsNewCode() {
        TestRegistrationCodeSettingsService settingsService = new TestRegistrationCodeSettingsService();
        TestAuthService authService = new TestAuthService();
        TestUserAdminAuditService auditService = new TestUserAdminAuditService();
        User admin = new User();
        admin.setId(7L);
        authService.admin = admin;

        AdminRegistrationCodeService service = new AdminRegistrationCodeService(
                settingsService,
                authService,
                auditService
        );

        var response = service.generateRegistrationCode();

        assertThat(response.registrationCode()).matches("[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){3}");
        assertThat(response.source()).isEqualTo("DATABASE");
        assertThat(settingsService.updatedRegistrationCode).isEqualTo(response.registrationCode());
        assertThat(auditService.adminUserId).isEqualTo(7L);
        assertThat(auditService.actionType).isEqualTo(UserAdminAuditService.ACTION_GENERATE_REGISTRATION_CODE);
    }

    private static class TestRegistrationCodeSettingsService extends RegistrationCodeSettingsService {

        private String updatedRegistrationCode;

        private TestRegistrationCodeSettingsService() {
            super(null, null);
        }

        @Override
        public RegistrationCodeSetting resolveEffectiveRegistrationCode() {
            return new RegistrationCodeSetting("OLD-CODE", "CONFIG");
        }

        @Override
        public void updateRegistrationCode(String registrationCode) {
            this.updatedRegistrationCode = registrationCode;
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
        private String actionType;

        private TestUserAdminAuditService() {
            super(null);
        }

        @Override
        public void record(Long adminUserId, Long targetUserId, String actionType, String oldValue, String newValue) {
            this.adminUserId = adminUserId;
            this.actionType = actionType;
        }
    }
}
