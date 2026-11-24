package com.medianexus.orchestrator.integration.openlist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class OpenListFileIdentityTest {
    final ObjectMapper json = new ObjectMapper();
    ObjectNode file(String url) {
        ObjectNode node = json.createObjectNode().put("name", "video.mkv").put("size", 123L).put("is_dir", false).put("raw_url", url);
        node.putObject("hash_info").put("gcid", "A".repeat(40));
        return node;
    }
    @Test void officialIdentityHasNoSignedUrlOrSignature() throws Exception {
        var identity = OpenListClient.parseIdentity("/media/video.mkv", file("https://dl-z01a-0041.mypikpak.net/x?fileid=official_id&sign=SECRET&expire=1"));
        assertThat(identity.providerFileId()).isEqualTo("official_id");
        assertThat(identity.gcid()).isEqualTo("a".repeat(40));
        assertThat(json.writeValueAsString(identity)).doesNotContain("https", "SECRET", "sign", "expire", "raw_url");
    }
    @ParameterizedTest
    @ValueSource(strings = {"http://dl.mypikpak.net/x?fileid=id", "https://mypikpak.net.evil.test/x?fileid=id",
            "https://dl.mypikpak.net@evil.test/x?fileid=id", "https://user@dl.mypikpak.net/x?fileid=id",
            "https://dl.mypikpak.net:444/x?fileid=id", "https://dl.mypikpak.net/x?fid=id",
            "https://dl.mypikpak.net/x?fileid=", "https://dl.mypikpak.net/x?fileid=id&fileid=other"})
    void rejectsUntrustedOrAmbiguousUrl(String url) {
        assertThatThrownBy(() -> OpenListClient.parseIdentity("/media/video.mkv", file(url)))
                .isInstanceOf(OpenListClientException.class).hasMessage("缺少可信 PikPak 文件身份").hasNoCause();
    }
    @Test void rejectsMissingGcidWrongPathAndNonFile() {
        ObjectNode node = file("https://dl.mypikpak.com/x?fileid=id");
        node.remove("hash_info");
        assertThatThrownBy(() -> OpenListClient.parseIdentity("/media/video.mkv", node)).isInstanceOf(OpenListClientException.class);
        ObjectNode other = file("https://dl.mypikpak.com/x?fileid=id").put("path", "/other/video.mkv");
        assertThatThrownBy(() -> OpenListClient.parseIdentity("/media/video.mkv", other)).isInstanceOf(OpenListClientException.class);
        ObjectNode directory = file("https://dl.mypikpak.com/x?fileid=id").put("is_dir", true);
        assertThatThrownBy(() -> OpenListClient.parseIdentity("/media/video.mkv", directory)).isInstanceOf(OpenListClientException.class);
    }
    @Test void exactProductionAbsenceIsNullButOtherFailuresRemainUnknown() throws Exception {
        var response = new java.util.concurrent.atomic.AtomicReference<>("{\"code\":500,\"message\":\"object not found\"}");
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/fs/get", exchange -> {
            byte[] body = response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var properties = new com.medianexus.orchestrator.config.OpenListProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setAuthorization("test"); properties.setTimeout(java.time.Duration.ofSeconds(2));
            var client = new OpenListClient(properties, json);
            assertThat(client.readIdentity("/media/missing.mkv")).isNull();
            for (String error : new String[]{"{\"code\":500,\"message\":\"database unavailable\"}", "{\"code\":401,\"message\":\"object not found\"}"}) {
                response.set(error);
                assertThatThrownBy(() -> client.readIdentity("/media/missing.mkv")).isInstanceOf(OpenListClientException.class);
            }
        } finally { server.stop(0); }
    }
}
