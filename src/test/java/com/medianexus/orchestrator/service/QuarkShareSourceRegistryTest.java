package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.medianexus.orchestrator.integration.qas.QasShareNode;
import com.medianexus.orchestrator.integration.qas.QasShareTree;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuarkShareSourceRegistryTest {

    private final QuarkShareSourceRegistry registry = new QuarkShareSourceRegistry();

    @Test
    void exposesLeafAndDirectFileCandidatesWithOpaqueIds() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(
                        directory("pack", "全集", file("01.mkv"), directory("s1", "S01", file("01.mkv")))
                )
        );

        QuarkShareSourceRegistry.PreviewSession session = registry.create("https://pan.quark.cn/s/share-id", "SERIES", tree);

        assertThat(session.candidates()).hasSize(2);
        assertThat(session.candidates().values()).extracting(QuarkShareSourceRegistry.SourceCandidate::kind)
                .containsExactlyInAnyOrder("DIRECT_FILES", "LEAF_DIRECTORY");
        assertThat(session.candidates().keySet()).allSatisfy(id -> assertThat(id).doesNotContain("pack", "s1"));
        assertThat(session.candidates().values()).anySatisfy(candidate ->
                assertThat(candidate.detectedSeason()).isEqualTo(1));
    }

    @Test
    void marksOneSourceContainingTwoExplicitSeasonsAsMixed() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(directory("pack", "全集", file("Show.S01E01.mkv"), file("Show.S02E01.mkv")))
        );

        QuarkShareSourceRegistry.PreviewSession session = registry.create("https://pan.quark.cn/s/share-id", "SERIES", tree);

        assertThat(session.candidates().values()).singleElement().satisfies(candidate -> {
            assertThat(candidate.seasonStatus()).isEqualTo("MIXED");
            assertThat(candidate.detectedSeason()).isNull();
        });
    }

    @Test
    void exposesRootDirectFilesAsAnOpaqueCandidateWithAutomaticSeason() {
        QasShareTree tree = new QasShareTree(
                "https://pan.quark.cn/s/share-id",
                List.of(file("Show.S05E01.mkv"), file("Show.S05E02.mkv"))
        );

        QuarkShareSourceRegistry.PreviewSession session = registry.create(
                "https://pan.quark.cn/s/share-id", "SERIES", tree
        );

        assertThat(session.rootCandidateIds()).singleElement().satisfies(candidateId -> {
            assertThat(candidateId).doesNotContain("fid", "S05");
            assertThat(session.candidates().get(candidateId)).satisfies(candidate -> {
                assertThat(candidate.kind()).isEqualTo("DIRECT_FILES");
                assertThat(candidate.detectedSeason()).isEqualTo(5);
                assertThat(candidate.seasonStatus()).isEqualTo("AUTO");
            });
        });
    }

    private static QasShareNode directory(String fid, String name, QasShareNode... children) {
        return new QasShareNode(fid, name, true, null, 0, List.of(children));
    }

    private static QasShareNode file(String name) {
        return new QasShareNode("fid-" + name, name, false, "video", 1, List.of());
    }
}
