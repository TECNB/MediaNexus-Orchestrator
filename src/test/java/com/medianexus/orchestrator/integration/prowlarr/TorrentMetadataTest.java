package com.medianexus.orchestrator.integration.prowlarr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.medianexus.orchestrator.integration.guangya.TorrentFileTable;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TorrentMetadataTest {
    @Test
    void singleFileHashUsesOriginalInfoBytesAndUtf8NameWins() throws Exception {
        Map<String, Object> info = singleInfo("fallback.mkv", 8L);
        info.put("name.utf-8", "正片.mkv");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(encode(info)));
        TorrentFileTable table = TorrentMetadata.parse(torrent(info), hash);
        assertThat(table.identity().btih()).isEqualTo(hash);
        assertThat(table.rootName()).isEqualTo("正片.mkv");
        assertThat(table.files()).containsExactly(new TorrentFileTable.Entry("正片.mkv", 8));
        assertThat(table.totalBytes()).isEqualTo(8);
    }

    @Test
    void multifilePathsAreRelativeToRootAndUtf8PathWins() {
        Map<String, Object> info = multiInfo(List.of(
                Map.of("length", 3L, "path", List.of("sub", "fallback"), "path.utf-8", List.of("sub", "第一集.mkv")),
                Map.of("length", 4L, "path", List.of("第二集.mkv"))));
        TorrentFileTable table = TorrentMetadata.parse(torrent(info), null);
        assertThat(table.files()).containsExactly(new TorrentFileTable.Entry("sub/第一集.mkv", 3),
                new TorrentFileTable.Entry("第二集.mkv", 4));
        assertThat(table.totalBytes()).isEqualTo(7);
    }

    @Test
    void rejectsSearchHashMismatch() {
        invalid(torrent(singleInfo("a.mkv", 1L)), "0000000000000000000000000000000000000000");
    }

    @Test
    void rejectsUnsafeComponentsAndDuplicateOrConflictingPaths() {
        for (String path : List.of(".", "..", "/root", "a/b", "a\\b", "C:", "", " x", "x ", "x\u0000", "x\n")) {
            invalid(torrent(singleInfo(path, 1L)), null);
            invalid(torrent(multiInfo(List.of(Map.of("length", 1L, "path", List.of(path))))), null);
        }
        invalid(torrent(multiInfo(List.of(file(1, "a.mkv"), file(1, "a.mkv")))), null);
        invalid(torrent(multiInfo(List.of(file(1, "a"), file(1, "a", "b")))), null);
        invalid(torrent(multiInfo(List.of(file(1, "a", "b"), file(1, "a")))), null);
    }

    @Test
    void rejectsInvalidUtf8AndSymlinks() {
        Map<String, Object> info = singleInfo("fallback.mkv", 1L);
        info.put("name.utf-8", new byte[] {(byte) 0xc3, 0x28});
        invalid(torrent(info), null);
        info = singleInfo("a.mkv", 1L);
        info.put("attr", "l");
        invalid(torrent(info), null);
        invalid(torrent(multiInfo(List.of(Map.of("length", 0L, "path", List.of("link"), "symlink path", List.of("target"))))), null);
    }

    @Test
    void rejectsNegativeSizesOverflowAndInvalidStructure() {
        invalid(torrent(singleInfo("a.mkv", -1L)), null);
        invalid(torrent(multiInfo(List.of(file(Long.MAX_VALUE, "a"), file(1, "b")))), null);
        Map<String, Object> info = singleInfo("a", 1L);
        info.put("files", List.of(file(1, "b")));
        invalid(torrent(info), null);
        invalid(torrent(multiInfo(List.of())), null);
        info = singleInfo("a", 1L);
        info.remove("pieces");
        invalid(torrent(info), null);
        info = singleInfo("a", 1L);
        info.put("piece length", 0L);
        invalid(torrent(info), null);
        info = singleInfo("a", 1L);
        info.put("pieces", new byte[19]);
        invalid(torrent(info), null);
        invalid(encode(Map.of("info", "not a dictionary")), null);
    }

    @Test
    void rejectsNoncanonicalNumbersStringsDuplicateKeysAndTrailingGarbage() {
        for (String body : List.of("d4:infoi1ee", "d4:infode4:infodee", "d1:ai1e1:ai2ee", "degarbage",
                "d1:ai01ee", "d1:ai-0ee", "d1:ai+1ee", "d1:aiee", "d1:ai9223372036854775808ee",
                "d1:ai-9223372036854775809ee", "d1:a01:xe", "d1:a99999999999999999999:x", "d1:a3:xe",
                "d1:a-1:xe", "d1:ali1ee", "d1:ai1e", "d1:ae", "d", "", "l4:infoee")) {
            invalid(body.getBytes(StandardCharsets.US_ASCII), null);
        }
    }

    @Test
    void rejectsBodyDepthAndNodeLimits() {
        invalid(new byte[TorrentMetadata.MAX_BYTES + 1], null);
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("info", singleInfo("a", 1L));
        Object nested = "leaf";
        for (int i = 0; i < 33; i++) nested = List.of(nested);
        top.put("deep", nested);
        invalid(encode(top), null);
        top.remove("deep");
        top.put("nodes", java.util.Collections.nCopies(20_000, "x"));
        invalid(encode(top), null);
    }

    private static Map<String, Object> file(long size, String... path) {
        return Map.of("length", size, "path", List.of(path));
    }

    static Map<String, Object> singleInfo(String name, long size) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("length", size);
        info.put("name", name);
        info.put("piece length", 16_384L);
        info.put("pieces", new byte[size == 0 ? 0 : 20]);
        return info;
    }

    private static Map<String, Object> multiInfo(List<Map<String, Object>> files) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("files", files);
        info.put("name", "season");
        info.put("piece length", 16_384L);
        info.put("pieces", new byte[20]);
        return info;
    }

    static byte[] torrent(Map<String, Object> info) { return encode(Map.of("info", info)); }

    static byte[] encode(Object value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (value instanceof Map<?, ?> dictionary) {
            output.write('d');
            dictionary.forEach((key, item) -> { output.writeBytes(encode(key)); output.writeBytes(encode(item)); });
            output.write('e');
        } else if (value instanceof List<?> list) {
            output.write('l');
            list.forEach(item -> output.writeBytes(encode(item)));
            output.write('e');
        } else if (value instanceof Number number) {
            output.writeBytes(("i" + number + "e").getBytes(StandardCharsets.US_ASCII));
        } else {
            byte[] bytes = value instanceof byte[] raw ? raw : value.toString().getBytes(StandardCharsets.UTF_8);
            output.writeBytes((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
            output.writeBytes(bytes);
        }
        return output.toByteArray();
    }

    private static void invalid(byte[] bytes, String known) {
        assertThatThrownBy(() -> TorrentMetadata.parse(bytes, known))
                .isInstanceOfSatisfying(ProwlarrClientException.class,
                        exception -> assertThat(exception.getReason()).isEqualTo(ProwlarrClientException.Reason.INVALID_RESPONSE));
    }
}
