package com.medianexus.orchestrator.service;

import org.springframework.stereotype.Component;

/** 普通登录态的加密热设置入口；单一版本绑定 access/refresh/device，禁止跨版本组合。 */
@Component
public class GuangyaCredentials {
    private final SettingsService settings;
    public GuangyaCredentials(SettingsService settings) { this.settings = settings; }
    public Snapshot snapshot() { return settings.guangyaCredentials(); }
    /** CAS 失败或持久化失败必须向调用方抛错，不允许 best-effort 静默丢失轮换。 */
    public void rotate(Snapshot expected, Snapshot replacement) { settings.rotateGuangyaCredentials(expected, replacement); }

    public record Snapshot(long version, String accessToken, String refreshToken, String deviceId) {
        public boolean configured() { return valid(accessToken) && valid(refreshToken) && valid(deviceId); }
        private static boolean valid(String value) {
            return value != null && !value.isBlank() && value.length() <= 8192
                    && value.chars().noneMatch(c -> c < 32 || c == 127);
        }
        @Override public String toString() { return "GuangyaCredentials.Snapshot[version=" + version + ", credentials=REDACTED]"; }
    }
    /** 不参与 ConfigurationProperties 绑定，凭据只能通过管理员加密设置进入。 */
    public static class Values {
        private String accessToken;
        private String refreshToken;
        private String deviceId;
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String value) { accessToken = value; }
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String value) { refreshToken = value; }
        public String getDeviceId() { return deviceId; }
        public void setDeviceId(String value) { deviceId = value; }
    }
}
