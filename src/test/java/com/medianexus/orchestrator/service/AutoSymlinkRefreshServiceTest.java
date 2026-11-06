package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.AutoSymlinkProperties;
import com.medianexus.orchestrator.dto.autosymlink.request.AutoSymlinkRefreshRequest;
import com.medianexus.orchestrator.integration.autosymlink.AutoSymlinkClient;
import com.medianexus.orchestrator.integration.autosymlink.AutoSymlinkClientException;
import com.medianexus.orchestrator.support.IngestTargetFixtures;
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
        AutoSymlinkRefreshService service = new AutoSymlinkRefreshService(properties, client, objectMapper, IngestTargetFixtures.legacy());

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
        AutoSymlinkRefreshService service = new AutoSymlinkRefreshService(properties, client, objectMapper, IngestTargetFixtures.legacy());

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
        AutoSymlinkRefreshService service = new AutoSymlinkRefreshService(properties, client, objectMapper, IngestTargetFixtures.legacy());

        AutoSymlinkRefreshService.RefreshOutcome outcome = service.refreshMovie();

        assertThat(outcome.status()).isEqualTo(AutoSymlinkRefreshService.Status.SUBMITTED);
        assertThat(outcome.message()).isEqualTo("AutoSymlink 刷新任务已提交");
        assertThat(outcome.detail()).isEqualTo("task=movie, attempt=2");
        assertThat(client.calls()).hasSize(2);
        assertThat(client.calls().get(1).uuid()).isEqualTo("movie-task");
        assertThat(client.calls().get(1).requestBody().path("mode").asText()).isEqualTo("manual");
    }

    @Test
    void usesOriginalAndExplicitTargetBindingsWithFullJsonWhileNoTargetKeepsLegacy() throws Exception {
        IngestTargetFixtures targets = new IngestTargetFixtures();
        targets.useDefault("new");
        AutoSymlinkProperties properties = completeProperties();
        properties.getAdult().setUuid("telegram-legacy");
        properties.getAdult().setRequestBodyJson("{\"mode\":\"legacy-adult\"}");
        RecordingAutoSymlinkClient client = new RecordingAutoSymlinkClient(properties, objectMapper);
        AutoSymlinkRefreshService service = new AutoSymlinkRefreshService(properties, client, objectMapper, targets.registry);
        AuthService auth = new AuthService(null, (RegistrationCodeSettingsService) null, null) {
            @Override public com.medianexus.orchestrator.model.User requireAdminUser() {
                return new com.medianexus.orchestrator.model.User();
            }
        };
        AdminAutoSymlinkRefreshService admin = new AdminAutoSymlinkRefreshService(auth, service);

        service.refreshForPath("MOVIE", "/old/Movies/Movie");
        admin.refresh(new AutoSymlinkRefreshRequest("MOVIE", "new"));
        admin.refresh(new AutoSymlinkRefreshRequest("MOVIE"));
        service.refreshAdult();

        assertThat(client.calls()).extracting(SubmitCall::uuid)
                .containsExactly("old-MOVIE", "new-MOVIE", "movie-task", "telegram-legacy");
        assertThat(client.calls().get(0).requestBody()).isEqualTo(objectMapper.readTree("""
                {"task_name":"old-MOVIE","media_dir":"/mount/old/Movies","symlink_creator":true,
                 "metadata_copyer":true,"num_threads":"4","symlink_dir_checker":false,
                 "symlink_checker":false,"metadata_checker":false,"symlink_size":"0",
                 "symlink_ext":".strm","metadata_ext":".nfo"}
                """));
        assertThat(client.calls().get(1).requestBody().path("media_dir").asText()).isEqualTo("/mount/new/Movies");
        assertThatThrownBy(() -> service.refreshForPath("MOVIE", "/new2/Movies/Movie"))
                .isInstanceOf(com.medianexus.orchestrator.common.exception.BusinessException.class);
        assertThatThrownBy(() -> admin.refresh(new AutoSymlinkRefreshRequest("MOVIE", "unknown")))
                .isInstanceOf(com.medianexus.orchestrator.common.exception.BusinessException.class);
        assertThat(client.calls()).hasSize(4);
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
