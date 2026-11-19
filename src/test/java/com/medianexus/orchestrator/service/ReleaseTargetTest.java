package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ReleaseTargetTest {
    @Test void unspecifiedSubmissionQualityDoesNotInventA720pRejection() {
        assertThat(ReleaseTarget.of("Show", null, null, null, 1, null).qualities()).contains("720p");
        assertThat(ReleaseTarget.of("Show", null, null, null, 1, "1080p").qualities()).doesNotContain("720p");
    }
    @Test void permitsEvidenceQueueForScrapedPrefixButKeepsSequelAndIdentityDistinct() {
        var target = ReleaseTarget.of("Movie", null, null, 2023, null, "1080p");
        assertThat(target.matches("AnotherMovie 2023 1080p")).isFalse();
        assertThat(target.matches("Movie 2 2023 1080p")).isFalse();
        assertThat(target.conflict("Movie 1999 1080p")).contains("年份");
        var anime = ReleaseTarget.of("Show!", null, null, null, 1, "1080p");
        assertThat(anime.matches("Show!! S01 1080p")).isFalse();
        var lion = ReleaseTarget.of("3-gatsu no Lion", null, null, null, 2, "1080p");
        assertThat(lion.matches("63-gatsu no Lion 2nd Season")).isFalse();
        assertThat(lion.possiblyRelated("63-gatsu no Lion 2nd Season")).isTrue();
    }
}
