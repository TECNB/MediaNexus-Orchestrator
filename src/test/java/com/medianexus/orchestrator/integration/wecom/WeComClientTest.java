package com.medianexus.orchestrator.integration.wecom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.WeComProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

class WeComClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final List<JsonNode> messages = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private WeComProperties properties;
    private volatile String messageResponse = "{\"errcode\":0}";
    private volatile String tokenResponse = "{\"errcode\":0,\"access_token\":\"demo-token\",\"expires_in\":7200}";
    private volatile int messageStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/gettoken", exchange -> {
            tokenRequests.incrementAndGet();
            respond(exchange, 200, tokenResponse);
        });
        server.createContext("/cgi-bin/message/send", exchange -> {
            messages.add(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, messageStatus, messageResponse);
        });
        server.start();
        properties = new WeComProperties();
        properties.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setCorpId("demo-corp");
        properties.setAgentId(1000002L);
        properties.setAppSecret("secret-with-特殊&?");
        properties.setUserId("demo-member");
        properties.setTimeout(Duration.ofSeconds(2));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsSingleArticleToExplicitMemberAndReusesToken() {
        WeComClient client = new WeComClient(properties, mapper);

        properties.setUserId("legacy-recipient");
        client.sendNews(new WeComClient.Recipient(client.scope(), "demo-member"), "片名 · 任务处理完成",
                "入库任务已完成", "https://images.example/cover.jpg", "https://media.example/tasks/movie/task-1");
        client.sendText(new WeComClient.Recipient(client.scope(), "another-member"), "任务失败");

        assertThat(tokenRequests.get()).isEqualTo(1);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).path("touser").asText()).isEqualTo("another-member");
        JsonNode message = messages.get(0);
        assertThat(message.path("touser").asText()).isEqualTo("demo-member");
        assertThat(message.path("agentid").asLong()).isEqualTo(1000002L);
        assertThat(message.path("msgtype").asText()).isEqualTo("news");
        assertThat(message.path("news").path("articles").size()).isEqualTo(1);
        JsonNode article = message.path("news").path("articles").get(0);
        assertThat(article.path("title").asText()).isEqualTo("片名 · 任务处理完成");
        assertThat(article.path("description").asText()).isEqualTo("入库任务已完成");
        assertThat(article.path("picurl").asText()).isEqualTo("https://images.example/cover.jpg");
        assertThat(article.path("url").asText()).isEqualTo("https://media.example/tasks/movie/task-1");
    }

    @Test
    void refreshesExpiredTokenBeforeNextSend() {
        Clock clock = org.mockito.Mockito.mock(Clock.class);
        Instant start = Instant.parse("2026-11-06T00:00:00Z");
        org.mockito.Mockito.when(clock.instant()).thenReturn(start);
        WeComClient client = new WeComClient(properties, mapper, HttpClient.newHttpClient(), clock);

        send(client);
        org.mockito.Mockito.when(clock.instant()).thenReturn(start.plusSeconds(7200));
        send(client);

        assertThat(tokenRequests.get()).isEqualTo(2);
    }

    @Test
    void invalidatesRejectedTokenWithoutRepeatingTheMessage() {
        WeComClient client = new WeComClient(properties, mapper);
        messageResponse = "{\"errcode\":42001}";

        assertThatThrownBy(() -> send(client)).hasMessageContaining("42001");
        assertThat(messages).hasSize(1);
        messageResponse = "{\"errcode\":0}";
        send(client);

        assertThat(tokenRequests.get()).isEqualTo(2);
        assertThat(messages).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"errcode\":60020}",
            "{\"errcode\":0,\"invaliduser\":\"demo-member\"}",
            "{\"errcode\":0,\"unlicenseduser\":\"demo-member\"}",
            "{}", "[]", "not-json"
    })
    void rejectsBusinessErrorsAndMalformedResponses(String response) {
        messageResponse = response;
        WeComClient client = new WeComClient(properties, mapper);

        assertThatThrownBy(() -> send(client)).isInstanceOf(IllegalStateException.class)
                .hasNoCause().hasMessageNotContaining(properties.getAppSecret()).hasMessageNotContaining("demo-token");
        assertThat(messages).hasSize(1);
    }

    @Test
    void rejectsMissingTokenWithoutSendingMessage() {
        tokenResponse = "{\"errcode\":0,\"expires_in\":7200}";

        assertThatThrownBy(() -> send(new WeComClient(properties, mapper))).hasMessageContaining("token");
        assertThat(messages).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"@all", "first|second", "first second"})
    void rejectsBroadcastOrMultipleRecipientsBeforeNetwork(String member) {
        properties.setUserId(member);

        assertThatThrownBy(() -> send(new WeComClient(properties, mapper))).hasMessageContaining("接收成员无效");
        assertThat(tokenRequests.get()).isZero();
    }

    @Test
    void neverFollowsRedirectWithToken() {
        AtomicInteger redirected = new AtomicInteger();
        server.removeContext("/cgi-bin/message/send");
        server.createContext("/cgi-bin/message/send", exchange -> {
            exchange.getResponseHeaders().set("Location", properties.getApiBaseUrl() + "/unexpected");
            respond(exchange, 302, "redirect");
        });
        server.createContext("/unexpected", exchange -> {
            redirected.incrementAndGet();
            respond(exchange, 200, "{\"errcode\":0}");
        });

        assertThatThrownBy(() -> send(new WeComClient(properties, mapper))).hasMessageContaining("302");
        assertThat(redirected.get()).isZero();
    }

    @Test
    void sanitizesInvalidConfigurationAndDoesNotExposeCredentials() {
        properties.setApiBaseUrl("https://example.invalid/bad address");

        assertThatThrownBy(() -> send(new WeComClient(properties, mapper)))
                .hasMessageContaining("API 地址").hasNoCause()
                .hasMessageNotContaining(properties.getAppSecret()).hasMessageNotContaining("example.invalid");
    }

    @Test
    void readsOnlyConfiguredApplicationScopeAndDeduplicatesMembers() {
        server.createContext("/cgi-bin/agent/get", exchange -> respond(exchange, 200, """
                {"errcode":0,"allow_userinfos":{"user":[{"userid":"direct"}]},
                 "allow_partys":{"partyid":[2]},"allow_tags":{"tagid":[3]}}
                """));
        server.createContext("/cgi-bin/user/get", exchange -> respond(exchange, 200,
                "{\"errcode\":0,\"userid\":\"direct\",\"name\":\"Direct\"}"));
        server.createContext("/cgi-bin/user/simplelist", exchange -> respond(exchange, 200,
                "{\"errcode\":0,\"userlist\":[{\"userid\":\"department\",\"name\":\"Department\"}]}"));
        server.createContext("/cgi-bin/department/simplelist", exchange -> respond(exchange, 200,
                "{\"errcode\":0,\"department_id\":[{\"id\":2},{\"id\":4}]}"));
        server.createContext("/cgi-bin/tag/get", exchange -> respond(exchange, 200,
                "{\"errcode\":0,\"userlist\":[{\"userid\":\"direct\",\"name\":\"Direct\"},{\"userid\":\"tag\",\"name\":\"Tag\"}]}"));
        assertThat(new WeComClient(properties, mapper).visibleMembers().members())
                .extracting(WeComClient.Member::id).containsExactly("department", "direct", "tag");
        assertThat(tokenRequests.get()).isEqualTo(1);
    }

    @Test
    void rejectsRecipientFromAnotherApplicationBeforeAnyNetworkRequest() {
        WeComClient client = new WeComClient(properties, mapper);
        var wrongApplication = new WeComClient.Recipient(new WeComClient.Scope("another-corp", properties.getAgentId()), "member");
        assertThatThrownBy(() -> client.sendText(wrongApplication, "private task")).hasMessageContaining("应用已变更");
        assertThat(tokenRequests.get()).isZero();
    }

    private void send(WeComClient client) {
        client.sendNews(new WeComClient.Recipient(client.scope(), properties.getUserId()), "片名 · 任务处理完成", "入库任务已完成", "https://images.example/cover.jpg",
                "https://media.example/tasks/movie/task-1");
    }

    @Test
    void bindsDisabledDefaultsWithoutCredentialsOrAgentId() throws IOException {
        var yaml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        new ApplicationContextRunner()
                .withInitializer(context -> yaml.forEach(source -> context.getEnvironment().getPropertySources().addLast(source)))
                .withPropertyValues("MEDIANEXUS_WECOM_ENABLED=false", "MEDIANEXUS_WECOM_AGENT_ID=",
                        "MEDIANEXUS_WECOM_CORP_ID=", "MEDIANEXUS_WECOM_APP_SECRET=", "MEDIANEXUS_WECOM_USER_ID=")
                .withUserConfiguration(PropertiesConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    WeComProperties bound = context.getBean(WeComProperties.class);
                    assertThat(bound.isEnabled()).isFalse();
                    assertThat(bound.getAgentId()).isNull();
                    assertThat(bound.getTimeout()).isEqualTo(Duration.ofSeconds(10));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WeComProperties.class)
    static class PropertiesConfiguration { }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
