package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Provider;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Source;
import com.medianexus.orchestrator.integration.wecom.WeComClient.Recipient;
import com.medianexus.orchestrator.integration.wecom.WeComClient.Scope;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.BgmWishlistSourceStore.CollectedSubject;
import com.medianexus.orchestrator.service.BgmWishlistSourceStore.SourceState;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemberAutomationTest {
    @Test void normalAccountSeesOnlyBoundTabsAndItsOwnWishlist() {
        AuthService auth = mock(AuthService.class);
        BgmWishlistSourceStore sources = mock(BgmWishlistSourceStore.class);
        BgmWishlistOperationStore operations = mock(BgmWishlistOperationStore.class);
        WeComBindings bindings = mock(WeComBindings.class);
        User member = new User();
        member.setId(7L);
        member.setRole("USER");
        when(auth.requireCurrentUser()).thenReturn(member);
        when(sources.ownedProviders(7L)).thenReturn(List.of("BGM"));
        var source = new Source("source-7", "BGM 账号", 7L, "member", true, "09:00", 1, "2160p",
                "NEW_ONLY", "Asia/Shanghai", true, Provider.BGM);
        var work = new CollectedSubject(1L, "作品", null, null, 2024, "2026-11-09T00:00:00Z");
        when(sources.listOwned(7L)).thenReturn(List.of(new SourceState(source, List.of(work), Set.of(), Set.of(),
                Map.of(), Map.of(), "SUCCESS", null, null, null)));
        var service = new MemberAutomation(auth, sources, operations, bindings);
        assertEquals(new MemberAutomation.Access(true, false, false), service.access());
        when(bindings.recipient(7L)).thenReturn(new Recipient(new Scope("corp", 1L), "member"));
        assertEquals(new MemberAutomation.Access(true, false, true), service.access());
        assertTrue(service.wishlists(Provider.DOUBAN).isEmpty());
        assertEquals("作品", service.wishlists(Provider.BGM).get(0).items().get(0).title());
        verify(operations).recentOwned("source-7", 7L);
        verify(sources, never()).list();
    }
}
