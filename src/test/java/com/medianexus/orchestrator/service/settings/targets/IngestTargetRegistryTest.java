package com.medianexus.orchestrator.service.settings.targets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IngestTargetRegistryTest {

    private final ObjectMapper json = new ObjectMapper();
    private final SystemSettingMapper mapper = mock(SystemSettingMapper.class);
    private final IngestTargetRegistry registry = new IngestTargetRegistry(mapper, json);

    @Test
    void unadoptedRegistryUsesOnlyExplicitLegacyMappings() {
        assertThat(registry.creationRoot("MOVIE", "/legacy/Movie")).isEqualTo("/legacy/Movie");
        assertThat(registry.creationAnimeTemplate("/legacy/Anime/{title}")).isEqualTo("/legacy/Anime/{title}");
        assertThat(registry.rootForExistingPath("MOVIE", "/legacy/Movie/A", "/legacy/Movie"))
                .isEqualTo("/legacy/Movie");
        assertThat(registry.bindingForPath("MOVIE", "/legacy/Movie/A")).isEmpty();
        assertThat(registry.toCloudDrivePath("/legacy/Movie/A", "/legacy", "/OldCloud"))
                .isEqualTo("/OldCloud/Movie/A");
        assertThat(registry.cloudDriveRootForIngestPath("/legacy/Movie/A", "/legacy", "/OldCloud"))
                .isEqualTo("/OldCloud");
        assertThat(registry.ownsMediaSourcePath("/source2/A", "/source")).isFalse();
        assertThatThrownBy(() -> registry.toCloudDrivePath("/legacy2/A", "/legacy", "/OldCloud"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void defaultChangeKeepsStoppedOriginalTargetsAndTheirBindings() throws Exception {
        store(state("old"));
        assertThat(registry.creationRoot("MOVIE", "/ignored")).isEqualTo("/old/Media/Movie");
        IngestTargetRegistry.CategoryBinding before = registry.bindingForPath("MOVIE", "/old/Media/Movie/中文 空格")
                .orElseThrow();
        IngestTargetState switched = state("new");
        IngestTargetState.Target old = switched.targets().get(0);
        IngestTargetState.Target stopped = new IngestTargetState.Target(old.id(), old.displayName(),
                old.ingestPrefix(), old.cloudDrivePrefix(), old.mediaSourcePrefix(), false, old.bindings());
        store(new IngestTargetState(1, 2, true, List.of(stopped, switched.targets().get(1)), Map.of("MOVIE", "new")));

        assertThat(registry.creationRoot("MOVIE", "/ignored")).isEqualTo("/new/Media/Movie");
        assertThat(registry.bindingForPath("MOVIE", "/old/Media/Movie/中文 空格").orElseThrow()).isEqualTo(before);
        assertThat(before.targetName()).isEqualTo("原主号");
        assertThat(registry.rootsForExistingMedia("MOVIE", "/ignored"))
                .containsExactly("/new/Media/Movie", "/old/Media/Movie");
        assertThat(registry.rootForExistingPath("MOVIE", "/old/Media/Movie/中文 空格", "/new/Media/Movie"))
                .isEqualTo("/old/Media/Movie");
    }

    @Test
    void managedPathsUseTheirOwnCloudRootAndRejectUnknownOrWrongCategory() throws Exception {
        store(state("new"));
        assertThat(registry.toCloudDrivePath("/new/Media/Movie/A", "/old", "/OldCloud"))
                .isEqualTo("/NewCloud/Media/Movie/A");
        assertThat(registry.cloudDriveRootForIngestPath("/new/Media/Movie/A", "/old", "/OldCloud"))
                .isEqualTo("/NewCloud");
        assertThat(registry.toCloudDriveMediaPath("/mount/new/Media/Movie/A", "/mount/old", "/OldCloud"))
                .isEqualTo("/NewCloud/Media/Movie/A");
        assertThat(registry.ownsMediaSourcePath("/mount/new/Media/Movie/A", "/ignored")).isTrue();
        assertThat(registry.ownsMediaSourcePath("/mount/new2/A", "/mount/new2")).isFalse();
        assertThatThrownBy(() -> registry.bindingForPath("MOVIE", "/new2/Media/Movie/A"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> registry.rootForExistingPath("TV", "/new/Media/Movie/A", "/new/Media/Movie"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> registry.toCloudDriveMediaPath("/mount/new", "/ignored", "/ignored"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> registry.toCloudDrivePath("/new/../old/A", "/ignored", "/ignored"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void corruptOrOverlappingStateNeverFallsBackAndSecretBearingFormsAreRejected() throws Exception {
        when(mapper.selectSettingValue(IngestTargetRegistry.SETTING_KEY)).thenReturn("{}");
        assertThatThrownBy(() -> registry.creationRoot("MOVIE", "/legacy"))
                .isInstanceOf(BusinessException.class);
        IngestTargetState valid = state("old");
        IngestTargetState.Target old = valid.targets().get(0);
        IngestTargetState.Target overlapping = new IngestTargetState.Target("overlap", "重叠",
                "/old/child", "/AnotherCloud", "/anotherMount", true, List.of(binding("/old/child", "/anotherMount")));
        store(new IngestTargetState(1, 1, true, List.of(old, overlapping), Map.of("MOVIE", "old")));
        assertThatThrownBy(() -> registry.creationRoot("MOVIE", "/legacy"))
                .isInstanceOf(BusinessException.class);

        String form = old.bindings().get(0).asRequestBodyJson();
        String unsafeForm = form.substring(0, form.length() - 1) + ",\"cloud_url\":\"sensitive-value\"}";
        IngestTargetState.Binding unsafe = new IngestTargetState.Binding("MOVIE", "/old/Media/Movie", null,
                "real-uuid-old", unsafeForm, "/strm/old/Movie", "library-movie");
        store(new IngestTargetState(1, 1, true, List.of(new IngestTargetState.Target("old", "原主号", "/old",
                "/OldCloud", "/mount/old", true, List.of(unsafe))), Map.of("MOVIE", "old")));
        assertThatThrownBy(() -> registry.bindingForPath("MOVIE", "/old/Media/Movie/A"))
                .isInstanceOf(BusinessException.class).hasMessageNotContaining("sensitive-value");
    }

    private void store(IngestTargetState state) throws Exception {
        when(mapper.selectSettingValue(IngestTargetRegistry.SETTING_KEY)).thenReturn(json.writeValueAsString(state));
    }

    private IngestTargetState state(String defaultTarget) throws Exception {
        return new IngestTargetState(1, 1, true, List.of(
                new IngestTargetState.Target("old", "原主号", "/old", "/OldCloud", "/mount/old", true,
                        List.of(binding("/old", "/mount/old"))),
                new IngestTargetState.Target("new", "新盘", "/new", "/NewCloud", "/mount/new", true,
                        List.of(binding("/new", "/mount/new")))), Map.of("MOVIE", defaultTarget));
    }

    private IngestTargetState.Binding binding(String prefix, String source) throws Exception {
        String body = """
                {"task_name":"Movie","media_dir":"%s/Media/Movie","symlink_creator":true,
                 "metadata_copyer":true,"num_threads":"4","symlink_dir_checker":false,
                 "symlink_checker":false,"metadata_checker":false,"symlink_size":"0",
                 "symlink_ext":".strm","metadata_ext":".nfo"}
                """.formatted(source).trim();
        return new IngestTargetState.Binding("MOVIE", prefix + "/Media/Movie", null, "real-uuid-" + prefix.substring(1),
                body, "/strm" + prefix + "/Movie", "library-movie");
    }
}
