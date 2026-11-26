package com.medianexus.orchestrator.integration.javdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class JavdbEditionTest {

    @ParameterizedTest
    @CsvSource({
            "JQJ-007-C.mp4,JQJ-007,true,false",
            "JQJ-007-CH.mkv,JQJ-007,true,false",
            "[site99.com]jqj00007-UC.mp4,JQJ-007,true,true",
            "www.site99.com@JQJ-007-CU.mp4,JQJ-007,true,true",
            "JQJ-007-U.mp4,JQJ-007,false,true",
            "JQJ-007-LTX.mp4,JQJ-007,false,true",
            "JQJ-007-cracked.mp4,JQJ-007,false,true",
            "[中字]dsvr2009.mkv,DSVR-2009,true,false",
            "ＪＱＪ－００７－Ｃ.mp4,JQJ-007,true,false",
            "fc2_ppv_004828968-UC.mkv,FC2-PPV-4828968,true,true",
            "FC2-4828968.mp4,FC2-PPV-4828968,false,false",
            "JQJ-007.h264.mp4,JQJ-007,false,false",
            "jqj7 jqj007.mkv,JQJ-007,false,false"
    })
    void parsesWorkNumberAndEditionDeclarations(String filename, String code, boolean subtitles, boolean cracked) {
        assertThat(JavdbEdition.parse(filename)).contains(new JavdbEdition(code, subtitles, cracked));
    }

    @Test
    void usesOnlyDecodedMagnetDisplayName() {
        String name = URLEncoder.encode("[中字] dsvr2009-UC.mkv", StandardCharsets.UTF_8);
        assertThat(JavdbEdition.parse("magnet:?xt=urn:btih:JQJ-007&dn=" + name
                + "&tr=https://tracker/ABC-123"))
                .contains(new JavdbEdition("DSVR-2009", true, true));
    }

    @Test
    void ed2kUsesOnlyDecodedFileNameForIdentityAndEdition() {
        assertThat(JavdbEdition.parse("ed2k://|file|ABC-123-LTX%2B中字.mkv|11042905835|0123456789abcdef0123456789abcdef|/"))
                .contains(new JavdbEdition("ABC-123", true, true));
        assertThat(JavdbEdition.parse("ed2k://|file|ABC-123-LTX.mkv|11042905835|invalid|/"))
                .isEmpty();
        assertThat(JavdbEdition.parse("ed2k://|file|ABC-123%2FDEF-456.mkv|123|0123456789abcdef0123456789abcdef|/"))
                .isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ", "magnet:?xt=urn:btih:JQJ-007&tr=https://tracker/ABC-123", "magnet:?xt=urn:btih:abcdef",
            "magnet:?xt=urn:btih:abcdef&dn=JQJ-007&dn=ABC-123", "magnet:?dn=%ZZJQJ-007",
            "JQJ-007 ABC-123.mp4", "https://tracker/JQJ-007", "abcdef0123456789", "h264.mp4"
    })
    void rejectsMissingOrAmbiguousWorkIdentity(String input) {
        assertThat(JavdbEdition.parse(input)).isEmpty();
    }
}
