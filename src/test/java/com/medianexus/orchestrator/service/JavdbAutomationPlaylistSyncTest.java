package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.dto.javdb.response.JavdbPlaylistSyncRunResponse;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyItem;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import com.medianexus.orchestrator.integration.emby.EmbyPlaylist;
import com.medianexus.orchestrator.integration.emby.EmbyUserAccount;
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
import com.medianexus.orchestrator.model.JavdbPlaylistMembership;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class JavdbAutomationPlaylistSyncTest {

    @Test
    void reconcilesAddedExistingAndWaitingItemsIdempotently() {
        AuthService authService = mock(AuthService.class);
        SystemSettingMapper systemSettingMapper = mock(SystemSettingMapper.class);
        JavdbClient javdbClient = mock(JavdbClient.class);
        EmbyClient embyClient = mock(EmbyClient.class);
        AdultMagnetIngestService adultIngestService = mock(AdultMagnetIngestService.class);
        AdultMagnetIngestTaskMapper adultTaskMapper = mock(AdultMagnetIngestTaskMapper.class);
        JavdbAutomationRunMapper runMapper = mock(JavdbAutomationRunMapper.class);
        JavdbAutomationRunItemMapper itemMapper = mock(JavdbAutomationRunItemMapper.class);
        JavdbAutomationLedgerMapper ledgerMapper = mock(JavdbAutomationLedgerMapper.class);
        JavdbAutomationRunLogMapper logMapper = mock(JavdbAutomationRunLogMapper.class);
        JavdbPlaylistMembershipMapper membershipMapper = mock(JavdbPlaylistMembershipMapper.class);
        JavdbPlaylistSyncRunMapper syncRunMapper = mock(JavdbPlaylistSyncRunMapper.class);
        JavdbPlaylistHistoryMapper historyMapper = mock(JavdbPlaylistHistoryMapper.class);
        JavdbAutomationService service = new JavdbAutomationService(
                authService, systemSettingMapper, javdbClient, embyClient, adultIngestService,
                adultTaskMapper, runMapper, itemMapper, ledgerMapper, logMapper,
                membershipMapper, syncRunMapper, historyMapper, new ObjectMapper()
        );

        JavdbPlaylistMembership top = membership("top", "ABC-123", "TOP_250_2026");
        JavdbPlaylistMembership cracked = membership("cracked", "DEF-456", "CRACKED");
        JavdbPlaylistMembership subtitle = membership("subtitle", "GHI-789", "SUBTITLE");
        when(historyMapper.selectExecutedItems()).thenReturn(List.of());
        when(membershipMapper.selectList(any())).thenReturn(List.of(top, cracked, subtitle));
        when(embyClient.listUsers()).thenReturn(List.of(
                new EmbyUserAccount("owner-id", "tecnb", false, false)
        ));
        AtomicBoolean playlistNamesMigrated = new AtomicBoolean();
        when(embyClient.listPlaylists("owner-id")).thenAnswer(invocation -> List.of(
                new EmbyPlaylist("top-2026", playlistNamesMigrated.get() ? "Top 250(2026)" : "Top 250 2026"),
                new EmbyPlaylist("top-2025", playlistNamesMigrated.get() ? "Top 250(2025)" : "Top 250 2025"),
                new EmbyPlaylist("top-2024", playlistNamesMigrated.get() ? "Top 250(2024)" : "Top 250 2024"),
                new EmbyPlaylist("cracked-list", "破解"),
                new EmbyPlaylist("subtitle-list", "字幕")
        ));
        doAnswer(invocation -> {
            if ("top-2024".equals(invocation.getArgument(0))) {
                playlistNamesMigrated.set(true);
            }
            return null;
        }).when(embyClient).renamePlaylist(any(), eq("owner-id"), any());
        when(embyClient.listLibraries()).thenReturn(List.of(
                new EmbyLibrary("adult-jav", "Adult-JAV", List.of())
        ));
        EmbyItem topItem = new EmbyItem("movie-top", "ABC-123 title", "Movie", "/ABC-123", null);
        EmbyItem crackedItem = new EmbyItem("movie-cracked", "DEF-456 title", "Movie", "/DEF-456", null);
        when(embyClient.listLibraryVideoItems("adult-jav")).thenReturn(List.of(topItem, crackedItem));

        AtomicBoolean topAdded = new AtomicBoolean();
        when(embyClient.listPlaylistVideoItems(any(), eq("owner-id"))).thenAnswer(invocation -> {
            String playlistId = invocation.getArgument(0);
            if ("top-2026".equals(playlistId) && topAdded.get()) {
                return List.of(topItem);
            }
            if ("cracked-list".equals(playlistId)) {
                return List.of(crackedItem);
            }
            return List.of();
        });
        doAnswer(invocation -> {
            topAdded.set(true);
            return null;
        }).when(embyClient).addItemsToPlaylist("top-2026", "owner-id", List.of("movie-top"));

        JavdbPlaylistSyncRunResponse first = service.syncPlaylistsManually();
        JavdbPlaylistSyncRunResponse second = service.syncPlaylistsManually();

        assertThat(first.addedCount()).isEqualTo(1);
        assertThat(first.existingCount()).isEqualTo(1);
        assertThat(first.waitingCount()).isEqualTo(1);
        assertThat(first.groups()).hasSize(5);
        assertThat(second.addedCount()).isZero();
        assertThat(second.existingCount()).isEqualTo(2);
        assertThat(second.waitingCount()).isEqualTo(1);
        verify(embyClient).addItemsToPlaylist("top-2026", "owner-id", List.of("movie-top"));
        verify(embyClient).renamePlaylist("top-2026", "owner-id", "Top 250(2026)");
        verify(embyClient).renamePlaylist("top-2025", "owner-id", "Top 250(2025)");
        verify(embyClient).renamePlaylist("top-2024", "owner-id", "Top 250(2024)");
        assertThat(top.getStatus()).isEqualTo("SYNCED");
        assertThat(cracked.getStatus()).isEqualTo("SYNCED");
        assertThat(subtitle.getStatus()).isEqualTo("WAITING_EMBY");
    }

    private JavdbPlaylistMembership membership(String id, String code, String playlistKey) {
        JavdbPlaylistMembership membership = new JavdbPlaylistMembership();
        membership.setId(id);
        membership.setCode(code);
        membership.setPlaylistKey(playlistKey);
        membership.setStatus("WAITING_EMBY");
        membership.setCreatedAt(LocalDateTime.now());
        membership.setUpdatedAt(LocalDateTime.now());
        return membership;
    }
}
