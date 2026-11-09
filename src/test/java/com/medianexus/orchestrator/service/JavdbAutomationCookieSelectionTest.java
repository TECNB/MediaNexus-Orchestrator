package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.dto.javdb.request.JavdbCookieUpdateRequest;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.javdb.JavdbClient;
import com.medianexus.orchestrator.integration.javdb.JavdbClientException;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.JavdbAutomationLedgerMapper;
import com.medianexus.orchestrator.mapper.JavdbAutomationRunItemMapper;
import com.medianexus.orchestrator.mapper.JavdbAutomationRunLogMapper;
import com.medianexus.orchestrator.mapper.JavdbAutomationRunMapper;
import com.medianexus.orchestrator.mapper.JavdbPlaylistHistoryMapper;
import com.medianexus.orchestrator.mapper.JavdbPlaylistMembershipMapper;
import com.medianexus.orchestrator.mapper.JavdbPlaylistSyncRunMapper;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JavdbAutomationCookieSelectionTest {

    @Test
    void usesMemberCookieOnlyForFc2Details() {
        SystemSettingMapper settings = mock(SystemSettingMapper.class);
        when(settings.selectSettingValue("javdb_top_cookie")).thenReturn("member-cookie");
        JavdbAutomationService service = service(settings, mock(JavdbClient.class), mock(IngestExceptionNotifier.class));

        String fc2Cookie = ReflectionTestUtils.invokeMethod(
                service, "detailCookie", "FC2-4828968", "regular-cookie");
        String regularCookie = ReflectionTestUtils.invokeMethod(
                service, "detailCookie", "ABC-123", "regular-cookie");

        assertThat(fc2Cookie).isEqualTo("member-cookie");
        assertThat(regularCookie).isEqualTo("regular-cookie");
    }

    @Test
    void alertsOncePerCredentialFailureAndIgnoresUpstreamErrors() {
        SystemSettingMapper settings = mock(SystemSettingMapper.class);
        Map<String, String> stored = new HashMap<>();
        when(settings.selectSettingValue(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
        doAnswer(call -> {
            stored.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(settings).upsertSetting(anyString(), anyString());
        JavdbClient client = mock(JavdbClient.class);
        IngestExceptionNotifier notifier = mock(IngestExceptionNotifier.class);
        JavdbAutomationService service = service(settings, client, notifier);
        JavdbClientException expired = new JavdbClientException(JavdbClientException.Reason.AUTHENTICATION, "expired");
        doThrow(expired).when(client).validate("expired");
        doThrow(expired).when(client).validateTop("expired");
        doThrow(new JavdbClientException(JavdbClientException.Reason.UPSTREAM, "limited"))
                .when(client).validate("limited");

        service.updateCookie(new JavdbCookieUpdateRequest("expired"));
        service.updateCookie(new JavdbCookieUpdateRequest("expired"));
        service.updateTopCookie(new JavdbCookieUpdateRequest("expired"));
        service.updateCookie(new JavdbCookieUpdateRequest("limited"));

        verify(notifier).send(eq("javdb_automation_cookie_validation:null"),
                eq("JAVDB 常规 Cookie 认证异常"), anyString(), eq("/automation"));
        verify(notifier).send(eq("javdb_top_cookie_validation:null"),
                eq("JAVDB Top 250 Cookie 认证异常"), anyString(), eq("/automation"));
        verifyNoMoreInteractions(notifier);
    }

    private JavdbAutomationService service(SystemSettingMapper settings, JavdbClient client,
                                          IngestExceptionNotifier notifier) {
        return new JavdbAutomationService(
                mock(AuthService.class), settings, client, mock(EmbyClient.class),
                mock(AdultMagnetIngestService.class), mock(AdultMagnetIngestTaskMapper.class),
                mock(JavdbAutomationRunMapper.class), mock(JavdbAutomationRunItemMapper.class),
                mock(JavdbAutomationLedgerMapper.class), mock(JavdbAutomationRunLogMapper.class),
                mock(JavdbPlaylistMembershipMapper.class), mock(JavdbPlaylistSyncRunMapper.class),
                mock(JavdbPlaylistHistoryMapper.class), notifier, new ObjectMapper()
        );
    }
}
