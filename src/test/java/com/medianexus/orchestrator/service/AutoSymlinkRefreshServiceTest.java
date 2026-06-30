package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.AutoSymlinkProperties;
import com.medianexus.orchestrator.integration.autosymlink.AutoSymlinkClient;
import com.medianexus.orchestrator.integration.autosymlink.AutoSymlinkClientException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutoSymlinkRefreshServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void skipsWhenGlobalConfigurationIsIncomplete() {
        AutoSymlinkProperties properties = completeProperties();
        properties.setBaseUrl("");
        RecordingAutoSymlinkClient client = new RecordingAutoSymlinkClient(properties, objectMapper);
        AutoSymlinkRefreshService service = new AutoSymlinkRefreshService(properties, client, objectMapper);

        AutoSymlinkRefreshService.RefreshOutcome outcome = service.refreshMovie();

        assertThat(outcome.status()).isEqualTo(AutoSymlinkRefreshService.Status.SKIPPED);
        assertThat(outcome.message()).isEqualTo("AutoSymlink 刷新未配置，已跳过");
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void skipsInvalidConfiguredRequestBodyBeforeSubmitting() {
        AutoSymlinkProperties properties = completeProperties();
        properties.getMovie().setRequestBodyJson("not-json");
        RecordingAutoSymlinkClient client = new RecordingAutoSymlinkClient(properties, objectMapper);
        AutoSymlinkRefreshService service = new AutoSymlinkRefreshService(properties, client, objectMapper);

        AutoSymlinkRefreshService.RefreshOutcome outcome = service.refreshMovie();

        assertThat(outcome.status()).isEqualTo(AutoSymlinkRefreshService.Status.SKIPPED);
        assertThat(outcome.message()).isEqualTo("AutoSymlink 刷新请求体配置解析失败，已跳过");
        assertThat(client.calls()).isEmpty();
    }

    @Test
    void retriesConfiguredRefreshUntilSubmitted() {
        AutoSymlinkProperties properties = completeProperties();
        properties.setMaxAttempts(2);
        properties.setRetryInterval(Duration.ZERO);
        RecordingAutoSymlinkClient client = new RecordingAutoSymlinkClient(properties, objectMapper);
        client.failuresBeforeSuccess(1);
        AutoSymlinkRefreshService service = new AutoSymlinkRefreshService(properties, client, objectMapper);

        AutoSymlinkRefreshService.RefreshOutcome outcome = service.refreshMovie();

        assertThat(outcome.status()).isEqualTo(AutoSymlinkRefreshService.Status.SUBMITTED);
        assertThat(outcome.message()).isEqualTo("AutoSymlink 刷新任务已提交");
        assertThat(outcome.detail()).isEqualTo("task=movie, attempt=2");
        assertThat(client.calls()).hasSize(2);
        assertThat(client.calls().get(1).uuid()).isEqualTo("movie-task");
        assertThat(client.calls().get(1).requestBody().path("mode").asText()).isEqualTo("manual");
    }

    private AutoSymlinkProperties completeProperties() {
        AutoSymlinkProperties properties = new AutoSymlinkProperties();
        properties.setBaseUrl("http://autos.example:8095");
        properties.setCookie("authenticated=true");
        properties.setTimeout(Duration.ofSeconds(15));
        properties.setMaxAttempts(1);
        properties.setRetryInterval(Duration.ofSeconds(1));
        properties.getMovie().setUuid("movie-task");
        properties.getMovie().setRequestBodyJson("{\"mode\":\"manual\"}");
        return properties;
    }

    private static class RecordingAutoSymlinkClient extends AutoSymlinkClient {

        private final List<SubmitCall> calls = new ArrayList<>();
        private int remainingFailures;

        RecordingAutoSymlinkClient(AutoSymlinkProperties properties, ObjectMapper objectMapper) {
            super(properties, objectMapper);
        }

        void failuresBeforeSuccess(int failures) {
            this.remainingFailures = failures;
        }

        List<SubmitCall> calls() {
            return calls;
        }

        @Override
        public void submitSyncTask(String uuid, JsonNode requestBody) {
            calls.add(new SubmitCall(uuid, requestBody));
            if (remainingFailures > 0) {
                remainingFailures--;
                throw new AutoSymlinkClientException("temporary failure");
            }
        }
    }

    private record SubmitCall(String uuid, JsonNode requestBody) {
    }
}
