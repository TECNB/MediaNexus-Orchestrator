package com.medianexus.orchestrator.service.settings.targets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.accounts.AccountDtos.*;
import com.medianexus.orchestrator.integration.onboarding.*;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import com.medianexus.orchestrator.service.AuthService;
import com.medianexus.orchestrator.service.SettingsCipher;
import com.medianexus.orchestrator.service.SettingsService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class DownloadAccountServiceTest {
    @Test
    void registeredAccountReadsOnlyExposeWhitelistedBindingFields() throws Exception {
        ObjectMapper json = new ObjectMapper(); Map<String, String> rows = new HashMap<>();
        SystemSettingMapper mapper = mock(SystemSettingMapper.class);
        when(mapper.selectSettingValue(anyString())).thenAnswer(call -> rows.get(call.getArgument(0)));
        String form = """
                {"task_name":"private-as-body","media_dir":"/mount/main/Movie","symlink_creator":true,
                 "metadata_copyer":true,"num_threads":"4","symlink_dir_checker":false,"symlink_checker":false,
                 "metadata_checker":false,"symlink_size":"0","symlink_ext":".strm","metadata_ext":".nfo"}
                """;
        var target = new IngestTargetState.Target("main", "主账号", "/main", "/Cloud", "/mount/main", true,
                List.of(new IngestTargetState.Binding("MOVIE", "/main/Movie", null, "real-as-uuid", form, "/strm/main/Movie", "library-1")));
        rows.put(IngestTargetRegistry.SETTING_KEY, json.writeValueAsString(new IngestTargetState(1, 1, true, List.of(target), Map.of("MOVIE", "main"))));
        String now = java.time.Instant.now().toString();
        rows.put("target_observation_main", json.writeValueAsString(new OnboardingRecords.Observation(11, "/main", "WebDAV", "/Cloud", "/mount/main",
                Map.of("library-1", "Movies"), new Capacity(null, null, null, null, "UNAVAILABLE", "尚未观测"), List.of(), now, now)));
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        AuthService auth = mock(AuthService.class); IngestTargetRegistry registry = new IngestTargetRegistry(mapper, json);
        DownloadAccountService service = new DownloadAccountService(auth, mock(SettingsService.class), mock(SettingsCipher.class), registry,
                new TargetRegistration(mapper, json, registry, manager), new OnboardingRecords(mapper, json, registry, manager), json,
                mock(OpenListAccountClient.class), mock(CloudDriveAccountClient.class), mock(AutoSymlinkAccountClient.class), mock(EmbyAccountLibraryClient.class), mock(OnboardingExecutor.class));
        String list = json.writeValueAsString(service.list()); String detail = json.writeValueAsString(service.detail("main"));
        assertThat(list).contains("主账号", "real-as-uuid", "library-1").doesNotContain("private-as-body", "asRequestBodyJson", "password", "addition", "refreshToken");
        assertThat(detail).doesNotContain("private-as-body", "asRequestBodyJson", "claimOwner", "protectedPayload");
        verify(auth, times(2)).requireAdminUser();
    }

    @Test
    void missingRealPrerequisitesProduceDurableBlockedPlanWithEncryptedAuthorizationAndNoUpstreamWrites() throws Exception {
        ObjectMapper json = new ObjectMapper(); Map<String, String> rows = new HashMap<>();
        SystemSettingMapper mapper = mock(SystemSettingMapper.class);
        when(mapper.selectSettingValue(anyString())).thenAnswer(call -> rows.get(call.getArgument(0)));
        when(mapper.selectSettingValueForUpdate(anyString())).thenAnswer(call -> rows.get(call.getArgument(0)));
        doAnswer(call -> { rows.putIfAbsent(call.getArgument(0), call.getArgument(1)); return null; })
                .when(mapper).insertSettingIfAbsent(anyString(), anyString());
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        AuthService auth = mock(AuthService.class); SettingsService settings = mock(SettingsService.class);
        when(settings.effectiveFingerprint(anyString())).thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "not ready"));
        SettingsCipher cipher = new SettingsCipher(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        IngestTargetRegistry registry = new IngestTargetRegistry(mapper, json);
        OnboardingRecords records = new OnboardingRecords(mapper, json, registry, manager);
        OpenListAccountClient ol = mock(OpenListAccountClient.class); CloudDriveAccountClient cd = mock(CloudDriveAccountClient.class);
        AutoSymlinkAccountClient as = mock(AutoSymlinkAccountClient.class); EmbyAccountLibraryClient em = mock(EmbyAccountLibraryClient.class);
        DownloadAccountService service = new DownloadAccountService(auth, settings, cipher, registry,
                new TargetRegistration(mapper, json, registry, manager), records, json, ol, cd, as, em, mock(OnboardingExecutor.class));
        String token = "private-pikpak-authorization";
        OnboardingPlan plan = service.previewOnboarding(new OnboardingPlanRequest(0L, "新账号", List.of("MOVIE"),
                new PikPakAuthorization("web", null, null, token, null, null), null));
        assertThat(plan.canExecute()).isFalse();
        assertThat(plan.issues()).extracting(Issue::code).contains("MASTER_NOT_ADOPTED", "EFFECTIVE_PREREQUISITES_UNAVAILABLE");
        assertThat(json.writeValueAsString(plan)).doesNotContain(token, "refreshToken", "protectedPayload", "asRequestBodyJson");
        assertThat(rows.values()).allMatch(value -> !value.contains(token));
        var persisted = records.plan(plan.id());
        assertThat(cipher.decrypt("onboarding-plan/" + plan.id(), persisted.protectedPayload())).contains(token);
        assertThat(service.plan(plan.id())).isEqualTo(plan);
        assertThat(service.list().accounts()).isEmpty();
        verifyNoInteractions(ol, cd, as, em); verify(auth, atLeastOnce()).requireAdminUser();
    }
}
