package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReleaseTitleTagParserTest {

    private final ReleaseTitleTagParser parser = new ReleaseTitleTagParser();

    @Test
    void parsesExplicitSeasonNamingVariants() {
        assertThat(parser.parse("Show.S01E04.1080p").seasonTags()).containsExactly("S01");
        assertThat(parser.parse("Show Season 1 Complete").seasonTags()).containsExactly("S01");
        assertThat(parser.parse("Show 第1季").seasonTags()).containsExactly("S01");
        assertThat(parser.parse("Show 第一期").seasonTags()).containsExactly("S01");
        assertThat(parser.parse("Show 第二季").seasonTags()).containsExactly("S02");
        assertThat(parser.parse("Show 第十二季").seasonTags()).containsExactly("S12");
        assertThat(parser.parse("Show 1st Season").seasonTags()).containsExactly("S01");
        assertThat(parser.parse("Show.S02.1080p").seasonTags()).containsExactly("S02");
        assertThat(parser.parse("Show.S01-S04.Complete").seasonTags())
                .containsExactly("S01", "S02", "S03", "S04");
    }

    @Test
    void infersFirstSeasonFromWholeSeriesPackNaming() {
        assertThat(parser.parse(
                "[LoliHouse] 失忆投捕 / Boukyaku Battery [01-12 合集] [WebRip 1080p] [Fin]"
        ).seasonTags()).containsExactly("S01");
        assertThat(parser.parse(
                "[DBD-Raws][失忆投捕/Boukyaku Battery][01-12TV全集+特典映像][1080P]"
        ).seasonTags()).containsExactly("S01");
        assertThat(parser.parse(
                "[Prejudice-Studio] 失忆投捕 Boukyaku Battery [01-12][Bilibili WEB-DL 1080P]"
        ).seasonTags()).containsExactly("S01");
        assertThat(parser.parse("Show [01-12] [Fin] 1080p").seasonTags()).containsExactly("S01");
        assertThat(parser.parse("Show 01-12 1080p").seasonTags()).containsExactly("S01");
        assertThat(parser.parse("Show 全12集 1080p").seasonTags()).containsExactly("S01");
        assertThat(parser.parse("Show 12集全 1080p").seasonTags()).containsExactly("S01");
        assertThat(parser.parse("Show Complete 1080p").seasonTags()).containsExactly("S01");
        assertThat(parser.parse("Show 全集 1080p").seasonTags()).containsExactly("S01");
    }

    @Test
    void parsesNumberedAnimeContinuationBeforeWholePackFallback() {
        assertThat(parser.parse(
                "[千夏字幕组&VCB-Studio] 吹响吧！上低音号 3 / Hibike! Euphonium 3 / 響け! ユーフォニアム3 10-bit 1080p HEVC BDRip [Fin]"
        ).seasonTags()).containsExactly("S03");
    }

    @Test
    void doesNotTreatSingleEpisodesOrDatesAsFirstSeasonPacks() {
        assertThat(parser.parse("Show [04] 1080p").seasonTags()).isEmpty();
        assertThat(parser.parse("Show 2024-01-12 1080p").seasonTags()).isEmpty();
        assertThat(parser.parse("Show S02 Complete 1080p").seasonTags()).containsExactly("S02");
    }
}
