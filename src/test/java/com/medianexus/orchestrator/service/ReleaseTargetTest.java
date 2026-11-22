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
    @Test void dimensionsDoNotDeclareYearsButExplicitYearConflictsRemain() {
        var target = ReleaseTarget.of("Buddy", null, null, 2026, null, "1080p");
        for (String dimension : new String[]{"1920x1080", "2048x1080", "1920×1080", "2048 * 1080", "１９２０ｘ１０８０"}) {
            assertThat(target.conflict("Buddy 2026 " + dimension)).as(dimension).isNull();
            assertThat(target.conflict("Buddy 2025 " + dimension)).as(dimension).contains("年份");
        }
    }

    @Test void foldsLatinAccentsWithoutErasingOtherScripts() {
        var target = ReleaseTarget.of("Acá", null, null, 2026, null, "1080p");
        assertThat(target.matches("Aca.2026.1080p.WEB-DL")).isTrue();
        assertThat(ReleaseTarget.comparable("中文 й हिंदी")).isEqualTo("中文 й हिंदी");
        assertThat(ReleaseTarget.of("中文", null, null, null, null, null).matches("中文 1080p")).isTrue();
    }

    @Test void recognizesAttachedChineseYearWithoutCollapsingSequels() {
        var target = ReleaseTarget.of("你好世界", null, null, 2026, null, "1080p");
        assertThat(target.matches("你好世界2026 1080p")).isTrue();
        assertThat(target.conflict("你好世界2025 1080p")).contains("年份");
        assertThat(target.matches("你好世界2 2026 1080p")).isFalse();
        assertThat(target.matches("你好世界22026 1080p")).isFalse();
    }

    @Test void shortLatinAliasesNeedReleaseBoundariesNotLongerTitlesOrProse() {
        for (String title : new String[]{"Buddy", "Everytime"}) {
            var target = ReleaseTarget.of(title, null, null, 2026, null, "1080p");
            for (String unrelated : new String[]{title + " Daddies 2026 1080p", "I remember " + title + " in this unrelated sentence"}) {
                assertThat(target.matches(unrelated)).as(unrelated).isFalse();
                assertThat(target.possiblyRelated(unrelated)).as(unrelated).isFalse();
            }
            assertThat(target.matches(title + ".2026.1080p.WEB-DL")).isTrue();
            assertThat(target.matches("[Group] " + title + " (2026) 1080p WEB-DL")).isTrue();
            assertThat(target.matches(title + ".1080p.WEB-DL.mkv")).isTrue();
            assertThat(target.matches(title)).isTrue();
            assertThat(target.matches("[" + title + "] (2026) 1080p")).isTrue();
        }
    }

    @Test void keepsMultilingualReleaseAliasesAndLongTitlePollutionEvidence() {
        var target = ReleaseTarget.of("你好世界", "Buddy", null, 2026, null, "1080p");
        assertThat(target.matches("[Group] 你好世界 / Buddy (2026) 1080p")).isTrue();
        assertThat(target.matches("[Group] Buddy / 你好世界 (2026) 1080p")).isTrue();
        assertThat(ReleaseTarget.of("3-gatsu no Lion", null, null, null, 2, "1080p")
                .possiblyRelated("63-gatsu no Lion 2nd Season")).isTrue();
        assertThat(ReleaseTarget.of("Dead Poets Society", null, null, 1989, null, "1080p")
                .matches("Dead.Poets.Society.1989.1080p.mkv")).isTrue();
        assertThat(ReleaseTarget.of("Show", null, null, null, 1, "1080p").matches("[Group] Show 01-12 1080p")).isTrue();
        assertThat(ReleaseTarget.of("Show", null, null, null, 1, "1080p").matches("Show Complete 1080p")).isTrue();
    }
}
