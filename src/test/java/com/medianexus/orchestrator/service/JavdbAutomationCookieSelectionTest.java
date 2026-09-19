package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.javdb.JavdbClient;
import com.medianexus.orchestrator.mapper.AdultMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.JavdbAutomationLedgerMapper;
import com.medianexus.orchestrator.mapper.JavdbAutomationRunItemMapper;
import com.medianexus.orchestrator.mapper.JavdbAutomationRunLogMapper;
import com.medianexus.orchestrator.mapper.JavdbAutomationRunMapper;
import com.medianexus.orchestrator.mapper.JavdbPlaylistHistoryMapper;
import com.medianexus.orchestrator.mapper.JavdbPlaylistMembershipMapper;
import com.medianexus.orchestrator.mapper.JavdbPlaylistSyncRunMapper;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JavdbAutomationCookieSelectionTest {

    @Test
    void usesMemberCookieOnlyForFc2Details() {
        SystemSettingMapper settings = mock(SystemSettingMapper.class);
        when(settings.selectSettingValue("javdb_top_cookie")).thenReturn("member-cookie");
        JavdbAutomationService service = new JavdbAutomationService(
                mock(AuthService.class), settings, mock(JavdbClient.class), mock(EmbyClient.class),
                mock(AdultMagnetIngestService.class), mock(AdultMagnetIngestTaskMapper.class),
                mock(JavdbAutomationRunMapper.class), mock(JavdbAutomationRunItemMapper.class),
                mock(JavdbAutomationLedgerMapper.class), mock(JavdbAutomationRunLogMapper.class),
                mock(JavdbPlaylistMembershipMapper.class), mock(JavdbPlaylistSyncRunMapper.class),
                mock(JavdbPlaylistHistoryMapper.class), new ObjectMapper()
        );

        String fc2Cookie = ReflectionTestUtils.invokeMethod(
                service, "detailCookie", "FC2-4828968", "regular-cookie");
        String regularCookie = ReflectionTestUtils.invokeMethod(
                service, "detailCookie", "ABC-123", "regular-cookie");

        assertThat(fc2Cookie).isEqualTo("member-cookie");
        assertThat(regularCookie).isEqualTo("regular-cookie");
    }
}
