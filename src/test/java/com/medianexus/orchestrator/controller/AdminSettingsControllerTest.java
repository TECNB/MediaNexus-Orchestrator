package com.medianexus.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.GlobalExceptionHandler;
import com.medianexus.orchestrator.config.*;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.AuthService;
import com.medianexus.orchestrator.service.SettingsService;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validation;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AdminSettingsControllerTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Map<String, String> rows = new ConcurrentHashMap<>();
    private final SystemSettingMapper database = mock(SystemSettingMapper.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final String key = Base64.getEncoder().encodeToString(new byte[32]);
    private final TmdbProperties tmdb = tmdb();
    private final SubtitleUploadProperties subtitles = new SubtitleUploadProperties();
    private final UserQuotaProperties quota = new UserQuotaProperties();
    private String role = "ADMIN";

    AdminSettingsControllerTest() {
        when(database.selectSettingValue(anyString())).thenAnswer(i -> rows.get(i.getArgument(0)));
        when(database.selectSettingValueForUpdate(anyString())).thenAnswer(i -> rows.get(i.getArgument(0)));
        doAnswer(i -> { rows.putIfAbsent(i.getArgument(0), i.getArgument(1)); return null; })
                .when(database).insertSettingIfAbsent(anyString(), anyString());
        when(database.compareAndSetSetting(anyString(), anyString(), anyString())).thenAnswer(i ->
                rows.replace(i.getArgument(0), i.getArgument(1), i.getArgument(2)) ? 1 : 0);
        doAnswer(i -> { rows.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(database).upsertSetting(anyString(), anyString());
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void defaultsToPanSouAndPersistsOnlySupportedEngineThroughSettings() throws Exception {
        SettingsService settings = service(key);
        MagnetSearchProperties defaults = new MagnetSearchProperties();
        defaults.setEngine("PANSOU");
        settings.configureMagnetSearch(defaults);
        assertThat(settings.usesPanSouForMagnetSearch()).isTrue();
        MockMvc api = api(settings);
        api.perform(put("/api/v1/admin/settings/groups/magnet-search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"values\":{\"engine\":\"PROWLARR\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.effective.engine").value("PROWLARR"));
        assertThat(settings.usesPanSouForMagnetSearch()).isFalse();
        SettingsService restarted = service(key);
        restarted.configureMagnetSearch(defaults);
        assertThat(restarted.usesPanSouForMagnetSearch()).isFalse();
        api.perform(put("/api/v1/admin/settings/groups/magnet-search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"values\":{\"engine\":\"OTHER\"}}"))
                .andExpect(status().isBadRequest());
        assertThat(settings.usesPanSouForMagnetSearch()).isFalse();
    }

    @Test
    void savesTmdbThroughAdminApiAndNextRealRequestUsesEncryptedPersistentSelection() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/search/movie", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"results\":[{\"id\":42}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        upstream.createContext("/configuration", exchange -> {
            byte[] response = "{\"images\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        upstream.start();
        try {
            MockMvc api = api(service(key));
            String response = api.perform(put("/api/v1/admin/settings/groups/tmdb")
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                            "version", 0, "values", Map.of("baseUrl", "http://127.0.0.1:" + upstream.getAddress().getPort()),
                            "secrets", Map.of("apiToken", Map.of("action", "REPLACE", "value", "new-private-token"))))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1))
                    .andExpect(jsonPath("$.data.sources.apiToken").value("DATABASE"))
                    .andExpect(jsonPath("$.data.status").value("EFFECTIVE"))
                    .andExpect(jsonPath("$.data.effective.apiToken.configured").value(true))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("new-private-token", "deployment-token");
            assertThat(rows.values()).allSatisfy(stored -> assertThat(stored).doesNotContain("new-private-token"));
            assertThat(new TmdbClient(tmdb, json).searchMovies("example", "en-US").get(0).path("id").asInt()).isEqualTo(42);
            assertThat(authorization.get()).isEqualTo("Bearer new-private-token");
            TmdbProperties restarted = tmdb();
            new SettingsService(database, json, Validation.buildDefaultValidatorFactory().getValidator(),
                    transactions, restarted, new SubtitleUploadProperties(), new UserQuotaProperties(), new com.medianexus.orchestrator.service.SettingsCipher(key)).activateConsumers();
            assertThat(new TmdbClient(restarted, json).searchMovies("example", "en-US").size()).isEqualTo(1);
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void reportsServletCeilingAndRejectsUploadLimitAboveItBeforeChangingEffectiveLimits() throws Exception {
        SettingsService settings = service(key);
        settings.configureMultipartLimits(new jakarta.servlet.MultipartConfigElement("", 2_097_152, 3_145_728, 0));
        MockMvc api = api(settings);
        api.perform(get("/api/v1/admin/settings/groups/subtitle-upload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effective.maxUploadSize").value(2_097_152))
                .andExpect(jsonPath("$.data.constraints.multipartMaxFileSizeBytes").value(2_097_152))
                .andExpect(jsonPath("$.data.status").value("LIMITED_BY_DEPLOYMENT"));
        api.perform(put("/api/v1/admin/settings/groups/subtitle-upload").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"values\":{\"maxUploadSize\":3145728}}"))
                .andExpect(status().isBadRequest());
        api.perform(get("/api/v1/admin/settings/groups/subtitle-upload"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.effective.maxUploadSize").value(2_097_152));
        settings.configureMultipartLimits(new jakarta.servlet.MultipartConfigElement("", 4_194_304, 1_048_576, 0));
        api.perform(get("/api/v1/admin/settings/groups/subtitle-upload"))
                .andExpect(jsonPath("$.data.effective.maxUploadSize").value(1_048_576))
                .andExpect(jsonPath("$.data.constraints.multipartMaxRequestSizeBytes").value(1_048_576));
    }

    @Test
    void existingQuotaApiAndSettingsSharePersistentSelectionAndRestoreDeploymentExplicitly() throws Exception {
        SettingsService settings = service(key);
        AuthService auth = adminAuth();
        var legacyApi = new com.medianexus.orchestrator.service.UserQuotaSettingsService(
                database, quota, auth, mock(com.medianexus.orchestrator.service.UserAdminAuditService.class));
        legacyApi.configureManagedSettings(settings);
        rows.put("daily_content_create_limit", "4");
        MockMvc api = api(settings);
        api.perform(get("/api/v1/admin/settings/groups/quota"))
                .andExpect(jsonPath("$.data.effective.dailyContentCreateLimit").value(4))
                .andExpect(jsonPath("$.data.sources.dailyContentCreateLimit").value("LEGACY_DATABASE"));
        api.perform(put("/api/v1/admin/settings/groups/quota").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"values\":{\"dailyContentCreateLimit\":7}}"))
                .andExpect(status().isOk());
        assertThat(legacyApi.getDefaultDailyContentCreateLimit()).isEqualTo(7);
        legacyApi.updateDefaultQuota(2);
        api.perform(get("/api/v1/admin/settings/groups/quota"))
                .andExpect(jsonPath("$.data.effective.dailyContentCreateLimit").value(2))
                .andExpect(jsonPath("$.data.version").value(2));
        User user = new User(); user.setDailyContentCreateLimitOverride(1);
        assertThat(legacyApi.resolveDailyContentCreateLimit(user)).isEqualTo(1);
        api.perform(post("/api/v1/admin/settings/groups/quota/restore").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effective.dailyContentCreateLimit").value(3))
                .andExpect(jsonPath("$.data.sources.dailyContentCreateLimit").value("DEPLOYMENT"));
        assertThat(legacyApi.getDefaultDailyContentCreateLimit()).isEqualTo(3);
    }

    @Test
    void invalidOrRejectedCandidateCannotPartiallyChangeSavedOrEffectiveConfiguration() throws Exception {
        MockMvc api = api(service(key));
        api.perform(put("/api/v1/admin/settings/groups/tmdb").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"values\":{\"defaultLanguage\":\"en-US\",\"timeout\":\"PT0S\"}}"))
                .andExpect(status().isBadRequest());
        api.perform(put("/api/v1/admin/settings/groups/tmdb").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"values\":{\"source\":\"DATABASE\"}}"))
                .andExpect(status().isBadRequest());
        api.perform(put("/api/v1/admin/settings/groups/tmdb").contentType(MediaType.APPLICATION_JSON)
                .content("{\"values\":{\"defaultLanguage\":\"en-US\"}}"))
                .andExpect(status().isBadRequest());
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/configuration", exchange -> { exchange.sendResponseHeaders(401, -1); exchange.close(); });
        upstream.start();
        try {
            api.perform(put("/api/v1/admin/settings/groups/tmdb").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("version", 0, "values", Map.of("baseUrl",
                            "http://127.0.0.1:" + upstream.getAddress().getPort(), "defaultLanguage", "en-US"),
                            "secrets", Map.of("apiToken", Map.of("action", "REPLACE", "value", "rejected-private-token"))))))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.message").value("TMDB 拒绝候选凭据"));
            api.perform(get("/api/v1/admin/settings/groups/tmdb"))
                    .andExpect(jsonPath("$.data.version").value(0))
                    .andExpect(jsonPath("$.data.saved.defaultLanguage").value("zh-CN"))
                    .andExpect(jsonPath("$.data.effective.baseUrl").value("https://api.themoviedb.org/3"));
        } finally { upstream.stop(0); }
    }

    @Test
    void replacementKeepRestoreAndStaleVersionHaveDistinctPersistentEffects() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/configuration", exchange -> {
            byte[] response = "{\"images\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        upstream.createContext("/search/movie", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        upstream.start();
        tmdb.setBaseUrl("http://127.0.0.1:" + upstream.getAddress().getPort());
        try {
            SettingsService settings = service(key); MockMvc api = api(settings);
            String originalFingerprint = settings.effectiveFingerprint("tmdb");
            api.perform(put("/api/v1/admin/settings/groups/tmdb").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"secrets\":{\"apiToken\":{\"action\":\"REPLACE\",\"value\":\"replacement-token\"}}}"))
                    .andExpect(status().isOk());
            api.perform(put("/api/v1/admin/settings/groups/tmdb").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":1,\"values\":{\"defaultLanguage\":\"en-US\"},\"secrets\":{\"apiToken\":{\"action\":\"KEEP\"}}}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
            new TmdbClient(tmdb, json).searchMovies("example", "en-US");
            assertThat(authorization.get()).isEqualTo("Bearer replacement-token");
            assertThat(settings.effectiveFingerprint("tmdb")).isNotEqualTo(originalFingerprint);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> settings.requireEffectiveFingerprint("tmdb", originalFingerprint))
                    .isInstanceOf(com.medianexus.orchestrator.common.exception.BusinessException.class);
            api.perform(put("/api/v1/admin/settings/groups/tmdb").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":1,\"values\":{\"defaultLanguage\":\"fr-FR\"}}"))
                    .andExpect(status().isConflict());
            api.perform(put("/api/v1/admin/settings/groups/tmdb").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":2,\"secrets\":{\"apiToken\":{\"action\":\"REPLACE\",\"value\":\"\"}}}"))
                    .andExpect(status().isBadRequest());
            api.perform(post("/api/v1/admin/settings/groups/tmdb/restore").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":2}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(3))
                    .andExpect(jsonPath("$.data.sources.apiToken").value("DEPLOYMENT"))
                    .andExpect(jsonPath("$.data.effective.defaultLanguage").value("zh-CN"));
            new TmdbClient(tmdb, json).searchMovies("example", "en-US");
            assertThat(authorization.get()).isEqualTo("Bearer deployment-token");
        } finally { upstream.stop(0); }
    }

    @Test
    void missingEncryptionKeyRefusesReplacementWithoutChangingDeploymentSelection() throws Exception {
        MockMvc api = api(service(""));
        api.perform(put("/api/v1/admin/settings/groups/tmdb").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"values\":{\"defaultLanguage\":\"en-US\"},\"secrets\":{\"apiToken\":{\"action\":\"REPLACE\",\"value\":\"must-not-store\"}}}"))
                .andExpect(status().isServiceUnavailable());
        String response = api.perform(get("/api/v1/admin/settings/groups/tmdb"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.effective.defaultLanguage").value("zh-CN"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("must-not-store", "deployment-token");
        assertThat(rows.values()).allSatisfy(stored -> assertThat(stored).doesNotContain("must-not-store"));
    }

    @Test
    void nonAdminCannotReadMutateCheckOrListSettingsResources() throws Exception {
        MockMvc api = api(service(key)); role = "USER";
        api.perform(get("/api/v1/admin/settings")).andExpect(status().isForbidden());
        api.perform(get("/api/v1/admin/settings/groups/tmdb")).andExpect(status().isForbidden());
        api.perform(put("/api/v1/admin/settings/groups/quota").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"values\":{\"dailyContentCreateLimit\":2}}"))
                .andExpect(status().isForbidden());
        api.perform(post("/api/v1/admin/settings/groups/tmdb/restore").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isForbidden());
        api.perform(post("/api/v1/admin/settings/groups/tmdb/check")).andExpect(status().isForbidden());
        api.perform(get("/api/v1/admin/settings/groups/tmdb/resources")).andExpect(status().isForbidden());
    }

    @Test
    void subtitleUploadUsesNewLimitsWhileAlreadyStagedUploadRetainsItsSnapshot() throws Exception {
        MockMvc api = api(service(key));
        api.perform(put("/api/v1/admin/settings/groups/subtitle-upload").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"values\":{\"allowedExtensions\":[\".srt\"],\"maxUploadSize\":20,\"maxTextSubtitleSize\":20}}"))
                .andExpect(status().isOk());
        var extractor = new com.medianexus.orchestrator.service.SubtitleArchiveExtractor(subtitles);
        var file = new org.springframework.mock.web.MockMultipartFile("file", "example.srt", "text/plain", new byte[12]);
        var staged = extractor.stage(file);
        try {
            api.perform(put("/api/v1/admin/settings/groups/subtitle-upload").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":1,\"values\":{\"maxUploadSize\":5,\"maxTextSubtitleSize\":5}}"))
                    .andExpect(status().isOk());
            assertThat(extractor.extract(staged).entries()).hasSize(1);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> extractor.stage(file))
                    .isInstanceOf(com.medianexus.orchestrator.common.exception.BusinessException.class)
                    .hasMessage("上传文件超过大小限制");
        } finally { extractor.deleteQuietly(staged.workDir()); }
    }

    @Test
    void inFlightTmdbRequestKeepsOldSnapshotWhileNextRequestUsesNewTokenAndTimeout() throws Exception {
        var executor = java.util.concurrent.Executors.newCachedThreadPool();
        var oldRequestStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseOldRequest = new java.util.concurrent.CountDownLatch(1);
        var nextAuthorization = new AtomicReference<String>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(executor);
        upstream.createContext("/configuration", exchange -> {
            byte[] body = "{\"images\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        upstream.createContext("/search/movie", exchange -> {
            String token = exchange.getRequestHeaders().getFirst("Authorization");
            try {
                if ("Bearer deployment-token".equals(token)) {
                    oldRequestStarted.countDown(); releaseOldRequest.await(3, java.util.concurrent.TimeUnit.SECONDS);
                } else {
                    nextAuthorization.set(token); Thread.sleep(1500);
                }
                byte[] body = "{\"results\":[{\"id\":42}]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException exception) { /* 客户端超时主动关闭了测试连接。 */ }
            finally { exchange.close(); }
        });
        upstream.start();
        tmdb.setBaseUrl("http://127.0.0.1:" + upstream.getAddress().getPort()); tmdb.setTimeout(Duration.ofSeconds(3));
        try {
            MockMvc api = api(service(key)); TmdbClient client = new TmdbClient(tmdb, json);
            var inFlight = executor.submit(() -> client.searchMovies("old", "en-US"));
            assertThat(oldRequestStarted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            api.perform(put("/api/v1/admin/settings/groups/tmdb").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"values\":{\"timeout\":\"PT1S\"},\"secrets\":{\"apiToken\":{\"action\":\"REPLACE\",\"value\":\"next-token\"}}}"))
                    .andExpect(status().isOk());
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.searchMovies("new", "en-US"))
                    .isInstanceOf(com.medianexus.orchestrator.integration.tmdb.TmdbClientException.class)
                    .hasCauseInstanceOf(java.net.http.HttpTimeoutException.class);
            assertThat(nextAuthorization.get()).isEqualTo("Bearer next-token");
            releaseOldRequest.countDown();
            assertThat(inFlight.get(2, java.util.concurrent.TimeUnit.SECONDS).get(0).path("id").asInt()).isEqualTo(42);
        } finally {
            releaseOldRequest.countDown(); upstream.stop(0); executor.shutdownNow();
        }
    }

    @Test
    void bootMultipartBeanUsesApplicationDeploymentDefaultsWithoutStartingBackend() throws Exception {
        var environment = new org.springframework.core.env.StandardEnvironment();
        new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application", new org.springframework.core.io.ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration.class))
                .withPropertyValues("spring.servlet.multipart.max-file-size=" + environment.getProperty("spring.servlet.multipart.max-file-size"),
                        "spring.servlet.multipart.max-request-size=" + environment.getProperty("spring.servlet.multipart.max-request-size"))
                .run(context -> {
                    var multipart = context.getBean(jakarta.servlet.MultipartConfigElement.class);
                    assertThat(multipart.getMaxFileSize()).isEqualTo(52_428_800);
                    assertThat(multipart.getMaxRequestSize()).isEqualTo(53_477_376);
                    SettingsService settings = service(key); settings.configureMultipartLimits(multipart);
                    api(settings).perform(get("/api/v1/admin/settings/groups/subtitle-upload"))
                            .andExpect(jsonPath("$.data.effective.maxUploadSize").value(52_428_800));
                });
    }

    @Test
    void emptyDatabaseAllowsConsumerConstructionBeforeReadyAndManagedLanguageAppliesAfterInitialization() throws Exception {
        var initialized = new java.util.concurrent.atomic.AtomicBoolean();
        when(database.selectSettingValue(anyString())).thenAnswer(call -> {
            if (!initialized.get()) { throw new IllegalStateException("test table is not initialized"); }
            return rows.get(call.getArgument(0));
        });
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/search/movie", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        upstream.start(); tmdb.setBaseUrl("http://127.0.0.1:" + upstream.getAddress().getPort());
        try {
            SettingsService settings = new SettingsService(database, json, Validation.buildDefaultValidatorFactory().getValidator(),
                    transactions, tmdb, subtitles, quota, new com.medianexus.orchestrator.service.SettingsCipher(key));
            TmdbClient client = new TmdbClient(tmdb, json);
            var catalog = new com.medianexus.orchestrator.service.catalog.TmdbMovieCatalogSearch(client, tmdb);
            assertThat(tmdb.getDefaultLanguage()).isEqualTo("zh-CN");
            initialized.set(true); settings.activateConsumers();
            api(settings).perform(put("/api/v1/admin/settings/groups/tmdb").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"values\":{\"defaultLanguage\":\"en-US\"}}"))
                    .andExpect(status().isOk());
            assertThat(catalog.searchMovies("example")).isEmpty();
            assertThat(query.get()).contains("language=en-US");
        } finally { upstream.stop(0); }
    }

    @Test
    void effectiveFingerprintSurvivesServiceRecreationButChangesWithConnectionVersionOrKey() throws Exception {
        SettingsService original = service(key);
        String fingerprint = original.effectiveFingerprint("tmdb");
        assertThat(service(key).effectiveFingerprint("tmdb")).isEqualTo(fingerprint);
        tmdb.setApiToken("changed-token");
        assertThat(service(key).effectiveFingerprint("tmdb")).isNotEqualTo(fingerprint);
        tmdb.setApiToken("deployment-token");
        tmdb.setImageBaseUrl("https://changed.example/posters");
        assertThat(service(key).effectiveFingerprint("tmdb")).isNotEqualTo(fingerprint);
        tmdb.setImageBaseUrl("https://image.tmdb.org/t/p/w500");
        byte[] otherKey = new byte[32]; otherKey[0] = 1;
        assertThat(service(Base64.getEncoder().encodeToString(otherKey)).effectiveFingerprint("tmdb")).isNotEqualTo(fingerprint);
        api(original).perform(put("/api/v1/admin/settings/groups/tmdb").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"values\":{\"defaultLanguage\":\"zh-CN\"}}"))
                .andExpect(status().isOk());
        assertThat(original.effectiveFingerprint("tmdb")).isNotEqualTo(fingerprint);
        assertThat(service(key).effectiveFingerprint("tmdb")).isEqualTo(original.effectiveFingerprint("tmdb"));
        assertThat(json.writeValueAsString(original.get("tmdb"))).doesNotContain("deployment-token", "changed-token", key);
    }

    @Test
    void missingFingerprintKeyKeepsSettingsReadableButRefusesOnboarding() throws Exception {
        String encrypted = new com.medianexus.orchestrator.service.SettingsCipher(key).encrypt("tmdb:apiToken", "persisted-private-token");
        rows.put("managed_settings_tmdb", json.writeValueAsString(Map.of("version", 1, "overrides", Map.of("apiToken", encrypted), "restored", false)));
        SettingsService settings = service("");
        api(settings).perform(get("/api/v1/admin/settings/groups/tmdb"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.effectiveFingerprint").value("UNAVAILABLE"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> settings.effectiveFingerprint("tmdb"))
                .isInstanceOfSatisfying(com.medianexus.orchestrator.common.exception.BusinessException.class,
                        error -> assertThat(error.getHttpStatus()).isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void restartFingerprintUsesActiveValuesAndVersionWhilePendingStillRefusesOnboarding() throws Exception {
        var current = softwareSettings("http://127.0.0.1:9999");
        String fingerprint = current.settings.effectiveFingerprint("openlist");
        assertThat(softwareSettings("http://127.0.0.1:9999").settings.effectiveFingerprint("openlist")).isEqualTo(fingerprint);
        rows.put("managed_settings_openlist", json.writeValueAsString(Map.of("version", 1, "overrides", Map.of("animeRenameTemplate", "changed-template"), "restored", false)));
        assertThat(current.settings.get("openlist").effectiveFingerprint()).isEqualTo(fingerprint);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> current.settings.effectiveFingerprint("openlist"))
                .isInstanceOfSatisfying(com.medianexus.orchestrator.common.exception.BusinessException.class,
                        error -> assertThat(error.getHttpStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
        var restarted = softwareSettings("http://127.0.0.1:9999");
        assertThat(restarted.settings.effectiveFingerprint("openlist")).isNotEqualTo(fingerprint);
        assertThat(softwareSettings("http://127.0.0.1:9999").settings.effectiveFingerprint("openlist"))
                .isEqualTo(restarted.settings.effectiveFingerprint("openlist"));
    }

    private SettingsService service(String encryptionKey) {
        SettingsService service = new SettingsService(database, json, Validation.buildDefaultValidatorFactory().getValidator(),
                transactions, tmdb, subtitles, quota, new com.medianexus.orchestrator.service.SettingsCipher(encryptionKey));
        service.configureMultipartLimits(new jakarta.servlet.MultipartConfigElement("", 52_428_800, 53_477_376, 0));
        service.activateConsumers();
        return service;
    }

    @Test
    void softwareSaveStaysPendingUntilRebuiltClientUsesEncryptedDatabaseSelection() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/api/admin/storage/list", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "{\"code\":200,\"data\":{\"content\":[{\"id\":1,\"driver\":\"PikPak\",\"mount_path\":\"/old\",\"status\":\"work\",\"disabled\":false,\"mount_details\":{\"used_space\":10,\"total_space\":100,\"free_space\":90}}]}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        upstream.start();
        try {
            String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
            var first = softwareSettings(base);
            var oldClient = new com.medianexus.orchestrator.integration.openlist.OpenListClient(
                    first.integrations.activeSnapshot("openlist", OpenListProperties.class), json);

            api(first.settings).perform(put("/api/v1/admin/settings/groups/openlist").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"values\":{\"timeout\":\"PT3S\"},\"secrets\":{\"authorization\":{\"action\":\"REPLACE\",\"value\":\"next-openlist-token\"}}}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_RESTART"))
                    .andExpect(jsonPath("$.data.saved.timeout").value("PT3S"))
                    .andExpect(jsonPath("$.data.effective.timeout").value("PT2S"))
                    .andExpect(jsonPath("$.data.restartServices[0]").value("MediaNexus-Orchestrator"));
            assertThat(authorization.get()).isEqualTo("next-openlist-token");
            assertThat(rows.get("managed_settings_openlist")).doesNotContain("next-openlist-token");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> first.settings.effectiveSnapshot("openlist", OpenListProperties.class))
                    .isInstanceOf(com.medianexus.orchestrator.common.exception.BusinessException.class);
            oldClient.storageStatus();
            assertThat(authorization.get()).isEqualTo("deployment-openlist-token");

            var restarted = softwareSettings(base);
            api(restarted.settings).perform(get("/api/v1/admin/settings/groups/openlist"))
                    .andExpect(jsonPath("$.data.status").value("EFFECTIVE"))
                    .andExpect(jsonPath("$.data.effective.timeout").value("PT3S"));
            var client = new com.medianexus.orchestrator.integration.openlist.OpenListClient(
                    restarted.settings.effectiveSnapshot("openlist", OpenListProperties.class), json);
            client.storageStatus();
            assertThat(authorization.get()).isEqualTo("next-openlist-token");
            api(restarted.settings).perform(put("/api/v1/admin/settings/groups/anirss").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"values\":{\"baseUrl\":\"http://localhost/changed\"}}"))
                    .andExpect(status().isBadRequest());
        } finally { upstream.stop(0); }
    }

    private record SoftwareSettings(SettingsService settings, com.medianexus.orchestrator.service.SettingsIntegrationRuntime integrations) {}

    @Test
    void explicitRestoreCanReturnOptionalSoftwareToUnconfiguredDeploymentWithoutClearingActiveCredentials() throws Exception {
        String protectedCookie = new com.medianexus.orchestrator.service.SettingsCipher(key).encrypt("autosymlink:cookie", "retained-session");
        rows.put("managed_settings_autosymlink", json.writeValueAsString(Map.of("version", 1, "restored", false,
                "overrides", Map.of("baseUrl", "http://127.0.0.1:1", "cookie", protectedCookie))));
        var first = softwareSettings("http://127.0.0.1:1");
        api(first.settings).perform(post("/api/v1/admin/settings/groups/autosymlink/restore")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING_RESTART"))
                .andExpect(jsonPath("$.data.saved.cookie.configured").value(false))
                .andExpect(jsonPath("$.data.effective.cookie.configured").value(true))
                .andExpect(jsonPath("$.data.sources.cookie").value("DEPLOYMENT"));
        assertThat(first.integrations.activeSnapshot("autosymlink", AutoSymlinkProperties.class).getCookie()).isEqualTo("retained-session");
    }

    private SoftwareSettings softwareSettings(String openlistBase) throws Exception {
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("MEDIANEXUS_OPENLIST_BASE_URL", openlistBase)
                .withProperty("MEDIANEXUS_OPENLIST_AUTHORIZATION", "deployment-openlist-token")
                .withProperty("MEDIANEXUS_OPENLIST_TIMEOUT", "2s");
        new org.springframework.boot.env.YamlPropertySourceLoader().load("application", new org.springframework.core.io.ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        factory.registerSingleton("mapper", database); factory.registerSingleton("json", json);
        factory.registerSingleton("cipher", new com.medianexus.orchestrator.service.SettingsCipher(key));
        factory.registerSingleton("tunnel", mock(DatabaseSshTunnelLifecycle.class));
        factory.registerSingleton("validator", Validation.buildDefaultValidatorFactory().getValidator());
        var bootstrap = new com.medianexus.orchestrator.service.SettingsIntegrationBootstrap(factory.getBeanProvider(SystemSettingMapper.class),
                factory.getBeanProvider(ObjectMapper.class), factory.getBeanProvider(com.medianexus.orchestrator.service.SettingsCipher.class),
                factory.getBeanProvider(DatabaseSshTunnelLifecycle.class), factory.getBeanProvider(jakarta.validation.Validator.class));
        Map<Class<?>, Object> softwareBeans = new java.util.LinkedHashMap<>();
        for (var group : com.medianexus.orchestrator.service.SettingsIntegrationCatalog.SOFTWARE) {
            if (softwareBeans.containsKey(group.type())) continue;
            Object bean = group.type().getDeclaredConstructor().newInstance();
            String prefix = group.type().getAnnotation(org.springframework.boot.context.properties.ConfigurationProperties.class).prefix();
            org.springframework.boot.context.properties.bind.Binder.get(environment).bind(prefix, org.springframework.boot.context.properties.bind.Bindable.ofInstance(bean));
            bootstrap.postProcessAfterInitialization(bean, group.id());
            softwareBeans.put(group.type(), bean);
        }
        var context = mock(org.springframework.context.ApplicationContext.class);
        when(context.getBean(any(Class.class))).thenAnswer(call -> softwareBeans.get(call.getArgument(0)));
        var integrations = new com.medianexus.orchestrator.service.SettingsIntegrationRuntime(bootstrap,
                new com.medianexus.orchestrator.service.SettingsConnectionProbe(json), json, environment, context);
        SettingsService settings = service(key); settings.configureSoftware(integrations);
        return new SoftwareSettings(settings, integrations);
    }

    @Test
    void springBindingAppliesStoredConnectionsBeforeConstructingClientsWithoutStartingTheServer() throws Exception {
        String ciphertext = new com.medianexus.orchestrator.service.SettingsCipher(key).encrypt("openlist:authorization", "boot-private-token");
        AtomicReference<String> token = new AtomicReference<>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/api/admin/storage/list", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "{\"code\":200,\"data\":{\"content\":[{\"driver\":\"PikPak\",\"status\":\"work\",\"mount_details\":{\"total_space\":100}}]}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        upstream.start();
        rows.put("managed_settings_openlist", json.writeValueAsString(Map.of("version", 2, "restored", false,
                "overrides", Map.of("baseUrl", "http://127.0.0.1:" + upstream.getAddress().getPort(), "authorization", ciphertext))));
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        try {
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withUserConfiguration(SoftwareBootFixture.class)
                    .withBean(SystemSettingMapper.class, () -> database)
                    .withBean(ObjectMapper.class, () -> json)
                    .withBean(jakarta.validation.Validator.class, () -> validator)
                    .withBean(PlatformTransactionManager.class, () -> transactions)
                    .withBean(DatabaseSshTunnelLifecycle.class, () -> mock(DatabaseSshTunnelLifecycle.class))
                    .withPropertyValues("MEDIANEXUS_SETTINGS_ENCRYPTION_KEY=" + key,
                            "medianexus.clouddrive2.organization-enabled=false")
                    .withInitializer(context -> {
                        try {
                            new org.springframework.boot.env.YamlPropertySourceLoader().load("application", new org.springframework.core.io.ClassPathResource("application.yml"))
                                    .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                        } catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
                    })
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        context.getBean(com.medianexus.orchestrator.integration.openlist.OpenListClient.class).storageStatus();
                        assertThat(token.get()).isEqualTo("boot-private-token");
                        assertThat(context.getBean(SettingsService.class).get("openlist").status()).isEqualTo("EFFECTIVE");
                        assertThat(context.getBean(SettingsService.class).get("openlist").version()).isEqualTo(2);
                    });
        } finally { upstream.stop(0); }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties({OpenListProperties.class, CloudDrive2Properties.class,
            AutoSymlinkProperties.class, EmbyProperties.class, ProwlarrProperties.class, PanSouProperties.class,
            QasProperties.class, WeComProperties.class, AniRssProperties.class, TelegramWorkerProperties.class,
            TmdbProperties.class, SubtitleUploadProperties.class, UserQuotaProperties.class})
    @org.springframework.context.annotation.Import({com.medianexus.orchestrator.service.SettingsIntegrationBootstrap.class,
            com.medianexus.orchestrator.service.SettingsIntegrationRuntime.class, com.medianexus.orchestrator.service.SettingsConnectionProbe.class,
            com.medianexus.orchestrator.service.SettingsCipher.class, SettingsService.class,
            com.medianexus.orchestrator.integration.openlist.OpenListClient.class})
    static class SoftwareBootFixture {
        @org.springframework.context.annotation.Bean
        jakarta.servlet.MultipartConfigElement multipart() { return new jakarta.servlet.MultipartConfigElement("", 52_428_800, 53_477_376, 0); }
    }

    private MockMvc api(SettingsService settings) {
        return MockMvcBuilders.standaloneSetup(new AdminSettingsController(settings, adminAuth()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private AuthService adminAuth() {
        return new AuthService(null, new AuthProperties(), null) {
            @Override public User requireCurrentUser() {
                User user = new User(); user.setId(1L); user.setRole(role); return user;
            }
        };
    }

    private static TmdbProperties tmdb() {
        TmdbProperties properties = new TmdbProperties();
        properties.setBaseUrl("https://api.themoviedb.org/3");
        properties.setImageBaseUrl("https://image.tmdb.org/t/p/w500");
        properties.setApiToken("deployment-token");
        properties.setDefaultLanguage("zh-CN"); properties.setFallbackLanguage("en-US");
        properties.setTimeout(Duration.ofSeconds(5));
        return properties;
    }
}
