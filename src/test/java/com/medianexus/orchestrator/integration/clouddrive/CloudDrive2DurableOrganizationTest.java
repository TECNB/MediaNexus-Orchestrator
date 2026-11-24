package com.medianexus.orchestrator.integration.clouddrive;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.medianexus.orchestrator.config.CloudDrive2Properties;
import com.medianexus.orchestrator.service.organization.DurableLibraryOrganizer;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan;
import com.medianexus.orchestrator.support.IngestTargetFixtures;
import java.util.*;
import org.junit.jupiter.api.Test;

class CloudDrive2DurableOrganizationTest {
    final CloudDrive2FileOperations files = mock(CloudDrive2FileOperations.class);
    final CloudDrive2Properties properties = new CloudDrive2Properties();
    final com.medianexus.orchestrator.integration.openlist.OpenListClient identities = mock(com.medianexus.orchestrator.integration.openlist.OpenListClient.class);
    final Map<String, CloudDrive2FileEntry> visible = new LinkedHashMap<>();
    final LibraryOrganizationPlan plan = new LibraryOrganizationPlan("/pikpak/movie",
            List.of(new LibraryOrganizationPlan.RenameOperation("/pikpak/movie/sub/old.mkv", "new.mkv")),
            List.of(new LibraryOrganizationPlan.MoveOperation("/pikpak/movie/sub/new.mkv", "/pikpak/movie")),
            List.of(new LibraryOrganizationPlan.DeleteOperation("/pikpak/movie/aux.txt")), Set.of("new.mkv"), Set.of());

    CloudDrive2LibraryOrganizer organizer() {
        properties.setIngestPathPrefix("/pikpak");
        when(identities.offlineDirectoryForPath(anyString())).thenReturn(
                new com.medianexus.orchestrator.integration.openlist.OpenListClient.OfflineDirectory("account", "root", "/movie"));
        properties.setCloudDrivePathPrefix("/Cloud");
        when(files.list(anyString(), eq(true))).thenAnswer(call -> {
            String parent = call.getArgument(0);
            return visible.values().stream().filter(f -> LibraryOrganizationPlan.parentPath(f.fullPath()).equals(parent)).toList();
        });
        doAnswer(call -> {
            String path = call.getArgument(0);
            var entry = visible.get(path.replace("/pikpak", "/Cloud"));
            if (entry == null) return null;
            return new com.medianexus.orchestrator.integration.openlist.OpenListClient.FileIdentity("official-file-id", "a".repeat(40), entry.size(), path);
        }).when(identities).readIdentity(anyString());
        var organizer = new CloudDrive2LibraryOrganizer(files, properties, IngestTargetFixtures.legacy());
        org.springframework.test.util.ReflectionTestUtils.setField(organizer, "openList", identities);
        return organizer;
    }
    void source() { visible.put("/Cloud/movie/sub/old.mkv", new CloudDrive2FileEntry("old.mkv", "/Cloud/movie/sub/old.mkv", 100, false, "encoded-original-path")); }

    @Test void databaseFailureBeforeIntentPermitsZeroWrites() {
        var organizer = organizer(); source();
        var snapshot = organizer.capture(plan);
        assertThatThrownBy(() -> organizer.resume(snapshot, new HashMap<>(), (key, state) -> { throw new IllegalStateException("DB"); }))
                .isInstanceOf(IllegalStateException.class);
        verify(files, never()).rename(anyList()); verify(files, never()).move(anyList(), anyString()); verify(files, never()).delete(anyList());
    }
    @Test void sentIntentWithUnchangedSourceIsNeverResentByNewInstance() {
        var organizer = organizer(); source();
        var snapshot = organizer.capture(plan);
        Map<String, DurableLibraryOrganizer.ActionState> states = new HashMap<>();
        doThrow(new IllegalStateException("timeout")).when(files).rename(anyList());
        assertThatThrownBy(() -> organizer.resume(snapshot, states, states::put)).isInstanceOf(RuntimeException.class);
        assertThat(states.values()).containsExactly(DurableLibraryOrganizer.ActionState.SENT);
        assertThatThrownBy(() -> organizer().resume(snapshot, states, states::put)).isInstanceOf(RuntimeException.class);
        verify(files, times(1)).rename(anyList()); verify(files, never()).move(anyList(), anyString());
    }
    @Test void observesCompletedRenameAfterLostCheckpointWithoutReplayingIt() {
        var organizer = organizer(); source();
        var snapshot = organizer.capture(plan);
        Map<String, DurableLibraryOrganizer.ActionState> states = new HashMap<>();
        doAnswer(call -> { visible.clear(); visible.put("/Cloud/movie/sub/new.mkv", new CloudDrive2FileEntry("new.mkv", "/Cloud/movie/sub/new.mkv", 100, false, "encoded-renamed-path")); return null; }).when(files).rename(anyList());
        assertThatThrownBy(() -> organizer.resume(snapshot, states, (key, state) -> {
            if (state == DurableLibraryOrganizer.ActionState.DONE) throw new IllegalStateException("DB lost");
            states.put(key, state);
        })).isInstanceOf(RuntimeException.class);
        doAnswer(call -> { visible.clear(); visible.put("/Cloud/movie/new.mkv", new CloudDrive2FileEntry("new.mkv", "/Cloud/movie/new.mkv", 100, false, "encoded-final-path")); return null; }).when(files).move(anyList(), anyString());
        organizer().resume(snapshot, states, states::put);
        verify(files, times(1)).rename(anyList()); verify(files, times(1)).move(anyList(), anyString()); verify(files, never()).delete(anyList());
    }
    @Test void collisionAndChangedSizeBlockBeforeAnyMutation() {
        var organizer = organizer(); source();
        visible.put("/Cloud/movie/NEW.mkv", new CloudDrive2FileEntry("NEW.mkv", "/Cloud/movie/NEW.mkv", 100, false, "other"));
        assertThatThrownBy(() -> organizer.capture(plan)).isInstanceOf(RuntimeException.class);
        visible.remove("/Cloud/movie/NEW.mkv");
        var snapshot = organizer.capture(plan);
        visible.put("/Cloud/movie/sub/old.mkv", new CloudDrive2FileEntry("old.mkv", "/Cloud/movie/sub/old.mkv", 101, false, "stable"));
        assertThatThrownBy(() -> organizer.resume(snapshot, new HashMap<>(), (key, state) -> {})).isInstanceOf(RuntimeException.class);
        verify(files, never()).rename(anyList());
    }
    @Test void thirtyVideosWithTwentySixAlreadyFinalOnlyMoveFour() {
        var organizer = organizer();
        Set<String> expected = new LinkedHashSet<>();
        List<LibraryOrganizationPlan.MoveOperation> moves = new ArrayList<>();
        for (int episode = 1; episode <= 30; episode++) {
            String name = "Series - S01E%02d.mkv".formatted(episode);
            expected.add(name);
            String parent = Set.of(8, 12, 16, 23).contains(episode) ? "/Cloud/movie/sub" : "/Cloud/movie";
            visible.put(parent + "/" + name, new CloudDrive2FileEntry(name, parent + "/" + name, 100 + episode, false, "path-" + name));
            if (parent.endsWith("sub")) moves.add(new LibraryOrganizationPlan.MoveOperation("/pikpak/movie/sub/" + name, "/pikpak/movie"));
        }
        doAnswer(call -> {
            String path = call.getArgument(0);
            var entry = visible.get(path.replace("/pikpak", "/Cloud"));
            if (entry == null) return null;
            return new com.medianexus.orchestrator.integration.openlist.OpenListClient.FileIdentity("id-" + entry.name(), "a".repeat(40), entry.size(), path);
        }).when(identities).readIdentity(anyString());
        var partial = new LibraryOrganizationPlan("/pikpak/movie", List.of(), moves, List.of(), expected, Set.of());
        var snapshot = organizer.capture(partial);
        assertThat(snapshot.files()).hasSize(30);
        doAnswer(call -> {
            List<String> sources = call.getArgument(0);
            var file = visible.remove(sources.get(0));
            String path = "/Cloud/movie/" + file.name();
            visible.put(path, new CloudDrive2FileEntry(file.name(), path, file.size(), false, "new-path-id"));
            return null;
        }).when(files).move(anyList(), anyString());
        organizer.resume(snapshot, new HashMap<>(), (key, state) -> {});
        verify(files, times(4)).move(anyList(), anyString());
        verify(files, never()).rename(anyList()); verify(files, never()).delete(anyList());
    }

    @Test void sameGcidDifferentOfficialFileIdIsNotIdentity() {
        var organizer = organizer(); source();
        var snapshot = organizer.capture(plan);
        doReturn(new com.medianexus.orchestrator.integration.openlist.OpenListClient.FileIdentity("other-copy", "a".repeat(40), 100,
                "/pikpak/movie/sub/old.mkv")).when(identities).readIdentity(anyString());
        assertThatThrownBy(() -> organizer.resume(snapshot, new HashMap<>(), (key, state) -> {})).isInstanceOf(RuntimeException.class);
        verify(files, never()).rename(anyList()); verify(files, never()).move(anyList(), anyString());
    }
}
