package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.WeComProperties;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.integration.wecom.WeComClient;
import com.medianexus.orchestrator.mapper.BgmWishlistExecutionMapper;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WeComIngestChatTest {
    @Test void parsesExplicitSeasonAndYearButKeepsBareNumbersInTitles() {
        assertEquals(new WeComIngestChat.Title("怪奇物语", 1, null), WeComIngestChat.parse("怪奇物语第一季"));
        assertEquals(new WeComIngestChat.Title("怪奇物语", 3, null), WeComIngestChat.parse("怪奇物语 S03"));
        assertEquals(new WeComIngestChat.Title("怪奇物语", 3, 2016), WeComIngestChat.parse("怪奇物语（2016）第 ３ 季"));
        assertEquals(new WeComIngestChat.Title("怪奇物语 3", null, null), WeComIngestChat.parse("怪奇物语 3"));
        assertEquals(new WeComIngestChat.Title("叶问 3", null, null), WeComIngestChat.parse("叶问 3"));
        assertEquals(new WeComIngestChat.Title("1917", null, null), WeComIngestChat.parse("1917"));
    }

    @Test void acceptsBoundUsersAndKeepsTheirNumberedCandidatesSeparate() throws Exception {
        var f = new BgmWishlistOperationTest.Fixture();
        f.owner.setRole("USER");
        var other = new com.medianexus.orchestrator.model.User();
        other.setId(2L);
        other.setUsername("other");
        other.setRole("USER");
        when(f.users.selectById(2L)).thenReturn(other);
        var bindings = mock(WeComBindings.class);
        when(bindings.userIdForMember("not-member")).thenReturn(null);
        when(bindings.userIdForMember("member")).thenReturn(1L);
        when(bindings.userIdForMember("other")).thenReturn(2L);
        var config = new WeComProperties();
        config.setIngestEnabled(true);
        config.setCorpId("corp");
        config.setAgentId(1L);
        config.setAppSecret("test");
        config.setIngestToken("testToken");
        config.setIngestAesKey("A".repeat(43));
        var json = new ObjectMapper().findAndRegisterModules();
        var mapper = mock(BgmWishlistExecutionMapper.class);
        var tmdb = mock(TmdbClient.class);
        var client = mock(WeComClient.class);
        var operations = spy(f.service);
        var pending = new ConcurrentLinkedQueue<String>();
        var seen = ConcurrentHashMap.<String>newKeySet();
        var choices = new ConcurrentHashMap<String, String>();
        when(mapper.insertWeComMessage(anyString(), anyString(), anyLong(), anyString())).thenAnswer(call -> {
            if (!seen.add(call.getArgument(0))) return 0;
            pending.add(call.getArgument(3));
            return 1;
        });
        when(mapper.pendingWeComMessages()).thenAnswer(call -> List.copyOf(pending));
        doAnswer(call -> { pending.remove(); return null; }).when(mapper).consumeWeComMessage(anyString());
        when(mapper.activeWeComSelection(anyString())).thenAnswer(call -> {
            var active = f.rows.values().stream().filter(o -> o.source().id().equals(call.getArgument(0))).filter(o -> Set.of("RESOLVING", "WAITING_WORK", "WAITING_SEASON")
                    .contains(o.status())).findFirst().orElse(null);
            return active == null ? null : json.writeValueAsString(active);
        });
        doAnswer(call -> { choices.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(mapper).saveWeComChoices(anyString(), anyString(), anyString());
        when(mapper.select(anyString(), eq("WECOM_CHOICES"))).thenAnswer(call -> choices.get(call.getArgument(0)));
        when(tmdb.searchWeComTitles("沙丘")).thenReturn(json.readTree("""
                {"total_pages":1,"results":[{"media_type":"movie","id":101,"title":"沙丘","release_date":"1984-12-01"},
                {"media_type":"movie","id":102,"title":"沙丘","release_date":"2021-12-01"}]}
                """));
        when(tmdb.searchWeComTitles("信条")).thenReturn(json.readTree("""
                {"total_pages":1,"results":[{"media_type":"movie","id":103,"title":"信条","release_date":"2020-08-01"},
                {"media_type":"movie","id":104,"title":"信条","release_date":"1984-12-01"}]}
                """));
        var chat = new WeComIngestChat(config, f.users, bindings, mapper, f.records, operations, f.locks, json, tmdb, client);
        try {
            chat.accept("not-member", "100", "怪奇物语 S3");
            verifyNoInteractions(mapper);
            chat.accept("member", "101", "沙丘");
            chat.accept("other", "105", "信条");
            chat.accept("member", "102", "信条");
            chat.accept("other", "106", "2");
            chat.accept("member", "103", "1");
            chat.accept("member", "103", "1");
            String first = UUID.nameUUIDFromBytes("corp:1:101".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            String latest = UUID.nameUUIDFromBytes("corp:1:102".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            verify(operations, timeout(3000)).enqueueWeCom(eq(latest), argThat(identity -> identity.tmdbId() == 103), isNull());
            String otherId = UUID.nameUUIDFromBytes("corp:1:105".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            verify(operations).enqueueWeCom(eq(otherId), argThat(identity -> identity.tmdbId() == 104), isNull());
            verify(operations).setWeComResolution(eq(first), eq("REPLACED"), anyString());
            verify(operations, never()).enqueueWeCom(eq(first), any(), any());
        } finally {
            chat.close();
        }
    }
}
