package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.*;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

/** 旧写者已经退役，事件负载不再触发元数据读取、计时或清理。 */
class EmbyPlaybackWebhookServiceTest {
    @Test void authenticatedEventsAndMalformedPayloadsAreCompatibilitySuccess() {
        var properties=new EmbyProperties(); properties.setWebhookSecret(" secret ");
        var service=new EmbyPlaybackWebhookService(properties);
        for (String body:new String[]{null,"", "not-json", "{\"Event\":\"playback.stop\"}"}) {
            assertThatCode(() -> service.receivePlaybackEvent("secret",body)).doesNotThrowAnyException();
        }
    }
    @Test void originalSecretComparisonAndForbiddenResponseRemain() {
        var properties=new EmbyProperties(); properties.setWebhookSecret("secret");
        var service=new EmbyPlaybackWebhookService(properties);
        for (String secret:new String[]{null,"wrong"," secret "}) {
            assertThatThrownBy(() -> service.receivePlaybackEvent(secret,"{}"))
                    .isInstanceOfSatisfying(BusinessException.class,e -> {
                        assertThat(e.getHttpStatus().value()).isEqualTo(403);
                        assertThat(e.getMessage()).isEqualTo("Emby webhook secret 无效");
                    });
        }
        properties.setWebhookSecret(" ");
        assertThatThrownBy(() -> service.receivePlaybackEvent("","{}")).isInstanceOf(BusinessException.class);
    }
}
