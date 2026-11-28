package com.medianexus.orchestrator.integration.smartstrm;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.config.SmartStrmProperties;
import com.medianexus.orchestrator.integration.autosymlink.AutoSymlinkClientException;
import com.medianexus.orchestrator.integration.onboarding.AutoSymlinkAccountClient;
import com.medianexus.orchestrator.config.AutoSymlinkProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class SmartStrmAccountClientTest {
    @TempDir Path temporary;
    final ObjectMapper json = new ObjectMapper();
    final String uuid = "b3da792f-c45e-427c-852b-3c18b0cf627a";

    @Test void readsLegacyFormOnlyWhenActualStorageTaskAndAliasMatch() throws Exception {
        Path root = temporary.toRealPath();
        var p = new SmartStrmProperties(); p.setPermittedOutputRoot(root.toString()); p.setOutputAliasRoot(Files.createDirectory(root.resolve("SmartStrm")).toString());
        var outputs = new SmartStrmOutputs(p);
        ObjectNode form = form(root.resolve("Movies"));
        outputs.prepare(uuid, form.path("symlink_dir").asText());
        var http = mock(SmartStrmClient.class); when(http.isEnabled()).thenReturn(true);
        var accounts = new SmartStrmAccountClient(http, outputs, json);
        ObjectNode config = (ObjectNode) json.readTree(json.writeValueAsString(accounts.taskConfiguration(uuid, form)));
        ObjectNode tasks = json.createObjectNode(); tasks.putObject(uuid).set("config", config);
        tasks.putObject("Quark-TV").putObject("config").put("storage", "quark");
        when(http.tasks()).thenReturn(tasks);
        ObjectNode storage = accounts.storageConfiguration(uuid, form);
        when(http.storages()).thenReturn(json.createArrayNode().add(storage));
        var legacy = new AutoSymlinkAccountClient(json);
        ReflectionTestUtils.setField(legacy, "smartStrm", accounts);
        var disabled = new AutoSymlinkProperties(); disabled.setDisabled(true);
        assertThat(legacy.readConfiguration(disabled, uuid)).isEqualTo(form);
        assertThat(legacy.readTasks(disabled)).singleElement().satisfies(view -> {
            assertThat(view.uuid()).isEqualTo(uuid); assertThat(view.symlinkDir()).isEqualTo(root.resolve("Movies").toString());
        });
        assertThat(config.path("plugins").path("custom_strm_name").path("custom_name").asText()).isEqualTo("{name}.strm");
        assertThat(config.path("plugins").path("scan_filter").path("media_ext").asText()).isEqualTo("mkv,mp4");
        assertThat(config.path("plugins").path("scan_filter").path("media_size_min").asInt()).isEqualTo(100);
        assertThat(config.path("plugins").path("scan_filter").path("copy_ext").asText()).isEqualTo("nfo,srt");
        assertThat(config.path("watchdog").asBoolean()).isFalse();
        assertThat(config.path("crontab").asText()).isEmpty();
        assertThat(accounts.migratedTaskIds()).containsExactly(uuid);
        ((ObjectNode) config.path("plugins").path("scan_filter")).put("media_size_min", 101);
        assertThatThrownBy(() -> accounts.readConfiguration(uuid)).hasMessageContaining("实际任务配置");
        ((ObjectNode) config.path("plugins").path("scan_filter")).put("media_size_min", 100);
        config.put("storage_path", "/Different");
        assertThatThrownBy(() -> accounts.readConfiguration(uuid)).hasMessageContaining("实际任务配置");
        assertThatThrownBy(() -> legacy.readConfiguration(disabled, uuid))
                .isInstanceOf(com.medianexus.orchestrator.common.exception.BusinessException.class);
        config.put("storage_path", "/"); storage.put("root_path", "/different-mount");
        assertThatThrownBy(() -> accounts.readConfiguration(uuid)).hasMessageContaining("实际本地存储");
        storage.put("root_path", "/mount/Movies");
        Files.delete(root.resolve("SmartStrm").resolve(uuid));
        Files.createSymbolicLink(root.resolve("SmartStrm").resolve(uuid), Files.createDirectory(root.resolve("Other")));
        assertThatThrownBy(() -> accounts.readConfiguration(uuid)).hasMessageContaining("输出别名");
    }

    @Test void aliasesPreserveExistingOutputAndRejectCollisionsAndEscapes() throws Exception {
        Path root = temporary.toRealPath();
        Path aliases = Files.createDirectory(root.resolve("SmartStrm"));
        var p = new SmartStrmProperties(); p.setPermittedOutputRoot(root.toString()); p.setOutputAliasRoot(aliases.toString());
        var outputs = new SmartStrmOutputs(p);
        Path movies = Files.createDirectory(root.resolve("Movies"));
        Files.writeString(movies.resolve("Film (2026) Extended.strm"), "/mount/Movies/Film (2026) Extended.mkv");
        outputs.prepare(uuid, movies.toString());
        assertThat(Files.readString(aliases.resolve(uuid).resolve("Film (2026) Extended.strm")))
                .isEqualTo("/mount/Movies/Film (2026) Extended.mkv");
        assertThatThrownBy(() -> outputs.prepare(uuid, root.resolve("Other").toString())).hasMessageContaining("输出别名");
        assertThatThrownBy(() -> outputs.prepare(uuid, root.resolve("../outside").toString())).hasMessageContaining("允许根");
        assertThatThrownBy(() -> outputs.prepare("../escape", movies.toString())).hasMessageContaining("UUID");
        assertThatThrownBy(() -> outputs.prepare(uuid, aliases.resolve("nested").toString())).hasMessageContaining("重叠");
        Files.delete(aliases.resolve(uuid)); Files.createDirectory(aliases.resolve(uuid));
        assertThatThrownBy(() -> outputs.prepare(uuid, movies.toString())).hasMessageContaining("输出别名");
        assertThat(Files.isDirectory(aliases.resolve(uuid))).isTrue();
    }

    @Test void unknownCreateDoesNotReplayTaskWrite() throws Exception {
        var outputs = mock(SmartStrmOutputs.class); var http = mock(SmartStrmClient.class);
        when(http.tasks()).thenReturn(json.createObjectNode());
        doThrow(new AutoSymlinkClientException("unknown")).when(http).saveTask(anyString(), any());
        var accounts = new SmartStrmAccountClient(http, outputs, json);
        assertThatThrownBy(() -> accounts.createAndReadUuid(form(Path.of("/srv/media/STRM/Movies")))).hasMessage("unknown");
        verify(http, times(1)).saveTask(anyString(), any());
        verify(http, times(1)).saveStorage(anyString(), any());
    }

    @Test void onboardingBrowsesOnlyExactNewMountThroughBootstrapStorage() {
        var outputs = mock(SmartStrmOutputs.class); var http = mock(SmartStrmClient.class);
        when(http.tasks()).thenReturn(json.createObjectNode());
        when(http.storages()).thenReturn(json.createArrayNode().add(json.createObjectNode()
                .put("name", "CloudNAS-Browse").put("driver", "local").put("root_path", "/srv/media/CloudNAS")));
        when(http.browse("CloudNAS-Browse", "/PikPak-new/Movies")).thenReturn(json.createArrayNode());
        var accounts = new SmartStrmAccountClient(http, outputs, json);
        accounts.verifyReadableDirectory("/srv/media/CloudNAS/PikPak-new/Movies");
        verify(http).browse("CloudNAS-Browse", "/PikPak-new/Movies");
        verify(http, never()).browse(anyString(), eq("/"));
    }

    @Test void disabledCopyingNeverFallsBackToGlobalExtensions() {
        var accounts = new SmartStrmAccountClient(mock(SmartStrmClient.class), mock(SmartStrmOutputs.class), json);
        var form = form(Path.of("/srv/media/STRM/Movies")).put("metadata_copyer", false);
        assertThat(accounts.taskConfiguration(uuid, form).path("plugins").path("scan_filter").path("copy_ext").asText())
                .isEqualTo("medianexus-no-copy");
    }

    @Test void rejectsCloudModeRelativeSourceAndInvalidSizeInsteadOfPretendingCompatibility() {
        var accounts = new SmartStrmAccountClient(mock(SmartStrmClient.class), mock(SmartStrmOutputs.class), json);
        var form = form(Path.of("/srv/media/STRM/Movies"));
        assertThatThrownBy(() -> accounts.taskConfiguration(uuid, form.deepCopy().put("strm_mode", "cloud")))
                .hasMessageContaining("cloud 模式尚不支持");
        assertThatThrownBy(() -> accounts.taskConfiguration(uuid, form.deepCopy().put("media_dir", "/mount/../Movies")))
                .hasMessageContaining("规范绝对路径");
        assertThatThrownBy(() -> accounts.taskConfiguration(uuid, form.deepCopy().put("symlink_size", "-1")))
                .hasMessageContaining("最小大小");
        assertThat(accounts.taskConfiguration(uuid, form.deepCopy().put("symlink_creator", false))
                .path("plugins").path("scan_filter").path("media_ext").asText()).isEqualTo("medianexus-no-media");
    }

    ObjectNode form(Path output) {
        return json.createObjectNode().put("task_name", "Movies").put("media_dir", "/mount/Movies").put("symlink_dir", output.toString())
                .put("symlink_mode", "strm").put("strm_mode", "local").put("exclude_folder", "")
                .put("symlink_creator", true).put("metadata_copyer", true).put("symlink_ext", ".mkv,.mp4")
                .put("metadata_ext", ".nfo .srt").put("symlink_size", "100").put("sync_scheduled", true).put("sync_time", "0 3 * * *");
    }
}
