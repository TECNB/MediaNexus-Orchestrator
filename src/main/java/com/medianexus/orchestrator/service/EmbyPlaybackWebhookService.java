package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.EmbyProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 旧播放 Webhook 仅鉴权并成功应答；计时、结算与清理全部归后台采集。媒体库入口独立保留。 */
@Service
public class EmbyPlaybackWebhookService {
    private final EmbyProperties embyProperties;
    public EmbyPlaybackWebhookService(EmbyProperties embyProperties) {
        this.embyProperties = embyProperties;
    }
    public void receivePlaybackEvent(String secret, String body) {
        String configured = embyProperties.getWebhookSecret();
        String expected = configured == null ? null : configured.trim();
        if (!StringUtils.hasText(expected) || !expected.equals(secret)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Emby webhook secret 无效", HttpStatus.FORBIDDEN);
        }
    }
}
