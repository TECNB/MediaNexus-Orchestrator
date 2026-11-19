package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.*;
import com.medianexus.orchestrator.dto.settings.SettingsDtos.*;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import jakarta.validation.Validation;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class GuangyaCredentialsTest {
    private final Map<String, String> rows = new ConcurrentHashMap<>();
    private final SystemSettingMapper database = mock(SystemSettingMapper.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final String key = Base64.getEncoder().encodeToString(new byte[32]);

    private SettingsService settings(String encryptionKey) {
        when(database.selectSettingValue(anyString())).thenAnswer(i -> rows.get(i.getArgument(0)));
        when(database.selectSettingValueForUpdate(anyString())).thenAnswer(i -> rows.get(i.getArgument(0)));
        doAnswer(i -> { rows.putIfAbsent(i.getArgument(0), i.getArgument(1)); return null; })
                .when(database).insertSettingIfAbsent(anyString(), anyString());
        when(database.compareAndSetSetting(anyString(), anyString(), anyString())).thenAnswer(i ->
                rows.replace(i.getArgument(0), i.getArgument(1), i.getArgument(2)) ? 1 : 0);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new SettingsService(database, json, Validation.buildDefaultValidatorFactory().getValidator(), transactions,
                new TmdbProperties(), new SubtitleUploadProperties(), new UserQuotaProperties(), new SettingsCipher(encryptionKey));
    }
    private Update login(long version, String access, String refresh, String device) {
        return new Update(version, Map.of(), Map.of("accessToken", new SecretInput("REPLACE", access),
                "refreshToken", new SecretInput("REPLACE", refresh), "deviceId", new SecretInput("REPLACE", device)));
    }
    @Test void storesAllCredentialsEncryptedAndHotReloadsAfterRestartWithoutDeploymentSecrets() throws Exception {
        SettingsService settings = settings(key);
        assertThat(new GuangyaCredentials(settings).snapshot().configured()).isFalse();
        var saved = settings.save("guangya", login(0, "private-access", "private-refresh", "private-device"));
        assertThat(saved.applyMode()).isEqualTo("HOT");
        assertThat(json.writeValueAsString(saved)).doesNotContain("private-access", "private-refresh", "private-device");
        assertThat(rows.get("managed_settings_guangya")).doesNotContain("private-access", "private-refresh", "private-device").contains("gcm1:");
        var credentials = new GuangyaCredentials(settings(key));
        assertThat(credentials.snapshot()).isEqualTo(new GuangyaCredentials.Snapshot(1, "private-access", "private-refresh", "private-device"));
        assertThat(credentials.snapshot().toString()).doesNotContain("private-access", "private-refresh", "private-device");
        settings.save("guangya", login(1, "changed-access", "changed-refresh", "stable-device"));
        assertThat(credentials.snapshot().accessToken()).isEqualTo("changed-access");
        settings.restore("guangya", 2L);
        assertThat(credentials.snapshot().configured()).isFalse();
    }
    @Test void pairRotationIsAtomicAndStaleRefreshCannotOverwriteAdminOrNewerRotation() {
        SettingsService settings = settings(key);
        settings.save("guangya", login(0, "old-access", "old-refresh", "device"));
        GuangyaCredentials credentials = new GuangyaCredentials(settings);
        var old = credentials.snapshot();
        var rotated = new GuangyaCredentials.Snapshot(2, "rotated-access", "rotated-refresh", "device");
        credentials.rotate(old, rotated);
        assertThat(credentials.snapshot()).isEqualTo(rotated);
        assertThatThrownBy(() -> credentials.rotate(old, rotated)).hasMessageContaining("版本");
        settings.save("guangya", login(2, "admin-access", "admin-refresh", "new-device"));
        assertThatThrownBy(() -> credentials.rotate(rotated,
                new GuangyaCredentials.Snapshot(3, "stale-access", "stale-refresh", "device"))).hasMessageContaining("版本");
        assertThat(credentials.snapshot().accessToken()).isEqualTo("admin-access");
    }
    @Test void databaseCasFailureAndMissingEncryptionKeyAreExplicit() {
        SettingsService settings = settings(key);
        settings.save("guangya", login(0, "old-access", "old-refresh", "device"));
        var credentials = new GuangyaCredentials(settings);
        var observed = credentials.snapshot();
        when(database.compareAndSetSetting(anyString(), anyString(), anyString())).thenReturn(0);
        assertThatThrownBy(() -> credentials.rotate(observed,
                new GuangyaCredentials.Snapshot(2, "new-access", "new-refresh", "device"))).hasMessageContaining("版本");
        assertThat(credentials.snapshot()).isEqualTo(observed);
        assertThatThrownBy(() -> settings("").guangyaCredentials()).hasMessageContaining("加密密钥");
    }
    @Test void rejectsPartialOrPlaintextCredentialInputAndCheckNeverClaimsAuthenticationSucceeded() {
        SettingsService settings = settings(key);
        assertThat(settings.check("guangya", null).status()).isEqualTo("NOT_CONFIGURED");
        assertThatThrownBy(() -> settings.save("guangya", new Update(0L, Map.of("accessToken", "plaintext"), Map.of())))
                .hasMessageContaining("普通可写字段");
        assertThatThrownBy(() -> settings.save("guangya", new Update(0L, Map.of(), Map.of("accessToken", new SecretInput("REPLACE", "only-access")))))
                .hasMessageContaining("同时配置");
        settings.save("guangya", login(0, "access", "refresh", "device"));
        var probe = settings.check("guangya", null);
        assertThat(probe.status()).isEqualTo("UNSUPPORTED");
        assertThat(probe.message()).contains("认证将在只读解析时核验");
        assertThat(probe.resources()).isEmpty();
    }
}
