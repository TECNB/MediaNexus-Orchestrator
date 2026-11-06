package com.medianexus.orchestrator.service.settings.targets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.dto.accounts.AccountDtos.*;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class TargetStateTransactionsTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> rows = new HashMap<>();
    private final SystemSettingMapper mapper = mock(SystemSettingMapper.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final IngestTargetRegistry registry;
    private final OnboardingRecords records;
    private final TargetRegistration registration;

    TargetStateTransactionsTest() {
        when(mapper.selectSettingValue(anyString())).thenAnswer(call -> rows.get(call.getArgument(0)));
        when(mapper.selectSettingValueForUpdate(anyString())).thenAnswer(call -> rows.get(call.getArgument(0)));
        doAnswer(call -> { rows.putIfAbsent(call.getArgument(0), call.getArgument(1)); return null; })
                .when(mapper).insertSettingIfAbsent(anyString(), anyString());
        doAnswer(call -> { rows.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(mapper).upsertSetting(anyString(), anyString());
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        registry = new IngestTargetRegistry(mapper, json);
        records = new OnboardingRecords(mapper, json, registry, manager);
        registration = new TargetRegistration(mapper, json, registry, manager);
    }

    @Test
    void categoryDefaultCasPreservesOriginalBindingAndProtectsDefaultAccountFromStopping() throws Exception {
        var old = target("old"); var next = target("next");
        rows.put(IngestTargetRegistry.SETTING_KEY, json.writeValueAsString(new IngestTargetState(1, 4, true,
                List.of(old, next), Map.of("MOVIE", "old"))));
        var original = registry.bindingForPath("MOVIE", "/old/Movie/A").orElseThrow();
        assertThatThrownBy(() -> registration.setAcceptingNewTasks(4, "old", false)).isInstanceOf(BusinessException.class);
        registration.switchDefaults(4, Map.of("MOVIE", "next"));
        assertThat(registry.creationRoot("MOVIE", null)).isEqualTo("/next/Movie");
        assertThat(registry.bindingForPath("MOVIE", "/old/Movie/A").orElseThrow()).isEqualTo(original);
        assertThatThrownBy(() -> registration.switchDefaults(4, Map.of("MOVIE", "old"))).isInstanceOf(BusinessException.class);
        registration.setAcceptingNewTasks(5, "old", false);
        assertThat(registry.rootForExistingPath("MOVIE", "/old/Movie/A", null)).isEqualTo("/old/Movie");
        verify(mapper, atLeastOnce()).selectSettingValueForUpdate(IngestTargetRegistry.SETTING_KEY);
    }

    @Test
    void persistentClaimRefusesConcurrentContinueAndFencesExpiredOwner() throws Exception {
        Instant now = Instant.now();
        Execution initial = new Execution("execution-1", 0, "plan-1", 0, "target-1", "新号", List.of("MOVIE"),
                "PENDING", null, List.of(), List.of(), false, false, false, null, false, now.toString(), now.toString(), null);
        rows.put("target_execution_execution-1", json.writeValueAsString(new OnboardingRecords.ExecutionRecord(initial, null, null)));
        var claim = records.claim("execution-1", 0, "owner-A", now);
        assertThatThrownBy(() -> records.claim("execution-1", 1, "owner-B", now.plusSeconds(1)))
                .isInstanceOf(BusinessException.class);
        var takeover = records.claim("execution-1", 1, "owner-B", now.plusSeconds(601));
        Execution v = takeover.view();
        Execution finishedStep = new Execution(v.id(), v.executionVersion() + 1, v.planId(), v.planVersion(), v.targetId(),
                v.displayName(), v.categories(), "RUNNING", "OPENLIST_STORAGE", v.steps(), v.defaultChanges(), false,
                false, false, null, false, v.createdAt(), now.plusSeconds(602).toString(), null);
        assertThatThrownBy(() -> records.checkpoint("execution-1", claim.view().executionVersion(), "owner-A", finishedStep, now.plusSeconds(602)))
                .isInstanceOf(BusinessException.class);
        assertThat(records.checkpoint("execution-1", v.executionVersion(), "owner-B", finishedStep, now.plusSeconds(602))
                .view().executionVersion()).isEqualTo(3);
        verify(mapper, atLeastOnce()).selectSettingValueForUpdate("target_execution_execution-1");
    }

    @Test
    void planAndExecutionKeysAreSeparateAndUnsupportedPlanCannotStart() {
        String now = Instant.now().toString();
        OnboardingPlan plan = new OnboardingPlan("plan-2", 0, 0, "target-2", "新号", List.of("MOVIE"), null, null, null,
                List.of(), List.of(), List.of(), List.of(), true, false,
                List.of(new Issue("CD2_CLOUD_NAMING_UNSUPPORTED", "CD2_CLOUD", null, "尚不支持")), now, now);
        records.createPlan(new OnboardingRecords.PlanRecord(plan, "ciphertext-only", Map.of()));
        assertThatThrownBy(() -> records.begin("plan-2", 0, 0)).isInstanceOf(BusinessException.class);
        assertThat(rows.keySet()).containsExactly("target_plan_plan-2");
        assertThat(rows.get("target_plan_plan-2")).doesNotContain("password", "refreshToken");
    }

    @Test
    void finalCommitRequiresReadbackAndCompletedReplayCannotOverwriteLaterDefault() throws Exception {
        Instant now = Instant.now(); String time = now.toString();
        var old = target("old"); var next = target("next");
        rows.put(IngestTargetRegistry.SETTING_KEY, json.writeValueAsString(new IngestTargetState(1, 1, true, List.of(old), Map.of("MOVIE", "old"))));
        var changes = List.of(new DefaultChange("MOVIE", "old", "next"));
        var plan = new OnboardingPlan("plan-final", 0, 1, "next", "next", List.of("MOVIE"), next.ingestPrefix(), next.cloudDrivePrefix(), next.mediaSourcePrefix(),
                List.of(new PlannedBinding("MOVIE", "/next/Movie", null, "/mount/next/Movie", "/strm/next", "library")),
                List.of(), changes, List.of(), true, true, List.of(), time, time);
        records.createPlan(new OnboardingRecords.PlanRecord(plan, "temporary-protected-payload", Map.of()));
        java.util.ArrayList<Step> steps = new java.util.ArrayList<>();
        for (String stage : List.of("OPENLIST_STORAGE", "MEDIA_DIRECTORIES", "CD2_CLOUD", "CD2_MOUNT", "MEDIA_READABLE", "AS_BINDINGS", "EMBY_PATHS")) {
            steps.add(new Step(stage, stage.equals("AS_BINDINGS") || stage.equals("EMBY_PATHS") ? "MOVIE" : null, "SUCCEEDED", 1, List.of(), List.of(), null, null, time, time));
        }
        var incomplete = new Execution("execution-final", 2, "plan-final", 0, "next", "next", List.of("MOVIE"), "RUNNING", "READBACK", List.copyOf(steps),
                changes, false, false, false, null, false, time, time, null);
        rows.put("target_execution_execution-final", json.writeValueAsString(new OnboardingRecords.ExecutionRecord(incomplete, "owner-A", now.plusSeconds(600).toString())));
        var observation = new OnboardingRecords.Observation(42, "/next", "WebDAV", "/cloud/next", "/mount/next", Map.of("library", "Movies"),
                new Capacity(100L, 0L, 100L, time, "AVAILABLE", null), List.of(), time, time);
        assertThatThrownBy(() -> records.commitVerifiedExecution("execution-final", 2, "owner-A", next, observation, registration, now))
                .isInstanceOf(BusinessException.class);
        assertThat(registry.creationRoot("MOVIE", null)).isEqualTo("/old/Movie");
        steps.add(new Step("READBACK", null, "SUCCEEDED", 1, List.of(), List.of(), null, null, time, time));
        var verified = new Execution(incomplete.id(), 2, incomplete.planId(), 0, "next", "next", List.of("MOVIE"), "RUNNING", "READBACK", List.copyOf(steps),
                changes, false, false, false, null, false, time, time, null);
        rows.put("target_execution_execution-final", json.writeValueAsString(new OnboardingRecords.ExecutionRecord(verified, "owner-A", now.plusSeconds(600).toString())));
        var result = records.commitVerifiedExecution("execution-final", 2, "owner-A", next, observation, registration, now);
        assertThat(result.status()).isEqualTo("SUCCEEDED"); assertThat(result.defaultsCommitted()).isTrue();
        assertThat(result.playbackVerified()).isFalse(); assertThat(records.plan("plan-final").protectedPayload()).isNull();
        registration.switchDefaults(2, Map.of("MOVIE", "old"));
        assertThat(records.commitVerifiedExecution("execution-final", 2, "owner-A", next, observation, registration, now)).isEqualTo(result);
        assertThat(registry.creationRoot("MOVIE", null)).isEqualTo("/old/Movie");
    }

    @Test
    void changedConfigurationVersionAfterReadbackRejectsExecutionAndAdoptionCommit() throws Exception {
        Instant now = Instant.now(); String time = now.toString();
        var old = target("old"); var next = target("next");
        rows.put(IngestTargetRegistry.SETTING_KEY, json.writeValueAsString(new IngestTargetState(1, 1, true,
                List.of(old), Map.of("MOVIE", "old"))));
        var versions = List.of(new ConnectionVersion("OPENLIST", 0, null), new ConnectionVersion("CD2", 0, null),
                new ConnectionVersion("AS", 0, null), new ConnectionVersion("EMBY", 0, null));
        var changes = List.of(new DefaultChange("MOVIE", "old", "next"));
        var plan = new OnboardingPlan("guard-plan", 0, 1, "next", "next", List.of("MOVIE"), next.ingestPrefix(), next.cloudDrivePrefix(), next.mediaSourcePrefix(),
                List.of(new PlannedBinding("MOVIE", "/next/Movie", null, "/mount/next/Movie", "/strm/next", "library")),
                versions, changes, List.of(), true, true, List.of(), time, time);
        records.createPlan(new OnboardingRecords.PlanRecord(plan, "protected-payload", Map.of()));
        var steps = new java.util.ArrayList<Step>();
        for (String stage : List.of("OPENLIST_STORAGE", "MEDIA_DIRECTORIES", "CD2_CLOUD", "CD2_MOUNT", "MEDIA_READABLE", "AS_BINDINGS", "EMBY_PATHS", "READBACK")) {
            steps.add(new Step(stage, stage.equals("AS_BINDINGS") || stage.equals("EMBY_PATHS") ? "MOVIE" : null,
                    "SUCCEEDED", 1, List.of(new ResourceRef("storage", "42", "/next")), List.of(), null, null, time, time));
        }
        var execution = new Execution("guard-plan", 2, "guard-plan", 0, "next", "next", List.of("MOVIE"), "RUNNING", "READBACK", steps,
                changes, false, false, false, null, false, time, time, null);
        rows.put("target_execution_guard-plan", json.writeValueAsString(new OnboardingRecords.ExecutionRecord(execution, "owner", now.plusSeconds(600).toString())));
        var observation = new OnboardingRecords.Observation(42, "/next", "WebDAV", "/cloud/next", "/mount/next", Map.of(), null, List.of(), time, time);
        rows.put("managed_settings_openlist", "{\"version\":1,\"overrides\":{},\"restored\":false}");

        assertThatThrownBy(() -> records.commitVerifiedExecution("guard-plan", 2, "owner", next, observation, registration, now))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getHttpStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
        assertThat(registry.creationRoot("MOVIE", null)).isEqualTo("/old/Movie");
        assertThat(records.execution("guard-plan").view().defaultsCommitted()).isFalse();
        assertThat(rows).doesNotContainKey("target_observation_next");

        rows.remove(IngestTargetRegistry.SETTING_KEY);
        records.createAdoption(new OnboardingRecords.AdoptionRecord(new AdoptionPlan("guard-adopt", 0, 0, "next", "next", true,
                null, versions, List.of(), List.of(), time), Map.of()));
        assertThatThrownBy(() -> records.completeAdoption("guard-adopt", 0, 0, next, observation, registration))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getHttpStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
        assertThat(registry.readState().managed()).isFalse();
        assertThat(rows).doesNotContainKey("target_observation_next");
    }

    private IngestTargetState.Target target(String id) throws Exception {
        String source = "/mount/" + id + "/Movie";
        String body = """
                {"task_name":"Movie","media_dir":"%s","symlink_creator":true,"metadata_copyer":true,
                 "num_threads":"4","symlink_dir_checker":false,"symlink_checker":false,"metadata_checker":false,
                 "symlink_size":"0","symlink_ext":".strm","metadata_ext":".nfo"}
                """.formatted(source).trim();
        return new IngestTargetState.Target(id, id, "/" + id, "/cloud/" + id, "/mount/" + id, true,
                List.of(new IngestTargetState.Binding("MOVIE", "/" + id + "/Movie", null, "uuid-" + id, body, "/strm/" + id, "library")));
    }
}
