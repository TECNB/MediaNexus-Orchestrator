package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.medianexus.orchestrator.integration.qas.QasShareNode;
import com.medianexus.orchestrator.integration.qas.QasShareTree;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class QuarkIngestPlannerTest {

    private final QuarkIngestPlanner planner = new QuarkIngestPlanner();

    @Test
    void plansOneTaskPerVersionDirectoryWithUniqueEmbyNames() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(directory(
                        "wrapper-fid",
                        "W 我的阿勒泰（2024）全8集 4K",
                        directory("high-fid", "4K高码率", numberedEpisodes()),
                        directory("standard-fid", "4K", numberedEpisodes())
                ))
        );

        QasIngestPlan plan = planner.planSeasonMedia(
                "SERIES",
                "我的阿勒泰",
                1,
                "/TV/我的阿勒泰/Season 01",
                tree,
                Map.of()
        );

        assertThat(plan.tasks()).hasSize(2);
        assertThat(plan.tasks()).extracting(QasTaskPlan::savePath)
                .containsOnly("/TV/我的阿勒泰/Season 01");
        assertThat(plan.tasks()).extracting(QasTaskPlan::sourceUrl)
                .containsExactlyInAnyOrder(
                        "https://pan.quark.cn/s/share-id/high-fid",
                        "https://pan.quark.cn/s/share-id/standard-fid"
                );
        assertThat(plan.tasks()).extracting(QasTaskPlan::taskName)
                .containsExactlyInAnyOrder("我的阿勒泰 S01 [4K高码率]", "我的阿勒泰 S01 [4K]");
        assertThat(plan.tasks()).extracting(QasTaskPlan::replace)
                .containsExactlyInAnyOrder(
                        "我的阿勒泰 - S01E\\1 - 4K高码率\\2.\\3",
                        "我的阿勒泰 - S01E\\1 - 4K\\2.\\3"
                );
        Set<String> plannedNames = plan.tasks().stream()
                .flatMap(task -> IntStream.rangeClosed(1, 8)
                        .mapToObj(episode -> String.format(
                                "我的阿勒泰 - S01E%02d - %s.mkv",
                                episode,
                                task.versionLabel()
                        )))
                .collect(Collectors.toSet());
        assertThat(plannedNames).hasSize(16)
                .contains("我的阿勒泰 - S01E01 - 4K.mkv")
                .contains("我的阿勒泰 - S01E08 - 4K高码率.mkv");
    }

    @Test
    void unwrapsSingleDirectoryAndPlansDateBasedVarietyNames() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id?pwd=1234",
                List.of(directory(
                        "wrapper-fid",
                        "喜人奇妙夜2",
                        file("20250927第1期下纯享版.mp4"),
                        file("20251004第2期.mp4")
                ))
        );

        QasIngestPlan plan = planner.planSeasonMedia(
                "VARIETY",
                "喜人奇妙夜",
                1,
                "/Variety/喜人奇妙夜/Season 01",
                tree,
                Map.of()
        );

        assertThat(plan.tasks()).singleElement().satisfies(task -> {
            assertThat(task.sourceUrl())
                    .isEqualTo("https://pan.quark.cn/s/share-id/wrapper-fid?pwd=1234");
            assertThat(task.pattern())
                    .isEqualTo("^(20\\d{2})(\\d{2})(\\d{2})(.*)\\.(mp4|mkv|srt|ass|ssa|vtt|sub)$");
            assertThat(task.replace()).isEqualTo("喜人奇妙夜 - \\1-\\2-\\3 - \\4.\\5");
        });
    }

    @Test
    void fallsBackToEmptyRuleWhenOrdinaryDirectoryContainsUnmatchedExtra() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(
                        file("第03期 沉浸版.mp4"),
                        file("第04期.mp4"),
                        file("番外.mp4")
                )
        );

        QasIngestPlan plan = planner.planSeasonMedia(
                "VARIETY",
                "极限挑战",
                2,
                "/Variety/极限挑战/Season 02",
                tree,
                Map.of()
        );

        assertThat(plan.tasks()).singleElement().satisfies(task -> {
            assertThat(task.pattern()).isEmpty();
            assertThat(task.replace()).isEmpty();
        });
        assertThat(plan.warnings()).anyMatch(message -> message.contains("空重命名规则"));
    }

    @Test
    void padsSingleDigitChineseEpisodeNumbers() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(file("第3期 沉浸版.mp4"), file("第4期 纯享版.mp4"))
        );

        QasIngestPlan plan = planner.planSeasonMedia(
                "VARIETY", "极限挑战", 2, "/Variety/极限挑战/Season 02", tree, Map.of()
        );

        assertThat(plan.tasks()).singleElement().satisfies(task ->
                assertThat(task.replace()).isEqualTo("极限挑战 - S02E0\\1 - \\2.\\3")
        );
    }

    @Test
    void keepsMatchingSubtitleBesideItsNumberedEpisode() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(file("01.mkv"), subtitle("01.zh-CN.srt"))
        );

        QasIngestPlan plan = planner.planSeasonMedia(
                "SERIES",
                "我的阿勒泰",
                1,
                "/TV/我的阿勒泰/Season 01",
                tree,
                Map.of()
        );

        assertThat(plan.tasks()).singleElement().satisfies(task -> {
            assertThat(task.pattern()).contains("srt");
            assertThat(task.replace()).isEqualTo("我的阿勒泰 - S01E\\1\\2.\\3");
        });
    }

    @Test
    void usesValidatedTmdbAirDatesForDateOnlyMultiVersionEpisodes() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(
                        directory("hdr-fid", "HDR", file("20250101.mkv"), file("20250108.mkv")),
                        directory("web-fid", "WEB-DL", file("20250101.mkv"), file("20250108.mkv"))
                )
        );

        assertThatThrownBy(() -> planner.planSeasonMedia(
                "SERIES", "日期剧", 1, "/TV/日期剧/Season 01", tree, Map.of()
        )).isInstanceOfSatisfying(QuarkIngestPlanningException.class, exception ->
                assertThat(exception.getReason())
                        .isEqualTo(QuarkIngestPlanningException.Reason.DATE_MAPPING_REQUIRED)
        );

        QasIngestPlan plan = planner.planSeasonMedia(
                "SERIES",
                "日期剧",
                1,
                "/TV/日期剧/Season 01",
                tree,
                Map.of(LocalDate.of(2025, 1, 1), 1, LocalDate.of(2025, 1, 8), 2)
        );

        assertThat(plan.tasks()).hasSize(2).allSatisfy(task -> {
            assertThat(task.pattern()).isEqualTo("^(20\\d{2})(\\d{2})(\\d{2})\\.(mkv|mp4)$");
            assertThat(task.replace()).contains("S01E{II}");
        });
    }

    @Test
    void recognizesExistingSeasonEpisodeAndNxNNInsideVersionDirectories() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(
                        directory("remux-fid", "REMUX", file("Show.S01E01.mkv"), file("Show.S01E02.mkv")),
                        directory("web-fid", "WEB-DL", file("Show.1x01.mkv"), file("Show.1x02.mkv"))
                )
        );

        QasIngestPlan plan = planner.planSeasonMedia(
                "SERIES", "测试剧", 1, "/TV/测试剧/Season 01", tree, Map.of()
        );

        assertThat(plan.tasks()).extracting(QasTaskPlan::replace)
                .containsExactlyInAnyOrder(
                        "测试剧 - S01E\\1 - REMUX\\2.\\3",
                        "测试剧 - S01E\\1 - WEB-DL\\2.\\3"
                );
    }

    private static List<QasShareNode> numberedEpisodes() {
        return java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(episode -> file(String.format("%02d.mkv", episode)))
                .toList();
    }

    private static QasShareNode directory(String fid, String name, QasShareNode... children) {
        return directory(fid, name, List.of(children));
    }

    private static QasShareNode directory(String fid, String name, List<QasShareNode> children) {
        return new QasShareNode(fid, name, true, null, 0, children);
    }

    private static QasShareNode file(String name) {
        return new QasShareNode("fid-" + name, name, false, "video", 1, List.of());
    }

    private static QasShareNode subtitle(String name) {
        return new QasShareNode("fid-" + name, name, false, "doc", 1, List.of());
    }
}
