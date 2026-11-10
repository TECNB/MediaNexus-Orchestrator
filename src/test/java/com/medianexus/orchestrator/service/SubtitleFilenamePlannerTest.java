package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.medianexus.orchestrator.service.SubtitleArchiveExtractor.ExtractedSubtitleEntry;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubtitleFilenamePlannerTest {
    private final SubtitleFilenamePlanner planner = new SubtitleFilenamePlanner();

    @Test
    void filenameWinsThenNearestDirectoryThenUserWithoutGuessingFromChineseOrSc() {
        assertThat(planner.language(entry("繁体/Movie.CHS.srt"), "unknown"))
                .isEqualTo(new SubtitleFilenamePlanner.LanguageMatch("chs", "filename"));
        assertThat(planner.language(entry("简体/01.srt"), "cht"))
                .isEqualTo(new SubtitleFilenamePlanner.LanguageMatch("chs", "directory"));
        assertThat(planner.language(entry("简繁合集/01.srt"), "chs").language()).isEqualTo("unknown");
        assertThat(planner.language(entry("电影.sc.srt"), "unknown").language()).isEqualTo("unknown");
        assertThat(planner.language(entry("movie.srt"), "cht"))
                .isEqualTo(new SubtitleFilenamePlanner.LanguageMatch("cht", "user"));
        assertThatThrownBy(() -> planner.validateLanguage("english")).hasMessageContaining("仅支持");
    }

    @Test
    void namesByRealVideoStemAndLanguageAndAllowsDifferentFormats() {
        assertThat(planner.finalName("Actual.S01E01.mkv", "chs", "srt")).isEqualTo("Actual.S01E01.zh-CN.srt");
        assertThat(planner.finalName("Actual.mkv", "unknown", "sup")).isEqualTo("Actual.und.sup");
        assertThat(planner.plan("Actual.mkv", List.of(entry("a.chs.srt"), entry("b.cht.srt"))).files())
                .extracting(SubtitleFilenamePlanner.PlannedSubtitleFile::finalName)
                .containsExactly("Actual.zh-CN.srt", "Actual.zh-TW.srt");
        assertThatThrownBy(() -> planner.plan("Actual.mkv", List.of(entry("a.chs.srt"), entry("b.chs.srt"))))
                .hasMessageContaining("重复");
    }

    private ExtractedSubtitleEntry entry(String path) {
        return new ExtractedSubtitleEntry(path, path.substring(path.lastIndexOf('/') + 1), "srt", 12, "hash", Path.of("unused"));
    }
}
