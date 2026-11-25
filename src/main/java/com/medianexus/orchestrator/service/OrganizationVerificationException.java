package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.integration.emby.EmbyMovieIdentitySnapshot;
import java.util.Set;

/** Controlled reason codes only; upstream response bodies and signed URLs never enter the journal. */
public class OrganizationVerificationException extends RuntimeException {
    private final String reason;
    private final boolean isolated;
    private final EmbyMovieIdentitySnapshot movie;
    private final Set<String> paths;

    public OrganizationVerificationException(String reason, boolean isolated) {
        this(reason, isolated, null, Set.of());
    }
    public OrganizationVerificationException(String reason, boolean isolated, EmbyMovieIdentitySnapshot movie, Set<String> paths) {
        super(reason); this.reason = reason; this.isolated = isolated; this.movie = movie; this.paths = Set.copyOf(paths);
    }
    public String reason() { return reason; }
    public boolean isolated() { return isolated; }
    public EmbyMovieIdentitySnapshot movie() { return movie; }
    public Set<String> paths() { return paths; }
}
