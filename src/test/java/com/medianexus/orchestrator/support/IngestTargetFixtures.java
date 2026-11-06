package com.medianexus.orchestrator.support;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import com.medianexus.orchestrator.service.settings.targets.IngestTargetRegistry;
import com.medianexus.orchestrator.service.settings.targets.IngestTargetState;
import java.util.List;
import java.util.Map;

/** 使用真实 registry，只有持久化读取由内存中的已登记状态替代。 */
public final class IngestTargetFixtures {
    private final SystemSettingMapper mapper = mock(SystemSettingMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private IngestTargetState state;
    public final IngestTargetRegistry registry = new IngestTargetRegistry(mapper, json);

    public static IngestTargetRegistry legacy() {
        return new IngestTargetFixtures().registry;
    }

    public void useDefault(String targetId) {
        store(new IngestTargetState(1, 1, true,
                List.of(target("old", "原主号", "/OldCloud"), target("new", "新账号", "/NewCloud")),
                Map.of("MOVIE", targetId, "TV", targetId, "ANIME", targetId, "ADULT", targetId)));
    }

    public void stopOriginal() {
        List<IngestTargetState.Target> targets = state.targets().stream().map(target ->
                target.id().equals("old") ? new IngestTargetState.Target(target.id(), target.displayName(),
                        target.ingestPrefix(), target.cloudDrivePrefix(), target.mediaSourcePrefix(), false,
                        target.bindings()) : target).toList();
        store(new IngestTargetState(1, 2, true, targets, state.categoryDefaults()));
    }

    private IngestTargetState.Target target(String id, String name, String cloudRoot) {
        return new IngestTargetState.Target(id, name, "/" + id, cloudRoot, "/mount/" + id, true,
                List.of(binding(id, "MOVIE", "Movies"), binding(id, "TV", "TV"),
                        binding(id, "ANIME", "Anime"), binding(id, "ADULT", "Adult")));
    }

    private IngestTargetState.Binding binding(String id, String category, String folder) {
        String root = "/" + id + "/" + folder;
        String body = """
                {"task_name":"%s-%s","media_dir":"/mount/%s/%s","symlink_creator":true,
                 "metadata_copyer":true,"num_threads":"4","symlink_dir_checker":false,
                 "symlink_checker":false,"metadata_checker":false,"symlink_size":"0",
                 "symlink_ext":".strm","metadata_ext":".nfo"}
                """.formatted(id, category, id, folder).trim();
        return new IngestTargetState.Binding(category, root,
                "ANIME".equals(category) ? root + "/{themoviedbName}/Season {seasonFormat}" : null,
                id + "-" + category, body, "/strm/" + id + "/" + folder, "library-" + category);
    }

    private void store(IngestTargetState state) {
        this.state = state;
        try {
            when(mapper.selectSettingValue(IngestTargetRegistry.SETTING_KEY)).thenReturn(json.writeValueAsString(state));
        } catch (JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }
}
