package com.medianexus.orchestrator.service.settings.targets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.dto.accounts.AccountDtos.*;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import com.medianexus.orchestrator.model.SystemSetting;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OnboardingRecordsRetentionTest {
    @Test
    void expiredAuthorizationIsRemovedWithoutDeletingResourcesHistoryOrAnActiveLease() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Map<String, String> rows = new LinkedHashMap<>();
        SystemSettingMapper mapper = mock(SystemSettingMapper.class);
        when(mapper.selectSettingValue(anyString())).thenAnswer(c -> rows.get(c.getArgument(0)));
        when(mapper.selectSettingValueForUpdate(anyString())).thenAnswer(c -> rows.get(c.getArgument(0)));
        doAnswer(c -> { rows.putIfAbsent(c.getArgument(0), c.getArgument(1)); return null; })
                .when(mapper).insertSettingIfAbsent(anyString(), anyString());
        doAnswer(c -> { rows.put(c.getArgument(0), c.getArgument(1)); return null; })
                .when(mapper).upsertSetting(anyString(), anyString());
        when(mapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenAnswer(c ->
                rows.entrySet().stream().filter(e -> e.getKey().startsWith("target_plan_")).map(e -> {
                    SystemSetting row = new SystemSetting(); row.setSettingKey(e.getKey()); row.setSettingValue(e.getValue()); return row;
                }).toList());
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var records = new OnboardingRecords(mapper, json, new IngestTargetRegistry(mapper, json), tx);
        Instant at = Instant.parse("2100-01-02T00:00:00Z");
        for (String id : List.of("abandoned", "failed", "active", "fresh")) {
            String created = at.minusSeconds(id.equals("fresh") ? 86399 : 86400).toString();
            var plan = new OnboardingPlan(id, 0, 0, id, id, List.of("MOVIE"), "/media", "/cloud", "/mount",
                    List.of(), List.of(), List.of(), List.of(new PlannedResource("OPENLIST_STORAGE", "REUSE", "storage", "42", "/media", "retain")),
                    true, true, List.of(), created, created);
            records.createPlan(new OnboardingRecords.PlanRecord(plan, "gcm1:protected-fixture-" + id, Map.of("openlist", "fingerprint")));
        }
        var resources = List.of(new ResourceRef("storage", "42", "/media"));
        var steps = List.of(new Step("OPENLIST_STORAGE", null, "SUCCEEDED", 1, resources, List.of(), null, null, at.toString(), at.toString()));
        for (String id : List.of("failed", "active")) {
            var execution = new Execution(id, 3, id, 0, id, id, List.of("MOVIE"), id.equals("active") ? "RUNNING" : "FAILED",
                    "CD2_CLOUD", steps, List.of(), false, false, false, null, true, at.toString(), at.toString(), null);
            rows.put("target_execution_" + id, json.writeValueAsString(new OnboardingRecords.ExecutionRecord(execution,
                    id.equals("active") ? "owner" : null, id.equals("active") ? at.plusSeconds(600).toString() : null)));
        }
        int originalRows = rows.size();

        assertThat(records.cleanupExpired(at)).isEqualTo(2);

        for (String id : List.of("abandoned", "failed")) {
            var expired = records.plan(id);
            assertThat(expired.protectedPayload()).isNull();
            assertThat(expired.view().authorizationConfigured()).isFalse();
            assertThat(expired.view().canExecute()).isFalse();
            assertThat(expired.view().resources()).extracting(PlannedResource::resourceId).containsExactly("42");
            assertThat(expired.fingerprints()).containsEntry("openlist", "fingerprint");
        }
        assertThat(records.execution("failed").view().steps().get(0).resourceRefs()).isEqualTo(resources);
        assertThat(records.execution("failed").view().canContinue()).isFalse();
        assertThat(records.plan("active").protectedPayload()).isNotNull();
        assertThat(records.plan("fresh").protectedPayload()).isNotNull();
        assertThat(records.cleanupExpired(at.plusSeconds(601))).isEqualTo(2);
        assertThat(records.plan("active").protectedPayload()).isNull();
        assertThat(records.execution("active").view().status()).isEqualTo("NEEDS_ACTION");
        assertThat(records.execution("active").view().issue().code()).isEqualTo("AUTHORIZATION_EXPIRED");
        assertThat(records.execution("active").view().steps().get(0).resourceRefs()).isEqualTo(resources);
        assertThat(records.execution("active").view().canContinue()).isFalse();
        assertThat(rows).hasSize(originalRows);
    }
}
