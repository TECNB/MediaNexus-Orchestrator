package com.medianexus.orchestrator.integration.emby;

/** A missing video size prevents publishing its group's subtotal as a complete total. */
public record EmbyMediaSize(Long bytes, boolean partial) {
    public static final EmbyMediaSize UNKNOWN = new EmbyMediaSize(null, false);

    EmbyMediaSize plus(EmbyMediaSize other) {
        if (bytes == null && other.bytes == null) return UNKNOWN;
        long subtotal = Math.addExact(bytes == null ? 0 : bytes, other.bytes == null ? 0 : other.bytes);
        return new EmbyMediaSize(subtotal, partial || other.partial || bytes == null || other.bytes == null);
    }

    public String status() {
        if (partial) return "partial";
        return bytes != null ? "complete" : "unknown";
    }
}
