package com.medianexus.orchestrator.integration.quark;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;

class QuarkMediaDeletionTest {

    @Test
    void deletesTheExactQuarkFileIdsEmbeddedInSmartStrmUrls() {
        QuarkDirectClient client = mock(QuarkDirectClient.class);
        QuarkMediaDeletion deletion = new QuarkMediaDeletion(client);

        deletion.deleteMediaSourcePaths(List.of(
                "http://host:8024/smartstrm_fid/QuarkTV/8a98133a4d99445096bf722f8c628dd1/TV/title/01.mp4?sign=a",
                "http://host:8024/smartstrm_fid/QuarkTV/f5e5be29427c40d1a7e384fe2049b0c4/TV/title/02.mp4?sign=b"
        ));

        verify(client).deleteOwnedFiles(List.of(
                "8a98133a4d99445096bf722f8c628dd1",
                "f5e5be29427c40d1a7e384fe2049b0c4"
        ));
    }
}
