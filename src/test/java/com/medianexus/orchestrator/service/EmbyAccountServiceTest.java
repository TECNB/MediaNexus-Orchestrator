package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyClientException;
import com.medianexus.orchestrator.integration.emby.EmbyUserAccount;
import com.medianexus.orchestrator.model.User;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbyAccountServiceTest {

    @Test
    void provisionsFromCsyPolicyAndDerivesTheSameEightCharacterPassword() {
        TestEmbyClient client = new TestEmbyClient();
        EmbyProperties properties = properties();
        EmbyUserAccount template = new EmbyUserAccount("template-id", "csy", false, false);
        client.users = List.of(template);
        EmbyAccountService service = new EmbyAccountService(client, properties);
        User user = user(42L, "alice", null);

        String embyUserId = service.provisionUser(user);

        assertThat(embyUserId).isEqualTo("emby-alice");
        assertThat(client.createdUsername).isEqualTo("alice");
        assertThat(client.templateUserId).isEqualTo("template-id");
        assertThat(client.updatedPassword).matches("[A-HJ-NP-Z2-9]{8}");

        user.setEmbyUserId(embyUserId);
        assertThat(service.credentialsFor(user).password()).isEqualTo(client.updatedPassword);
    }

    @Test
    void rejectsAnExistingEmbyUsernameWithoutTakingItOver() {
        TestEmbyClient client = new TestEmbyClient();
        EmbyUserAccount template = new EmbyUserAccount("template-id", "csy", false, false);
        EmbyUserAccount existing = new EmbyUserAccount("existing-id", "Alice", false, false);
        client.users = List.of(template, existing);
        EmbyAccountService service = new EmbyAccountService(client, properties());

        assertThatThrownBy(() -> service.provisionUser(user(42L, "alice", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Emby 已存在同名用户，请联系管理员");
        assertThat(client.createdUsername).isNull();
    }

    @Test
    void leavesLegacyUsersUnmanagedWithoutRequiringThePasswordSecret() {
        EmbyProperties properties = new EmbyProperties();
        EmbyAccountService service = new EmbyAccountService(new TestEmbyClient(), properties);

        assertThat(service.credentialsFor(user(7L, "legacy", null)).managed()).isFalse();
    }

    @Test
    void deletesThePartiallyCreatedEmbyUserWhenPasswordSetupFails() {
        TestEmbyClient client = new TestEmbyClient();
        client.users = List.of(new EmbyUserAccount("template-id", "csy", false, false));
        client.failPasswordUpdate = true;
        EmbyAccountService service = new EmbyAccountService(client, properties());

        assertThatThrownBy(() -> service.provisionUser(user(42L, "alice", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Emby 账号创建失败，请稍后重试");
        assertThat(client.deletedUserId).isEqualTo("emby-alice");
    }

    @Test
    void deletesManagedUsersButLeavesLegacyUsersWithoutAnEmbyIdAlone() {
        TestEmbyClient client = new TestEmbyClient();
        EmbyAccountService service = new EmbyAccountService(client, properties());

        assertThat(service.deleteManagedUser(user(42L, "alice", "emby-alice"))).isTrue();
        assertThat(client.deletedUserId).isEqualTo("emby-alice");
        client.deletedUserId = null;

        assertThat(service.deleteManagedUser(user(7L, "legacy", null))).isFalse();
        assertThat(client.deletedUserId).isNull();
    }

    private EmbyProperties properties() {
        EmbyProperties properties = new EmbyProperties();
        properties.setRegistrationPasswordSecret("0123456789abcdef0123456789abcdef");
        properties.setRegistrationTemplateUsername("csy");
        return properties;
    }

    private User user(Long id, String username, String embyUserId) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmbyUserId(embyUserId);
        return user;
    }

    private static class TestEmbyClient extends EmbyClient {

        private List<EmbyUserAccount> users = List.of();
        private String createdUsername;
        private String templateUserId;
        private String updatedPassword;
        private String deletedUserId;
        private boolean failPasswordUpdate;

        private TestEmbyClient() {
            super(new EmbyProperties(), new ObjectMapper());
        }

        @Override
        public List<EmbyUserAccount> listUsers() {
            return users;
        }

        @Override
        public EmbyUserAccount createUserFromTemplate(String username, String templateUserId) {
            this.createdUsername = username;
            this.templateUserId = templateUserId;
            return new EmbyUserAccount("emby-alice", username, false, false);
        }

        @Override
        public void updateUserPassword(String userId, String password) {
            if (failPasswordUpdate) {
                throw new EmbyClientException("password update failed");
            }
            this.updatedPassword = password;
        }

        @Override
        public void deleteUser(String userId) {
            this.deletedUserId = userId;
        }
    }
}
