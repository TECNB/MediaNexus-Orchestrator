package com.medianexus.orchestrator.service.organization;

import java.util.List;
import java.util.Map;

/** CD2-only contract: capture is read-only; checkpoint must commit before returning permission to write. */
public interface DurableLibraryOrganizer extends LibraryOrganizer {
    void requireRecoveryWritersQuiet();

    Snapshot capture(LibraryOrganizationPlan plan);

    void resume(Snapshot snapshot, Map<String, ActionState> actions, Checkpoint checkpoint);

    void verifyFiles(Snapshot snapshot);

    record Snapshot(LibraryOrganizationPlan plan, String namespaceProof, Map<String, String> cloudPaths,
                    List<FileEvidence> files) {
        public Snapshot {
            cloudPaths = Map.copyOf(cloudPaths);
            files = List.copyOf(files);
        }
    }

    record FileEvidence(String originalPath, String renamedPath, String finalPath,
                        String providerFileId, String gcid, long bytes) {}

    enum ActionState { SENT, DONE }

    @FunctionalInterface
    interface Checkpoint {
        void save(String action, ActionState state);
    }
}
