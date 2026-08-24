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

        QasIngestPlan plan = planner.planSeries(
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

        QasIngestPlan plan = planner.planVariety(
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
                    .contains("20\\d{2}")
                    .contains("ts");
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

        QasIngestPlan plan = planner.planVariety(
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

        QasIngestPlan plan = planner.planVariety(
                "极限挑战", 2, "/Variety/极限挑战/Season 02", tree, Map.of()
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

        QasIngestPlan plan = planner.planSeries(
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

        assertThatThrownBy(() -> planner.planSeries(
                "日期剧", 1, "/TV/日期剧/Season 01", tree, Map.of()
        )).isInstanceOfSatisfying(QuarkIngestPlanningException.class, exception ->
                assertThat(exception.getReason())
                        .isEqualTo(QuarkIngestPlanningException.Reason.DATE_MAPPING_REQUIRED)
        );

        QasIngestPlan plan = planner.planSeries(
                "日期剧",
                1,
                "/TV/日期剧/Season 01",
                tree,
                Map.of(
                        LocalDate.of(2025, 1, 1), List.of(1),
                        LocalDate.of(2025, 1, 8), List.of(2)
                )
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

        QasIngestPlan plan = planner.planSeries(
                "测试剧", 1, "/TV/测试剧/Season 01", tree, Map.of()
        );

        assertThat(plan.tasks()).extracting(QasTaskPlan::replace)
                .containsExactlyInAnyOrder(
                        "测试剧 - S01E\\1 - REMUX\\2.\\3",
                        "测试剧 - S01E\\1 - WEB-DL\\2.\\3"
                );
    }

    @Test
    void rejectsSharesWithoutPlayableVideo() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(new QasShareNode("zip-fid", "全集.zip", false, "archive", 1, List.of()))
        );

        assertThatThrownBy(() -> planner.planSeries(
                "测试剧", 1, "/TV/测试剧/Season 01", tree, Map.of()
        )).isInstanceOfSatisfying(QuarkIngestPlanningException.class, exception ->
                assertThat(exception.getMessage()).contains("可播放视频")
        );
    }

    @Test
    void renamesNumericEpisodesWithQualityLabelsAndTsVideos() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(file("01 4K.mp4"), file("02_4K.ts"))
        );

        QasIngestPlan plan = planner.planSeries(
                "测试剧", 1, "/TV/测试剧/Season 01", tree, Map.of()
        );

        assertThat(plan.tasks()).singleElement().satisfies(task -> {
            assertThat(task.pattern()).contains("[ _-]");
            assertThat(task.pattern()).contains("ts");
            assertThat(task.replace()).isEqualTo("测试剧 - S01E\\1 - \\2.\\3");
            assertThat(task.renameRule()).isEqualTo("数字加标签");
            assertThat(task.matchedFileCount()).isEqualTo(2);
            assertThat(task.renameSamples()).extracting(QasRenameSample::sourceName, QasRenameSample::targetName)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("01 4K.mp4", "测试剧 - S01E01 - 4K.mp4"),
                            org.assertj.core.groups.Tuple.tuple("02_4K.ts", "测试剧 - S01E02 - 4K.ts")
                    );
        });
    }

    @Test
    void renamesSeasonEpisodeFilesWithHumanReadableSuffix() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(file("S01E12 - 第06期 上：标题.mkv"), file("S01E13 - 第07期.mp4"))
        );

        QasIngestPlan plan = planner.planVariety(
                "节目", 1, "/Variety/节目/Season 01", tree, Map.of()
        );

        assertThat(plan.tasks()).singleElement().satisfies(task ->
                assertThat(task.replace()).isEqualTo("节目 - S01E\\1 - \\2.\\3")
        );
    }

    @Test
    void renamesSeasonDotEpReleaseNames() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(
                        file("Joy.of.Life.2019.S01.EP01.WEB-DL.4K.mp4"),
                        file("Joy.of.Life.2019.S01.EP46.WEB-DL.4K.mp4")
                )
        );

        QasIngestPlan plan = planner.planSeries(
                "庆余年", 1, "/TV/庆余年/Season 01", tree, Map.of()
        );

        assertThat(plan.tasks()).singleElement().satisfies(task ->
                assertThat(task.replace()).isEqualTo("庆余年 - S01E\\1\\2.\\3")
        );
    }

    @Test
    void supportsSeparatedAndPartiallyCompactVarietyDates() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(
                        file("2025-03-21 第1期上.mkv"),
                        file("2025.03.28-第2期下.mp4")
                )
        );

        QasIngestPlan plan = planner.planVariety(
                "节目", 1, "/Variety/节目/Season 01", tree, Map.of()
        );

        assertThat(plan.tasks()).singleElement().satisfies(task ->
                assertThat(task.replace()).isEqualTo("节目 - \\1-\\2-\\3 - \\4.\\5")
        );

        QasShareTree compactTree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(file("2025.0411.第4期上.mp4"), file("2025.0418.第5期下.mp4"))
        );
        QasIngestPlan compactPlan = planner.planVariety(
                "节目", 1, "/Variety/节目/Season 01", compactTree, Map.of()
        );
        assertThat(compactPlan.tasks()).singleElement().satisfies(task ->
                assertThat(task.replace()).isEqualTo("节目 - \\1-\\2-\\3 - \\4.\\5")
        );
    }

    @Test
    void requiresTmdbScheduleBeforeRenamingMonthDayVarietyNames() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(file("01.07上.mp4"), file("01.14下.mp4"), file("02.25爆点料.mp4"))
        );

        assertThatThrownBy(() -> planner.planVariety(
                "节目", 1, "/Variety/节目/Season 01", tree, Map.of()
        )).isInstanceOfSatisfying(QuarkIngestPlanningException.class, exception ->
                assertThat(exception.getReason())
                        .isEqualTo(QuarkIngestPlanningException.Reason.DATE_MAPPING_REQUIRED)
        );
    }

    @Test
    void renamesRealMonthDayVarietyShareAndSplitsTmdbDoubleEpisodeDate() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(
                        file("06.12.mp4"),
                        file("06.19第一期.mp4"),
                        file("06.20 第一期加更 上.mp4"),
                        file("06.21第一期加更 下.mp4"),
                        file("06.26上.mp4"),
                        file("06.26下.mp4"),
                        file("06.27第2期加更.mp4"),
                        file("06.28第2期加更.mp4"),
                        file("07.03第3期.mp4"),
                        file("07.04第3期加更上.mp4"),
                        file("07.05第3期加更下.mp4"),
                        file("07.10第4期.mp4"),
                        file("07.11 第4期加更上.mp4"),
                        file("07.12 第4期加更下.mp4"),
                        file("07.15 第5期.mp4"),
                        file("07.18 第5期加更上.mp4"),
                        file("07.19 第5期加更下.mp4"),
                        file("07.24第6期.mp4"),
                        file("07.25第6期加更.mp4"),
                        file("07.26第6期加更.mp4"),
                        file("07.31第7期.mp4"),
                        file("08.01第7期加更.mp4"),
                        file("08.02第7期加更.mp4"),
                        file("08.07第8期.mp4"),
                        file("08.08第8期加更.mp4"),
                        file("08.09加更.mp4"),
                        file("08.14.mp4"),
                        file("08.15 第9期加更上.mp4"),
                        file("08.16 第9期加更下.mp4"),
                        file("08.21第10期.mp4"),
                        file("08.22第10期加更.mp4"),
                        file("08.23第10期加更.mp4")
                )
        );
        Map<LocalDate, List<Integer>> schedule = Map.ofEntries(
                Map.entry(LocalDate.of(2022, 6, 19), List.of(1)),
                Map.entry(LocalDate.of(2022, 6, 26), List.of(2, 3)),
                Map.entry(LocalDate.of(2022, 7, 3), List.of(4)),
                Map.entry(LocalDate.of(2022, 7, 10), List.of(5)),
                Map.entry(LocalDate.of(2022, 7, 17), List.of(6)),
                Map.entry(LocalDate.of(2022, 7, 24), List.of(7, 8)),
                Map.entry(LocalDate.of(2022, 7, 31), List.of(9, 10)),
                Map.entry(LocalDate.of(2022, 8, 7), List.of(11)),
                Map.entry(LocalDate.of(2022, 8, 14), List.of(12)),
                Map.entry(LocalDate.of(2022, 8, 21), List.of(13))
        );

        QasIngestPlan plan = planner.planVariety(
                "五十公里桃花坞", 2, "/Variety/五十公里桃花坞/Season 02", tree, schedule
        );

        assertThat(plan.tasks()).hasSize(4);
        assertThat(plan.tasks()).extracting(QasTaskPlan::matchedFileCount)
                .containsExactlyInAnyOrder(28, 2, 1, 1);
        assertThat(plan.tasks()).extracting(QasTaskPlan::replace)
                .contains(
                        "五十公里桃花坞 - 2022-\\1-\\2 - \\3.\\4",
                        "五十公里桃花坞 - 2022-\\1-\\2\\3.\\4"
                )
                .anyMatch(replace -> replace.startsWith("五十公里桃花坞 - S02E02"))
                .anyMatch(replace -> replace.startsWith("五十公里桃花坞 - S02E03"));
        assertThat(plan.tasks()).flatExtracting(QasTaskPlan::renameSamples)
                .extracting(QasRenameSample::sourceName, QasRenameSample::targetName)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(
                                "06.12.mp4", "五十公里桃花坞 - 2022-06-12.mp4"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "06.19第一期.mp4", "五十公里桃花坞 - 2022-06-19 - 第一期.mp4"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "06.26上.mp4", "五十公里桃花坞 - S02E02 - 上.mp4"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "06.26下.mp4", "五十公里桃花坞 - S02E03 - 下.mp4"
                        )
                );
        assertThat(plan.warnings()).anyMatch(message ->
                message.contains("同一播出日期") && message.contains("TMDB 集号")
        );
    }

    @Test
    void keepsShortDateSubtitleBasenameAlignedWithVideo() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(file("06.12.mp4"), subtitle("06.12.zh-CN.srt"))
        );

        QasIngestPlan plan = planner.planVariety(
                "节目",
                2,
                "/Variety/节目/Season 02",
                tree,
                Map.of(LocalDate.of(2022, 6, 19), List.of(1))
        );

        assertThat(plan.tasks()).singleElement().satisfies(task -> {
            assertThat(task.replace()).isEqualTo("节目 - 2022-\\1-\\2\\3.\\4");
            assertThat(task.renameSamples()).extracting(QasRenameSample::targetName)
                    .containsExactly("节目 - 2022-06-12.mp4", "节目 - 2022-06-12.zh-CN.srt");
        });
    }

    @Test
    void splitsDisjointNamingFamiliesIntoSafeTasks() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(
                        file("01.mp4"),
                        file("02.mp4"),
                        file("S01E03 - 第3集.mkv"),
                        file("S01E04 - 第4集.mkv")
                )
        );

        QasIngestPlan plan = planner.planSeries(
                "我的阿勒泰", 1, "/TV/我的阿勒泰/Season 01", tree, Map.of()
        );

        assertThat(plan.tasks()).hasSize(2);
        assertThat(plan.tasks()).extracting(QasTaskPlan::sourceUrl)
                .containsOnly("https://pan.quark.cn/s/share-id");
        assertThat(plan.tasks()).extracting(QasTaskPlan::taskName)
                .allMatch(name -> name.startsWith("我的阿勒泰 S01 ["));
        assertThat(plan.warnings()).anyMatch(message -> message.contains("多个互斥命名规则"));
    }

    @Test
    void selectsRequestedSeasonFromMultiSeasonCollection() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(directory(
                        "wrapper-fid",
                        "剧集全集",
                        directory("s1-fid", "第1季", file("01.mkv")),
                        directory("s2-fid", "S02 2025", file("01.mkv"), file("02.mkv")),
                        directory("s3-fid", "Season 3", file("01.mkv"))
                ))
        );

        QasIngestPlan plan = planner.planSeries(
                "测试剧", 2, "/TV/测试剧/Season 02", tree, Map.of()
        );

        assertThat(plan.tasks()).singleElement().satisfies(task -> {
            assertThat(task.sourceUrl()).isEqualTo("https://pan.quark.cn/s/share-id/s2-fid");
            assertThat(task.replace()).isEqualTo("测试剧 - S02E\\1\\2.\\3");
        });
    }

    @Test
    void ignoresShortDateFilesOutsideRequestedVarietySeason() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(directory(
                        "wrapper-fid",
                        "综艺全集",
                        directory("s1-fid", "Season 1", file("06.12.mp4")),
                        directory("s2-fid", "Season 2", file("01.mp4"), file("02.mp4"))
                ))
        );

        QasIngestPlan plan = planner.planVariety(
                "测试综艺", 2, "/Variety/测试综艺/Season 02", tree, Map.of()
        );

        assertThat(plan.tasks()).singleElement().satisfies(task -> {
            assertThat(task.sourceUrl()).isEqualTo("https://pan.quark.cn/s/share-id/s2-fid");
            assertThat(task.replace()).isEqualTo("测试综艺 - S02E\\1\\2.\\3");
        });
    }

    @Test
    void selectsSeasonWhenCollectionAlsoContainsSpecials() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(directory(
                        "wrapper-fid", "全集",
                        directory("special-fid", "圣诞特别篇", file("Special.mp4")),
                        directory("s1-fid", "S01", file("S01E01.mp4")),
                        directory("s2-fid", "第二季", file("S02E01.mp4"))
                ))
        );

        QasIngestPlan plan = planner.planSeries(
                "黑镜", 1, "/TV/黑镜/Season 01", tree, Map.of()
        );

        assertThat(plan.tasks()).singleElement().satisfies(task ->
                assertThat(task.sourceUrl()).isEqualTo("https://pan.quark.cn/s/share-id/s1-fid")
        );
    }

    @Test
    void rejectsExplicitEpisodeFromAnotherSeason() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(file("Show.S02E01.mkv"))
        );

        assertThatThrownBy(() -> planner.planSeries(
                "测试剧", 1, "/TV/测试剧/Season 01", tree, Map.of()
        )).isInstanceOfSatisfying(QuarkIngestPlanningException.class, exception ->
                assertThat(exception.getMessage()).contains("第 2 季").contains("第 1 季")
        );
    }

    @Test
    void unwrapsMovieFolderButKeepsMovieRenameRulesEmpty() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id?pwd=1234",
                List.of(directory("movie-fid", "电影包装目录", file("电影.mkv"), subtitle("电影.zh-CN.srt")))
        );

        QasIngestPlan plan = planner.planMovie("电影 (2025)", "/Movie/电影 (2025)", tree);

        assertThat(plan.tasks()).singleElement().satisfies(task -> {
            assertThat(task.sourceUrl()).isEqualTo("https://pan.quark.cn/s/share-id/movie-fid?pwd=1234");
            assertThat(task.pattern()).isEmpty();
            assertThat(task.replace()).isEmpty();
        });
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
