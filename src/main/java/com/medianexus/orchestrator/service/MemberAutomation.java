package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Provider;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** 普通用户只读本站归属的来源快照与本人历史，不复用管理员配置接口。 */
@Service
public class MemberAutomation {
    public record Access(boolean bgm, boolean douban, boolean wecom) {}
    public record Work(long id, String title, Integer year, String coverUrl) {}
    public record Recent(String id, Instant startedAt, String status, String title, String reason) {}
    public record Wishlist(String id, String account, boolean enabled, String refreshStatus,
                           List<Work> items, List<Recent> recent) {}

    private final AuthService auth;
    private final BgmWishlistSourceStore sources;
    private final BgmWishlistOperationStore operations;
    private final WeComBindings bindings;

    public MemberAutomation(AuthService auth, BgmWishlistSourceStore sources,
                            BgmWishlistOperationStore operations, WeComBindings bindings) {
        this.auth = auth; this.sources = sources; this.operations = operations; this.bindings = bindings;
    }

    public Access access() {
        var user = auth.requireCurrentUser();
        if ("ADMIN".equalsIgnoreCase(user.getRole())) return new Access(true, true, true);
        List<String> providers = sources.ownedProviders(user.getId());
        return new Access(providers.contains(Provider.BGM.name()), providers.contains(Provider.DOUBAN.name()),
                bindings.recipient(user.getId()) != null);
    }

    public List<Wishlist> wishlists(Provider provider) {
        long owner = auth.requireCurrentUser().getId();
        return sources.listOwned(owner).stream().filter(s -> s.source().provider() == provider).map(s -> new Wishlist(
                s.source().id(), s.source().bgmUsername(), s.source().enabled(), s.refreshStatus(),
                s.subjects().stream().map(work -> new Work(work.subjectId(), work.title(), work.year(), work.coverUrl())).toList(),
                operations.recentOwned(s.source().id(), owner).stream().map(op -> new Recent(op.id(), op.startedAt(),
                        op.status(), op.entries().size() == 1 ? op.entries().get(0).item().title() : null,
                        op.reason() != null ? op.reason().message() : op.entries().size() == 1 && op.entries().get(0).reason() != null
                                ? op.entries().get(0).reason().message() : null)).toList())).toList();
    }
}
