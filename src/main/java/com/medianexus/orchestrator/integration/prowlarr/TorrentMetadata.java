package com.medianexus.orchestrator.integration.prowlarr;

import com.medianexus.orchestrator.integration.guangya.TorrentFileTable;
import com.medianexus.orchestrator.integration.pansou.PanSouMagnetIdentity;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 原始 torrent 的只读安全边界；hash 使用原始 info 字节而非重新编码后的字典。 */
public final class TorrentMetadata {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 20_000;

    private TorrentMetadata() {}

    /** knownInfoHash 为可空的规范 BTIH；文件表仅在完整结构及身份校验后返回。 */
    public static TorrentFileTable parse(byte[] bytes, String knownInfoHash) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid("Torrent 字节体积无效或超限");
        Parser parser = new Parser(bytes);
        Node root = parser.read(0);
        if (parser.cursor != bytes.length) throw invalid("Torrent 存在尾随字节");
        Map<String, Node> top = dictionary(root);
        Node infoNode = top.get("info");
        Map<String, Node> info = dictionary(infoNode);
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(Arrays.copyOfRange(bytes, infoNode.start(), infoNode.end())));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 unavailable", exception);
        }
        if (knownInfoHash != null && !hash.equalsIgnoreCase(knownInfoHash)) throw invalid("Torrent 与搜索 BTIH 不一致");
        String rootName = component(preferred(info, "name"));
        rejectLink(info);
        List<TorrentFileTable.Entry> files = new ArrayList<>();
        long total = 0;
        if (info.containsKey("files")) {
            if (info.containsKey("length")) throw invalid("Torrent 单文件及多文件结构冲突");
            List<Node> entries = list(info.get("files"));
            if (entries.isEmpty()) throw invalid("Torrent 文件表为空");
            Set<String> leaves = new HashSet<>();
            Set<String> directories = new HashSet<>();
            for (Node entry : entries) {
                Map<String, Node> file = dictionary(entry);
                rejectLink(file);
                List<Node> parts = list(preferred(file, "path"));
                if (parts.isEmpty() || parts.size() > MAX_DEPTH) throw invalid("Torrent 文件路径深度无效");
                List<String> names = new ArrayList<>();
                for (Node part : parts) names.add(component(part));
                String path = String.join("/", names);
                if (path.length() > 4096 || directories.contains(path) || !leaves.add(path)) throw invalid("Torrent 重复或冲突文件路径");
                String parent = "";
                for (int i = 0; i < names.size() - 1; i++) {
                    parent = parent.isEmpty() ? names.get(i) : parent + "/" + names.get(i);
                    if (leaves.contains(parent)) throw invalid("Torrent 文件与目录路径冲突");
                    directories.add(parent);
                }
                long size = nonnegative(file.get("length"));
                try { total = Math.addExact(total, size); }
                catch (ArithmeticException exception) { throw invalid("Torrent 文件大小总和溢出"); }
                files.add(new TorrentFileTable.Entry(path, size));
            }
        } else {
            total = nonnegative(info.get("length"));
            files.add(new TorrentFileTable.Entry(rootName, total));
        }
        long pieceLength = nonnegative(info.get("piece length"));
        byte[] pieces = string(info.get("pieces"));
        if (pieceLength == 0 || pieces.length % 20 != 0
                || pieces.length / 20 != total / pieceLength + (total % pieceLength == 0 ? 0 : 1)) {
            throw invalid("Torrent 分片声明与文件大小不一致");
        }
        return new TorrentFileTable(new PanSouMagnetIdentity(hash), rootName, files, total);
    }

    private static void rejectLink(Map<String, Node> file) {
        if (file.containsKey("symlink path") || (file.containsKey("attr")
                && new String(string(file.get("attr")), StandardCharsets.ISO_8859_1).contains("l"))) {
            throw invalid("Torrent 不支持符号链接");
        }
    }

    private static Node preferred(Map<String, Node> dictionary, String key) {
        return dictionary.getOrDefault(key + ".utf-8", dictionary.get(key));
    }

    private static String component(Node node) {
        String name;
        try {
            name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(string(node))).toString();
        } catch (CharacterCodingException exception) { throw invalid("Torrent 文件路径不是有效 UTF-8"); }
        if (name.isBlank() || name.length() > 255 || !name.equals(name.strip()) || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                || name.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid("Torrent 非法相对路径组件");
        return name;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Node> dictionary(Node node) {
        if (node == null || !(node.value() instanceof Map)) throw invalid("Torrent 缺少有效字典");
        return (Map<String, Node>) node.value();
    }

    @SuppressWarnings("unchecked")
    private static List<Node> list(Node node) {
        if (node == null || !(node.value() instanceof List)) throw invalid("Torrent 缺少有效列表");
        return (List<Node>) node.value();
    }

    private static byte[] string(Node node) {
        if (node == null || !(node.value() instanceof byte[] value)) throw invalid("Torrent 缺少有效字节串");
        return value;
    }

    private static long nonnegative(Node node) {
        if (node == null || !(node.value() instanceof Long value) || value < 0) throw invalid("Torrent 文件大小或分片长度无效");
        return value;
    }

    private static ProwlarrClientException invalid(String message) {
        return new ProwlarrClientException(ProwlarrClientException.Reason.INVALID_RESPONSE, message);
    }

    private record Node(Object value, int start, int end) {}

    private static final class Parser {
        private final byte[] bytes;
        private int cursor;
        private int nodes;

        private Parser(byte[] bytes) { this.bytes = bytes; }

        private Node read(int depth) {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES || cursor >= bytes.length) throw invalid("Torrent 结构超限或截断");
            int start = cursor;
            int marker = bytes[cursor];
            Object value;
            if (marker == 'd') {
                cursor++;
                Map<String, Node> entries = new LinkedHashMap<>();
                while (!atEnd()) {
                    String key = new String(string(read(depth + 1)), StandardCharsets.ISO_8859_1);
                    if (entries.containsKey(key)) throw invalid("Torrent 字典键重复");
                    entries.put(key, read(depth + 1));
                }
                cursor++;
                value = entries;
            } else if (marker == 'l') {
                cursor++;
                List<Node> entries = new ArrayList<>();
                while (!atEnd()) entries.add(read(depth + 1));
                cursor++;
                value = entries;
            } else if (marker == 'i') {
                cursor++;
                int numberStart = cursor;
                while (cursor < bytes.length && bytes[cursor] != 'e') {
                    if (cursor - numberStart >= 20) throw invalid("Torrent 整数溢出");
                    cursor++;
                }
                if (cursor >= bytes.length) throw invalid("Torrent 整数截断");
                String number = new String(bytes, numberStart, cursor - numberStart, StandardCharsets.US_ASCII);
                if (!number.matches("0|-?[1-9][0-9]*")) throw invalid("Torrent 非规范整数");
                try { value = Long.parseLong(number); }
                catch (NumberFormatException exception) { throw invalid("Torrent 整数溢出"); }
                cursor++;
            } else if (marker >= '0' && marker <= '9') {
                int length = 0;
                int digits = 0;
                while (cursor < bytes.length && bytes[cursor] != ':') {
                    int digit = bytes[cursor++] - '0';
                    if (digit < 0 || digit > 9 || ++digits > 7 || (digits > 1 && bytes[start] == '0')) throw invalid("Torrent 非规范字节串长度");
                    length = length * 10 + digit;
                    if (length > MAX_BYTES) throw invalid("Torrent 字节串超限");
                }
                if (cursor >= bytes.length || length > bytes.length - cursor - 1) throw invalid("Torrent 字节串截断");
                cursor++;
                value = Arrays.copyOfRange(bytes, cursor, cursor + length);
                cursor += length;
            } else {
                throw invalid("Torrent 非法 bencode 标记");
            }
            return new Node(value, start, cursor);
        }

        private boolean atEnd() {
            if (cursor >= bytes.length) throw invalid("Torrent 容器截断");
            return bytes[cursor] == 'e';
        }
    }
}
