package com.medianexus.orchestrator.integration.qas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.QasProperties;
import com.medianexus.orchestrator.service.QasIngestPlan;
import com.medianexus.orchestrator.service.QasTaskPlan;
import com.medianexus.orchestrator.service.QuarkIngestPlanner;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class QasLiveSharePlanningTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_QAS_LIVE_TESTS", matches = "true")
    void inspectsKnownMultiVersionShareWithoutCreatingTasks() {
        QasProperties properties = new QasProperties();
        properties.setBaseUrl(System.getenv("MEDIANEXUS_QAS_BASE_URL"));
        properties.setApiToken(System.getenv("MEDIANEXUS_QAS_API_TOKEN"));
        properties.setTimeout(Duration.ofSeconds(15));
        QasClient client = new QasClient(properties, new ObjectMapper());
        try {
            QasShareTree tree = client.inspectShare("https://pan.quark.cn/s/9259970f4a63");
            QasIngestPlan plan = new QuarkIngestPlanner().planSeasonMedia(
                    "SERIES",
                    "我的阿勒泰",
                    1,
                    "/TV/我的阿勒泰/Season 01",
                    tree,
                    Map.of()
            );

            assertThat(plan.tasks()).hasSize(2);
            assertThat(plan.tasks()).extracting(QasTaskPlan::taskName)
                    .containsExactlyInAnyOrder("我的阿勒泰 S01 [4K]", "我的阿勒泰 S01 [4K高码率]");
            assertThat(plan.tasks()).extracting(QasTaskPlan::savePath)
                    .containsOnly("/TV/我的阿勒泰/Season 01");
            assertThat(plan.tasks()).extracting(QasTaskPlan::sourceUrl)
                    .allSatisfy(url -> assertThat(url).matches(".*/[a-f0-9]{32}$"));
        } finally {
            client.close();
        }
    }
}
